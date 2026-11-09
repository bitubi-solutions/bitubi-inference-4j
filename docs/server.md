# bi4j serve: the chat UI and the OpenAI-compatible API

`bi4j serve` runs one model and offers two ways to talk to it from the same port: a **chat UI** in the browser and an
**OpenAI-compatible HTTP API** under `/v1`. Both share the same model, the same conversations and the same rules.

## Run it

```shell
./run-cli.sh serve tjake/Llama-3.2-1B-Instruct-JQ4 --auto-download --open
```

It prints, within a couple of seconds:

```
Chat UI:    http://127.0.0.1:8080
OpenAI API: http://127.0.0.1:8080/v1
```

The server listens **before** the model is loaded. While the model downloads or loads, the page shows a status panel
(with the download percentage) and swaps in the chat by itself when the model is ready; API calls answer `503` with
`Retry-After: 5` and the code `model_loading`. If the model cannot be loaded, the server stays up and the panel (and
`/healthz`) show the reason verbatim.

Useful flags (`bi4j serve --help` has all of them):

| Flag | Meaning |
|---|---|
| `--port N` | HTTP port, `0` picks a free one (default 8080) |
| `--host ADDRESS` | Address to bind (default `127.0.0.1`). Anything but loopback has **no authentication**, and the command says so |
| `--allowed-host NAME[,NAME]` | Host header names accepted besides `localhost` and the loopback addresses |
| `--data-dir DIR` | Where conversations are stored (default `~/.bi4j`) |
| `--no-history` | Keep conversations in memory only; nothing is written to disk |
| `--open` | Open the chat in the default browser once the server is up |
| `--context-length N` | The most tokens a conversation may hold |
| `--threads N` | Threads for the math (default: the physical cores) |

`./run-cli.sh --create-aot <model>` writes a JVM cache next to the jar; later launches start about twice as fast.

## The chat UI

- **Conversations** are kept on the server as plain-text JSON files (next section). The sidebar lists them by day, filters by title,
  renames inline (Enter saves, Esc cancels) and deletes after a confirmation.
- **Answers** are Markdown, rendered on the server with the HTML escaped; code blocks have a Copy button. While an answer is
  written, **Send** becomes **Stop**. A stopped answer stays in the conversation and the next message continues from it.
- **Reload** while an answer is being written: the page shows what exists so far and keeps following it. Closing the tab
  cancels the answer after `bi4j.ui.orphan-timeout`; what was written is kept.
- **Settings** (per conversation): temperature (0 to 2), max new tokens, system prompt; **Reset** restores the defaults.
  A temperature or length change applies from the next answer. A new system prompt rebuilds the model's memory of the
  conversation from the stored history on the next message, and the panel says so. It cannot change while an answer runs.
- **Theme**: light, dark or system, remembered in the browser (the only thing the browser keeps besides your unsent text).
- **Keyboard**: Enter sends, Shift+Enter is a new line, Esc stops an answer or closes a panel; Ctrl or Cmd + Shift + O new chat,
  Ctrl or Cmd + K search, Ctrl or Cmd + Shift + S show or hide the conversation list, `?` lists them all.
- **Small screens**: the conversation list becomes a drawer behind the Menu button and Settings a bottom sheet.
- **Accessibility**: WCAG 2.2 AA is the target; axe-core finds no serious or critical violation in any state, in both themes, at
  1280 and 360 px (a browser test checks it).

When the model is already answering the same conversation in another tab or API call you get a notice with a link to the
running answer. When a message does not fit the context window the message is **not** stored, you see the numbers, and the
text stays in the box. After a server restart the first message of an old conversation rebuilds the model's context from
the stored history (the page says "Restoring context").

## The API

OpenAI-compatible, under `/v1` only:

