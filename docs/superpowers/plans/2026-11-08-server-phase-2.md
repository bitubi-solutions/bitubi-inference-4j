# Server phase 2 (UI MVP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A ChatGPT-like web UI served by `bi4j serve`: sidebar of stored conversations, streaming chat with Stop, server-side Markdown, file-backed history that survives restarts, 409/400 handling, a loading panel, and the old static UI deleted.

**Architecture:** JTE (precompiled) renders HTML fragments; htmx 4 (`htmax` bundle) posts the composer and attaches a server-sent-event stream per turn; every event is a full snapshot of the rendered answer, morphed into place. A `ChatTurns` hub runs each generation on a virtual thread independently of any HTTP connection, publishes snapshots to subscribers, persists the result, and is cancelled by Stop or by an orphan timeout. The UI reuses phase 1's `TurnService` and `SessionRegistry`, so the API and the UI share one 409 rule, one `X-Bi4j-Session` id space and one rebuild-from-history path (restart and edits need no extra code).

**Tech Stack:** Spring Boot 4.1.2 (phase 1), `gg.jte:jte-spring-boot-starter-4` 3.2.5 + `jte-maven-plugin` 3.2.5 (precompile), `org.webjars.npm:htmx.org` 4.0.1 (`htmax.min.js`), `org.commonmark:commonmark` and `commonmark-ext-gfm-tables` 0.32.0, jsoup 1.24.1 (test), Playwright for Java 1.64.0 (profile `e2e`).

**Spec:** `docs/superpowers/specs/2026-11-06-web-ui-design.md` (sections 5, 6, 7, 8, 10, 12, 15 phase 2, 18). Previous plan: `docs/superpowers/plans/2026-11-08-server-phase-1.md` (delivered; its `TurnService`, `SessionRegistry`, `ModelHost`, `FakeBi4j`, `TestServer` are used here).

## Global Constraints

- Package `ao.bitubi.inference4j.server`, new sub-packages `ui` (controllers, view models), `chat` (conversations, store, turns, markdown); same Bitubi Apache 2.0 header; bytecode 25 for `server`.
- **No inline script and no inline style anywhere** (strict CSP `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'`). No `style=` attributes, no `<script>` bodies, no CDN. Only `htmax.min.js`, `app.js`, `app.css`, an SVG logo.
- htmx facts from the phase 0 spike: use the `htmax.min.js` bundle; unnamed SSE events swap, named events only fire DOM events; `HX-Retarget`/`HX-Reswap` are ignored on a stream, so the target comes from the element's own attributes; **never abort a stream from the server**: every turn ends with a final unnamed event (footer or error alert, plus out-of-band fragments) and then completes normally; `innerMorph` keeps node identity and scroll.
- User text is escaped plain text (`white-space: pre-wrap`); only model output goes through the Markdown renderer (raw HTML escaped, URLs allow-listed to `http`, `https`, `mailto`).
- Conversation ids are random UUIDs validated before they touch the file system. Files: `<data-dir>/conversations/{uuid}.json`, temp file plus `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` with a retry on `AccessDeniedException` (Windows). `--no-history` keeps conversations in memory. Logs never contain prompts or answers at INFO.
- `bi4j.ui.enabled` and `bi4j.api.enabled` (both `true`) switch the UI and API beans (`@ConditionalOnProperty`).
- Snapshots are coalesced to at most about 20 per second; a `:` heartbeat comment every 15 s; a turn with no subscriber for `bi4j.ui.orphan-timeout` (30 s) is cancelled and what was generated is saved.
- The UI is English only. Mobile layout, themes, axe-core and the full shortcut list are **phase 3**; phase 2 ships a clean desktop layout with the keyboard basics (Enter sends, Shift+Enter newline, Esc stops).
- Process rules unchanged: no Claude trailers in commits; commit and push straight to `main`; never dispatch release/publish workflows (`unit-tests.yaml` and `perf.yaml` only); build with `./mvnw -B ... package`, never `test`; builds take 1 to 4 minutes on this machine, run them in the background and wait; do not use `pkill -f` with a pattern in your own command line (`fuser -k <port>/tcp`); the tests module only rebuilds the CLI jar when `-pl bitubi-inference-4j-cli,bitubi-inference-4j-tests -am` is used.
- For the CSS and layout (Task 8) load the `frontend-design` skill first; the author's brief is the spec's section 5.1 layout and a calm, readable, neutral look (no gradients, no emoji, system font stack).

## Decisions this plan needs from the author

- **D1 (UI startup time).** The acceptance asks for the URL in under 2 s; phase 1 measured 5.0 s from process start to the first answered request, on a slow `/mnt/c` file system. Task 11 measures where the time goes and applies the cheap fixes (lazy springdoc, trimmed autoconfiguration, optionally AppCDS). **Recommendation:** accept "under 2 s of Spring time after the JVM is up" as the criterion on slow file systems, and report both numbers. Otherwise the criterion needs AppCDS or a native image, a bigger project.
- **D2 (E2E on the dev machine).** Playwright for Java downloads its own Chromium. **Recommendation:** run the E2E job only in CI and locally with `-Pe2e` when the browser is available; the plan does not block on it.

## Review Focus

1. A user sends while the context is full: the message must not be stored, the composer keeps the text, the alert shows the numbers and offers New chat (Task 6).
2. Two tabs on one conversation: the second POST gets a 409 fragment with an "Open the running answer" link, and attaching to the running turn shows the current snapshot (Tasks 4 and 6).
3. A model answer containing `<script>`, `<img onerror>`, `javascript:` links and raw HTML renders inert, and so does a conversation title or user message with the same payloads (Tasks 3 and 5).
4. The server restarts in the middle of an answer: the conversation shows "Answer interrupted" with Retry, the stored history is intact, and the next message works through the rebuild (Tasks 2, 4, 6).
5. The browser closes mid-answer: the generation is cancelled after the orphan timeout, the partial answer is saved and the session is free (Task 4).
6. A conversation file that is corrupt, from a newer `version`, or has a name that is not a UUID is skipped with a warning and never crashes the sidebar (Task 2).

