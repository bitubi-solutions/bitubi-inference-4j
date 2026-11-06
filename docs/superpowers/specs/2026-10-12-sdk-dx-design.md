# bitubi-inference-4j SDK: design

Date: 2026-10-12, revised 2026-11-05. Status: **design approved in conversation; this written spec awaits the
author's review.** Implementation starts only after that review and an implementation plan.

## 1. Goal

A Java developer reaches the first token in a few lines. They do not need to know about tensors, `DType` or the
engine's classes, and every error says what to do. The SDK stands on its own: it uses only what the JDK offers
(records, sealed types, `Stream`, virtual threads, JFR) and no framework. Spring, Quarkus or LangChain4j users can
wrap it, but its value must show without them. It is built for large projects: an immutable configuration, a
thread-safe entry point, typed errors, and observability built into the JDK.

Out of scope (author's decision, 2026-11-02): the LangChain4j adapter and a Spring Boot starter. The project is
not part of either ecosystem yet. Revisit them once the SDK has users.

## 2. Decisions

| Topic | Decision |
|---|---|
| Package | `ao.bitubi.inference4j.sdk`, inside `bitubi-inference-4j-core` (one artifact; `Part`, `Modality` and `Message` already live there) |
| API style | Fluent at the entry, functional values in the middle, small interfaces at the edges (section 3) |
| Sessions | An explicit `ChatSession` object that keeps the KV cache between turns; temporary sessions free it after each call (section 4) |
| Streaming | `java.util.stream.Stream<Token>`, lazy and pull-driven; closing or short-circuiting the stream cancels generation between tokens (section 5) |
| `DType` | Automatic by default (estimate, then choose or fail with an explanation); manual at the developer's risk, with a WARN (section 6) |
| JVM flags | Checked at `build()` time with one message that names the flag and where to put it. The SDK makes no "zero flags" promise |
| Engine internals | Not part of the API: `module-info` exports only the SDK package (section 9) |
| Inputs | `Message` holds `Part`s (`Text`, `Image`, `Audio`); a model declares the `Modality` set it reads; today every model reads text only |
| Backward compatibility | None with Jlama. The migration guide and the OpenRewrite recipe gain an "old API to SDK" section |

## 3. API

```java
try (Bi4j bi4j = Bi4j.builder()
        .model("tjake/Llama-3.2-1B-Instruct-JQ4")    // Hugging Face id, or .model(Path.of(...)) for a local folder or .gguf
        .modelsDir(Path.of("models"))
        .contextLength(4096)                          // what the sessions may use; drives the memory estimate
        .build()) {                                   // downloads, checks flags and memory, loads

    // One-shot: a temporary session per call, safe to call from many threads at once
    ChatResponse r = bi4j.chat()
        .system("Be concise.")
        .user("When is the best time to plant avocado?")
        .maxNewTokens(256)
        .temperature(0.2f)
        .generate();
    System.out.println(r.text());

    // Conversation: the session keeps the history in its KV cache
    try (ChatSession chat = bi4j.newSession(s -> s.system("Be concise."))) {
        chat.send("When do I plant avocado?");
        try (Stream<Token> tokens = chat.stream("And mango?")) {
            tokens.forEach(t -> System.out.print(t.text()));
        }
    }

    // Requests are values: build once, reuse, test
    ChatRequest req = bi4j.chat().user("Summarize: ...").maxNewTokens(64).request();
    ChatResponse again = bi4j.generate(req);
}
```

Types (all in `ao.bitubi.inference4j.sdk`):

| Type | Kind | Role |
|---|---|---|
| `Bi4j` | interface, `AutoCloseable` | Entry point: `builder()`, `chat()`, `generate(ChatRequest)`, `stream(ChatRequest)`, `newSession(...)`, `embed(String)`, `capabilities()` |
| `Bi4j.Builder` | final class | Model source, models dir, context length, `DType` override, threads |
| `ChatSession` | interface, `AutoCloseable` | `send(String or Message)`, `stream(...)`, `history()`, `close()` frees its KV cache |
| `SessionOptions` | record | `system`, `temporary`, defaults for `maxNewTokens`, `temperature` and `stop` |
| `ChatRequest` | record | `List<Message> messages`, `GenerationSettings settings` |
| `GenerationSettings` | record | `maxNewTokens` (default 512), `temperature` (default 0.7), `List<String> stop` |
| `ChatRequestBuilder` | final class | The fluent `bi4j.chat()...` chain; ends in `generate()`, `stream()` or `request()` |
| `ChatResponse` | record | `text`, `FinishReason finishReason`, `Usage usage` |
| `Token` | record | `text`, `index` |
| `Usage` | record | `promptTokens`, `generatedTokens`, `Duration timeToFirstToken`, `double tokensPerSecond` |
| `FinishReason` | enum | `STOP`, `LENGTH`, `STOP_SEQUENCE`, `CANCELLED` |
| `Capabilities` | record | `Set<Modality> inputs`, `boolean chat`, `boolean embeddings`, `int contextLength` |
| `Message`, `Part`, `Modality` | records and enum | Already on main (`6cd87e4`) |
| `Bi4jException` and subtypes | sealed exceptions | Section 8 |

Rules:
- `Bi4j` is immutable and thread-safe once built. `generate` and `stream` on `Bi4j` use a temporary session per
  call, so concurrent calls do not share state.
- The fluent builder is the only mutable object. It creates a new `ChatRequest` each time it ends, so keeping the
  builder around cannot change a request that was already sent.
- `Bi4j.load("id")` is a shortcut for `builder().model("id").build()`.
- Tool calling stays in the engine but is not part of SDK phase 1.

## 4. Sessions

- A `ChatSession` owns a session id in the engine. Each `send` passes only the new turn to the model (the
  preamble and the earlier turns are already in the KV cache), the way the `chat` command does today.
- One generation at a time per session. A second concurrent call gets `SessionBusyException`. Different sessions
  run in parallel.
- **Persistent session (default):** the KV cache stays until `close()`. Nothing is freed on a timer.
- **Temporary session** (`s -> s.temporary()`): the KV cache is freed after every call, and each call starts
  from the system prompt. The one-shot `bi4j.chat()...generate()` always uses a temporary session.
- `Bi4j.close()` frees every session that is still open.
- When a session reaches the context window, the next call fails with `ContextLengthExceededException`. The
  message gives the numbers and the fix: start a new session or shorten the prompt. Trimming the history
  automatically is out of scope until someone asks for it.

## 5. Streaming and cancellation

- `stream(...)` returns a lazy `Stream<Token>`. Generation starts on the first terminal operation, not when
  `stream` is called.
- The engine runs on a virtual thread and hands tokens over through a small bounded queue. A slow consumer makes
  the producer wait, so generation does not race ahead of the reader.
- Closing the stream, or a short-circuit operation (`limit`, `takeWhile`, `findFirst`, `anyMatch`), interrupts
  that virtual thread. The engine stops between tokens with `CANCELLED` (on main since `56d1b3f`), and the tokens
  generated so far stay in the session.
- The stream must be used in try-with-resources. A stream that is never closed or never fully consumed holds its
  session as busy. A JFR event and a WARN report it when its `Bi4j` closes.
- Stop sequences (`GenerationSettings.stop`) are matched in the SDK on the decoded text. The matching text is
  removed from the response and the stream, and generation is cancelled with `STOP_SEQUENCE`.

## 6. `DType`: automatic and manual

The weight type comes from the model file. The SDK chooses the working types: the KV cache and memory type
(`F32` or `BF16`) and the activation quantization type (`I8` or `F32`).

**Automatic (default).**

| Weights | Activations | KV cache | Why |
|---|---|---|---|
| Q4 (JQ4, GGUF Q4_0) | I8 | F32 | The I8×Q4 kernel is the fastest on both backends |
| Q8_0 (GGUF) | F32 | F32 | Only the F32×Q8 kernel exists |
| BF16 | F32 | F32 | F32×BF16 kernel |
| F16 (GGUF) | F32 | F32 | Widened to F32 at load |
| F32 | F32 | F32 | No conversion |

Before loading, the SDK estimates the memory it needs: the weights as loaded (after any widening) plus the KV
cache for `contextLength` (`2 × layers × contextLength × kvLength × size(KV type)`). It compares the estimate with
what is available: heap, `MaxDirectMemorySize` and free disk in the working directory, since the KV pages are
file-backed. If the model does not fit, `build()` fails with `InsufficientMemoryException`. The message states the
estimate, what is available, and the options: a smaller `contextLength`, a more quantized model, or the flag to
raise and its value.

**Manual** (`.workingTypes(DType memory, DType activations)`). The developer's choice wins. The SDK logs one WARN
for each risk it detects: it does not fit the estimate, it is slower than the automatic choice, or it costs
precision (I8 activations with non-Q4 weights). The SDK fails only when no kernel exists for the combination.

To validate before release: compare the reference answers with I8 and F32 activations on the smoke models, and
record the difference in `docs/`.

## 7. JVM flags

`build()` checks:
- `ModuleLayer.boot().findModule("jdk.incubator.vector")` for the Panama backend.
- `Bi4j.class.getModule().isNativeAccessEnabled()` only when `bi4j.backend=native`.

On failure it throws `EngineSetupException` with the exact flag and three ways to set it: the `java` command line,
`JAVA_TOOL_OPTIONS`, and the Maven Surefire or exec plugin. The flags cannot be enabled from code on the class
path, so the SDK validates and explains instead.

## 8. Errors

The `sealed` class `Bi4jException extends RuntimeException` permits:

| Exception | When |
|---|---|
| `ModelNotFoundException` | Unknown id, missing local path, or Hugging Face 401/404 (with a hint about the token) |
| `UnsupportedModelException` | Architecture or file format the engine cannot load |
| `InsufficientMemoryException` | The estimate in section 6 does not fit |
| `EngineSetupException` | Missing JVM flag, or no backend available |
| `SessionBusyException` | A second concurrent call on one session |
| `ContextLengthExceededException` | Session plus prompt beyond the context window |
| `UnsupportedInputException` | A `Part` whose `Modality` the model does not read, checked before any work starts |

Every message says what happened and what to do. `SessionBusyException` and `ContextLengthExceededException`
already exist in the engine. They move under `Bi4jException`, which changes their supertypes
(`IllegalStateException` and `IllegalArgumentException` today). The REST mapping (409 and 400) stays the same.

## 9. Encapsulation

- `bitubi-inference-4j-core` gains a `module-info.java` that exports `ao.bitubi.inference4j.sdk` to everyone,
  and the engine packages only to the project's own modules (`exports ... to bitubi.inference4j.net, ...`).
- This is enforced for applications on the module path. On the class path, Java cannot hide packages. There, the
  engine packages are documented as internal and excluded from the published Javadoc, and the migration guide
  points at the SDK.
- Checked in phase 2 (2026-11-06): the dependencies work as modules. Jackson and the template engine reach the
  engine through qualified `opens`, one line per engine package, to `com.fasterxml.jackson.databind` and `jinjava`
  only. The one conflict was a transitive `com.google.code.findbugs:annotations` that exports the same package as
  `jsr305`; it is excluded from `jinjava` in the core pom. `jinjava` and `jctools-core` have no module name, so
  `requires` uses the names derived from their file names (the build warns not to publish such a module to a public
  repository such as Maven Central; GitHub Packages is fine, but the names must be revisited if either library adds one).
  The native backend shares the package `ao.bitubi.inference4j.tensor.operations` with the engine, so it cannot be a
  separate module: it stays class-path only. `ModulePathTest` in `examples` runs a model on the module path and
  checks that the engine is refused. Javadoc covers only the exported package.

## 10. Observability

- JFR events, which need no dependency: `ao.bitubi.inference4j.Load` (model, weight and working types, load time,
  estimate) and `ao.bitubi.inference4j.Generation` (session, prompt and generated tokens, time to first token,
  tokens/s, finish reason).
- Logging stays on SLF4J, which core already uses; the SDK adds no logging dependency.
- `Usage` on every response carries the same numbers for applications that do not use JFR.

## 11. Acceptance criteria

- First token in **≤ 10 lines** of application code, not counting imports. An example in `examples/` compiles
  in CI on Linux, Windows and macOS.
- Concurrency:
  - 8 concurrent one-shot `generate` calls on one `Bi4j` succeed, and their outputs at temperature 0 match a
    single-threaded run.
  - A second call on a busy session fails fast.
- Cancellation:
  - `stream(...).limit(3)` returns three tokens.
  - The engine reports `CANCELLED` and generates at most one token after the third.
  - The session stays usable afterwards.
- `maxNewTokens` with a prompt longer than it still generates (covered in the engine by `GenerationLimitsTest`).
- Without `--add-modules jdk.incubator.vector`, `build()` throws one `EngineSetupException` that names the flag
  (tested in a forked JVM).
- Memory:
  - An oversized `contextLength` makes `build()` fail with `InsufficientMemoryException` before loading.
  - A manual `DType` that does not fit logs the WARN and still loads.
- An image `Part` on a text-only model throws `UnsupportedInputException` before tokenization.
- Temporary sessions leave no `.page` files behind; closing `Bi4j` deletes every page file it created.
- No regression: the current suite and the smoke reference stay green.
- Complete Javadoc on every public type of the SDK package.

## 12. Phases

1. **SDK core** (this spec):
   - `Bi4j`, builder, sessions, `generate` and `stream` with cancellation and stop sequences;
   - `embed`, `capabilities` and the typed errors;
   - automatic and manual `DType` with the memory estimate;
   - the flag check, JFR events and the example.
2. **Encapsulation and migration:** `module-info`, the dependency check from section 9, Javadoc that covers only
   the SDK, and the migration guide and OpenRewrite recipe updated from the old API to the SDK.
3. **Web server on the SDK** (sub-project 2, its own spec): Spring Boot 4, JTE + htmx with a ChatGPT-like UI. The
   server is the SDK's first real client, and anything it misses goes back into this spec.
