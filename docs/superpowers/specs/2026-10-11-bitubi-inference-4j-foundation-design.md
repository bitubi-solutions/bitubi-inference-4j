# bitubi-inference-4j: overview and foundation (sub-project 0)

Date: 2026-10-11. Status: awaiting the author's review.

## 1. Intent

Continue Jlama (an LLM inference engine in Java, upstream `tjake/jlama`, abandoned by its owner) as a new product, **bitubi-inference-4j**, maintained by the `bitubi-solutions` organization.

- **Identity:** rebrand with a new name, crediting the original Jlama (Apache 2.0).
- **Business model:** open core. Open core (engine, SDK, server, basic cluster) under Apache 2.0. Paid enterprise extras (Kubernetes operator, multi-tenancy, SSO, quotas, auditing, hosting, support) in a separate repository that depends on the open one via SPI, never the other way around.
- **Overall success criterion:** a Java engine that is competitive on old hardware, usable as an SDK, as a self-hosted server (OpenAI and Anthropic APIs), and as a cluster that justifies moving to the cloud.

Assumptions (from the author): `groupId` `ao.bitubi`, CLI `bi4j`, repository migrated to `bitubi-solutions`.

## 2. Breakdown into sub-projects

Each one has its own spec, plan and implementation cycle.

| # | Sub-project | Depends on |
|---|---|---|
| 0 | Foundation: rename, attribution, CI, migration, test publication | nothing |
| 1 | Engine and SDK: new models, optimization for old hardware, stable API | 0 |
| 1b | GPU backend via WebGPU (spike first) | 1 |
| 2 | Server and UI: improved OpenAI API, Anthropic API, htmx + JTE on Spring Boot 4 | 1 |
| 3 | Cluster for the cloud: evolved coordinator/worker (HA, autoscaling, worker discovery) | 2 |
| 4 | `bi4j` agent harness (Claude Code style), **last**, after the engine stabilizes | 2 |

This spec details only sub-project 0. The others have their intent and constraints recorded below, but will be specified when their turn comes.

### 2.1 Constraints already decided for the next sub-projects