## File structure

```
bitubi-inference-4j-server/
  pom.xml                                  + jte, commonmark, htmx unpack, e2e profile
  src/main/java/.../server/
    chat/Conversation.java                 record + Message/Settings records, JSON shape of the spec
    chat/ConversationStore.java            interface (first SPI point)
    chat/FileConversationStore.java        atomic files, retry, UUID-only names
    chat/MemoryConversationStore.java      --no-history and tests
    chat/MarkdownRenderer.java             commonmark, escaped HTML, allow-listed URLs, code block wrapper
    chat/ChatTurns.java                    hub: start, subscribe, stop, orphan timeout, persist, snapshots
    chat/RunningTurn.java                  one live turn: text so far, subscribers, state
    ui/UiController.java                   pages, fragments, stream, stop, rename, delete, sidebar, status
    ui/ViewModels.java                     records the templates read
    ui/UiSecurityHeaders.java              CSP and friends on UI responses
    ui/UiProperties.java                   bi4j.ui.* (enabled, orphan-timeout, defaults, template-dir)
  src/main/jte/                            layout.jte, page.jte, chat.jte, sidebar.jte, message.jte, answer.jte, status.jte, alert.jte, error.jte
  src/main/resources/static/               app.css, app.js, logo.svg (vendor/ is unpacked at build)
  src/test/java/.../server/chat|ui/...     one test class per task; e2e/ for Playwright (profile e2e)
bitubi-inference-4j-cli/                   ServeCommand flags; old webapp/ui deleted
.github/workflows/unit-tests.yaml          + ui-e2e job
```

---

### Task 1: JTE, commonmark and htmx wiring, with a hello page

**Files:**
- Modify: `bitubi-inference-4j-server/pom.xml`
- Create: `src/main/jte/hello.jte`, `.../ui/UiProperties.java`, `.../ui/UiController.java` (a `GET /hello` stub, removed in Task 6)
- Create: `src/main/resources/application.properties` additions
- Test: `.../ui/PrecompiledTemplatesTest.java`

**Interfaces:**
- Produces: `UiProperties(boolean enabled, Duration orphanTimeout, String templateDir)` bound to `bi4j.ui` (`@ConfigurationProperties`, defaults `true`, `30s`, `""`); the JTE `TemplateEngine` bean from the starter; `static/vendor/htmx/htmax.min.js` available at `/vendor/htmx/htmax.min.js`.

