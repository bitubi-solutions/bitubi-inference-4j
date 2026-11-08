# bitubi-inference-4j web server and UI: design

Date: 2026-11-06. Status: **draft for the author's review.** No plan and no code until it is approved. This is the DX
review of the server and the UI (see `2026-10-12-dx-product-wide-intent.md`), and sub-project 2 of the foundation spec.
The SDK it builds on is `2026-10-12-sdk-dx-design.md` (implemented on main, package `ao.bitubi.inference4j.sdk`).

## 1. Goal and non-goals

**Goal.** `bi4j serve <model>` gives, in one process and with no extra tooling, (a) a ChatGPT-like web UI, and
(b) the programmatic APIs (OpenAI-compatible now, Anthropic-compatible next). The server is the SDK's first real
client: it talks to `Bi4j`, `ChatSession` and `Stream<Token>`, never to the loose engine classes. Whoever runs it
sees the URL in under 2 seconds, whoever extends it adds a page with one template and one controller method, and
whoever chats sees the first token as soon as the engine produces it.

**Non-goals (this phase).**
- Authentication, users, quotas, TLS: local server. Auth belongs to the enterprise repository through an SPI (section 11).
- Multi-model hosting or a model switcher. One model per process; the UI shows it.
- Image and audio input in the UI (the SDK has `Modality`, today every model reads text only).
- Regenerate, edit-and-resend, branching, attachments, LLM-written titles, sharing, import/export.
- A Spring starter or LangChain4j adapter (SDK decision of 2026-11-02 applies).
- Cluster endpoints (`Coordinator`, `Worker`, gRPC): untouched, `cluster-cloud` owns them. Only their Spring version moves.

## 2. Facts checked (2026-11-06)

Maven Central `maven-metadata.xml` and the vendors' pages. "Unverified" items become spike tasks in phase 0.

| Item | Result |
|---|---|
| Spring Boot | 4.1.2 stable (4.2.0-RC1 exists). Baseline: Spring Framework 7, Jakarta EE 11 (Servlet 6.1), Java 17 minimum. **Undertow is removed** (it lacks Servlet 6.1): the current `spring-boot-starter-undertow` goes; use Tomcat (the default) or Jetty. Jackson 3 is the default (`tools.jackson.*`; `jackson-annotations` keeps `com.fasterxml.jackson.annotation`). Starters are modular. |
| JTE | `gg.jte:jte-spring-boot-starter-4` 3.2.5 (also 3.2.3 and 3.2.4), plus `gg.jte:jte` and `jte-maven-plugin` 3.2.5. |
| htmx | `org.webjars.npm:htmx.org` 4.0.1 (4.0.0 final, then 4.0.1; betas before). One source says the npm dist-tag may still be `next`: pin the exact version, do not float. |
| springdoc | `springdoc-openapi-starter-webmvc-ui` 3.2.0 is the latest; the 3.x line is the Boot 4 line (3.0.0 moved to Boot 4.0.0). Same artifact id as 2.x; the current `springdoc-openapi-ui` 1.6.14 is the Boot 2 artifact and cannot stay. Unverified: that 3.2.0 runs on Boot 4.1.2 (phase 0). |
| openapi-generator plugin | 7.27.0 is the latest; the module pins 7.7.0. Can stay in principle (section 3). |
| Spring Boot 3.3.2 | Cannot read class version 69. That is why bytecode is 21 today. |

**htmx 4 against 2.x** (from `four.htmx.org`; the ones that touch this design):
- Requests use `fetch()` and `ReadableStream`, not `XMLHttpRequest` and `EventSource`.
- **SSE is not the 2.x extension.** In 4.x a response with `Content-Type: text/event-stream` to *any* request
  (`hx-get`, `hx-post`, ...) is streamed. Each unnamed event is swapped using the request's `hx-target` and `hx-swap`;
  out-of-band swaps are extracted first. Named events do not swap; they fire a DOM event with `detail.data`.
  `hx-sse:connect="/url"` opens a persistent GET stream; `hx-sse:close="done"` closes it on an event named `done`.
  `sse-swap` is gone, `sse-connect` works with a warning. Reconnect is on by default for `hx-sse:connect`
  (500 ms doubling, jitter) and sends `Last-Event-ID`; the stream pauses while the tab is hidden.
- Attributes no longer inherit implicitly: use `:inherited` (`hx-target:inherited`).
- 4xx and 5xx responses are swapped (only 204 and 304 are not); `hx-status:NNN` gives per-status behaviour.
- `hx-delete` no longer includes the enclosing form's inputs (like `hx-get`); the localStorage history cache is gone
  (back navigation re-fetches); `hx-disabled-elt` became `hx-disable`; the config meta tag is `htmx-config`.
- New swap styles include `innerMorph` and `outerMorph` (idiomorph built in). `allowEval` and `allowScriptTags` are
  removed, `inlineScriptNonce` exists, and `htmx.config.mode` defaults to `same-origin`. Both help the CSP (section 10).
- **Unverified:** the exact out-of-band syntax inside an SSE event, a programmatic abort of a request, and whether
  `HX-Retarget` and `HX-Reswap` response headers are unchanged in 4.0.1. The design avoids depending on an abort
  (Stop is a server call) and has a fallback for each of the others (section 6).

JTE templates are precompiled to plain Java classes at build time (`jte-maven-plugin`, `precompile`): no runtime
compiler, no reflection, no classpath scanning. That makes JPMS and GraalVM non-issues for the UI, and the generated
classes follow the module's bytecode level. Dev mode reads `.jte` files from disk and reloads on change.

## 3. Module layout

| Module | Holds | Bytecode | Depends on |
|---|---|---|---|
| `bitubi-inference-4j-core` | engine and SDK (`ao.bitubi.inference4j.sdk`) | 21 (unchanged: the SDK is a library for apps on JDK 21+) | none |
| **`bitubi-inference-4j-server` (new)** | Spring Boot 4 app **library**: `api.openai`, `api.anthropic`, `ui` (controllers, JTE templates, assets), `chat` (shared services), `spi` | **25** | `core` only |
| `bitubi-inference-4j-net` | gRPC cluster (`Coordinator`, `Worker`, `grpc`), `TopologyService` | 25 (it shares the Spring runtime in the CLI) | `core` |
| `bitubi-inference-4j-cli` | picocli commands, shaded `bi4j.jar`, `ServeCommand` | 25 | `core`, `server`, `net` |

