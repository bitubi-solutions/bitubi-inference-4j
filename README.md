<p align="center">
  <img src="docs/logo.svg" width="160" height="160" alt="bitubi-inference-4j">
</p>

# bitubi-inference-4j

LLM inference engine for Java, maintained by [Bitubi Solutions](https://github.com/bitubi-solutions).
It runs models directly on the JVM, using the [Vector API](https://openjdk.org/jeps/529) (Panama) to
accelerate the CPU, and exposes a CLI, an OpenAI-compatible API, and distributed inference.

> **Origin.** This project is a maintained fork of [Jlama](https://github.com/tjake/jlama)
> (© T Jake Luciani and contributors, Apache 2.0). See the [`NOTICE`](./NOTICE) file.

## Features

Models:
  * Gemma and Gemma 2
  * Llama, Llama 2 and Llama 3
  * Mistral and Mixtral
  * Qwen2
  * IBM Granite
  * GPT-2
  * BERT
  * BPE and WordPiece tokenizers

Implements:
  * Paged attention
  * Mixture of Experts
  * Tool calling
  * Embeddings
  * Classifiers
  * Models and tokenizers in the Hugging Face [SafeTensors](https://github.com/huggingface/safetensors) format
  * F32, F16 and BF16 types
  * Q8 and Q4 quantization
  * Fast GEMM (Panama / Vector API by default)
  * Distributed inference

Requires **JDK 25 or later** (the Vector API is still an incubator module: `--add-modules jdk.incubator.vector`).
Only JDK 25 is tested; newer versions may change the Vector API.

Tensor backends: the default is Panama (`-Dbi4j.backend=auto|panama|native`). The native backend is never chosen on its own
(it was incorrect because of a defect in the edge-tile computation, now fixed; it still has to be made portable across CPUs). Details and measurements in
[`docs/backends.md`](./docs/backends.md).

## Quick start

Build and run the CLI (command `bi4j`):

```shell
export JAVA_HOME=/path/to/jdk-25
./mvnw -B clean package -DskipTests
./run-cli.sh serve tjake/Llama-3.2-1B-Instruct-JQ4 --auto-download
```

Open http://localhost:8080/ in your browser.

```
Usage:

bi4j [COMMAND]

Inference:
  chat                 Chat with the model
  serve                OpenAI-compatible REST API (alias: restapi)
  complete             Complete a prompt

Distributed inference:
  cluster-coordinator  Distributed REST API using workers
  cluster-worker       Connect to a coordinator for distributed inference

Other:
  download             Download a model from Hugging Face (owner/name format)
  list                 List local models
  quantize             Quantize a model
```

Pre-quantized models are available at https://hf.co/tjake.

## Use in a Java project

The artifacts use the `groupId` `ao.bitubi`. Publishing starts on GitHub Packages
(`https://maven.pkg.github.com/bitubi-solutions/bitubi-inference-4j`), which requires a token with `read:packages`.

```xml
<dependency>
  <groupId>ao.bitubi</groupId>
  <artifactId>bitubi-inference-4j-core</artifactId>
  <version>${bitubi-inference-4j.version}</version>
</dependency>
```

The engine needs the Vector API: start the JVM with `--add-modules jdk.incubator.vector`. The SDK checks this when
it loads a model and, if the flag is missing, says exactly where to add it.

### SDK

The SDK lives in the package `ao.bitubi.inference4j.sdk` and uses only what the JDK offers.

```java
try (Bi4j bi4j = Bi4j.load("tjake/Llama-3.2-1B-Instruct-JQ4");
     Stream<Token> tokens = bi4j.chat().user("When is the best time to plant avocado?").stream()) {
    tokens.forEach(token -> System.out.print(token.text()));
}
```

One answer at a time, as a value:

```java
ChatResponse r = bi4j.chat()
    .system("You are a concise assistant.")
    .user("When is the best time to plant avocado?")
    .maxNewTokens(256)       // new tokens only; the prompt does not count
    .temperature(0.2f)
    .stop("\n\n")           // optional stop sequences
    .generate();
System.out.println(r.text() + " (" + r.usage().tokensPerSecond() + " tokens/s)");
```

A conversation keeps its history in the model, so each turn only processes the new message:

```java
try (ChatSession chat = bi4j.newSession(s -> s.system("Be brief."))) {
    chat.send("My name is Ana.");
    System.out.println(chat.send("What is my name?").text());
}
```

For a Stop button, a status line or a server, `start` returns a `Generation`: the tokens, the outcome and a cancel that
is safe from any thread. `restore` feeds a saved conversation back into a fresh session without generating, and the
builder can report the steps of a load:

```java
try (Generation g = chat.start("Tell me a story", GenerationSettings.defaults().withTemperature(0.3f))) {
    new Thread(() -> g.tokens().forEach(t -> System.out.print(t.text()))).start();
    // ... when the user presses Stop, from any thread:
    g.cancel();                       // the reader's stream ends normally
    g.result().ifPresent(r -> System.out.println(r.finishReason() + " " + r.usage()));
}
chat.contextRemaining();              // tokens left; chat.restore(savedMessages) after a restart
Bi4j.builder().model("tjake/Llama-3.2-1B-Instruct-JQ4").onProgress(p -> System.out.println(p.phase())).build().info();
```

* **Streaming is a `java.util.stream.Stream<Token>`.** Closing it, or ending it early with `limit` or `takeWhile`
  inside try-with-resources, stops the generation at the next token.
* **Sessions are persistent by default** (the KV cache stays until `close()`). `s.temporary()` frees it after every call.
* **Working types are automatic**: the SDK estimates the memory before loading and fails with the numbers and the fix
  if the model does not fit. `Bi4j.builder().workingTypes(...)` overrides it at your own risk, with a warning.
* **Errors are typed**: `Bi4jException` is sealed (`ModelNotFoundException`, `UnsupportedModelException`,
  `InsufficientMemoryException`, `EngineSetupException`, `SessionBusyException`, `ContextLengthExceededException`,
  `UnsupportedInputException`), and every message says what to do.
* **Observability** needs no dependency: JFR events `ao.bitubi.inference4j.Load` and `ao.bitubi.inference4j.Generation`.

**On the module path** the engine is a named module, `ao.bitubi.inference4j`, that exports only the `sdk` package, so
the compiler and reflection refuse everything else, and the module requires the Vector API itself (no
`--add-modules` flag needed). On the class path Java cannot hide packages: there, treat everything outside `sdk` as
internal. Two limits: the optional native backend works on the class path only (its package is split with the
engine), and `jinjava` and `jctools-core` have no module name of their own, so the module requires the names
Java derives from their file names.

Runnable versions are in [`examples/`](./examples). The classes outside the `sdk` package (`AbstractModel`,
`ModelSupport` and the like) are the engine: they are internal and may change without notice.

## Roadmap

* GGUF support and more models, with a focus on older hardware
* Anthropic API and improvements to the OpenAI API
* htmx + JTE UI on Spring Boot 4
* Cloud-ready coordinator/worker
* Optional GPU backend via WebGPU (under evaluation)
* Agent harness in the CLI

## License and origin

The code is under the [Apache License 2.0](./LICENSE). Contributions follow the
[contributing guide](./CONTRIBUTING.md) (signed-off commits, DCO).

If this work is useful in your research, please cite the original project:

```
@misc{jlama2024,
    title = {Jlama: A modern Java inference engine for large language models},
    url = {https://github.com/tjake/jlama},
    author = {T Jake Luciani},
    month = {January},
    year = {2024}
}
```
