# Portable build of the native module: plan

Date: 2026-10-16. Origin: section 5 of `docs/superpowers/specs/2026-10-12-engine-direction-decisions.md` (approved
by the author) and "Before making native the default" in `docs/backends.md`.

Base of the work: `fix/gemm-corner-tiles` (it brings the GEMM fix, `bi4j.backend` and `TestNativeRemainderTiles`,
which this work depends on).

## Goal

Remove `-march=native`. A single `libbi4j` per operating system and architecture, with:

- one **base unit** (`vector_simd.c`: tile driver, exported API, CPU detection) compiled for generic
  x86-64, with no AVX instruction at all;
- **one unit per ISA** (`kernels_avx2.c`, `kernels_avxvnni.c`, `kernels_avx512.c`; `kernels_neon.c` on ARM64),
  each with its own flags and its own function table;
- **run-time selection** through CPUID (with the OS check through `xgetbv`), limited by a ceiling
  coming from Java: `-Dbi4j.native.isa=auto|avx2|avxvnni|avx512|neon`;
- **`objdump` verification** in the Linux build: the base unit cannot contain VEX/EVEX instructions, nor can the
  AVX2/AVX-VNNI units contain AVX-512 instructions.

x86 floor: **AVX2 + FMA** (that is what the `*_256` kernels use; the code does not use F16C). On a CPU without it: the Java
constructor fails and the `TensorOperationsProvider` falls back to Panama.
AVX-512 requires F, BW, DQ, VL and VNNI (Skylake-X without VNNI uses AVX2).

## Decisions

- The public C API does not change (`vector_simd.h`). The `flags` argument now carries the ISA ceiling in bits 8..11
  (`BI4J_ISA_CAP_SHIFT`). The old `HAS_AVX2` bit (which only enabled the 512 path) is no longer used.
- Two new functions: `bi4j_supported_isas()` (mask `1 << level`) and `bi4j_selected_isa(flags)` (chosen level).
  Levels: 1 = AVX2 (or NEON on ARM), 2 = AVX-VNNI, 3 = AVX-512.
- The kernels live in `kernels_x86_256.inc` / `kernels_x86_512.inc` (moved with no logic change), included
  by each unit; the `static` qualifiers avoid symbol clashes between units.
- ARM64: the NEON code is only moved (I have no way to compile it here). dotprod/i8mm variants are out of scope of this plan.

## Tasks (TDD, small)

1. **Baseline.** Save the current `libbi4j.so` (`-march=native`) and the result of the native `SmokeTest`. (done)
2. **Test that fails first.**
   - `TestNativeRemainderTiles` now runs for each supported ISA (`NativeSimdTensorOperations.supportedIsas()`);
   - `bitubi-inference-4j-native/src/main/c/check-isa.sh` (`objdump` verification) fails with the current build.
3. **Split the C.** `kernels.h` (parameter struct and table), `kernels_x86_256.inc`, `kernels_x86_512.inc`,
   `kernels_neon.c`, four thin units, `vector_simd.c` with only the driver, API and dispatch.
4. **Makefile and pom.** Per-ISA units with their own flags; remove `-mavx2 -march=native` from the three x86 profiles
   (Linux, Windows, macOS Intel); call `check-isa.sh` in the Linux profile.
5. **Java.** Ceiling read from `bi4j.native.isa`; `NativeSimd` with the two new functions; clear failure if the CPU lacks the
   floor; log of the chosen ISA.
6. **Verify.** Native `SmokeTest` per ISA (`avx2`, `avxvnni`; `avx512` only compiles), `TestNativeRemainderTiles`
   per ISA, `objdump` on the units, full `clean package` (`-Dtest='!TestSample'`).
7. **Performance.** Not measured: the machine was under heavy load (load average above 5, with other agents running).
8. **Document.** `docs/backends.md` (how to mask, what the CI has to confirm).

## What cannot be verified on this machine

- Execution of the AVX-512 variants (the machine has AVX2 + AVX-VNNI): only compilation and `objdump`.
- Windows, macOS Intel and macOS ARM: CI only.
- Linux ARM64 and dotprod/i8mm variants.
