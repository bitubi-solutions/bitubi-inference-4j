# Performance baseline: Intel Core Ultra 7 255H (WSL2)

Measured before the rename, at commit `2284237`, with `docs/benchmarks/run-perf.sh` (3 runs).

- Hardware: Intel(R) Core(TM) Ultra 7 255H, 16 CPUs, flags `avx avx2 fma` (no AVX-512), WSL2 on Windows
- JDK: 25.0.3 (Corretto), tensor operations: Panama (Vector API), default
- Model: `tjake/Qwen2.5-0.5B-Instruct-JQ4` (F32 working type, I8), 5 measured runs after 2 warm-up runs per round

| Run | load_ms | prefill tok/s | decode tok/s | heap MB |
|---|---|---|---|---|
| 1 | 4602 | 17.35 | 6.27 | 283 |
| 2 | 6666 | 7.84 | 4.19 | 190 |
| 3 | 4684 | 15.67 | 5.51 | 417 |

**Range (tolerance for "no regression"):** prefill 7.84 to 17.35 tok/s; decode 4.19 to 6.27 tok/s; load 4.6 to 6.7 s.

Run-to-run variation is large (up to 2x in prefill), probably because of hybrid P/E cores and WSL2.
A change only counts as a regression if it falls below the bottom of the range in consecutive repetitions; to
compare optimization gains, run more than 3 times and pin CPU affinity.

Reference output (temperature 0): `bitubi-inference-4j-tests/src/test/resources/smoke-golden-25.txt`.