Decisions:
- **API and UI stay in one module**, in separate packages, each switchable (`bi4j.api.enabled`, `bi4j.ui.enabled`).
  Both are thin views over the same `chat` services (model host, session registry, conversation store), and one Spring
  context means one model, one registry and one port. Splitting would need a third module for the shared services and
  buys nothing today. The enterprise repository adds modules next to `server`, never the reverse.
- The `openai` package moves out of `net` into `server`. `net` keeps only gRPC and the topology endpoint, and the
  `restapi`-only Spring wiring leaves it. The CLI, not `net`, owns the single Spring application (`ServeCommand` plus
  `@SpringBootApplication` in `server`). `server` has no dependency on `net`, so a headless API deployment does not pull gRPC.
- **Bytecode 25 for `server`, `net`, `cli`; `core` stays 21.** A 25 module consuming a 21 jar is fine. `core` moves
  to 25 only when the SDK's users ask. The parent POM sets `release` 21 today: `server`, `net` and `cli` override it
  (a property, `build-release` owns the `pom.xml`). Update the CLAUDE.md line about bytecode when this lands.
- **Container**: Tomcat (Boot's default; virtual threads with `spring.threads.virtual.enabled=true`, which suits
  long SSE responses). Undertow is dropped because Boot 4 removes it.
- **The CLI starts the server**, as today, with Spring Boot's `SpringApplicationBuilder` and not an embedded
  `main`. `ServeCommand` (rename of `restapi`, section 13) builds the properties from the flags, starts HTTP **first**,
  then loads the model on a virtual thread (section 7.5). Shaded jar: the shade plugin must also merge
  `META-INF/spring/*.imports` and `META-INF/spring.factories` (`AppendingTransformer`), which Boot 4's modular
  autoconfiguration needs; a jar-level boot test guards it (section 12).
- **OpenAPI models.** Keep the `openapi-generator-maven-plugin` only if the generated models work with Jackson 3
  (they use `com.fasterxml.jackson.annotation`, which still works, but also `jackson-databind` 2.x helpers and the
  Jersey client). Plan: phase 0 tries `library=spring`, models only, no Jersey, no `jackson-databind-nullable`. If that
  is not clean, **replace them with hand-written records** for the supported subset (chat, models, embeddings, errors)
  and keep `openai_spec.yaml` only as the reference for contract tests. The Anthropic side is hand-written records
  from the start (no generated source of truth worth the weight). Recommendation: hand-written records, because the
  server serves about 6 operations out of a spec with hundreds of models.
- `springdoc-openapi-starter-webmvc-ui` 3.x serves `/v3/api-docs` and Swagger UI at `/docs`. Drop `spring-data-commons`,
  Jersey, `jsr305`, the two hibernate-validator entries and the explicit Jackson 2.15 pins.

## 4. Server structure (package `ao.bitubi.inference4j.server`)

| Class | Role |
|---|---|
| `ModelHost` | Owns the one `Bi4j`. State machine `DOWNLOADING, LOADING, READY, FAILED(cause)`; loads on a virtual thread; closes the `Bi4j` on shutdown (the SDK waits for running generations). |
| `SessionRegistry` | `UUID -> ChatSession`, shared by the UI and the API. Caps live sessions (`bi4j.server.max-live-sessions`, default 4); evicts the least recently used **idle** one (close, KV cache freed) and rebuilds it from stored history on demand (section 7). |
| `TurnService` | One running turn per conversation: reserves the session (the 409 rule), consumes `Stream<Token>` on a virtual thread, publishes to subscribers, handles stop, writes the result to the store. |
| `ConversationStore` (interface) | `list`, `load`, `save`, `rename`, `delete`. `FileConversationStore` is the default; `MemoryConversationStore` for `--no-history` and tests. This is the first SPI point. |
| `MarkdownRenderer` | Server-side CommonMark to safe HTML (section 5.4). |
| `api.openai`, `api.anthropic` | Controllers mapping JSON to `TurnService`/`SessionRegistry`. Error mapping stays what it is today: `SessionBusyException` 409 `session_busy`, `ContextLengthExceededException` 400 `context_length_exceeded`; both gain `model_loading` as 503 with `Retry-After`. |
| `ui` | `ChatController` (pages and fragments), `SidebarController`, `ModelController`, JTE templates in `src/main/jte`, assets in `src/main/resources/static`. |

## 5. UI design

### 5.1 Layout

```
+--------------------+-----------------------------------------------------------+
| [=] bi4j           |  Llama-3.2-1B-Instruct-JQ4   Q4 | ctx 4096    [Settings] [o]|  <- header (model info, theme)
| [+ New chat]       +-----------------------------------------------------------+
| [ Search...      ] |                                                           |
|                    |   You                                                     |
| Today              |   | When is the best time to plant avocado?               |
|  > Avocado plan  : |                                                           |
|    Java streams  : |   bi4j                                                    |
| Previous 7 days    |   | The best time is late winter or spring ...            |
|    Release notes : |   | ```java                                  [Copy]       |
|                    |   | var s = bi4j.newSession();                            |
|                    |   | ```                                                   |
|                    |   | 212 tokens, 14.1 tok/s, stopped: end of text          |
|                    |                                                           |
|                    |   +-------------------------------------------------+    |
| [?] Shortcuts      |   | Message bi4j...                       [Send/Stop] |   |
| v0.x  bind 127.0.0.1|   +-------------------------------------------------+   |
+--------------------+   Local model: answers may be wrong. 1,284 / 4,096 tokens  |
  (: = rename, delete menu)                                                       |
```

Below 768 px the sidebar becomes an off-canvas drawer behind the `[=]` button; the settings panel is a right-hand
drawer on wide screens and a bottom sheet on narrow ones. Content width is capped at about 48rem and centred.

### 5.2 Features

- **Sidebar.** New chat (does not persist until the first message), title list grouped Today, Yesterday, Previous 7
  days, Older, title filter, inline rename (Enter saves, Esc cancels), delete through a native `<dialog>` confirm.
  Titles are the first 40 characters of the first user message; a rename sticks.