- [ ] **Step 1: pom.** Add `gg.jte:jte-spring-boot-starter-4:3.2.5`, `org.commonmark:commonmark:0.32.0`, `org.commonmark:commonmark-ext-gfm-tables:0.32.0`, `org.jsoup:jsoup:1.24.1` (test). Add the `jte-maven-plugin` 3.2.5 goal `precompile` in `process-classes` with `contentType` Html, `sourceDirectory` `${project.basedir}/src/main/jte`, `targetDirectory` `${project.build.directory}/classes`. Add `maven-dependency-plugin` `unpack` of `org.webjars.npm:htmx.org:4.0.1` into `${project.build.outputDirectory}/static/vendor/htmx` (include only `**/dist/htmax.min.js`; flatten with a `maven-resources-plugin` copy if the webjar nests the path, so the served URL is `/vendor/htmx/htmax.min.js`). Add the `e2e` profile placeholder (empty for now).
- [ ] **Step 2: properties.** `gg.jte.development-mode=false`, `gg.jte.use-precompiled-templates=true`.
- [ ] **Step 3: failing test** `PrecompiledTemplatesTest`: boots `TestServer`, `GET /hello` returns 200 with a body containing the text rendered from `hello.jte` (a `@param String name` template rendering `<p>Hello ${name}</p>`), and `GET /vendor/htmx/htmx.min.js`-style lookups: `GET /vendor/htmx/htmax.min.js` is 200 with `Content-Type` JavaScript and a body longer than 10,000 bytes.
- [ ] **Step 4: run, expect FAIL** (404). `./mvnw -B -pl bitubi-inference-4j-server -am package -Dtest=PrecompiledTemplatesTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 5: implement** the stub controller returning the template through the starter's `JteTemplateEngine`/`TemplateEngine` with `HtmlTemplateOutput`, and make the test pass.
- [ ] **Step 6: check bytecode and the jar:** `javap -v` on a generated `gg.jte.generated.precompiled.JteHelloGenerated` class shows `major version: 69`; run `JarBootTest` (it must still pass: the shaded jar carries the generated classes and the vendor file).
- [ ] **Step 7: commit** `feat(server): JTE, commonmark and the htmx bundle wired in`.

---

### Task 2: Conversation model and stores

**Files:**
- Create: `.../chat/Conversation.java`, `ConversationStore.java`, `FileConversationStore.java`, `MemoryConversationStore.java`
- Test: `.../chat/ConversationStoreTest.java` (parameterised over both stores), `.../chat/FileConversationStoreTest.java`

**Interfaces:**
- Produces (all in `ao.bitubi.inference4j.server.chat`):

```java
public record Conversation(int version, UUID id, String title, Instant createdAt, Instant updatedAt, String model,
                           Settings settings, List<Msg> messages) {
    public record Settings(float temperature, int maxNewTokens, String system) {}
    public record Msg(String role, String text, Instant at, String finishReason, Integer promptTokens, Integer generatedTokens,
                      Double tokensPerSecond, String status) {} // role user|assistant; status null|pending|stopped|interrupted
    public static final int VERSION = 1;
    public static Conversation create(UUID id, String model, Settings settings, Instant now) { /* empty title */ }
    public Conversation withMessage(Msg m, Instant now) { /* appends; sets title to the first 40 chars of the first user message if still empty */ }
    public Conversation withLastMessage(Msg m, Instant now)   // replaces the last message (pending user -> done, assistant final)
    public Conversation withTitle(String t, Instant now)
    public Conversation withSettings(Settings s, Instant now)
}
public interface ConversationStore {
    List<Conversation> list();                 // newest updatedAt first; corrupt entries skipped
    Optional<Conversation> load(UUID id);
    void save(Conversation c);                 // atomic
    void delete(UUID id);
    boolean writable();                        // false after a failed save: the UI shows a sidebar warning
    Optional<String> lastError();
}
```
`FileConversationStore(Path dataDir)` creates `<dataDir>/conversations` lazily; `MemoryConversationStore()` is a `ConcurrentHashMap`.

- [ ] **Step 1: failing tests.** Shared contract (both stores): save then load round-trips every field; `list` is newest first; `delete` removes; `load` of an unknown id is empty; `withMessage` titles the conversation from the first user message (40 chars, no newline) and a later `withTitle` sticks.
  File store only: (a) the file name is `{uuid}.json`, a temp file never remains; (b) a file named `not-a-uuid.json`, a corrupt JSON file, and a file with `"version": 99` are skipped by `list` and make `load` empty, with a warning logged and the others still listed (Review Focus 6); (c) `load("../../etc/passwd")` is impossible by construction (the id is a `UUID`), and a symlinked or oddly named file in the directory is ignored; (d) `save` into a read-only directory sets `writable()` false and `lastError()` non-empty and does not throw; the next successful save flips `writable()` back; (e) two threads saving the same id leave one valid file; (f) the retry: a `Files.move` that throws `AccessDeniedException` twice then succeeds is retried (inject the mover as a `Mover` functional interface in a package-private constructor).
- [ ] **Step 2: run, expect compile failure.**
- [ ] **Step 3: implement.** JSON via the Boot Jackson 3 `JsonMapper` (`tools.jackson`), `@JsonProperty` for `createdAt` style names as `created_at` etc. only if you choose snake case; keep camelCase (the format is the server's own). Write with `Files.writeString` to `<id>.json.tmp` in the same directory, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`, retrying up to 5 times with 40 ms sleeps on `AccessDeniedException`; if `ATOMIC_MOVE` is unsupported fall back to `REPLACE_EXISTING`.
- [ ] **Step 4: run, expect PASS.** `./mvnw -B -pl bitubi-inference-4j-server -am package -Dtest='ConversationStoreTest,FileConversationStoreTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 5: commit** `feat(server): conversation model with file and memory stores`.

---

### Task 3: Markdown renderer

**Files:**
- Create: `.../chat/MarkdownRenderer.java`
- Test: `.../chat/MarkdownRendererTest.java` + golden files `src/test/resources/markdown/*.md` and `*.html`

**Interfaces:**
- Produces: `public final class MarkdownRenderer { public String render(String markdown); }` (thread-safe, one `Parser` and `HtmlRenderer` per instance).

- [ ] **Step 1: failing tests (golden + XSS).**
  Golden: paragraphs, emphasis, lists, a GFM table, an inline code, a fenced Java block rendering exactly `<div class="code"><span class="lang">java</span><button type="button" data-copy aria-label="Copy code">Copy</button><pre><code class="language-java">...</code></pre></div>`, a fence with no language (`<span class="lang">text</span>`), an **unclosed fence** (half-streamed answer) rendering as a complete code block, an empty string, a very long line (100 KB) rendering in under 200 ms, and non-BMP Unicode (emoji, CJK) preserved.
  XSS fixtures (assert on the parsed output with jsoup, not on substrings): `<script>alert(1)</script>` appears as escaped text, no `script` element; `<img src=x onerror=alert(1)>` no `img` element and no `onerror`; `[x](javascript:alert(1))` has no `href` or an `href` that is not `javascript:`; `[x](data:text/html;base64,...)` the same; `[x](https://ok.example)` keeps `href`, gets `rel="noopener noreferrer"` and `target="_blank"`; `[x](mailto:a@b.c)` is kept; an HTML comment and `<iframe>` are text; a code block containing `</code></pre><script>` stays inside the `code` element.
- [ ] **Step 2: run, expect compile failure.**
- [ ] **Step 3: implement** with `Parser.builder().extensions(List.of(TablesExtension.create()))`, `HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).urlSanitizer(new DefaultUrlSanitizer(Set.of("http","https","mailto")))`, an `AttributeProviderFactory` adding `rel` and `target` to links, and a `NodeRendererFactory` for `FencedCodeBlock` that writes the wrapper above (language taken from the first word of the info string, restricted to `[A-Za-z0-9_+-]{1,20}` else `text`; code text escaped with `Escaping.escapeHtml`).
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(server): server-side Markdown with escaped HTML and allow-listed URLs`.

---

### Task 4: ChatTurns, the turn hub

**Files:**
- Create: `.../chat/ChatTurns.java`, `.../chat/RunningTurn.java`
- Test: `.../chat/ChatTurnsTest.java`

**Interfaces:**
- Consumes: `TurnService.start(Optional<UUID>, List<Message>, GenerationSettings)` returning `Turn`; `ConversationStore`; `ModelHost`; `Conversation`.
- Produces:

```java
public final class ChatTurns {
    public ChatTurns(TurnService turns, ConversationStore store, ModelHost host, Clock clock, Duration orphanTimeout, int snapshotsPerSecond)
    /** Stores the user message as pending, starts the turn, returns it. Throws the same exceptions as TurnService.start
     *  (SessionBusyException, ContextLengthExceededException, ModelNotReadyException) BEFORE anything is stored. */
    public RunningTurn send(UUID conversationId, String text, Conversation.Settings settings);
    public Optional<RunningTurn> running(UUID conversationId);        // the live turn of a conversation, if any
    public Optional<RunningTurn> find(UUID conversationId, UUID turnId);
    public void stop(UUID conversationId, UUID turnId);               // cancel; the normal end path stores "stopped"
    public void close();                                              // cancels all, waits briefly (destroyMethod)
}
public final class RunningTurn {
    public UUID id(); public UUID conversationId();
    public String text();                                             // answer so far
    public boolean finished(); public Conversation.Msg result();      // result is the stored assistant message when finished
    public Subscription subscribe(Consumer<Event> listener);          // immediately delivers the current snapshot
    public sealed interface Event permits Snapshot, Final {}
    public record Snapshot(String text, int tokens) implements Event {}
    public record Final(Conversation.Msg message, Throwable error) implements Event {}
    public interface Subscription extends AutoCloseable { void close(); }
}
```

Behaviour: `send` loads (or creates) the conversation, builds the SDK message list from the stored messages (skipping `interrupted` or `pending` leftovers: a pending user message with no running turn is not replayed) plus the new user message, calls `turns.start(...)`, calls `turn.prime()` **inside a short wait**: run `prime` on a virtual thread and wait up to 300 ms; a `ContextLengthExceededException` or `SessionBusyException` within the window is rethrown from `send` before storing; if prime has not completed in 300 ms the turn is accepted (a later engine error becomes an error `Final`). Then stores the user message with status `pending`, starts a virtual thread that reads tokens, appends to the text, and publishes `Snapshot` at most `snapshotsPerSecond` per second (always the last one before the end), and on the end stores the assistant `Msg` (`finishReason` from the SDK result, `status` `stopped` when `CANCELLED`) and the user message as done, then publishes `Final`. A subscriber that arrives late gets the current snapshot at once (reload mid-answer). With **no subscriber for `orphanTimeout`** the turn is cancelled and saved. A generation error stores the partial answer (status `interrupted`) and publishes `Final` with the error.

- [ ] **Step 1: failing tests** with `FakeBi4j` and a `MemoryConversationStore`:
  (a) `sendStoresPendingThenFinalMessages`: after the fake script finishes, the store holds `[user done, assistant STOP]` with the fake's text, tokens and `finishReason`;
  (b) `snapshotsAreCoalesced`: script of 200 tokens with 1 ms delay and `snapshotsPerSecond=20`: a subscriber sees at most about 20 snapshots per 1000 ms of generation and the last snapshot equals the full text;
  (c) `aLateSubscriberGetsTheCurrentSnapshotAtOnce` (Review Focus 2/4);
  (d) `stopEndsWithStoppedAndFreesTheSession`: after `stop`, the stored assistant message has status `stopped`, text shorter than the script, and a new `send` on the same conversation works (the SDK keeps the generated tokens: the fake's history contains the stopped answer);
  (e) `contextFullIsRejectedBeforeStoring` (fake `contextLimit` small): `send` throws `ContextLengthExceededException` and the store is unchanged (Review Focus 1);
  (f) `aBusyConversationIsRejected`: `send` while a turn runs throws `SessionBusyException`, and `running(id)` returns the live turn so the controller can link to it;
  (g) `noSubscriberCancelsAfterTheOrphanTimeout`: orphan timeout 200 ms in the test, nobody subscribes: within 2 s the turn ends, the partial answer is stored, the fake's `cancelCount` is 1 and the session is free (Review Focus 5); a subscriber that attaches before the timeout keeps the turn alive;
  (h) `anEngineErrorStoresThePartialAnswerAsInterrupted` (`failAt(2, ...)`): the `Final` carries the error, the stored assistant message has the 2 generated tokens and status `interrupted`;
  (i) `closeCancelsEverything`.
- [ ] **Step 2: run, expect compile failure.**
- [ ] **Step 3: implement.** One `ConcurrentHashMap<UUID, RunningTurn>` keyed by conversation id (a conversation has at most one running turn: the `TurnService` reservation guarantees it). The orphan watcher is one `ScheduledExecutorService` (single virtual-thread-backed scheduler is fine) checking `lastSubscriberLeftAt`. Coalescing: remember the last publish `System.nanoTime()`; publish when `now - last >= 1e9/snapshotsPerSecond`; always publish after the loop. All store writes go through `store.save` and a failure only flips `store.writable()` (the chat keeps working).
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(server): ChatTurns runs generations independently of HTTP connections`.