- **Sub-project 1.** Target: JDK 25 LTS as the minimum and only supported version (author's decision; JDK 21 is dropped). Research (2026-10) found no new, proven performance gain between JDK 21 and 26: the Vector API is still incubating (JEP 529, JDK 26, no substantial changes since 25), and Valhalla (JEP 401) is only expected as a preview in JDK 28/29. The gains for old hardware will come from the engine: GEMM kernels with a fallback for CPUs without AVX-512/AVX2, aggressive quantization, tiling for cache and threads, and small, efficient models (Phi, Gemma 3, Qwen3, Llama 3.2). Definition of done: benchmarks on a CPU without AVX-512.
  - **GGUF support (author's requirement).** The engine today reads only SafeTensors; GGUF is the most widely used format for quantized models and gives direct access to the existing ecosystem (Hugging Face, llama.cpp). Scope: a GGUF reader (metadata, embedded tokenizer, tensors), the most common quantization types (Q4_0, Q8_0 and the K-quants such as Q4_K_M, Q5_K_M, Q6_K), with dequantization/GEMM kernels for them, and direct loading without conversion. It needs a model-format abstraction layer (SafeTensors and GGUF behind the same interface). Open decision for the sub-project 1 spec: which architectures to enable first (Llama, Qwen, Gemma, Phi) and whether the custom JQ4 format remains supported.
- **Sub-project 1b (WebGPU).** Not verified that a mature Java binding for WebGPU exists. Hypothesis to test: bind `wgpu-native` via FFM (final API since Java 22), with matmul/attention kernels in WGSL. **It starts with a spike** (load a small model and measure tokens/s against the CPU path, on real old hardware) before any scope commitment. The backend must be optional and fall back to the CPU if there is no GPU.
- **Sub-project 2.** UI with htmx + JTE on Spring Boot 4 (Java 17 baseline). There is a JTE starter for Boot 4 and htmx-spring-boot 5.x declaring compatibility, but no official announcement putting the three together: validate versions on Maven Central before pinning them. APIs: OpenAI (streaming, tools, embeddings, `/v1/models`) and Anthropic (`/v1/messages`, streaming, tool use).
- **Sub-project 3.** The open source coordinator/worker must evolve to the point where self-hosting already justifies moving to the cloud (HA, autoscaling, discovery, queues). The managed operator stays on the paid side.
- **Sub-project 4.** The harness consumes models through the server API (sub-project 2). Known risk: small local models are weak at tool use; the harness must use lean prompts and a few well-defined tools, with an optional remote model.

## 3. Sub-project 0: foundation

Verified current state: branch `main`, `origin` at `DumiJDev/Jlama`. `git status` shows almost every file as modified, but `git diff --ignore-cr-at-eol --stat` is empty, that is, they are only line-ending differences (CRLF/LF). 154 files reference `com.github.tjake`. Current version `0.8.4`. Workflows: `unit-tests.yaml` and `release.yaml`. The root `pom.xml` references JDK 20, 21 and 22 in profiles.

### 3.1 Steps (one reviewable commit or PR each)

1. **Normalize line endings.** Add `.gitattributes` (`* text=auto eol=lf`) and renormalize.
2. **Migrate to `bitubi-solutions`.** Transfer or recreate the repository in the organization, keeping the history (preserves the original authorship) and adding `upstream` pointing to `tjake/jlama`.
3. **Mechanical rename, in one commit, with no behavior change:**
   - Package `com.github.tjake.jlama` to `ao.bitubi.inference4j` (the hyphen is not valid in a Java package).
   - `groupId` `ao.bitubi`; `artifactId`s `bitubi-inference-4j-core`, `-native`, `-net`, `-cli`, `-tests`, `-parent`.
   - CLI `bi4j` (`run-cli.sh`, `run-cli.bat`, jbang, Dockerfile, compose, kubernetes).
   - `<revision>` restarts at `0.1.0-SNAPSHOT`.
4. **Attribution (Apache 2.0).** `NOTICE` ("Este produto inclui software derivado do Jlama, © Tim Jake e colaboradores", i.e. "This product includes software derived from Jlama, © Tim Jake and contributors"), an "Origin" section in the README, original copyright headers preserved, modified files marked, `conf/license-header` updated. Do not use the original author's name or brand to suggest endorsement.
5. **Build and CI.** JDK 25 as the minimum and only target; CI on Linux, Windows and macOS (ARM and Intel); remove the JDK 20/21/22 profiles and sources (`jlama-native/src/main/java22` becomes `java25`); release workflow to GitHub Packages.
6. **Publication.** By the author's decision, it starts on GitHub Packages (`maven.pkg.github.com/bitubi-solutions/bitubi-inference-4j`); Maven Central comes later (requires verifying the `bitubi.ao` domain, or using `io.github.bitubi-solutions`).
7. **Minimal governance.** `CONTRIBUTING`, DCO or CLA (required before accepting external PRs, to preserve the commercial option), issue templates.

### 3.2 Definition of done

- `./mvnw verify` green in CI on JDK 25, on Linux, Windows and macOS.
- `grep -ri tjake` outside `NOTICE`, the README and attribution headers returns nothing, except Hugging Face model ids (`tjake/...`) and the `tjake/sdkman-for-toolchains` clone in the Dockerfile, which are external names.
- Artifact published on GitHub Packages.
- Generated text identical to the reference recorded before the rename (temperature 0, per JDK), and tokens/s within the variation range of the baseline (`docs/benchmarks/`).
- Smoke test: loads a small model and generates tokens, running in CI.

### 3.3 Risks

- **`jlama-native`:** class names or JNI symbols tied to the old package may break in the rename. The smoke test with a real model detects it.
- **Maven namespace:** without control of `bitubi.ao`, use the fallback.
- **Divergence from upstream:** after the rename, merging from upstream becomes laborious. Accepted, given the rebrand.
- **Pre-quantized models:** those at `hf.co/tjake` use the JQ4 format. Keep compatible reading and decide later where to host our own; with GGUF (sub-project 1) the dependency on our own models decreases.

### 3.4 Tests

The existing tests pass with no logic changes; plus the smoke test above.

## 4. Out of scope for this spec

Any implementation of sub-projects 1 to 4 and of the paid side. Each one will have its own spec.

## 5. Later decisions (2026-10-11 and 2026-10-12)

- **Product name:** `bitubi-inference-4j` (replaces `aby-inference`). `groupId` `ao.bitubi`, Java package `ao.bitubi.inference4j`, artifacts `bitubi-inference-4j-{parent,core,native,net,cli,tests}`, CLI command `bi4j`. `bi4j` is also the agent harness (the CLI), author's decision on 2026-10-12.
- **Panama by default on JDK 25.** The native backend (`jlama-native`) gave a wrong answer in the smoke test on JDK 25 ("I'm sorry, but I can't assist with that." versus "The capital of France is Paris." with Panama). It became opt-in with `-Dbi4j.use_native_tensor_operations=true`. Root cause (2026-10-12): the GEMM block driver did not compute the corner of the leftover rows and columns, in C and in Java; it was not JDK 25. Details in `docs/backends.md`.
- **Bytecode 21 with JDK 25 as the only JDK.** Spring Boot 3.3.x cannot read class version 69; the target moves up to 25 together with the Spring upgrade (sub-project 2).
- **Agent team** in `.claude/agents/` (engine, formats, server, cluster, build, QA, GPU, brand, harness) and project rules in `CLAUDE.md`. Author's rule: never add Claude trailers to commits or PRs.
- **Performance baseline** (Intel Core Ultra 7 255H, WSL2, JDK 25, Panama): `docs/benchmarks/baseline-intel-core-ultra-7-255h.md`.
