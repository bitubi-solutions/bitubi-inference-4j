# Web UI: DX review (phase 3 baseline)

Date: 2026-11-09. Method: the review model of `2026-10-12-dx-product-wide-intent.md`, applied to the chat UI and `bi4j serve`.
Verified items only. Measured on WSL2 (the repository sits on the Windows disk, which is slow), JDK 25, model
`tjake/Qwen2.5-0.5B-Instruct-JQ4` already on disk.

## 1. First success (clean clone)

| Step | Command | Time |
|---|---|---|
| Clone | `git clone <repo>` | 49 s (slow disk) |
| Build | `./mvnw -B -DskipTests package` | 73 s |
| Run | `./run-cli.sh serve <model> --open` | banner with the URL about 6 s after start; model ready 12.4 s on the first launch |

Three commands from nothing to the first answer; the model download time is on top of that for a model not yet on disk.
With the AOT cache (`--create-aot`) the same launch is about 3 s. The README quick start matches what was typed.

## 2. Findings

| # | Finding | Evidence | Severity | Where it is fixed |
|---|---|---|---|---|
| F1 | A launch without `--add-modules jdk.incubator.vector` starts, shows the FAILED panel and `/healthz` says which flag to add and where to put it (command line, `JAVA_TOOL_OPTIONS`, Maven). Good as it is. | `java -jar ... serve <model>` | none | n/a |
| F2 | `--auto-download` is accepted by `serve` and described as "default: false", but `serve` always downloads a missing model: the flag does nothing, while the README tells users to pass it. | `ServeCommand.load` builds `Bi4j.builder().model(...)` without reading the flag; `serve nobody/nothing` reached Hugging Face | medium | deferred to the author: honour the flag (fail fast with "use --auto-download") or drop it from `serve`; both change CLI behaviour |
| F3 | A mistyped model name shows "HTTP response code: 401 ... For a gated or private model, pass authToken(...)". The hint uses the Java API name, not the `--auth-token` flag, and does not say the name may be wrong. | `serve nobody/nothing` FAILED panel | medium | deferred: the message comes from the SDK (`core`); a CLI-side rewrite or an SDK wording change needs the author's choice |
| F4 | Start-up noise on every launch: two SpringDoc `WARN` lines ("enabled by default. To disable it in production...") and, from `run-cli.sh`, a JVM warning that `UseCompressedClassPointers` is deprecated in JDK 25 plus "1 compiler directives added". The useful lines (`Chat UI:`, `OpenAI API:`) are buried. | start logs | low | Task 2 (quiet the SpringDoc logger; drop the default-on `-XX:+UseCompressedClassPointers` flag from `run-cli.sh`) |
| F5 | Error states: busy, context full, model loading, model failed, read-only (full), disk not writable, interrupted (Retry), unknown conversation id, JS disabled each name a cause and a next step. Exceptions: "The answer stopped early: <message>" gives no next step, and an unknown route outside `/c/` returns the framework error page. | `UiController`, `UiRoutesTest` | low | Task 10 (404 page); the stopped-early wording gets "Send the message again to retry" in Task 10 |
| F6 | Vocabulary is consistent: New chat, Send, Stop, Rename, Delete, Settings (to come). The banner labels (`Chat UI:`, `OpenAI API:`) match the docs. | templates, README | none | n/a |
| F7 | There is no server guide: the keys, the data directory, the security model and the troubleshooting live only in code and the migration note. | `docs/` | medium | Task 11 |
| F8 | Accessibility has not been measured (no axe run yet); themes, keyboard shortcuts and a drawer layout are missing. | n/a | pending | Tasks 5 to 8 |
| F9 | Platforms: the browser tests pass on the four CI labels. | CI run `42520899495` | none | n/a |

## 3. Consequences for the plan

No task is dropped or re-ordered. Task 2 gains F4. Task 10 gains the "stopped early" wording. F2 and F3 are CLI and SDK
decisions and are listed for the author in the final message.