---

### Task 5: Templates and view models

**Files:**
- Create: `src/main/jte/layout.jte`, `page.jte`, `chat.jte`, `sidebar.jte`, `message.jte`, `answer.jte`, `status.jte`, `alert.jte`, `error.jte`; `.../ui/ViewModels.java`
- Delete: `src/main/jte/hello.jte` and the `/hello` stub
- Test: `.../ui/TemplatesTest.java` (jsoup)

**Interfaces:**
- Produces (records in `ViewModels`): `PageModel(ModelStatus model, List<SidebarGroup> sidebar, String query, ChatModel chat, boolean historyWritable, String historyError)`, `ChatModel(UUID id, String title, List<MessageModel> messages, RunningModel running, ContextModel context, SettingsModel settings, boolean readOnly, String banner)`, `MessageModel(String role, String html, String text, String footer, String status)` (`html` is the rendered Markdown for assistant messages, `text` the raw text for user messages), `RunningModel(UUID turnId, String htmlSoFar)`, `ContextModel(int used, int total)` with `percent()` and `warn()` (85 %), `SidebarGroup(String label, List<SidebarItem> items)`, `SidebarItem(UUID id, String title, boolean current)`, `ModelStatus(String state, String name, String detail, long done, long total, String info)`.

Template contract (the ids and attributes the JS and the E2E suite rely on): `<main id="chat">`, `<ol id="messages">`, answer shell `<article id="turn-{turnId}" class="answer" hx-sse:connect="/c/{id}/turns/{turnId}/stream" hx-sse:close="done" hx-target="this" hx-swap="innerMorph" aria-busy="true" aria-live="off">`, `<form id="composer" hx-post="/c/{id}/messages" hx-target="#messages" hx-swap="beforeend">` with `<textarea name="text" required>`, hidden `temperature` and `maxTokens`, `<div id="composer-actions">` holding the Send or Stop button, `<nav aria-label="Conversations" id="sidebar">`, `<div id="alerts" role="alert">` target for error fragments, `<div id="status-live" role="status" class="visually-hidden">`, `<span id="context-counter">`. Footer text: `212 tokens, 14.1 tok/s, stopped: end of text` mapped from `finishReason` (`STOP` end of text, `LENGTH` reached max tokens, `STOP_SEQUENCE` stop sequence, `CANCELLED` stopped).

