# GGUF support, slice 1: Qwen2 with F16, Q8_0 and Q4_0

Date: 2026-10-12. Status: **reviewed; the author's decisions in section 7 applied; the plan still needs approval** (nothing implemented). Part of sub-project 1
(engine and SDK); see `2026-10-11-bitubi-inference-4j-foundation-design.md`.

## 1. Intent

The engine only reads SafeTensors. GGUF is the most widely used format for quantized models and gives direct access
to the ecosystem (Hugging Face, llama.cpp). Author's decisions (2026-10-12): **approach A** (an adapter
over the existing models) and **first slice = Qwen2 with F16, Q8_0 and Q4_0**.

Success criterion of the slice: load `Qwen/Qwen2.5-0.5B-Instruct-GGUF` (Apache-2.0) in F16, Q8_0 and
Q4_0, and generate correct text at temperature 0, proven by the tests in section 6.

## 2. Verified facts (primary sources)

Specification `ggml-org/ggml/docs/gguf.md` and structs from `ggml/src/ggml-common.h`:

- Header: magic `GGUF` (bytes `47 47 55 46`), `uint32 version` (the spec describes version 3), `uint64 tensor_count`, `uint64 metadata_kv_count`. Little-endian by default.
- Order: header, tensor information, padding, tensor data. Alignment `general.alignment` (uint32, multiple of 8; default 32); padding with `0x00`.
- Metadata: key `gguf_string_t` (`uint64 len` + UTF-8 bytes), type `uint32` (UINT8=0, INT8=1, UINT16=2, INT16=3, UINT32=4, INT32=5, FLOAT32=6, BOOL=7, STRING=8, ARRAY=9, UINT64=10, INT64=11, FLOAT64=12). Array = element type + `uint64` count + elements (can be nested).
- Tensor information: name (string, up to 64 bytes), `uint32 n_dimensions` (up to 4), `uint64 dims[n]`, `uint32 type`, `uint64 offset` **relative to the start of the data**, a multiple of the alignment.
- `ggml_type`: F32=0, F16=1, Q4_0=2, Q4_1=3, Q5_0=6, Q5_1=7, Q8_0=8, Q8_1=9, Q2_K=10, Q3_K=11, Q4_K=12, Q5_K=13, Q6_K=14, Q8_K=15, I8=24, I16=25, I32=26, I64=27, F64=28, BF16=30 (and the IQ/TQ/MXFP4 families).
- Blocks (elements / bytes): Q4_0 32 / 18 (`half d` + 16 bytes of nibbles); Q8_0 32 / 34 (`half d` + 32 `int8`). For slice 2: Q4_K 256 / 144, Q5_K 256 / 176, Q6_K 256 / 210.
- Tokenizer: `tokenizer.ggml.model` (`llama`, `replit`, `gpt2`, `rwkv`), `tokenizer.ggml.tokens`, `tokenizer.ggml.merges`, `tokenizer.ggml.token_type` (1 normal, 2 unknown, 3 control, 4 user-defined, 5 unused, 6 byte), special IDs (`bos`, `eos`, `padding`...), `tokenizer.chat_template`.
- Architecture: `general.architecture` and keys `<arch>.*` (e.g. `<arch>.context_length`, `embedding_length`, `block_count`, `feed_forward_length`, `attention.head_count`, `attention.layer_norm_rms_epsilon`).

**The spec does not state** the order of the dimensions or the block sizes (these come from the reference code).

From our code (read): `Q4ByteBufferTensor` uses blocks of 32 values, one byte = value *j* (low nibble) and value *j+16* (high nibble), scale `max / -8` and value `(q - 8) * scale`, with the scales in a separate `FloatBufferTensor`. It is the same scheme as GGUF's Q4_0, which stores the scale (16 bits) inline in the block. `Q8ByteBufferTensor` has blocks of 32 and separate scales.

## 3. Scope

**Inside:** little-endian GGUF v3 reader for a single file; types F32, F16, BF16, Q8_0 and Q4_0; `qwen2` architecture; configuration, tokenizer and chat template from the metadata; loading through `ModelSupport.loadModel` when given a `.gguf`.

**Outside (later slices or never):** K-quants (Q4_K, Q5_K, Q6_K), Q5_0/Q5_1, IQ families; Llama, Mistral, Gemma etc. architectures (Llama requires handling the RoPE Q/K permutation); GGUF in parts (`-00001-of-0000N`); big-endian; exporting to GGUF; distributed inference with GGUF (a non-null `DistributedContext` fails with a clear message).

## 4. Design

New package `ao.bitubi.inference4j.gguf` in `bitubi-inference-4j-core`:

