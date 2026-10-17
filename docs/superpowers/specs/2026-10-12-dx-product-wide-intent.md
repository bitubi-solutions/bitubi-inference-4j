# DX across the whole product (record of intent)

Date: 2026-10-12. Status: **intent recorded; nothing designed or implemented**. The review of each
component is left for when we get to it, and that is when the "accounts get settled".

## Principle

The experience of whoever uses bitubi-inference-4j is a requirement of the whole product, not just of the SDK.
Each component is reviewed as a product: from first contact to first result, in its errors, its
defaults and its documentation.

## Components to review

| Component | Who uses it | Review status |
|---|---|---|
| Java SDK | whoever integrates the engine into apps | initial proposal in `2026-10-12-sdk-dx-design.md` (awaiting approval) |
| REST API (OpenAI and Anthropic) | HTTP clients and third-party SDKs | to be reviewed (sub-project 2) |
| Web UI | whoever chats with the model in the browser | to be reviewed (sub-project 2, htmx + JTE) |
| `bi4j` CLI | operators and developers | to be reviewed |
| Agent harness (`bi4j`) | whoever uses the agent in the terminal | to be reviewed (last sub-project) |
| Deployment (Docker, Helm, cluster) | operators | to be reviewed (sub-project 3) |
| Documentation, examples and error messages | everyone | to be reviewed, cross-cutting |
| Installation and build | whoever compiles or publishes | to be reviewed |

## Review model (to apply to each component, when its turn comes)

1. **First success:** time and steps from zero to the first useful result, measured in a clean environment.
2. **Defaults:** what works without configuration; what must be configured and whether that is clear.
3. **Errors:** each common failure states the cause and the fix; tested.
4. **Consistency:** names, flags, formats and messages follow the same vocabulary (`bi4j`, `bitubi-inference-4j`).
5. **Documentation and examples:** executable and verified in CI.
6. **Accessibility and platforms:** accessible UI; same behavior on Linux, Windows and macOS.

## Friction already observed (verified items only)

- CLI help text and headings still in English, mixed with the language of the documentation; decide the language policy.
- `--add-modules jdk.incubator.vector` is mandatory and today fails without guidance.
- `generate(..., ntokens, ...)` is a total token limit and produces `Prompt exceeds max tokens` with low values.
- The README and the old examples cited APIs that no longer existed (`Downloader`); the documentation needs automated testing.

Everything else is yet to be observed; do not assume problems without measuring.