- [ ] **Step 1: failing tests** rendering each template with fixtures and asserting with jsoup: exactly one `h1`; the landmarks and `aria-current="page"` on the current item; the answer shell has every attribute above; a user message `<img src=x onerror=alert(1)>` and a conversation title with the same payload render as escaped text (Review Focus 3); a read-only conversation has a disabled composer with the reason; the context counter has the `warn` class at 85 % and not at 84 %; the model status panel for `LOADING`, `DOWNLOADING` (shows a `<progress>` with value/max) and `FAILED` (the message verbatim in a `<pre>` plus the copyable command) and it polls with `hx-trigger="every 1s"` only while not ready; the "Answer interrupted" block has a Retry button posting the same text; no template contains `style=` or an inline `<script>` (a test scans the rendered HTML of all fixtures).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: write the templates** (JTE syntax `@param`, `${...}` escaped output, `$unsafe{...}` only for the Markdown HTML produced by `MarkdownRenderer`). `layout.jte` loads `/vendor/htmx/htmax.min.js` and `/app.js` with `defer`, `/app.css`, a `<meta name="htmx-config" content='{"inlineScriptNonce":"","allowEval":false}'>`-style config only if htmx 4.0.1 needs it (check; otherwise omit), and the `<noscript>` message "htmx is required". `GET /c/{id}` with `HX-Request` returns only the `chat` fragment, and the responses carry `Vary: HX-Request`.
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(server): JTE templates for the chat UI`.

---

### Task 6: UI controller

**Files:**
- Create: `.../ui/UiController.java`, `.../ui/UiBeans.java` (declares `MarkdownRenderer`, the `ConversationStore` bean from `bi4j.server.history`/`data-dir`, `ChatTurns` with `destroyMethod = "close"`)
- Modify: `ServerBeans.java` if bean ordering needs it
- Test: `.../ui/UiRoutesTest.java` (real Tomcat via `TestServer`, `HttpClient`, jsoup)

**Interfaces:**
- Consumes: `ChatTurns`, `ConversationStore`, `MarkdownRenderer`, `ModelHost`, templates.
- Produces the routes of the spec's section 6: `GET /`, `GET /c/{id}`, `POST /c/{id}/messages`, `GET /c/{id}/turns/{turn}/stream`, `POST /c/{id}/turns/{turn}/stop`, `PATCH /c/{id}`, `DELETE /c/{id}`, `GET /sidebar?q=`, `GET /model/status`. `/healthz` stays as phase 1.

Behaviours (each is a test):
- `GET /` renders the new-chat state with a fresh UUID in the composer's `hx-post`; nothing is stored until the first message; the first-run state shows the model name and four starter prompts (static text; a click fills the composer via `app.js`).
- `GET /c/{unknown-uuid}` for a conversation never stored behaves as a new chat with that id; `GET /c/not-a-uuid` and an unknown path return the 404 JTE page with a New chat link, never a stack trace.
- `POST /c/{id}/messages` (form `text`, `temperature`, `maxTokens`): empty or whitespace text is a 400 alert fragment; success returns the user bubble, the answer shell and out-of-band `#composer-actions` (Stop button) plus a cleared textarea; **409** returns the alert "This conversation is answering in another tab or API call" with an **Open the running answer** link to `/c/{id}` when `running(id)` is present, status 409, text kept; **400 context full** returns the alert with the numbers from the exception and a **New chat** link, status 400, the text is returned to the composer (Review Focus 1), the message is not stored; **503** while the model loads or failed returns the status panel; `ModelNotReadyException` never becomes a stack trace.
- `GET .../stream` is `text/event-stream`: first event is the current snapshot (rendered Markdown inside the answer shell body), then snapshots, then one final unnamed event containing the final snapshot, the footer and out-of-band fragments (`#composer-actions` back to Send, the sidebar entry refresh, `#context-counter`, `#status-live` with "Answer complete", or an `role="alert"` block on error), then a named `event: done`, then the response completes normally. A `:` comment heartbeat every 15 s (configurable in tests). A stream for a finished or unknown turn answers the stored final message once and `done`.
- `POST .../stop` is 204 and the stream ends through the normal path (the stored message has status `stopped`; at most one more token after the click on the fake).
- `PATCH /c/{id}` with `title`: renames and returns the rename form fragment or the refreshed item; blank titles are rejected; `DELETE /c/{id}` removes the conversation (cancelling a running turn first) and returns the refreshed sidebar. `GET /sidebar?q=` filters by title, case-insensitive; "No conversations match" with a clear button when empty.
- `GET /model/status` returns the status fragment; when `READY` it returns an `HX-Redirect`-free swap that replaces the panel with the chat (or a 286-style "stop polling" is not needed: the fragment simply stops carrying `hx-trigger`).
- A conversation created with another model than the loaded one shows the banner "created with X, now answering with Y"; a conversation whose history no longer fits the context opens read-only with New chat.
- The history-not-writable warning appears in the sidebar when `store.writable()` is false.

