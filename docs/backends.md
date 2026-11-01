# Tensor operation backends

Measurements from 2026-10-12, JDK 25, Intel Core Ultra 7 255H (AVX2 and AVX-VNNI, no AVX-512), WSL2.

## What exists today

There are **2 tensor operation backends** (`naive`, the scalar one, was dropped by the author's decision on 2026-10-12). There is no GPU backend (WebGPU is a spike still to be evaluated, sub-project 1b).

| Backend | `bi4j.backend` value | What it is | Status |
|---|---|---|---|
| Panama | `panama` | Pure Java with the Vector API (`jdk.incubator.vector`). Paths for AVX-512, AVX2 (256-bit) and 128-bit ARM | **Works and is the default** |
| Native | `native` | C with SIMD, called through FFM (module `bitubi-inference-4j-native`) | **Works after the fix in this branch**; remains opt-in (see "Before making native the default") |

## How to choose

```bash
java -Dbi4j.backend=panama ...      # auto (default) | panama | native
```

- `auto` (default): Panama, which requires the Vector API (`--add-modules=jdk.incubator.vector`); without it, it fails with a message that says what to do. **It never picks native on its own.**
- The `jlama.` prefix and the `use_native_tensor_operations` flag were removed (no backward compatibility): only `bi4j.backend` selects the backend. If either one is set, the engine logs a single WARN with the new name and does **not** apply the value.
- An explicit choice that cannot be satisfied fails with a message that says what to do: `auto` and `panama` without the Vector API enabled ask for `--add-modules=jdk.incubator.vector`; `native` without the module on the classpath asks for the artifact; an unknown name lists the valid ones.
- The backend in use shows up in the log (`Using Panama Vector Operations (OffHeap)`) and in the `PERF` line of the performance test.

## Native variants

On x86-64 the library has an x86-64 base unit without AVX instructions, plus separate AVX2+FMA, AVX-VNNI and AVX-512+VNNI units. At run time it checks CPUID and the operating system's AVX state (`XGETBV`) before picking a variant. AVX2+FMA is the minimum to run the native backend. `auto` keeps picking Panama when the Vector API is available; asking for `native` on a CPU below the floor logs a warning and uses Panama.

`bi4j.native.isa` caps the highest variant picked, so a lower variant can run on the same machine:

```bash
java -Dbi4j.backend=native -Dbi4j.native.isa=avx2 ...
java -Dbi4j.backend=native -Dbi4j.native.isa=avxvnni ...
java -Dbi4j.backend=native -Dbi4j.native.isa=auto ...
```

Accepted values: `auto`, `avx2`, `avxvnni`, `avx512`; on ARM64, `neon` and `neon-dotprod`. A cap above what the CPU supports selects the highest available variant below it and leaves a warning in the log. The list for the CPU appears in the log when the backend is created. On ARM64 the base unit is NEON; `neon-dotprod` is selected at run time (`sysctl` on macOS, `getauxval` on Linux).

On Linux x86-64, `check-isa.sh` uses `objdump` to require all the objects, and to confirm that the base unit has no VEX/EVEX, that AVX2 has no AVX-VNNI/AVX-512, that AVX-VNNI has no AVX-512, and that the advanced units contain the expected instructions. The AVX-512 variant is compiled and inspected on CPUs without AVX-512, but must not be executed on them.

## Why native gave wrong answers (root cause, verified)

**It was not JDK 25.** The GEMM tile driver, in C (`gemm`, in `vector_simd.c`) and its Java copy
(`Gemmer.mnpack`, in `PanamaTensorOperations`), splits the output matrix into tiles of up to 5x5. The leftover
cells (rows that do not fill a tile, columns that do not fill a tile) are handled by two recursive
calls: the bottom strip and the right strip. **The corner where the two leftovers meet was never computed.**
Those cells kept whatever value was already in memory.

- In decode (M = 1 row) there are no leftover rows, so the corner does not exist and everything was correct.
- In prefill (M = prompt tokens, e.g. 37) there are leftover rows and columns, and the last rows of the result were never computed. The model received garbage in the prompt and answered with a refusal.
- This affected all four native type combinations (`F32xF32`, `F32xQ4`, `F32xBF16`, `I8xQ4`).
- **Panama, the default backend, had the same defect** in `F32xF32` (12 of 70 tested shapes failed, for example M=5, N=5). The other Panama types escaped because their kernels use 1-column or 1-row tiles.

Fix: a third recursive call for the corner, in both places. Test: `TestNativeRemainderTiles` compares both
backends, across 4 type combinations and 70 shapes (M in 1..37, N in 1..112), against a plain-loop oracle; it failed before
(native: 15 wrong shapes per type; Panama: 12 in `F32xF32`) and passes now.

How the defect was found: `ShadowNativeInvestigation` (opt-in with `SHADOW=1`) runs the model on Panama and repeats every operation on
native over copies, reporting where they diverge. A first version gave false positives because each call compared the whole result
while other threads were filling other parts; the current version uses private buffers with a sentinel value.

## Measurement (after the fix)

Quantized Qwen2.5-0.5B-Instruct (`tjake/Qwen2.5-0.5B-Instruct-JQ4`, F32 and I8 working memory), fixed prompt, temperature 0,
2 warm-up runs and 5 measured runs per round (`PERF_BASELINE=1`, `PerfBaselineTest`). Rounds **interleaved in the same session**,
with the output checked against the reference (the smoke test passes on both).

| Round | Backend | Prefill tok/s | Decode tok/s | Peak RSS (MB) | Load (ms) |
|---|---|---|---|---|---|
| 1 | Panama | 47.5 | 17.4 | 1132 | 2254 |
| 2 | Native | 109.3 | 20.1 | 786 | 4187 |
| 3 | Panama | 39.4 | 15.9 | 1143 | 3613 |
| 4 | Native | 102.1 | 17.8 | 856 | 4750 |

| | Panama (mean of 2) | Native (mean of 2) | Ratio |
|---|---|---|---|
| Prefill | 43.5 | 105.7 | **about 2.4x** |
| Decode | 16.7 | 19.0 | +14% (the ranges overlap: 15.9 to 17.4 versus 17.8 to 20.1) |
| Peak RSS | 1138 MB | 821 MB | **about 28% less** |
| Model load | 2.9 s | 4.5 s | native slower to load |

Notes:
- **Memory:** the number that matters is peak RSS (`VmHWM`, Linux). Tensors live outside the Java heap, so `heap_used_mb` greatly underestimates. On other systems RSS shows as -1.
- **Speed:** prefill = prompt tokens per second; decode = generated tokens per second.
- **Noise:** this machine varies a lot between sessions (an hour earlier Panama measured 24 and 8 tok/s). Only compare rounds interleaved in the same session. Two rounds per backend give the order of magnitude, not a confidence interval.

## Memory for GGUF F16 models

The GGUF loader widens F16 to F32 in `ByteBuffer.allocateDirect`. The Qwen2.5-0.5B F16 file used in this slice has 1,266,425,696 bytes; the widened weights take about 2.53 GB of direct memory (not counting the KV cache and temporaries). With the default direct limit observed on this machine (~1.94 GB) loading failed; the smoke test passed with `JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=4g`.

Using `Arena`/`MemorySegment` to allocate outside `ByteBuffer` is technically possible, but it is not a small substitution: today `FloatBufferTensor` creates the segment from the `ByteBuffer`, and `AbstractModel.close()` closes the KV cache, with no shared owner or lifetime for the weights' memory. It would need ownership and closing of the weights' arena, and a guarantee that tensors and views do not outlive the model. It was neither implemented nor measured.

## Which one is the most efficient

Among the working ones: **native**, on this hardware and this model, in prefill (about 2.4x) and in memory (about 28% less RSS); in decode the advantage is small and within the noise. Panama remains the default because it does not depend on a library compiled per operating system.

## Where the memory goes (Native Memory Tracking and JFR)

- The peak RSS difference between Panama and native was almost entirely **committed Java heap** (585 MB versus 328 MB in one measurement); JIT (Code 19.6 versus 20.0 MB), classes and threads are the same. Tensors live outside the heap and are the same on both.
- **RSS varies a lot between runs of the same backend** (Panama ranged from 793 to 1143 MB), because it depends on G1 heap expansion. To compare memory you have to fix the heap size or measure the allocation rate.
- Panama allocation profile: **84% to 87% were `int[]` created in the GEMM hot loop** (varargs parameters of `getVector`, `get` and `set`). The `perf/no-varargs-alloc` branch adds two-int overloads and that share drops to 5%. **The effect on tok/s is not demonstrated:** the A/B runs done suffered from host load (model load went from 4 s to 18 s) and do not allow a conclusion. The change has green tests (28) and output identical to the reference, but it should not be merged as an "optimization" without an A/B on a quiet machine.

## Before making native the default

1. **Portability between CPUs:** the native module now compiles an x86-64 base without AVX and picks AVX2+FMA, AVX-VNNI and AVX-512+VNNI kernels by CPUID. The native backend's floor is AVX2+FMA; on CPUs below it, a request for `native` falls back to Panama with a warning in the log. `check-isa.sh` checks the x86 objects in the Linux build.
2. **Apple Silicon and Windows:** native was measured only on Linux x86.
3. **Test coverage:** `TestOperations` only includes native when it is the active backend; there are now also `TestNativeRemainderTiles` and `TestNativeRemainderTilesExtended`, which always run it.
4. **Model load:** native took longer to load (4.5 s versus 2.9 s); cause not investigated.

## Defects still open

No known correctness defect in either backend. Open: the native build is not portable across CPUs (`-march=native`), and the Panama hot loop still allocates (see above).

## Reproduce

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-amzn
JDK_JAVA_OPTIONS="-Dbi4j.backend=native" PERF_BASELINE=1 ./mvnw -B -pl bitubi-inference-4j-tests -am package \
  -Dtest=PerfBaselineTest -Dsurefire.failIfNoSpecifiedTests=false
# optional variables: PERF_WARMUP, PERF_RUNS, PERF_MAX_TOKENS (the token total includes the prompt)
# operation-by-operation diagnosis (native versus Panama): SHADOW=1 ... -Dtest=ShadowNativeInvestigation
```
