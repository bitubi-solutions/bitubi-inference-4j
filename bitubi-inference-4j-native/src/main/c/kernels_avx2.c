/* AVX2 + FMA unit (the native x86 floor). Flags: -mavx2 -mfma (see Makefile). */
#include "kernels.h"

#if !defined(__AVX2__) || !defined(__FMA__)
#error "kernels_avx2.c requires -mavx2 -mfma"
#endif

#include "kernels_x86_256.inc"

const struct bi4j_kernels bi4j_kernels_avx2 = {
    "avx2", gemm_q8_q4_256, gemm_f32_256, gemm_f32_q4_256, gemm_bf16_256, gemm_f32_bf16_256
};