- [ ] **Step 1: write `UiRoutesTest`** with one test per bullet above (use `FakeBi4j` with `delayMs` for the streaming ones; read the SSE body with `BodyHandlers.ofLines`; assert the DOM with jsoup). Stop test: script 200 tokens at 10 ms, read two events, POST stop, assert the last event's footer says "stopped", the stored message is `stopped`, and a second POST message works. Restart test (Review Focus 4): start the server with a `FileConversationStore` in a temp dir, send a message and let it finish, close the server, start a new `TestServer` on the same directory, `GET /c/{id}` shows both messages, and a new message streams (the session is rebuilt from the stored history: assert the fake's session history has 4 messages); then simulate a crash: write a conversation file whose last message is `pending` with no running turn and assert the "Answer interrupted" block with Retry.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement the controller.** The stream endpoint is an `SseEmitter(0L)` fed by `RunningTurn.subscribe`; send `" " + payload` after `data:` like phase 1 (a space after `data:` for client compatibility; htmx accepts both); multi-line HTML in one event must be sent as several `data:` lines (the SSE format forbids newlines inside one `data:` line): split the rendered HTML on `\n` and send each as its own `data:` line through `SseEmitter.event().data(line)` repeated, or pre-join with the SSE writer `event().data(html)` which Spring splits for you; verify with a test that a snapshot containing a code block with newlines arrives intact. On any `IOException` (tab closed) unsubscribe and do nothing else: **the generation continues** (spec 6.2); the orphan timer handles abandonment.
- [ ] **Step 4: run, expect PASS** (`UiRoutesTest`, then the whole server suite).
- [ ] **Step 5: commit** `feat(server): chat routes with streaming, stop, rename, delete and the busy and context-full states`.

---

### Task 7: Security headers for UI responses

**Files:**
- Create: `.../ui/UiSecurityHeaders.java`
- Test: `.../ui/UiSecurityHeadersTest.java`

