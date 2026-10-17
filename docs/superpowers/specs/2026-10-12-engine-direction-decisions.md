# Engine direction: backends, portable build, C or Rust, Valhalla

Date: 2026-10-12. Status: **author's decisions recorded; the portable build design awaits approval**.

## 1. Author's decisions

- The `naive` backend is dropped. What remains is **Panama** (Java, Vector API) and **Native** (C with SIMD). The scalar class became a test-only oracle (`ScalarReferenceOperations`).
- A **portable build** of the native code is wanted.
- The idea is to use the power of Java in the engine and, later, build a cluster and an enterprise product with small open models (SLMs) that are optimized and fine-tuned.

## 2. Measured evidence (on this machine: Core Ultra 7 255H, JDK 25, WSL2)

- **The native backend is hybrid.** Only the GEMMs run in C. `accumulate`, `saxpy`, `scale` and `quantize` are delegated to Panama (Java).
- **The native backend's wrong answer came from a defect in the GEMM block driver**, which did not compute the corner of the leftovers. The same defect existed in Panama, in `F32xF32`. Fixed in C and in Java (`docs/backends.md`).
- **Where Panama's memory goes.** With Native Memory Tracking, the difference in peak RSS between Panama and native was almost entirely **committed Java heap** (585 MB versus 328 MB in one measurement); JIT, classes and threads are the same in both. The tensors (weights, activations, KV) live outside the heap and are the same.
- **RSS varies a lot between runs of the same backend** (Panama gave 793 to 1143 MB), because it depends on how much G1 expands the heap. Backends must not be compared by a single RSS; the heap must be fixed (`-Xmx`/`-Xms`) or the allocation rate measured.
- **Allocation profile (JFR) of Panama:** 84% to 87% of the allocation pressure was `int[]` created in the GEMM hot loop, by the varargs parameters of `getVector(species, int... )`, `get(int...)` and `set(float, int...)`. They were not `Vector` objects. With two-int overloads, that `int[]` dropped to 5% (the `Float256Vector`s, 12% of a much smaller volume, became visible). **Effect on tok/s: not yet demonstrated** (see section 6).

## 3. Do value classes (Valhalla) reduce Panama's consumption?

