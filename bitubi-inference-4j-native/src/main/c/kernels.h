/**
 * @file kernels.h
 * @brief Private interface between the base unit (vector_simd.c) and the per-ISA units (kernels_*.c).
 *
 * Each per-ISA unit is compiled with its own flags and exports a function table. The base unit
 * (tile driver, public API and CPU detection) is compiled with no instruction above generic x86-64.
 */
#ifndef BI4J_KERNELS_H
#define BI4J_KERNELS_H

#include <stddef.h>
#include <stdint.h>
#include "vector_simd.h"

#define MIN(a,b) (((a)<(b))?(a):(b))
#define MAX(a,b) (((a)>(b))?(a):(b))

static inline short fp32_to_bf16(float s) {
    uint16_t bf;
    union {
        float f;
        uint32_t i;
    } u;
    u.f = s;
    if ((u.i & 0x7fffffff) > 0x7f800000) { /* nan */
        bf = (u.i >> 16) | 64; /* force to quiet */
        return bf;
    }
    if (!(u.i & 0x7f800000)) { /* subnormal */
        bf = (u.i & 0x80000000) >> 16; /* flush to zero */
        return bf;
    }
    bf = (u.i + (0x7fff + ((u.i >> 16) & 1))) >> 16;
    return bf;
}

//All params
struct gemm_params {
    int flags;
    const float* restrict af;
    const char* restrict a;
    const short* restrict as;
    int aoffset;
    const float* restrict bf;
    const char* restrict b;
    const short* restrict bs;
    int boffset;
    float * restrict r;
    short * restrict rs;
    int roffset;
    int m;
    int n;
    int k;
    int lda;
    int ldaf;
    int ldb;
    int ldbf;
    int ldc;
};

/* Computes an RM x RN tile of the output (see gemm() in vector_simd.c). */
typedef void (*bi4j_tile_fn)(int m0, int m, int n0, int n, int RM, int RN, struct gemm_params params);

/* Function table of one ISA: one kernel per type combination. */
struct bi4j_kernels {
    const char *name;
    bi4j_tile_fn q8_q4;
    bi4j_tile_fn f32;
    bi4j_tile_fn f32_q4;
    bi4j_tile_fn bf16;
    bi4j_tile_fn f32_bf16;
};

#if defined(__ARM_NEON__) || defined(__ARM_NEON)
#define BI4J_ARM 1
extern const struct bi4j_kernels bi4j_kernels_neon;
extern const struct bi4j_kernels bi4j_kernels_neon_dotprod;
#else
#define BI4J_ARM 0
extern const struct bi4j_kernels bi4j_kernels_avx2;
extern const struct bi4j_kernels bi4j_kernels_avxvnni;
extern const struct bi4j_kernels bi4j_kernels_avx512;
#endif

#endif