- [ ] **Step 1: failing tests.** Every UI response (pages, fragments, the stream, `app.js`, `app.css`, vendor files) carries the CSP of the Global Constraints, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`; API responses (`/v1/...`, `/healthz`, `/v3/api-docs`) do **not** get the UI CSP (Swagger UI at `/docs` needs inline assets of its own: give `/docs/**` and `/swagger-ui/**` no CSP rather than breaking it, and say so in a comment); `Vary: HX-Request` on `/c/{id}`.
- [ ] **Step 2: run, expect FAIL.** **Step 3: implement** as a `OncePerRequestFilter` ordered after the phase 1 guards (`HIGHEST_PRECEDENCE + 2`), applying by path prefix. **Step 4: run, expect PASS.** **Step 5: commit** `feat(server): CSP and security headers on UI responses`.

---

### Task 8: Assets (app.css, app.js, logo)

**Files:**
- Create: `src/main/resources/static/app.css`, `app.js`, `logo.svg` (reuse `docs/logo.svg`), `favicon.svg`
- Test: `.../ui/AssetsTest.java` (static checks) and the E2E suite of Task 10 for behaviour

**Interfaces:**
- `app.js` (about 150 lines, no framework, strict mode, one delegated listener per event type): copy buttons (`[data-copy]` copies the sibling `pre` text through `navigator.clipboard.writeText`, falls back to selecting the text; the button label becomes "Copied" for 1.5 s); stick-to-bottom scroll in `#messages` that stops when the user scrolls up and resumes when they return to the bottom; Enter sends and Shift+Enter inserts a newline (ignored while `event.isComposing`); Esc clicks the Stop button when present; textarea autosize up to 8 lines; starter prompts fill the composer; focus returns to the composer after a send; `htmx:sse:error` and `htmx:sse:close` are logged to the console only (the server always ends turns with a final event).
- `app.css`: layout of section 5.1 (sidebar 18rem, main column capped near 48rem, header, composer docked at the bottom), a system font stack, a neutral light palette defined as CSS custom properties on `:root` (phase 3 adds the dark set), visible focus rings, 44 px touch targets, `.visually-hidden`, `.answer[aria-busy="true"]` shows a subtle caret, code block styles (`.code`, `.lang`, copy button), table styles, alert styles for `role="alert"`, `prefers-reduced-motion` respected.

- [ ] **Step 1: load the `frontend-design` skill and read the layout of spec 5.1.** **Step 2: failing static test:** `AssetsTest` asserts `app.css` contains no `@import url(http`, no `expression(`, no remote URL at all; `app.js` contains no `eval(`, no `new Function`, no `innerHTML` assignment, no `document.write`, and runs under `'use strict'`; `logo.svg` has no `<script>`. **Step 3: write the three files.** **Step 4: run `AssetsTest`.** **Step 5: open the page in a browser** (Playwright screenshot of `/` and a seeded conversation with a long answer, a code block and a table) and look at them; fix obvious layout problems before committing. **Step 6: commit** `feat(server): app.css, app.js and logo for the chat UI`.

---

### Task 9: CLI flags, wiring, and deleting the old UI

**Files:**
- Modify: `bitubi-inference-4j-cli/.../ServeCommand.java`, `ClusterCoordinatorCommand.java` (stops serving `/ui/**`), `bitubi-inference-4j-cli/pom.xml` (the `maven-resources-plugin` step copies only `admin`)
- Delete: `bitubi-inference-4j-cli/src/main/webapp/ui/` and `webapp/index.html`; remove the `StaticUi` nested class
- Test: `ServeCommandTest` additions; `JarBootTest` additions (the tests module)

**Interfaces:**
- `ServeCommand` gains `--data-dir <dir>` (default `~/.bi4j`), `--no-history`, `--open`; they become `bi4j.server.data-dir`, `bi4j.server.history` and a browser launch after startup (`java.awt.Desktop.browse` when supported, otherwise print the URL; the flag is off by default). The banner prints `Chat UI: http://H:P` first, then `OpenAI API`, `API docs`, `Model`. The data directory is created with owner-only permissions where the OS supports it (POSIX `rwx------`).

- [ ] **Step 1: failing tests.** `ServeCommandTest`: the new options parse; `--no-history` sets history off; the default data dir ends with `.bi4j`; the banner starts with `Chat UI:`. `JarBootTest`: with a nonexistent model the jar boots, `GET /` answers 200 HTML containing the failed-model panel text (the SDK message verbatim) and `GET /vendor/htmx/htmax.min.js` is 200; `GET /ui/` is 404 (old UI gone); the response of `/` carries the CSP header.
- [ ] **Step 2: run, expect FAIL.** **Step 3: implement and delete the old files** (`git rm -r`). **Step 4: run** `./mvnw -B -pl bitubi-inference-4j-cli,bitubi-inference-4j-tests -am package -Dtest='ServeCommandTest,JarBootTest' -Dsurefire.failIfNoSpecifiedTests=false`. **Step 5: commit** `feat(cli): serve gains --data-dir, --no-history and --open; the old static UI is removed`.

---

### Task 10: Browser E2E with Playwright for Java

**Files:**
- Modify: `bitubi-inference-4j-server/pom.xml` (`e2e` profile: `com.microsoft.playwright:playwright:1.64.0` test scope, surefire including `**/e2e/*E2E.java`; the default build excludes them)
- Create: `src/test/java/.../server/e2e/E2EBase.java`, `ChatE2E.java`, `HistoryE2E.java`, `SecurityE2E.java`
- Create: `.github/workflows/unit-tests.yaml` job `ui-e2e` (see Task 12)

**Interfaces:**
- `E2EBase`: starts `TestServer` with a scripted `FakeBi4j` (tokens with delay), a `FileConversationStore` in a temp directory, launches headless Chromium with `ignoreHTTPSErrors` off, a context with a listener that **fails the test on any CSP violation or console error**, and exposes `page`, `server`, `fake`.
- Scenarios (each its own test, no sleeps: wait on DOM state with Playwright locators and `waitFor`):
  1. `sendShowsTokensProgressively`: type, press Enter, the answer grows over several DOM updates, the first text appears within 150 ms of the first fake token (measure with `performance.now()` taps in the page through `page.evaluate`, not wall-clock in the test), the footer shows tokens and the finish reason, the composer is empty and focused, the Send button is back;
  2. `stopEndsTheAnswerQuicklyAndTheChatContinues`: script of 300 tokens; click Stop after the first tokens; at most one more token arrives; the footer says stopped; send again and a new answer streams;
  3. `reloadMidAnswerReattaches`: reload during an answer; the page shows the partial text and keeps growing until the end;
  4. `restartKeepsConversations`: finish a chat, close the server, start another on the same directory, open `/c/{id}`; both messages are there; send a follow-up and it streams (the rebuild path);
  5. `renameAndDelete`: rename with Enter, cancel with Esc, delete through the `<dialog>`; the sidebar updates and the URL moves to a new chat;
  6. `busyAndContextFullAlerts`: a second tab sending while the first answers shows the 409 alert with the link and keeps the text; with `contextLimit` small the 400 alert shows numbers and New chat, and the text stays in the composer;
  7. `modelLoadingPanel`: with a host that stays loading, `/` shows the status panel with progress and the composer disabled; releasing the load swaps in the chat without a manual reload;
  8. `maliciousContentIsInert`: a conversation whose title and user message are `<img src=x onerror=alert(1)>` and a model answer with `<script>`, `<iframe>`, `javascript:` links renders as text, `window.__xss` stays undefined, no dialog opens;
  9. `markdownRenders`: a code block shows the language label and the Copy button copies the code (grant `clipboard-read`); a table renders as a table; links open in a new tab with `rel`.
- [ ] **Step 1: write `E2EBase` and the first scenario; run it** (`./mvnw -B -pl bitubi-inference-4j-server -am package -Pe2e -Dtest=ChatE2E#sendShowsTokensProgressively -Dsurefire.failIfNoSpecifiedTests=false`); expect FAIL until Tasks 5 to 8 are in place (they are), then fix issues the browser reveals (selectors, ids, htmx attributes). If Chromium cannot launch here, run `PLAYWRIGHT_BROWSERS_PATH` pointing at the cached `~/.cache/ms-playwright` or let Playwright download its own; if neither works record it and rely on CI (decision D2).
- [ ] **Step 2: add scenarios 2 to 9 one at a time, each RED then GREEN.** **Step 3: commit** `test(server): browser E2E for the chat UI (profile e2e)`.

---

### Task 11: Startup time

**Files:** `bitubi-inference-4j-cli/.../ServeCommand.java`, `bitubi-inference-4j-server/src/main/resources/application.properties`, possibly `docs/` notes

- [ ] **Step 1: measure** with the shaded jar and the small local model: JVM start to `Started` log line, to the first answered `/healthz`, and to `READY`; use `-Xlog:class+load` counts and `-Dspring.main.lazy-initialization=true` as experiments, and `-verbose` startup timings (`--debug` off). Record the numbers in the commit message.
- [ ] **Step 2: apply the cheap fixes** that measurably help and break nothing: `spring.main.lazy-initialization=true` with the controllers and filters exempt only if needed, springdoc initialised lazily (`springdoc.api-docs.enabled` stays on but the scan runs on the first request), `spring.jmx.enabled=false`, `server.tomcat.mbeanregistry.enabled=false`, `spring.main.banner-mode=off`, no unused autoconfiguration (`spring.autoconfigure.exclude` for what the server never uses, verified by the tests). Optionally document `-XX:SharedArchiveFile` (AppCDS) as a launcher option; do not add it to the default launcher in this phase.
- [ ] **Step 3: re-measure and run the whole server suite and `JarBootTest`.** **Step 4: commit** `perf(server): faster startup (numbers in the message)`. Report both numbers to the author (decision D1).

---

### Task 12: CI job, documentation and the final build

**Files:**
- Modify: `.github/workflows/unit-tests.yaml`, `CLAUDE.md` (UI note under the server line), `README.md` (screenshot-free usage), `docs/migration-from-jlama.md` (the new UI, `/ui` removal, `--data-dir`/`--no-history`, history in plain text on disk), `docs/superpowers/specs/2026-11-06-web-ui-design.md` (phase 2 delivered note)

- [ ] **Step 1: CI.** Add a job `ui-e2e` on the same four labels with the Playwright cache (`~/.cache/ms-playwright`, `%USERPROFILE%\AppData\Local\ms-playwright`), `./mvnw -B -pl bitubi-inference-4j-server -am package -Pe2e -Dtest='*E2E'`, uploading the Playwright trace directory on failure. One automatic retry of a failed test is allowed and reported (surefire `rerunFailingTestsCount=1` in the profile). Not part of the default `./mvnw package`.
- [ ] **Step 2: docs.** Document: the UI at `/`, where history lives and that it is plain text (`--no-history`, delete removes the file), the keyboard basics, the CSP rule for contributors (no inline script or style).
- [ ] **Step 3: full build** `./mvnw -B -Pallmodules clean package -Dtest='!TestSample'`; expected green except the known pre-existing failures (3 `TestCorrectness`, 2 `TestModels`, `DistributedServiceTest` 401).
- [ ] **Step 4: real-model check.** `bi4j serve` with the local Qwen2.5-0.5B model (`bitubi-inference-4j-tests/models/tjake_Qwen2.5-0.5B-Instruct-JQ4`); drive it with Playwright (a throw-away script, not committed): the chat page loads, a message streams, Stop works, restart keeps the conversation and a follow-up remembers it. Record times.
- [ ] **Step 5: commit, push, read the CI result on the four labels** (`gh run list --workflow unit-tests.yaml`; re-run a job cancelled for runner capacity with `gh run rerun --failed`). Fix what the other systems show.

---

## Self-review

- **Spec coverage (phase 2 acceptance):** E2E suite minus theme, mobile, axe on four labels (Tasks 10, 12); first visible token under 150 ms after the first fake token (Task 10 scenario 1); Stop at most one token after the click and the session reusable (Task 4 d, Task 10 scenario 2); XSS fixtures (Tasks 3, 5, 10 scenario 8); restart keeps conversations and the next message works through replay (Task 6 restart test, Task 10 scenario 4); old static UI deleted (Task 9); `bi4j serve` URL under 2 s and a first answer streams on a real small model (Tasks 11, 12 step 4, with decision D1). Section 5: sidebar, chat, Markdown, footer, context counter, accessibility landmarks (Task 5), keyboard basics (Task 8). Section 6: all routes, two-step send, snapshots, final event plus `done`, heartbeat, Stop, reload (Task 6). Section 7: busy, context full, restart, model loading and failed, model differs, generation error, disk not writable (Tasks 2, 4, 6). Section 8: first-run state, empty search, unknown id 404. Section 10: CSP (Task 7). Not in this phase by design: themes, responsive drawers, the settings panel UI beyond the hidden fields, axe-core, the shortcut list, eviction, regenerate/edit.
- **Settings:** per-conversation temperature, max tokens and system prompt are stored (Task 2) and sent from hidden fields with defaults; the panel to change them is phase 3 (spec 5.2 "Settings"). The system prompt is empty by default in phase 2.
- **Placeholders:** none intended; test bullets list the exact assertions. Template markup and CSS are described by their contracts (ids, attributes, behaviours) because they are written against the live page in Tasks 5 and 8.
- **Type consistency:** `Conversation`, `Conversation.Msg`, `Conversation.Settings`, `ConversationStore`, `ChatTurns.send/running/find/stop/close`, `RunningTurn.subscribe/Event/Snapshot/Final` are defined once in Tasks 2 and 4 and used in Tasks 5, 6, 10.
- **Unverified at plan time (each has a step that checks it):** the webjar's internal path for `htmax.min.js` (Task 1 step 1); whether htmx 4.0.1 needs a config meta tag (Task 5 step 3); SSE multi-line `data:` handling by Spring's emitter (Task 6 step 3); Swagger UI and the CSP (Task 7); Playwright Chromium on this machine (Task 10 step 1, decision D2); the startup numbers (Task 11).