```shell
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

- `GET /v1/models`, `POST /v1/chat/completions` (streaming with `stream: true`, usage with `stream_options.include_usage`).
- The interactive reference is at `/docs` (OpenAPI JSON at `/v3/api-docs`).
- **Sessions.** A request without a header uses a temporary session. With `X-Bi4j-Session: <uuid>` the conversation's memory is
  kept between requests, and **the UI uses the same ids**: a conversation started in the browser can be continued with
  `curl` and the other way round. One answer at a time per session: a second request gets `409 session_busy`.
- **Errors** have the OpenAI shape `{"error":{"message","type","param","code"}}`: `context_length_exceeded` (400), `session_busy`
  (409), `model_loading` (503, with `Retry-After`), `model_failed` (503), `unsupported_input` and `unsupported_feature` (501),
  `invalid_session` (400).
- A client that disconnects while streaming stops the generation.

`bi4j.api.enabled=false` removes `/v1` (and leaves the UI and `/healthz`); `bi4j.ui.enabled=false` does the opposite.

## Configuration

Every key is a Spring property (`application.properties`, `-D`, or the command line) and also an environment variable
(`BI4J_` plus the key in capitals with `_` for `.` and `-`). A flag wins over the environment, the environment over a file.

| Key | Default | Environment variable | Meaning |
|---|---|---|---|
| `bi4j.server.allowed-hosts` | empty | `BI4J_SERVER_ALLOWED_HOSTS` | Host names accepted besides loopback (`--allowed-host`) |
| `bi4j.server.history` | `true` | `BI4J_SERVER_HISTORY` | `false` keeps conversations in memory only (`--no-history`) |
| `bi4j.server.data-dir` | unset (`~/.bi4j`) | `BI4J_SERVER_DATA_DIR` | Where conversations are stored (`--data-dir`) |
| `bi4j.server.max-live-sessions` | `4` | `BI4J_SERVER_MAX_LIVE_SESSIONS` | Open model sessions; over it the least recently used idle one is closed (its memory freed) and rebuilt from the stored history when used again |
| `bi4j.api.enabled` | `true` | `BI4J_API_ENABLED` | Serve `/v1` |
| `bi4j.ui.enabled` | `true` | `BI4J_UI_ENABLED` | Serve the chat UI |
| `bi4j.ui.orphan-timeout` | `30s` | `BI4J_UI_ORPHAN_TIMEOUT` | An answer nobody is watching is cancelled after this |
| `bi4j.ui.heartbeat` | `15s` | `BI4J_UI_HEARTBEAT` | Keep-alive of the answer stream |
| `bi4j.ui.template-dir` | unset | `BI4J_UI_TEMPLATE_DIR` | Development: read templates from this folder and reload them on change; a missing folder stops the start-up |
| `bi4j.ui.defaults.temperature` | `0.7` | `BI4J_UI_DEFAULTS_TEMPERATURE` | Temperature of a new conversation |
| `bi4j.ui.defaults.max-new-tokens` | `512` | `BI4J_UI_DEFAULTS_MAX_NEW_TOKENS` | Longest answer of a new conversation |
| `bi4j.ui.defaults.system` | empty | `BI4J_UI_DEFAULTS_SYSTEM` | System prompt of a new conversation |

## What is stored, and where

Conversations are **plain text on this machine**: `<data-dir>/conversations/<uuid>.json`, one file per conversation (version,
title, settings, messages with the finish reason and token counts). New folders are private to your user where the operating
system allows it. Deleting a conversation in the UI deletes its file. Files that are corrupt, from a newer version, missing
fields or not named after a UUID are skipped with a warning in the log and never stop the page. If the folder cannot be
written the sidebar says "History is not being saved" and the chat keeps working in memory.

## Security

There is no authentication. The defaults keep it local:

- It binds to `127.0.0.1`. `--host` with anything else prints a warning.
- **Host check**: requests whose `Host` is not loopback or listed in `--allowed-host` are refused (`421`), which defeats DNS rebinding.
- **Origin check**: a state-changing request from a page of another origin is refused (`403`).
- **Content Security Policy** on every page: scripts, styles, images and fonts only from the server itself; no inline script or
  style; no framing. The page loads nothing from a CDN and works offline.
- Model answers are Markdown with the HTML escaped; links keep only `http`, `https` and `mailto`.

## Health

`GET /healthz` answers `200 {"status":"ready","model":...}` when the model is loaded and `503` while it loads or after it
failed, with the reason in `detail`. Use it from scripts and Kubernetes probes.

## When something goes wrong

| You see | It means | Do this |
|---|---|---|
| "The JVM was started without the Vector API module" | `--add-modules jdk.incubator.vector` is missing | Use `./run-cli.sh`, or add the flag to the `java` command (the message lists where) |
| "Cannot read a model at ..." / "Could not get model ..." | The path or the Hugging Face name is wrong, or the repository is private | Check the name; for a private model pass `--auth-token` |
| Panel "Loading the model" for a long time | A large model is being read from disk | Wait; the first start is the slowest |
| "This conversation is answering in another tab or API call" | One answer at a time per conversation | Use the link to follow it, or wait |
| "... tokens" context message with a **New chat** link | The message does not fit the model's window | Start a new chat, shorten the message, or raise `--context-length` |
| "The answer stopped early" | The engine failed in the middle of an answer | Send the message again; what was written is kept |
| "Answer interrupted" with **Retry** | The server stopped while it was answering | Press Retry |
| "History is not being saved" | The data folder cannot be written | Fix the folder or use `--data-dir` |
| `421` / `403` from curl | Host or Origin check | Call it by `127.0.0.1` or `localhost`, or add `--allowed-host` |

## Working on the UI

`./dev-server.sh` starts the UI on a scripted fake model, with templates and static files read from the source tree: edit,
save, refresh. See [server-extending.md](server-extending.md). The browser tests run with
`./mvnw -B -pl bitubi-inference-4j-server -am package -Pe2e -Dtest='*E2E' -Dsurefire.failIfNoSpecifiedTests=false` (Chromium; set
`BI4J_E2E_CHROMIUM` to use an installed one).
