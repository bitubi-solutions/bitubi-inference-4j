# bitubi-inference-4j SDK: developer experience (DX)

Date: 2026-10-12. Status: **proposal, awaiting the author's approval** (nothing implemented).

## 1. Goal

Whoever integrates the engine into a Java app (Spring Boot, Quarkus, Micronaut or a plain `main`) should reach the
first token in a few lines, without knowing about tensors, `DType` or JVM flags, and with errors that
say what to do. The project is new; backward compatibility is kept where it is cheap (the current API
keeps working), but the new API is the documented entry point.

## 2. What exists today (read in the code)

| Point | Situation | Effect for the integrator |
|---|---|---|
| Entry | Three loose statics: `SafeTensorSupport.maybeDownloadModel(dir, id)`, `ModelSupport.loadModel(File, DType, DType)` and `AbstractModel.generate(UUID, PromptContext, float, int, BiConsumer)` | The beginner has to discover the order and the meaning of `DType.F32, DType.I8` |
| Central type | `AbstractModel` exposes `forward`, `batchForward`, `makeTensor`, `sample`, `getLogits` | Huge public surface, with the internal one mixed into the usage one; hard to keep stable |
| Token limit | The 4th argument of `generate` is the **total** limit (prompt included); a low value produces `IllegalArgumentException: Prompt exceeds max tokens` | Silent trap (happens with 16 on a normal prompt) |
| Streaming | Only a `BiConsumer<String, Float>` callback; no cancellation | Does not fit `Flux`, SSE, `Flow` or `Stream` |
| Lifecycle | `Generator extends Closeable` (good), but no warning when you forget to close it | Off-heap memory leak |
| JVM | Requires `--add-modules jdk.incubator.vector` (and `--enable-native-access`) | Fails at runtime if the app does not pass the flags; poorly guided message |
| Configuration | Static `-D` properties (`bi4j.*`), read at class load | Cannot be configured from code or from `application.yml` |
| Errors | Generic `IOException` and `IllegalArgumentException` | No types for "model not found", "unsupported format", "out of memory" |
| Packaging | One `-core` artifact (Panama by default; native is optional); no BOM | Acceptable; missing a BOM and integration modules |
| Integrations | The existing LangChain4j integration points to the old Jlama artifacts | Breaks with the rename; needs its own adapter |

## 3. Proposed API (thin layer on top of the current one)

```java
try (Bi4j bi4j = Bi4j.builder()
        .model("tjake/Llama-3.2-1B-Instruct-JQ4")   // Hugging Face id or local path
        .modelsDir(Path.of("models"))
        .build()) {                                  // downloads and loads; picks the right DTypes

    ChatResponse r = bi4j.chat()
        .system("You are a concise assistant.")
        .user("When is the best time to plant avocado?")
        .maxNewTokens(256)                           // NEW tokens, without the total-limit trap
        .temperature(0.2f)
        .generate();
    System.out.println(r.text());

    bi4j.chat().user("Hello").stream()               // Flow.Publisher<Token>, cancellable
        .subscribe(token -> System.out.print(token.text()));

    float[] vector = bi4j.embed("text");             // embedding models
}
```

Principles:
- **A single entry point** (`Bi4j`), immutable and thread-safe once built; `AutoCloseable`.
- **Smart defaults:** `DType` chosen from the model and the hardware (`MachineSpec`); the beginner does not see tensors.
- **Limit on new tokens** (`maxNewTokens`); the total limit stays in the old API.
- **Reactive streaming:** `Flow.Publisher<Token>` (JDK, no dependency) and a blocking `Iterator`/`Stream`.
- **Typed errors** (`ModelNotFoundException`, `UnsupportedModelException`, `EngineSetupException`) with the cause and the fix in the message.
- **Fail early and guided:** at build time, if `jdk.incubator.vector` is not enabled, the message states exactly the flag and where to put it (Maven Surefire, Spring Boot plugin, `JAVA_TOOL_OPTIONS`).
- The old API (`ModelSupport`, `AbstractModel.generate`) does not change behavior; the new one sits on top of it.

## 4. New modules

| Module | Contents | Note |
|---|---|---|
| `bitubi-inference-4j-bom` | Manages the versions of all artifacts | One line in the client's `dependencyManagement` |
| `bitubi-inference-4j-sdk` | `Bi4j`, builders, `Token`, `ChatResponse`, exceptions | Depends only on `-core` |
| `bitubi-inference-4j-spring-boot-starter` | Auto-configuration: `bi4j.model`, `bi4j.models-dir`, `Bi4j` bean, health indicator | Targets Spring Boot 4 (aligned with sub-project 2) |
| `bitubi-inference-4j-langchain4j` | `ChatModel`/`EmbeddingModel` adapter | Replaces the old Jlama integration |
| `examples/` | Plain `main`, Spring Boot and streaming | Compiled in CI so they do not rot |

Spring AI and Quarkus come after the starter is validated.

## 5. Acceptance criteria (measurable)

- From zero to the first token in **≤ 10 lines** of application code (not counting imports).
- A sample Spring Boot app answers a prompt with **only** the starter dependency and `bi4j.model=...`.
- Forgetting `--add-modules jdk.incubator.vector` produces **one** error message with the fix, tested.
- `maxNewTokens` never triggers `Prompt exceeds max tokens` because of the prompt size (tested with a long prompt).
- Complete Javadoc on the public types of `-sdk`; the examples compile in CI on Linux, Windows and macOS.
- No regression: the current suite and the smoke test (output equal to the reference) stay green.

## 6. Phases

1. `-sdk` with `Bi4j`, synchronous chat, streaming, embeddings, typed errors and the flag check (no Spring).
2. BOM and examples compiled in CI.
3. Spring Boot starter (together with the upgrade to Spring Boot 4 and bytecode 25).
4. LangChain4j adapter.

## 7. Open questions

- The `--add-modules` flag cannot be enabled from code in classpath apps: the starter can only validate and guide. Confirm whether there is a viable mechanism via manifest or plugin before promising "zero flags".
- Streaming cancellation: the current engine does not expose interruption mid-generation; check the cost of introducing it in `generate`.
- Public package name: `ao.bitubi.inference4j` (current) or a dedicated `...sdk` package for the new API.
- Automatic `DType` choice: needs a per-model and per-RAM criterion; define it with `engine-perf`.