Verified facts ([JEP 529](https://openjdk.org/jeps/529), JDK 26, "Closed / Delivered"):
- The Vector API "will incubate until necessary features of Project Valhalla become available as preview features". The long-term goal is to turn the current (value-based) classes into *value classes*.
- Today C2 treats `Vector` instances specially to "overcome limitations in escape analysis and avoid boxing"; the JEP states that in the benchmarks "all Java object allocations are elided".
- The JEP **says nothing about memory consumption**.
- The Valhalla dates (JEP 401 in preview in JDK 28 or 29) come from secondary sources and conflict; there is no confirmed date. It does not exist in JDK 25.

Conclusion (inference, not measured): **it is not a memory lever.** The weights, the KV cache and the activations are outside the heap, and value classes do not touch them. What value classes can give is **performance predictability** (less dependence on C2 managing to eliminate boxing). The memory and GC gains we can get today come from removing allocations from the hot loop, which is already possible in Java 25.

## 4. C or Rust for the native code?

What the native code needs to be: a few thousand lines of SIMD kernels (quantized GEMM), called through FFM, on Linux, Windows and macOS (x86_64 and ARM64), compiled without being tied to the CPU of whoever compiles it.

| Criterion | C (today) | Rust |
|---|---|---|
| Cost now | zero: the code exists and is now correct and tested | rewrite and re-validation of every kernel |
| x86 SIMD | intrinsics (`immintrin.h`); per-file variants with `-mavx2`, `-mavx512f`... | `std::arch` with `#[target_feature]` per function, no separate files; AVX-512 stable since Rust 1.89 (Aug. 2025, according to the tech press; confirm in the official notes) |
| Runtime selection | `__builtin_cpu_supports` or CPUID | `is_x86_feature_detected!` (standard library default) |
| Memory safety | manual (the edge defects we found are of this kind) | bounds checking and localized `unsafe`, but in the kernels `unsafe` is unavoidable |
| Binding with Java | `jextract` reads the C header directly | `extern "C"` + `cbindgen` to generate the header, then `jextract`: one more step |
| Cross-compilation and CI | `gcc`/`clang` per platform, `Makefile` and `antrun` already in place | `cargo` greatly simplifies cross-compilation and packaging |
| Team | C is what exists | requires Rust skills |

**Recommendation (opinion, not yet measured):** keep **C** and do the portable build now; do not rewrite in Rust as a precaution. Rationale: Rust's gain (safety and packaging) is real but it is not proven to be worth the rewrite, and the immediate risk (old CPUs) is solved the same way in both. Propose a **time-boxed spike**: port only the `F32xQ4` kernel to Rust, measure performance and effort against C, and decide with evidence. If native remains strategic and the team wants it, Rust becomes a strong option.

A question that weighs more than the language: how much of the native gain can Java reach? Native prefill was about 2.4x faster than Panama's, but Panama still has allocations in the hot loop. It is worth **exhausting Java** (section 6) before investing in rewriting the native code.

## 5. Portable native build (proposal, awaiting approval)

Verified problem: Linux compiles with `-march=native`, that is, for the CPU of whoever compiles; a CI JAR can end with `SIGILL` on older CPUs.

Design:
1. **Fixed baseline:** the common code compiles for a minimum target (x86-64-v2, or v3 if AVX2 is the chosen floor; ARM64 with NEON).
2. **One compilation unit per ISA**, each with its own flags: AVX2+FMA+F16C; AVX-VNNI; AVX-512 (+VNNI). On ARM: NEON, and dotprod/i8mm as variants.
3. **Runtime selection** at initialization, via CPUID, returning a function table; the Java flag that today only chooses the 512 path becomes the real capability mask.
4. **Test every variant on a single machine** by forcing the capability mask down (the `TestNativeRemainderTiles` and `TestOperations` tests run per variant).
5. **Automatic verification:** `objdump` on the baseline unit to ensure it contains no instructions above the floor; and a CI run on the lowest-capability runner.
6. One artifact per operating system and architecture (existing classifier), published as today.

Acceptance criteria: the same JAR gives the same text (the `SmokeTest` reference) on every selectable variant; no instruction above the floor in the baseline unit; no performance regression in the highest variant compared with the current `-march=native` (measured in interleaved rounds).

## 6. Experiment: allocations in Panama's hot loop (branch `perf/no-varargs-alloc`)

Change: non-varargs overloads (`getOffset(i, j)`, `getVector(species, i, j)`, `get(i, j)`, `set(v, i, j)`) in `TensorShape`, `AbstractTensor`, `FloatBufferTensor` and `Q4ByteBufferTensor`; the old methods remain.

- **Proven:** the `int[]` allocation pressure in the kernel dropped from 87% to 5% (JFR). The per-operation tests, the edge tiles, backend selection and the smoke test (output equal to the reference) pass: 28 tests, 0 failures.
- **Not proven:** the effect on speed. Two A/B runs (4 and then 4 samples) gave contradictory results (the "after" runs came out worse, 12 versus 17 to 31 tok/s) but on a host with a load average of 8 to 12 on 16 CPUs, with model loading varying from 4 s to 18 s. They do not allow any conclusion, neither for nor against. Repeat on a quiet machine, with copies built side by side, alternating before and after.
- Decision: **do not merge as an optimization until the A/B exists**; it stays on the branch as an experiment.

## 7. Product direction (hypotheses to validate, not facts)

- For small models (0.5B to 8B), the model fits on one node. **"Cluster" tends to mean replicas, routing, queues and isolation between customers**, more than splitting a model across several nodes (which is what the current distributed code does, and serves models with tens of billions of parameters). This is worth validating before prioritizing sub-project 3.
- Fine-tuned models arrive as SafeTensors (LoRA already merged) or GGUF. Support for GGUF (spec in `2026-10-12-gguf-slice1-design.md`) and, later, for **LoRA adapters** (loading several adapters on top of a shared base model) can greatly reduce memory per customer. The old README already listed LoRA as pending.
- **Continuous batching of requests** (several concurrent sessions on the same model) is what decides throughput per machine. I have not verified whether the current engine supports it; it is an important open question.

## 8. Proposed next steps

1. Finish the Panama A/B and decide on the overloads.
2. Approval of the portable build design (section 5), then plan and implementation, with the Rust spike in parallel and time-boxed.
3. GGUF slice 1 (spec already written, awaiting review).
4. Investigate continuous batching and the feasibility of LoRA adapters, before designing the cluster.