| Unit | Responsibility | Depends on |
|---|---|---|
| `GgufType` | enum of `ggml_type` (id, elements per block, bytes per block) | nothing |
| `GgufFile` | opens the file (mmap), validates magic and version, reads metadata and tensor information, computes the start of the data and the alignment | `GgufType` |
| `GgufTensorInfo` | name, dimensions, type, absolute offset | `GgufType` |
| `GgufWeightLoader` (implements `WeightLoader`) | translates the HF name requested by the model into the GGUF name, reads the tensor and returns the right `AbstractTensor` | `GgufFile`, `GgufTensorNames`, `GgufRepack` |
| `GgufTensorNames` | per-architecture table: HF name to GGUF name | nothing |
| `GgufRepack` | converts GGUF blocks to the internal tensors (F16 directly; Q8_0 and Q4_0 splitting the `half` scale into a `float`) | existing tensors |
| `GgufConfigFactory` | `qwen2.*` metadata to `Qwen2Config` | `GgufFile` |
| `GgufTokenizerFactory` | `tokenizer.ggml.*` to a `TokenizerModel` and the chat template | `GgufFile` |

Changes to existing code (minimal):
- `BPETokenizer` and `LlamaTokenizer`: constructor from a ready `TokenizerModel` (today they only accept a `Path` with `tokenizer.json`).
- `ModelSupport.loadModel`: if the file ends in `.gguf`, uses the GGUF path (without requiring `config.json`), choosing the `ModelType` by `general.architecture`.

Name translation for `qwen2` (**to be confirmed by listing the tensors of the real file**): `model.embed_tokens.weight` = `token_embd.weight`; `model.norm.weight` = `output_norm.weight`; `lm_head.weight` = `output.weight` (if absent, use `token_embd.weight` only when the embeddings are shared); per layer `N`: `self_attn.{q,k,v}_proj.{weight,bias}` = `blk.N.attn_{q,k,v}.{weight,bias}`, `self_attn.o_proj.weight` = `blk.N.attn_output.weight`, `mlp.gate_proj` / `up_proj` / `down_proj` = `blk.N.ffn_gate` / `ffn_up` / `ffn_down`, `input_layernorm` = `blk.N.attn_norm`, `post_attention_layernorm` = `blk.N.ffn_norm`.

`Qwen2Config` configuration from the metadata: `qwen2.context_length`, `embedding_length`, `feed_forward_length`, `attention.head_count`, `attention.head_count_kv`, `block_count`, `attention.layer_norm_rms_epsilon`, `rope.freq_base`; vocabulary = size of `tokenizer.ggml.tokens`; `bos`/`eos` from the special IDs (if `bos` is missing, use the one from the Qwen2 `Config` and document it); SiLU activation (fixed for the architecture, it is not in the metadata).

## 5. Known risks

1. **Dimension order.** Confirmed on the real Q4_0: GGML stores the inner dimension first. For example, `blk.0.ffn_down.weight` has `[4864,896]` in the GGUF and the internal shape is `[896,4864]`. The loader reverses the dimensions.
2. **Tokenizer.** Qwen2 uses byte-level BPE with a regex pre-tokenizer; the metadata carries `tokenizer.ggml.pre`, not the regex. It must reproduce exactly the one from `tokenizer.json`. Mitigation: parity test (section 6).
3. **Special tokens.** The control ones (`token_type = 3`, e.g. `<|im_start|>`) must become *added tokens* so that the chat template text is segmented the same way.
4. **Chat template.** It comes in `tokenizer.chat_template` (Jinja); it must enter `PromptSupport` like the one from `tokenizer_config.json`.
5. **Shared embeddings.** Inspecting the real Q4_0 file `Qwen/Qwen2.5-0.5B-Instruct-GGUF` found `output.weight` Q8_0 and `token_embd.weight` Q4_0; keep the fallback for variants with shared weights.
6. **Large files.** `ByteBuffer` is limited to 2 GB; map per tensor (those of the 0.5B are small, but the design cannot assume that).
7. **Correctness false positive.** A small model can "answer well" by chance; that is why the verification compares against references, not just looking at the text.

## 6. Verification (acceptance criteria)

