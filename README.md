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
./run-cli.sh restapi tjake/Llama-3.2-1B-Instruct-JQ4 --auto-download
```

Open http://localhost:8080/ in your browser.

```
Usage:

bi4j [COMMAND]

Inference:
  chat                 Chat with the model
  restapi              OpenAI-compatible REST API
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

Enable the Vector API in the JVM: `--add-modules jdk.incubator.vector --enable-preview`.

```java
public void sample() throws IOException {
    String model = "tjake/Llama-3.2-1B-Instruct-JQ4";
    String workingDirectory = "./models";
    String prompt = "What is the best season to plant avocados?";

    // Downloads the model, or returns the local path if it is already downloaded
    File localModelPath = SafeTensorSupport.maybeDownloadModel(workingDirectory, model);

    // Loads the quantized model
    AbstractModel m = ModelSupport.loadModel(localModelPath, DType.F32, DType.I8);

    PromptContext ctx;
    if (m.promptSupport().isPresent()) {
        ctx = m.promptSupport()
                .get()
                .builder()
                .addSystemMessage("You are a helpful chatbot who writes short responses.")
                .addUserMessage(prompt)
                .build();
    } else {
        ctx = PromptContext.of(prompt);
    }

    // The fourth argument is the total token limit (prompt included)
    Generator.Response r = m.generate(UUID.randomUUID(), ctx, 0.0f, 256, (s, f) -> {});
    System.out.println(r.responseText);
}
```

Or with the **Builder API**:

```java
Generator.Response r = m.generateBuilder()
        .session(UUID.randomUUID())            // default: UUID.randomUUID()
        .promptContext(ctx)                    // required, or prompt(String text)
        .ntokens(256)                          // default: 256
        .temperature(0.0f)                     // default: 0.0f
        .onTokenWithTimings((s, t) -> {})      // default: does nothing
        .generate();
```

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