- **Chat.** User messages are plain text (escaped, `white-space: pre-wrap`). Answers are Markdown (5.4). While
  generating, the Send button turns into **Stop**, which calls `ChatSession`/stream cancellation on the server
  (section 6.3). A stopped answer stays in the conversation and is labelled "stopped"; the next turn continues from it,
  as the SDK keeps the tokens it generated. The footer of each answer shows tokens, tokens/s and the finish reason
  (`STOP`, `LENGTH` with a "reached max tokens" hint, `STOP_SEQUENCE`, `CANCELLED`).
- **Model info.** Header: model id, weight type, context length (from SDK `Capabilities`, plus the notes in section 14).
  The composer footer shows context use (`1,284 / 4,096`) and turns amber at 85 percent.
- **Settings** (per conversation, stored with it): temperature 0 to 2 (default 0.7), max new tokens (default 512, the
  SDK defaults), system prompt. Changing the system prompt after the first message rebuilds the session and says so.
  Defaults come from `bi4j.ui.defaults.*`; "Reset" restores them.
- **Theme.** Light, dark and "system" (default, `prefers-color-scheme`), stored in `localStorage` (the only thing the
  browser keeps besides the last conversation id and an unsent draft). Theme is applied by a blocking, external
  `theme.js` in `<head>` so there is no flash (inline scripts are forbidden by the CSP).
- **Keyboard.** Enter sends and Shift+Enter inserts a newline (ignored while an IME is composing); Esc stops a
  running answer, or closes a drawer or dialog; Ctrl/Cmd+Shift+O new chat; Ctrl/Cmd+K focus the sidebar filter;
  Ctrl/Cmd+Shift+S toggle the sidebar; `?` opens the shortcut list. Chrome reserves Ctrl+Shift+O; the page's handler
  calls `preventDefault`, and the list says what to do if the browser wins.
- **Accessibility** (target WCAG 2.2 AA): landmarks (`nav` "Conversations", `main`, `form`), `aria-current="page"` on the
  open conversation, every control a real `<button>` or `<a>` with an accessible name, visible focus rings, contrast at
  least 4.5:1 in both themes, touch targets at least 44 px, `prefers-reduced-motion` honoured. The streaming answer has
  `aria-live="off"` (no per-token announcements); when the answer ends, a visually hidden `role="status"` region
  says "Answer complete" and errors use `role="alert"`. After sending, focus stays in the composer. The page works with
  zoom to 200 percent.
- **No CDN.** The current UI loads Bootstrap, marked and DOMPurify from cdnjs and does not work offline. The new UI
  ships htmx (from the pinned webjar, unpacked at build into `static/vendor/`), one `app.css`, one `app.js` (about 150
  lines: copy buttons, stick-to-bottom scroll, shortcuts, composer autosize, theme toggle) and an SVG logo. No
  framework, no bundler, no Node in the build.

### 5.3 Fragments, not a SPA
Everything the user sees is an HTML fragment rendered by JTE; there is no client-side state beyond section 5.2's list.
A reload, a bookmark and the back button all work because every conversation has a URL (`/c/{id}`).