1. **Reader, no network:** a minimal GGUF generated by the test itself (test writer) covers all metadata types, arrays, strings, alignment padding and tensor offsets; an invalid magic or version gives a clear error.
2. **Tokenizer parity:** for a fixed set of texts (ASCII, accents, emoji, code, numbers, spaces and line breaks, chat template markers), the GGUF tokenizer IDs are **identical** to those of the tokenizer built from the `tokenizer.json` of the same model (`tjake/Qwen2.5-0.5B-Instruct-JQ4`). Zero difference.
3. **Weight parity:** for all Q8_0 and Q4_0 tensors of the real files, an independent test decoder, reading the raw GGML blocks, produces floats exactly equal to the `GgufWeightLoader` tensors. The comparison with GGUF F16 is only informative statistics (mean/max error in steps, fraction within half a step, and saturation), not a failure criterion.
4. **End to end:** with `qwen2.5-0.5b-instruct-fp16.gguf`, the prompt "What is the capital of France?" at temperature 0 produces an answer containing "Paris" and coherent; Q8_0 produces the same text or diverges only after a long prefix (record it); Q4_0 is coherent (record divergences). The output references enter as `smoke-golden-gguf-*.txt`.
5. **No regression:** the current smoke test and the existing suite stay green; CI green on the 4 systems.
6. **Performance:** record tokens/s of GGUF F16, Q8_0 and Q4_0 on the baseline hardware. There is no numeric target before measuring.

The test GGUF files (F16 1.27 GB, Q8_0 676 MB, Q4_0 429 MB) are downloaded on demand and kept out of Git; the tests that depend on them are opt-in through an environment variable, like `SmokeTest`.

### Revision of the Q4_0/Q8_0 parity criterion (2026-10-16)

The old universal half-step limit against GGUF F16 was removed: it does not verify the repack and is not valid for all quantizers. The ggml reference Q4_0 picks `d = max / -8` with the signed maximum, converts with `x / d + 8.5` and clamps the upper result to 15; there is one-sided saturation, whose error can reach one step. See [reference Q4_0 quantization](https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-quants.c#L2357-L2404). A divergence from a reference GGUF F16 may also reflect distinct quantization decisions; the provenance/imatrix of the files used was not confirmed.

The repack proof compares, in each quantized tensor, the loaded floats with an independent test decoder of the raw bytes: Q4_0 reads half `d` and the 16 bytes of nibbles (indices `j` and `j+16`); Q8_0 reads half `d` and 32 int8. The equality is exact. In the Q4_0 file tested, the 169 Q4_0 tensors (493 961 216 values) and the Q8_0 tensor `output.weight` (136 134 656 values) passed after fixing the block addressing for large indices. The informative Q4_0/F16 comparison had mean 0.24477 steps, maximum 1 step, 99.4083% within half a step; 10 179 596 values were identified as saturated (maximum of 1 step), while the non-saturated ones stayed within half a step. The Q8_0/F16 comparison on the Q8_0 file had mean 0.24274 steps, maximum 0.67213 and 99.2894% within half a step; it showed no Q4 saturation. The Q8_0/F16 comparison on the Q8_0 file had mean 0.24274 steps, maximum 0.67213 and 99.2894% within half a step; it showed no Q4 saturation.

## 7. Author's decisions (2026-10-13) and kernel finding

The recommendations were accepted:
- **Scale memory:** Q4_0 and Q8_0 get larger (the inline `half` scale becomes a separate `float`: Q4_0 18 to 20 bytes per block, +11%; Q8_0 34 to 36). Accepted in slice 1.
- **mmap per tensor** (without the 2 GB limit).
- **F16 copied** into an internal tensor first; we only switch to mmap without a copy if the load time hurts.
- **SentencePiece out:** only `gpt2` (byte BPE).
- Plan order: reader and test writer, weight parity, tokenizer, end to end.

**Finding (verified in the code, `PanamaTensorOperations.batchDotProduct`):** the GEMM kernels only accept, as weights (`b`), `F32`, `BF16` and `Q4` (with `F32` activation; `I8` only with `Q4`; `BF16` only with `BF16`). **There is no kernel for F16 or Q8 weights**; native has the same 4 combinations (`F32xF32`, `F32xQ4`, `F32xBF16`, `I8xQ4`). Consequences for the slice:
- **Q4_0:** works with the existing kernels (Panama and native).
- **Q8_0:** requires a new `F32xQ8` kernel (and `I8xQ8` if the working memory is I8) in Panama. In native, only if ported; until then native fails with a clear message or delegates to Panama (decide in the plan, after reading how `NativeSimdTensorOperations` handles combinations without a kernel).
- **F16:** requires a new `F32xF16` kernel, or widening to F32 at load time (simple, but doubles the memory: the 0.5B goes from 1.27 GB to about 2.5 GB). Recommendation: widen to F32 in slice 1 as the reference path, documented, and leave the `F32xF16` kernel for later.
- The success criterion (section 1) keeps F16, Q8_0 and Q4_0, but Q8_0 now includes the new kernel, with a test against the scalar oracle (`ScalarReferenceOperations`, like `TestNativeRemainderTiles`), including edge tiles.

## 8. Open questions

- How does native handle type combinations without a kernel (fail or delegate)? Check in the plan.
- `I8xQ8`: the I8 working memory is optional; is `F32xQ8` enough in slice 1? Preference: yes.