### 5.4 Markdown and code blocks
Rendered **on the server** with `commonmark-java` (core plus the GFM tables extension; version pinned in phase 0):
`escapeHtml(true)` (raw HTML in a model's answer is shown as text), `sanitizeUrls(true)` with an allow-list of
`http`, `https`, `mailto`, and links get `rel="noopener noreferrer"` and `target="_blank"`. A custom node renderer wraps
fenced code in `<div class="code"><span class="lang">java</span><button type="button" data-copy aria-label="Copy code">Copy</button><pre><code class="language-java">...</code></pre></div>`; `app.js` copies via
`navigator.clipboard` (a delegated handler, so it works on streamed content) and falls back to selecting the text.
CommonMark renders an unclosed fence as a code block, so half-streamed answers look right. Syntax highlighting is
out of phase 1; classes are in place for a self-hosted highlighter later. Why server-side: one escaping policy, no
JavaScript Markdown library to patch, and the same renderer serves stored and live answers.

## 6. Interaction model (htmx 4)

Routes of the UI (the API lives under `/v1`; the UI never uses it):

| Route | Returns |
|---|---|
| `GET /` | Full page, "new chat" state with a pre-generated conversation id (nothing stored yet) |
| `GET /c/{id}` | Full page; with the `HX-Request` header only the `#chat` fragment (`Vary: HX-Request`) |
| `POST /c/{id}/messages` | Fragment: user bubble + answer shell (6.1) |
| `GET /c/{id}/turns/{turn}/stream` | `text/event-stream` (6.2) |
| `POST /c/{id}/turns/{turn}/stop` | 204 |
| `PATCH /c/{id}` / `DELETE /c/{id}` | Rename form fragment / delete, returns the refreshed sidebar |
| `GET /sidebar?q=` | Sidebar list fragment |
| `GET /model/status` | Status fragment (polled while loading) |
| `GET /healthz` | JSON `{"state":"READY","model":"..."}` |

### 6.1 Sending a message (two steps, so reload and stop are robust)
1. The composer is `<form hx-post="/c/{id}/messages" hx-target="#messages" hx-swap="beforeend">` (textarea `text`,
   hidden `temperature`, `maxTokens`). htmx 4 sends it with `fetch`.
2. The server validates, persists the user message (status `pending`), reserves the session, creates the **turn**
   `T`, starts generation on a virtual thread, and answers `200 text/html` at once with:
   - the user bubble;
   - the answer shell `<article id="turn-T" class="answer" hx-sse:connect="/c/{id}/turns/T/stream"
     hx-sse:close="done" hx-target="this" hx-swap="innerMorph" aria-busy="true">`;
   - out-of-band: `#composer-actions` replaced by the Stop button, and the textarea cleared.

   `hx-sse:connect` is a GET, so a page reload during an answer simply re-attaches (6.4).

### 6.2 Streaming tokens
The stream endpoint subscribes to the turn. Generation does **not** depend on this connection.
- Each unnamed event carries the **whole rendered answer so far** (a snapshot), not a token. htmx morphs it into the
  shell (`innerMorph`, so code blocks and scroll position are not rebuilt). Snapshots are coalesced to at most about 20
  per second, which bounds the cost of re-rendering and the bytes sent for long answers (quadratic growth, capped by
  `maxNewTokens`). `id:` is the generated token index; a reconnect gets a fresh snapshot, so `Last-Event-ID` needs no replay buffer.
- The last unnamed event is the final snapshot plus the footer (tokens, tokens/s, finish reason) and out-of-band
  fragments that restore the Send button, refresh the sidebar entry and update the context counter. Then the
  named event `event: done` closes the stream (`hx-sse:close="done"`) and flips `aria-busy` to false.
- Errors during generation arrive as an unnamed event with a `role="alert"` block inside the answer, then `done`.
- A `:` comment heartbeat every 15 s keeps proxies from closing an idle stream (a slow first token on a big model).
- Fallback if 4.0.1 cannot do out-of-band swaps inside SSE events: send the composer and sidebar updates as named events
  and let `app.js` listen (`hx-on:` handlers) to fetch the fragments. Phase 0 decides; the routes do not change.

### 6.3 Stop
`<button hx-post="/c/{id}/turns/T/stop" hx-swap="none">`. The server closes the SDK `Stream<Token>`, which interrupts
the generation between tokens (`CANCELLED`); the stream then ends through the normal path of 6.2, so the UI and the
store reach the same final state as a normal finish. Esc triggers the same button. Stop does not depend on the SSE
connection or on htmx aborting a request. A turn with no subscriber for 30 seconds (tab closed) is cancelled the same
way (`bi4j.ui.orphan-timeout`), so an abandoned tab cannot keep the CPU busy; what was generated is saved.

### 6.4 Reloading a conversation
`GET /c/{id}` renders the stored messages. If a turn is still running, its answer shell is rendered with
`hx-sse:connect` and the first event is the current snapshot, so a reload or a second tab picks up mid-answer. A
`pending` user message with no running turn (the server restarted) renders as "Answer interrupted" with a
**Retry** button, which posts the same text again.

## 7. Conversations, sessions and failure modes

**Where history lives.** On the **server, on disk**: `<data-dir>/conversations/{uuid}.json`
(`--data-dir`, default `~/.bi4j`; `--no-history` keeps conversations in memory only). One file per conversation:
`{version, id, title, createdAt, updatedAt, model, settings{temperature,maxNewTokens,system}, messages[{role, text,
at, finishReason?, usage?}]}`, written with a temp file and an atomic move on every completed turn and on rename.
Why not the others: browser storage would lose history per browser profile and could not serve the API or a second
device on the LAN; server memory alone loses it on every restart. The browser keeps preferences only. Files are
plain text and the docs say so; delete removes the file.

**Mapping.** Conversation id = the UUID used by `SessionRegistry` = the value of `X-Bi4j-Session`. So a
conversation started in the UI can be continued with `curl -H "X-Bi4j-Session: <id>"`, and the reverse; the same
409 rule protects both. (`ChatSession.id()` is the engine's own id and is not exposed.) A request without the
header stays a temporary session.

| Situation | Behaviour |
|---|---|
| **Busy (409)** | The session is reserved before anything is persisted or streamed. The UI gets `409` with an alert fragment: "This conversation is answering in another tab or API call" and, if a UI turn is running, an **Open the running answer** link that attaches to it. The typed text stays in the composer. API: unchanged, `409 session_busy`. |
| **Context full (400)** | `ContextLengthExceededException`: the message is not stored; the alert states the numbers from the exception ("4,090 of 4,096 tokens used") and offers **New chat** (carries system prompt and settings). The text is returned to the composer. The conversation becomes read-only only when it cannot take even a short message. API: unchanged, `400 context_length_exceeded`. |
| **Server restart** | KV caches are gone; conversations are on disk. The first message after a restart rebuilds the session by replaying the stored history (prefill only, no generation); the shell shows "Restoring context (N tokens)..." and the time is logged. If the history no longer fits (smaller context or another model), the conversation opens read-only with the New chat action. An interrupted turn: section 6.4. |
| **Eviction** | When more than `max-live-sessions` are live, the least recently used idle one is closed. Next use rebuilds it as above. The user does not notice except for a short restore delay. |
| **Model still loading** | HTTP is up in under 2 s. `ModelHost` is `DOWNLOADING` (percentage) or `LOADING`; the UI shows a status panel that polls `/model/status` every second (`hx-trigger="every 1s"`) and swaps in the chat when `READY`. Stored conversations are readable meanwhile; the composer is disabled with the reason. API calls get `503` with `Retry-After: 5` and code `model_loading`. Behaviour change: the CLI no longer blocks before listening. |
| **Model failed** | `FAILED`: the panel shows the `Bi4jException` message verbatim (the SDK's messages already say what to do: `InsufficientMemoryException`, `EngineSetupException` naming the missing JVM flag, `ModelNotFoundException`) and a copyable command. `/healthz` returns 503. |
| **Model differs from the stored one** | The conversation opens with a banner ("created with X, now answering with Y"); history is replayed under the new chat template. |
| **Generation error** | Typed exception inside the answer block; the user message stays; Retry resends it. |

## 8. Error and empty states
- **First run, no conversations:** centred logo, model name, and four starter prompts (static text, click fills the composer).
- **No model configured or failed:** the status panel above, never a blank page.
- **Empty search:** "No conversations match" with a clear button.
- **Offline model download interrupted:** the downloader resumes (existing behaviour); the panel says so.
- **Disk not writable:** a persistent warning in the sidebar ("History is not being saved: <reason>"); chat keeps working in memory.
- **Unknown conversation id:** 404 page with a New chat link (never a stack trace; error pages are JTE templates too).
- **JS disabled:** the page loads and says htmx is required; reading stored conversations still works (they are server-rendered).

## 9. UI and API
- The JSON APIs stay and are first-class: OpenAI under `/v1/chat/completions`, `/v1/models`, `/v1/embeddings`;
  Anthropic under `/v1/messages`. The UI calls the shared services in process, **not** the HTTP API, so the API can
  change without breaking the UI and the UI needs no JSON.
- **`/v1` prefix.** Today the endpoint is `/chat/completions` with no prefix, which no official client accepts as a
  default `base_url`. Canonical becomes `/v1/...`; the unprefixed path is removed. This is a **breaking change**
  (documented in `docs/migration-from-jlama.md` and the release notes).
- Anthropic `/v1/messages` follows the vendor's documented stream events (`message_start`, `content_block_start`,
  `ping`, `content_block_delta` with `text_delta`, `content_block_stop`, `message_delta`, `message_stop`, `error`)
  and `stop_reason` mapping (`end_turn`, `max_tokens`, `stop_sequence`). Exact fields, tools and embeddings go in
  a separate API-contract spec, written from the vendors' current docs; this spec fixes only where they live and
  that they share the services. Tool use is blocked on the SDK (section 14).
- With the session header, the server treats the messages after the last assistant message as the new turn and assumes
  the earlier ones are already in the session (OpenAI clients resend the whole history). The API-contract spec confirms this.
- Swagger UI at `/docs` and the raw spec at `/v3/api-docs`, linked from the UI footer.

## 10. Security without authentication
- **Bind to `127.0.0.1` by default** (`--host`, `bi4j.server.host`). Binding elsewhere prints one WARN line:
  "no authentication: anyone who can reach this port can use the model and read stored conversations".
- **Host header check** (DNS rebinding): requests whose `Host` is not `localhost`, `127.0.0.1`, `[::1]` or in
  `bi4j.server.allowed-hosts` get 421. This is the real threat for a local LLM server.
- **CSRF without Spring Security** (no auth, no sessions, so no token to steal): one `OriginGuardFilter`
  (about 60 lines, SPI-replaceable) rejects state-changing requests (`POST`, `PATCH`, `DELETE`) with `Sec-Fetch-Site:
  cross-site|same-site` or an `Origin` that does not match `Host`. Requests without those headers (curl, SDKs) pass.
  htmx adds `HX-Request`, a custom header, so a cross-origin page cannot forge it without a preflight, and there is no
  CORS by default (`bi4j.server.cors-origins` is opt-in). If auth arrives later, a CSRF token goes through the same SPI.
- **CSP** on UI responses: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src
  'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'`, with `X-Content-Type-Options:
  nosniff` and `Referrer-Policy: no-referrer`. No inline script or style anywhere, which also removes the need for nonces
  (htmx 4 builds its indicator CSS with constructable stylesheets; confirm that `style-src 'self'` does not block it).
- **Escaping:** JTE's HTML content type escapes by context; user text is never Markdown-rendered; model output goes
  through commonmark with raw HTML escaped and URLs allow-listed (5.4). The XSS fixtures of section 12 are a gate.
- Conversation ids are random UUIDs and validated before they touch the file system (no path built from raw input).
- Logs never contain prompts or answers at INFO; `bi4j.server.log-content=true` (off) enables them for debugging.

## 11. Developer experience of the server

**Running.**
- `bi4j serve tjake/Llama-3.2-1B-Instruct-JQ4 --auto-download` prints, within 2 seconds, `Chat UI: http://127.0.0.1:8080`,
  `OpenAI API: .../v1`, `Anthropic API: .../v1/messages`, `API docs: .../docs`, and the model state. `--open` opens the
  browser (off by default). `--port 0` picks a free port and prints it.
- Config is Spring's, under `bi4j.*`, each key also settable as `BI4J_*` (relaxed binding: `BI4J_SERVER_PORT`). Flags
  win over environment, environment over `application.properties`. The full table lives in `docs/server.md`:

| Key | Default |
|---|---|
| `bi4j.server.host` / `port` | `127.0.0.1` / `8080` |
| `bi4j.server.data-dir` | `~/.bi4j` |
| `bi4j.server.history` | `true` |
| `bi4j.server.max-live-sessions` | `4` |
| `bi4j.server.allowed-hosts`, `cors-origins` | empty |
| `bi4j.ui.enabled`, `bi4j.api.enabled` | `true`, `true` |
| `bi4j.ui.orphan-timeout` | `30s` |
| `bi4j.ui.template-dir` | unset (dev hot reload when set) |

**Extending.**
- A new page is one `.jte` file in `src/main/jte`, one controller method, one test. `docs/server-extending.md` walks a worked
  example (add a "Prompts" page) and the fragment conventions (full page versus fragment on `HX-Request`).
- **Dev loop without a model:** a `DevServer` class in `src/test/java` boots the app with a scripted fake `Bi4j` (it is an
  interface, so a fake costs nothing: configurable delay, canned answers, failure injection) and
  `bi4j.ui.template-dir` pointing at the sources. `./mvnw -pl bitubi-inference-4j-server exec:java -Dexec.mainClass=...DevServer`
  gives edit, save, refresh in seconds with no download and no JVM flag for the vector module.
- SPI (package `ao.bitubi.inference4j.server.spi`), **marked unstable until the enterprise repository consumes it**:
  `ConversationStore`, and the servlet `Filter` order slots for authentication and the origin guard. The open
  repository never depends on enterprise code.
- Observability: the SDK's JFR events already cover load and generation. The server adds a `Turn` event and logs one INFO
  line per turn (session, tokens, tokens/s, finish reason, no content). `/healthz` for scripts and Kubernetes probes.

## 12. Testing

| Layer | What | Needs |
|---|---|---|
| Markdown | Golden files; unclosed fence; `<script>`, `javascript:` links, raw HTML, long lines, Unicode | none |
| Templates | Render each JTE template with fixtures and assert on the HTML with jsoup (test scope): escaping of titles and messages with `<img onerror>` payloads, `aria-*` attributes, one `h1`, form actions | none |
| Slice (MockMvc) | Every route, `HX-Request` versus full page, 409/400/503 fragments, stop, reload mid-turn, restart replay (a second `ConversationStore` instance on the same dir), origin and host guards | fake `Bi4j` |
| API contract | OpenAI with `jvm-openai` (already a test dependency) and the official OpenAI and Anthropic Java SDKs as clients against the fake: streaming events parse, `/v1/models`, error bodies | fake `Bi4j` |
| Real model (opt-in) | `MockedOpenAIServer`-style tests and one UI turn through the shaded jar | downloads a model; `Connection reset` is network, repeat; same switch style as `SMOKE_MODEL=1` |
| Jar boot | `java -jar bi4j.jar serve /nonexistent` must start, answer `/healthz` with 503 and show the FAILED panel. Guards the shade merge, Boot 4 autoconfiguration and error UX, with no download | none |
| E2E browser | Playwright for Java, Chromium only, against the fake `Bi4j` with scripted, delayed tokens: send, see tokens arrive progressively, Stop (final text shorter than the script, session reusable), reload mid-answer, rename and delete, context-full and busy alerts, theme persistence, a mobile viewport, keyboard shortcuts, axe-core with zero serious or critical violations, CSP violations fail the test | browser download |

- A browser-less E2E (HtmlUnit) is not an option: it does not implement a streaming `fetch`, which is the core of htmx 4.
- **CI:** the browser-less layers run in the existing `unit-tests.yaml` matrix. The Playwright job `ui-e2e` is a separate
  workflow job on the same four labels (`ubuntu-latest`, `windows-latest`, `macos-latest`, `macos-15-intel`), `-Pe2e`,
  with the Playwright cache (`~/.cache/ms-playwright`, `%USERPROFILE%\AppData\Local\ms-playwright`) and the trace
  uploaded on failure. It is not part of the default `./mvnw package`, so a developer without network is not blocked.
  `qa-bench` owns flakiness policy: no sleeps, wait on DOM state; one automatic retry, a retried pass is reported.
- Windows-specific: data-dir under the user profile, `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` with a retry on
  `AccessDeniedException`, UUID file names only, CRLF in `.jte` files (the repo edits CRLF by script).

## 13. Migration

**From the static UI** (`bitubi-inference-4j-cli/src/main/webapp/ui`: `index.html`, `chat.js`, `chat.css`, `api.js`):
- It stays untouched until phase 2 meets its acceptance criteria, then is deleted along with the `maven-resources-plugin`
  copy step and the `WebMvcConfigurer` resource handler in `ApiServiceCommand`. `webapp/index.html` (the redirect) and
  `webapp/admin/topology.html` move: the redirect disappears (`/` is the UI), the topology page moves to `net` or
  `server` with `TopologyService` (decision with `cluster-cloud`).
- What carries over: the `X-Bi4j-Session` contract (now also the conversation id) and the idea of auto-scroll that stops when the
  user scrolls up. What does not: CDN scripts, client-side Markdown, sessions as `URL.createObjectURL` strings.

**From Spring Boot 3.3.2 to 4.1.2** (done once, in phase 1, behind the existing test suite):
1. Parent import of `spring-boot-dependencies` 4.1.2; remove Undertow; modular starters (`-webmvc`, `-validation`, `-test` and its
   per-technology test starters, Jackson 3).
2. `javax`-to-`jakarta` is already done; check the Jakarta EE 11 effects (Servlet 6.1, Bean Validation 3.1, hibernate-validator 9).
3. Jackson: `tools.jackson.*` in our code; the `spring-boot-jackson2` bridge only if the generated models force it, and
   removed before the phase ends.
4. springdoc 1.6.14 to `springdoc-openapi-starter-webmvc-ui` 3.x; `@Operation` annotations, if any, keep their package.
5. Replace `OpenAIChatService`'s `Generator` and `CompletableFuture` plumbing with `TurnService` and the SDK. Behaviour
   pinned by `SessionConflictTest`, `SessionHeaderTest` and `ChatApiTest`, ported first and kept green.
6. Raise `release` to 25 on `server`, `net`, `cli`.

**Public names** (all pass `brand-compliance`, and each is a breaking change listed in `docs/migration-from-jlama.md`):
`restapi` is renamed `serve` (the old name stays as an alias for one release; it no longer describes a command that
serves a UI); `/chat/completions` becomes `/v1/chat/completions`; new `bi4j.server.*` and `bi4j.ui.*` properties; new
`BI4J_*` variables follow from them; new module `bitubi-inference-4j-server` (artifact and package names are public
for SDK-adjacent users). The OpenAI API error codes `session_busy` and `context_length_exceeded` and the header
`X-Bi4j-Session` do not change.

## 14. Notes for the SDK spec (gaps found while designing the server)

The server must not reach into engine classes, so these go back to `2026-10-12-sdk-dx-design.md`.
**Items 1 to 7 are closed** (implemented on 2026-11-06; signatures and semantics in section 13 of the SDK spec:
`restore`, per-call settings, `Generation`, `cancel`, `contextTokens`, `onProgress`, `info`). Item 8 stays open.
1. **Seed a session with history without generating** (`SessionOptions` with prior `Message`s, prefill only). Needed
   for restart recovery and eviction (section 7). Without it the server replays through `send`, which would generate.
2. **Per-call generation settings** on `ChatSession.send/stream` (temperature, max tokens): the UI changes them between
   turns, while `SessionOptions` fixes them for the session.
3. **Stream outcome:** after `Stream<Token>` ends or is closed, expose `FinishReason` and `Usage` (a `Generation` handle
   with `tokens()` and `result()`, or an accessor on the stream). The footer and the Anthropic `message_delta` need them.
4. **Cross-thread cancel:** confirm in the contract (and test) that closing the stream from another thread than the
   consumer is safe; Stop arrives on a request thread while the consumer is the turn's virtual thread. A `cancel()` is cleaner.
5. **Context accounting:** `ChatSession.contextTokens()` and the remaining budget, for the counter and the pre-check.
6. **Load progress:** a builder callback or listener (download bytes, phase), because `build()` blocks and the status panel needs it.
7. **Model info:** `Bi4j.info()` with model id, weight type, working types and context length (`Capabilities` has no
   name), for the header and `/v1/models`.
8. **Later:** `ChatSession.rewind(n)` for Regenerate and Edit; tool calling in the SDK (needed by Anthropic and OpenAI
   tool use); batched `embed`. Not needed by this spec's phases 1 to 3.

## 15. Phases and acceptance criteria

**Phase 0: spike (throw-away branch, notes appended to this spec).** Boot 4.1.2 + `jte-spring-boot-starter-4` 3.2.5 +
`htmx.org` 4.0.1 + springdoc 3.x on Java 25 bytecode, shaded. Accept when: (a) one precompiled JTE page renders from the
shaded jar on Linux, Windows and macOS; (b) an htmx `hx-post` plus `hx-sse:connect` page streams 200 snapshot events
and morphs them without losing scroll; (c) the OOB-in-SSE, `HX-Retarget` and constructable-stylesheet-under-CSP
questions of section 2 are answered; (d) the OpenAPI models decision (3) is made with a compiling proof; (e) springdoc
3.x serves `/v3/api-docs` on Boot 4.1.2 or the alternative is chosen.

**Phase 1: server on the SDK.** New `server` module, Spring 4.1.2, bytecode 25, OpenAI endpoints ported to the SDK,
`ServeCommand`, model-first-HTTP. Accept when: the existing `net` tests (`SessionConflictTest`, `SessionHeaderTest`,
`ChatApiTest`) pass unchanged except for the `/v1` prefix; `jvm-openai` and the OpenAI Java SDK complete a streaming chat
against the fake; `/v1/models` and `/healthz` answer while the model loads; the Jar boot test passes on 4 OS labels;
`./mvnw -B clean package` is green; the CLAUDE.md bytecode line and the migration guide are updated.

**Phase 2: UI MVP.** Sidebar, chat, streaming, Stop, Markdown, file store, 409 and 400 handling, loading panel.
Accept when: the E2E suite of section 12 (minus theme, mobile and axe) passes on 4 OS labels; time to first visible token
over the fake is under 150 ms after the first fake token; Stop yields at most one token after the click (fake) and the
session accepts the next turn; the XSS fixtures pass; a restart keeps all conversations and the next message works
(replay) ; the old static UI is deleted. Measured on a real small model (`SMOKE_MODEL`): `bi4j serve` prints the URL under 2 s
and a first answer streams.

**Phase 3: polish and DX.** Settings, themes, shortcuts, responsive layout, accessibility, reload mid-answer, eviction,
`DevServer`, `docs/server.md` and `docs/server-extending.md`. Accept when: axe-core reports zero serious and zero
critical violations in both themes and at 360 px width; keyboard-only completion of new chat, send, stop, rename, delete;
a new page following `docs/server-extending.md` takes a reviewer under 30 minutes (one person, timed, in a clean clone);
the dev loop starts in under 10 s.

**Phase 4: Anthropic, models, embeddings, tools** (own contract spec). Accept: the official Anthropic Java SDK completes a
streaming `messages` call against the fake; stream events match the vendor documentation (checked in the spec's review);
tool use only after the SDK has it.

## 16. Risks

| Risk | Mitigation |
|---|---|
| htmx 4.0.x is a new major, with thin documentation on out-of-band swaps in SSE | Phase 0 spike; the two-step flow only needs unnamed-event swaps and morphing, and the fallback is named events plus a small `app.js`; pin the exact version |
| `jte-spring-boot-starter-4` and springdoc 3.x together with Boot 4.1 are not announced as a set | Phase 0 compiles and runs the combination; fall back to JTE without the starter (a `TemplateEngine` bean, about 20 lines) and to a hand-served `openai_spec.yaml` |
| Shaded jar loses Spring metadata (`.imports`, `spring.factories`) | Append transformers; the Jar boot test on every OS |
| Generated OpenAPI models fight Jackson 3 | Hand-written records (recommended anyway) |
| Snapshot streaming costs CPU and bandwidth for very long answers | Coalescing to 20 events per second and `maxNewTokens` cap; measure with a 4,096-token answer in phase 2; switch to append-only for plain-text blocks if needed |
| Replay of long histories makes the first turn after a restart slow | Show "Restoring context", log the time, measure in phase 3; SDK note 1 is the prerequisite |
| Playwright is slow or flaky on macOS and Windows runners | Chromium only, separate job, traces, one retry reported, `qa-bench` owns the policy |
| Browser keys clash with ChatGPT-style shortcuts | `?` list, documented, and a setting to disable global shortcuts |
| Conversation files keep prompts in plain text | Said in the docs and in the sidebar footer, `--no-history`, delete removes the file; encryption belongs to enterprise |
| `net` moving to Boot 4 touches `cluster-cloud` code | Only the Spring version and bytecode level; gRPC and `TopologyService` logic unchanged; agree with `cluster-cloud` before phase 1 |
| Windows file locking on the atomic move | Retry and a Windows runner test in phase 2 |

## 17. Open questions for the author (with my recommendation)

1. **Rename `restapi` to `serve`?** Recommend yes, keep `restapi` as an alias for one release. (Otherwise the name lies once a UI is served.)
2. **Drop the unprefixed `/chat/completions` in favour of `/v1/chat/completions`?** Recommend yes: official clients expect `/v1`, and you chose no backward compatibility.
3. **Conversation storage on disk by default (`~/.bi4j`), with `--no-history` to opt out?** Recommend yes. Alternative: memory-only by default and opt in to disk, more private, but history is lost on every restart.
4. **Hand-written records instead of the OpenAPI generator?** Recommend yes, decided after phase 0 shows what Jackson 3 does to the generated models.
5. **One module `bitubi-inference-4j-server` for API and UI, or `-api` and `-ui`?** Recommend one, switchable by properties.
6. **Should `net` (gRPC cluster) move to Boot 4 and bytecode 25 in this sub-project?** Recommend yes (one Spring version in the shaded jar), with `cluster-cloud` reviewing the change.
7. **Generation outlives the HTTP connection (reload resumes, abandoned tab cancelled after 30 s)?** Recommend yes. It is what makes reload and Stop robust, and it costs one `TurnService`.
8. **Max live sessions default of 4 with eviction and replay?** Recommend yes. Persistent sessions without a bound hold a KV page file each, and the SDK does not free on a timer. This depends on SDK note 1.
9. **Start HTTP before the model loads (behaviour change for scripts that wait for the port)?** Recommend yes, with `/healthz` for scripts.
10. **Syntax highlighting in phase 1?** Recommend no; self-hosted highlighter in a later phase, since it adds a vendored asset and a licence to track.
11. **Browser E2E on all four CI labels, or Linux only plus a smoke on the others?** Recommend all four for the first month to learn the flake rate, then decide with `qa-bench`.
12. **Raise `core` to bytecode 25 too?** Recommend no: the SDK is a library and 21 keeps JDK 21 applications able to use it; the CLAUDE.md "bytecode 21 until the Spring upgrade" line is rewritten to "21 for `core`, 25 for the web modules".

## 18. Decisions and phase 0 results (2026-11-08)

**Decisions.** The author approved this spec on 2026-11-07 and said to follow the recommendation of every open
question in section 17. They are therefore decided: `restapi` becomes `serve` (with `restapi` as an alias for one
release); `/v1/chat/completions` only; conversations on disk under `~/.bi4j`, `--no-history` to opt out; hand-written
records instead of the OpenAPI generator; one `server` module; `net` moves to Boot 4 and bytecode 25; generation
outlives the HTTP connection; at most 4 live sessions; HTTP starts before the model loads; no syntax highlighting in
phase 1; browser E2E on all four CI labels at first; `core` stays at bytecode 21.

**Phase 0 (spike).** A throw-away project outside the repository (Spring Boot 4.1.2 parent, Java 25 bytecode,
`spring-boot-starter-webmvc`, `jte-spring-boot-starter-4` 3.2.5, `org.webjars.npm:htmx.org` 4.0.1, springdoc 3.2.0),
driven with headless Chromium (Playwright). Results against the acceptance criteria of section 15:

| | Question | Result |
|---|---|---|
| a | A precompiled JTE page renders from the shaded jar | **Yes, on Linux.** The jar boots in 1.4 s, class files are version 69 (Java 25), the page and the htmx webjar are served. Windows and macOS are not checked here: the phase 1 jar boot test covers them |
| b | `hx-post` plus a `text/event-stream` response streams 200 snapshots and morphs them | **Yes.** 200 snapshots gave 200 DOM updates; the first text was visible 78 ms after the click (request included); with `hx-swap="innerMorph"` the `<p>` element is the same node at the end and the container's scroll position (30 px) is kept; no `.htmx-request` class is left behind |
| c1 | Out-of-band swaps inside an SSE event | **Yes.** A `hx-swap-oob` element in the last event updated `#stats` (the SSE code swaps through the core swap, and a message that only has out-of-band parts does not clear the target) |
| c2 | `HX-Retarget` / `HX-Reswap` on the stream response | **Ignored.** The extension takes the stream over before response headers are applied: the snapshots still went to the element's own `hx-target`. Do not rely on them for streams |
| c3 | Constructable stylesheets under `style-src 'self'` | **No violation** in Chromium with `script-src 'self'; style-src 'self'; connect-src 'self'` (Firefox and WebKit not tried). The only violation seen came from an inline `style=` attribute of the test page: use a CSS file, never inline styles |
| d | Generated OpenAPI models with Jackson 3 | **They break, silently.** The same request body, `{"model":"m","messages":[{"role":"user","content":"hi"}]}`, parses to a `ChatCompletionRequestUserMessage` with the generated Jackson 2 mapper and to `messages[0] == null` with the Jackson 3 mapper that Boot 4 puts behind `@RequestBody`: the 12 `oneOf` models use `@JsonSerialize(using=...)` with Jackson 2 `StdSerializer`s that Jackson 3 ignores, without an error. Hand-written records it is |
| e | springdoc on Boot 4.1.2 | **Yes.** 3.2.0 serves `/v3/api-docs` (OpenAPI 3.1.0) and the Swagger UI |

**What changes in the design because of the spike.**
- Load `htmax.min.js` (the bundle: htmx 4.0.1 with the SSE, morph-related and other extensions), or `htmx.min.js` plus
  `ext/hx-sse.min.js`. Plain `htmx.min.js` has no streaming. Unnamed events swap; named events only fire a DOM event.
- Never abort a stream from the server. A stream that ends with an error (the connection reset) gives `htmx:sse:error`
  ("network error") and `htmx:sse:close`, keeps the last good snapshot and leaves the page usable, but shows nothing to the
  user. Every turn ends with a final unnamed event that carries the footer or the error alert as an out-of-band fragment,
  and then completes normally. The named-events fallback of section 6 is not needed.
- The Boot 4 starter is called `spring-boot-starter-webmvc`. The JTE plugin needs only this to precompile into the
  classes directory, which the Boot jar then carries: `jte-maven-plugin` 3.2.5, goal `precompile`, phase
  `process-classes`, `contentType` Html, `targetDirectory` `${project.build.directory}/classes`, and
  `gg.jte.development-mode=false` plus `gg.jte.use-precompiled-templates=true` in production.
- Maven 3.8.1 (the project's wrapper) builds a Boot 4.1.2 project; no Maven upgrade is needed.

Open after the spike: Windows and macOS for (a) and the Firefox and WebKit CSP behaviour (both fall into the phase 1
and 2 CI work), and the stop and replay behaviour, which needs the real server.

**Phase 1 delivered (2026-11-08).** Module `bitubi-inference-4j-server` (Boot 4.1.2, bytecode 25), `bi4j serve`, `/v1/chat/completions`
(streaming and not), `/v1/models`, `/healthz`, springdoc, host and origin guards; plan `docs/superpowers/plans/2026-11-08-server-phase-1.md`.
Found while booting the shaded jar (all fixed in the `cli` pom): logback 1.5.6 and slf4j 2.0.7 were too old for Boot 4,
`jackson-annotations` 2.15 lacks `JsonSerializeAs`, and a transitive `commons-lang3` 3.9 lacks `Strings` (springdoc).
The cluster coordinator no longer serves the chat API (decision D1). Not done in this phase by design: conversation store,
UI, eviction, Anthropic, embeddings, tools.

**Phase 2 delivered (2026-11-08).** The chat UI at `/`: server-rendered JTE templates, htmx 4 (SSE) and Markdown rendered on the
server with escaped HTML; conversations stored as plain-text JSON in `~/.bi4j` (`--data-dir`, `--no-history`, `--open`);
generations run independently of HTTP connections (stop, reload and reattach work); strict CSP, no inline script or style.
Browser tests (Playwright for Java, profile `-Pe2e`, CI job `ui-e2e`). The old static page was removed. Startup: the JVM AOT
cache (`./run-cli.sh --create-aot <model>`) takes HTTP-up from 4.4 s to 1.9 s. Plan `docs/superpowers/plans/2026-11-08-server-phase-2.md`.
