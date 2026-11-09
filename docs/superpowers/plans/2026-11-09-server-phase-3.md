# Server Phase 3: Polish and DX of the Chat UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the working chat UI into one that is pleasant and accessible to use and easy to extend: settings, themes, shortcuts, a responsive drawer layout, WCAG 2.2 AA, session eviction, a model-free dev loop, and the server documentation.

**Architecture:** All changes stay inside `bitubi-inference-4j-server` (plus `docs/` and the CI job). Preferences live in `localStorage` (theme only) and in the conversation file (settings). `SessionRegistry` gains a bounded LRU of idle sessions that `TurnService` already knows how to rebuild from history. A `DevServer` in `src/test/java` boots the real app on the scripted `FakeBi4j` with templates read from the source tree.

**Tech Stack:** Spring Boot 4.1.2, JTE 3.2.5 (dev mode via `DirectoryCodeResolver`), htmx 4.0.1, Playwright for Java 1.64.0 (`-Pe2e`), axe-core (WebJar `org.webjars.npm:axe-core`, version pinned when the task runs), jsoup.

**Spec:** `docs/superpowers/specs/2026-11-06-web-ui-design.md` (sections 5.2, 5.1, 7, 8, 11, 12, 15 "Phase 3"). Product intent: `docs/superpowers/specs/2026-10-12-dx-product-wide-intent.md` (review model, six points).

## Global Constraints

- JDK 25 builds; bytecode 25 in `server`, `net`, `cli`; 21 in `core` (do not touch `core`).
- Strict CSP stays: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'`. No inline script, no inline style, no `style=` attributes in markup, no CDN.
- UI, CLI and docs in English. `CLAUDE.md` and `.claude/agents` stay in Portuguese.
- WCAG 2.2 AA: contrast at least 4.5:1 in both themes, touch targets at least 44 px, visible focus rings, `prefers-reduced-motion` honoured, zoom to 200 percent works.
- The browser keeps only: theme, the last conversation id and an unsent draft. Everything else is on the server.
- Build with `./mvnw -B ... package`, never `test`. The tests module only rebuilds the cli jar with `-pl bitubi-inference-4j-cli,bitubi-inference-4j-tests -am`. Edit CRLF files by script. Spotless may touch other files: `git checkout -- <file>` before committing.
- E2E: no sleeps, wait on DOM state; any console error or CSP violation fails the test (`E2EBase`). Locally: `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 BI4J_E2E_CHROMIUM=<chromium>`.
- Commits to `main` are authorised; never add `Co-Authored-By`, `Claude-Session` or "Generated with" trailers; never trigger releases or tags.
- Known failures that stay as they are: `DistributedServiceTest` (401), 3 `TestCorrectness`, 2 `TestModels`.
- Keys under `bi4j.*` bind from `BI4J_*` environment variables (Spring relaxed binding).

## Review Focus

1. A setting changed while an answer is running: the running answer is untouched; the new value applies from the next turn; a changed system prompt answers `409` (busy) instead of rebuilding under a running turn (Task 3).
2. `localStorage` empty, blocked or holding garbage for the theme: the page falls back to "system" with no console error and no flash (Task 5).
3. An idle conversation whose session was evicted receives a message: it is rebuilt from the stored history, the answer is correct and the context counter is right (Task 2).
4. Typing `?`, Ctrl+K or Enter inside the composer, or during an IME composition, must not trigger a shortcut that eats the text (Task 7).
5. `bi4j.ui.template-dir` pointing at a missing folder fails at startup with one clear sentence, and hot reload is never on unless that key is set (Task 9).

---

## File structure

```
bitubi-inference-4j-server/src/main/java/.../server/
  Bi4jServerProperties.java        + maxLiveSessions, apiEnabled
  SessionRegistry.java             + cap and LRU eviction of idle sessions
  chat/ConversationStore.java      + update(UUID, UnaryOperator<Conversation>) (atomic per id)
  ui/UiProperties.java             + templateDir, defaults(temperature, maxNewTokens, system)
  ui/Templates.java                dev mode from templateDir
  ui/UiController.java             + settings routes, 404 page, restoring notice
src/main/jte/  settings.jte, shortcuts.jte, notFound.jte, sidebar/layout/chat edits
src/main/resources/static/  theme.js (new, blocking), app.css (tokens, drawer, sheets), app.js (shortcuts, drawers, theme toggle)
src/test/java/.../server/  DevServer.java, e2e/{ThemeE2E,ResponsiveE2E,KeyboardE2E,A11yE2E,SettingsE2E}.java, prompts/ (guide fixture)
docs/server.md, docs/server-extending.md, docs/superpowers/specs/2026-11-09-web-ui-dx-review.md
```

---

### Task 1: DX review of the chat UI (measure first)

**Files:**
- Create: `docs/superpowers/specs/2026-11-09-web-ui-dx-review.md`

**Interfaces:**
- Produces: a short findings table that later tasks cite. A finding that changes a later task is recorded in the ledger as `Ruling:` and the task text is followed as amended.

- [ ] **Step 1: Measure first success** in a clean clone (`git clone` to a temp directory, `./mvnw -B -DskipTests package`, `./run-cli.sh serve <small model> --open`): seconds and steps from clone to a first answer in the browser; record the commands typed and each snag. Do the same from the shaded jar alone.
- [ ] **Step 2: Walk the six points** of the intent record for the UI only: defaults (what works with no flag), errors (list every error state the UI can show and check each names a cause and a fix: busy, context full, loading, failed, read-only, disk not writable, interrupted, unknown id, JS disabled), consistency of words (button names, flag names, messages), documentation (what exists today), accessibility (run axe-core once by hand through the Playwright script of Task 8 when it exists, otherwise note "pending Task 8"), platforms.
- [ ] **Step 3: Write the review** as a table `finding | evidence | severity | task that fixes it or "deferred"`. Verified items only; no assumed problems.
- [ ] **Step 4: Commit** `docs: web UI DX review (phase 3 baseline)`.

---

### Task 2: Config surface and session eviction

**Files:**
- Modify: `Bi4jServerProperties.java`, `SessionRegistry.java`, `ServerBeans.java`, `ui/UiProperties.java`
- Test: `SessionRegistryTest.java` (new, `src/test/java/.../server/`), `ConfigBindingTest.java` (new)

**Interfaces:**
- `Bi4jServerProperties`: add `int maxLiveSessions` (default 4, minimum 1) and `boolean apiEnabled` (default true) next to the existing record components; keys `bi4j.server.max-live-sessions`, `bi4j.api.enabled`.
- `UiProperties`: add `Path templateDir` (nullable, `bi4j.ui.template-dir`) and `Defaults defaults` with `record Defaults(float temperature, int maxNewTokens, String system)` (0.7, 512, empty), keys `bi4j.ui.defaults.*`.
- `SessionRegistry(int maxLive)`: `getOrCreate(UUID, Bi4j, String system, List<Message> earlier)` keeps its signature; after inserting, closes the least recently used **idle** sessions until `size() <= maxLive`. A session is idle when it is not reserved by `TurnService`. Add `void markUsed(UUID)` called by `TurnService.start` and `boolean isIdle(UUID)`.

- [ ] **Step 1: write the failing tests.** `SessionRegistryTest`: (a) cap 2, three sessions created in order a, b, c, none busy: `a` is closed (`FakeBi4j.liveSessions()` is 2) and `find(a)` is empty; (b) with `a` busy (reserved) and cap 2, creating `c` closes `b`, never `a`; (c) when every session is busy the cap is exceeded rather than cutting a running turn, and shrinks back when one finishes; (d) Review Focus 3: through `TurnService.start` with the same conversation id after eviction and a stored history of two turns, the answer streams and `sessions.find(id).contextTokens()` matches a freshly built session. `ConfigBindingTest`: `BI4J_SERVER_MAX_LIVE_SESSIONS=2` (via `TestServer` property `bi4j.server.max-live-sessions=2`) is honoured; `bi4j.api.enabled=false` makes `/v1/models` answer 404 with the OpenAI error shape and leaves `/healthz` and the UI up; `bi4j.ui.enabled=false` makes `/` answer 404 and leaves `/v1` up.
- [ ] **Step 2: run, expect FAIL** (`-Dtest='SessionRegistryTest,ConfigBindingTest'`).
- [ ] **Step 3: implement.** Eviction lives in `SessionRegistry` under its existing lock; closing a session happens outside the lock like `restore` already does. `apiEnabled` gates the `/v1` controllers with `@ConditionalOnProperty` and `uiEnabled` the UI beans (check what `UiBeans` already does before adding).
- [ ] **Step 4: run, expect PASS**, then the whole server suite. **Step 5: commit** `feat(server): bounded live sessions with eviction; api and ui switches; template-dir and defaults keys`.

---

### Task 3: Per-conversation settings on the server

**Files:**
- Modify: `ui/UiController.java`, `ui/UiModels.java`, `chat/Conversation.java` (no change if `Settings` already carries `system`), `src/main/jte/chat.jte`
- Create: `src/main/jte/settings.jte`
- Test: `ui/SettingsRoutesTest.java` (new), `TemplatesTest` additions

**Interfaces:**
- `GET /c/{id}/settings` returns the settings fragment (form with `temperature` 0 to 2 step 0.1, `maxTokens` 1 to context length, `system` textarea, Save and Reset buttons, labelled inputs).
- `PUT /c/{id}/settings` (form body `temperature`, `maxTokens`, `system`): validates, stores with the conversation (creating it if needed), returns the fragment plus an out-of-band `#settings-note` message. Reset (`DELETE /c/{id}/settings`) restores `bi4j.ui.defaults.*`.
- The composer's hidden `temperature` and `maxTokens` inputs are removed; `send` reads the stored settings only.
- Changing `system` when the conversation has messages: removes the live session so the next turn rebuilds it, and the note says "System prompt changed: the next answer starts from the stored history with the new prompt".

- [ ] **Step 1: write the failing tests.** `SettingsRoutesTest` (MockMvc style like `UiRoutesTest`, or `TestServer`): (a) PUT stores `temperature=1.5` and the next `send` calls the fake with 1.5 (`FakeBi4j` records the last settings: add a getter `lastSettings()` to the fixture if absent); (b) out-of-range values answer 400 with an alert naming the allowed range; (c) `system` change on a conversation with messages removes the live session (`fake.liveSessions()` drops) and the note text appears; (d) Review Focus 1: PUT while a turn runs answers `409` with the busy alert for a `system` change and `200` for temperature (stored for the next turn); the running turn finishes with the old settings; (e) DELETE restores the defaults of `bi4j.ui.defaults.temperature=0.3` set through the property; (f) escaping: a system prompt of `<script>` renders as text in the form.
- [ ] **Step 2: run, expect FAIL. Step 3: implement** the routes and the fragment; validation errors reuse `alert(...)`. **Step 4: run, expect PASS. Step 5: commit** `feat(server): per-conversation settings (temperature, max tokens, system prompt) with reset`.

---

### Task 4: Settings panel in the page

**Files:**
- Modify: `src/main/jte/page.jte`, `chat.jte`, `app.css`, `app.js`
- Test: `e2e/SettingsE2E.java` (new)

**Interfaces:**
- Header gets a `Settings` button (`aria-expanded`, `aria-controls="settings-panel"`) that loads `GET /c/{id}/settings` into `#settings-panel`: a right-hand drawer at 768 px and wider, a bottom sheet below. Esc or the close button closes it and returns focus to the button.

- [ ] **Step 1: write `SettingsE2E`** with the fake: open the panel, set temperature `1.2`, system `Be brief`, Save, send; assert (through `fake.lastSettings()`) the values; Reset restores `0.7`; Esc closes and focus returns to the header button; at a 390 px viewport the panel is a bottom sheet (its top edge is below the header, its width equals the viewport).
- [ ] **Step 2: run, expect FAIL. Step 3: implement** markup (JTE), the drawer and sheet in CSS (transform transitions disabled under `prefers-reduced-motion`), and the open and close handlers in `app.js` (delegated, no inline handlers). **Step 4: run, expect PASS. Step 5: commit** `feat(server): settings drawer`.

---

### Task 5: Themes

**Files:**
- Create: `src/main/resources/static/theme.js`
- Modify: `layout.jte`, `page.jte`, `app.css`, `app.js`, `UiSecurityHeaders` (no change expected: `script-src 'self'` already allows it)
- Test: `e2e/ThemeE2E.java`, `AssetsTest` additions

**Interfaces:**
- `theme.js` is a blocking `<script src="/theme.js">` in `<head>` (no `defer`): reads `localStorage["bi4j.theme"]` inside try/catch (`light`, `dark`, anything else means system) and sets `document.documentElement.dataset.theme` to `light` or `dark` (system resolves through `matchMedia`).
- CSS tokens move to `:root` plus `:root[data-theme="dark"]`; every colour in `app.css` goes through a token. A header button cycles system, light, dark and writes the key (try/catch around the write).
- Dark palette keeps contrast at least 4.5:1 for text and 3:1 for UI borders (verified by Task 8).

- [ ] **Step 1: write the failing tests.** `AssetsTest`: `/theme.js` is served with `text/javascript`, contains no `innerHTML`, is the first script in `<head>` and has no `defer` or `async`. `ThemeE2E`: (a) seed `localStorage` to `dark` with `context.addInitScript`, load: `document.documentElement.dataset.theme` is `dark` at `DOMContentLoaded` and the computed `body` background is the dark token (no flash); (b) the toggle cycles system, light, dark and survives a reload; (c) Review Focus 2: `localStorage` throws (override `Storage.prototype.getItem` in an init script) and a garbage value `"pink"`: the page loads in system mode with no console error; (d) `emulateMedia(colorScheme=DARK)` with no stored value yields dark.
- [ ] **Step 2: run, expect FAIL. Step 3: implement. Step 4: run, expect PASS. Step 5: commit** `feat(server): light, dark and system themes without a flash`.

---

### Task 6: Responsive layout (drawer sidebar)

**Files:**
- Modify: `page.jte`, `sidebar.jte`, `app.css`, `app.js`
- Test: `e2e/ResponsiveE2E.java`

**Interfaces:**
- Below 768 px the sidebar is an off-canvas drawer behind a `[=]` button in the header (`aria-expanded`, `aria-controls="sidebar"`); it replaces the phase 2 stacked strip. Opening moves focus into the drawer, Esc or a click on the backdrop closes it and returns focus. At 768 px and wider the sidebar is always visible and the button is hidden.

- [ ] **Step 1: write `ResponsiveE2E`:** at 390x800 the sidebar is off-screen until the button is pressed; the composer is fully visible; selecting a conversation closes the drawer; no horizontal scroll (`document.documentElement.scrollWidth <= innerWidth`) at 360 px and at 200 percent zoom (emulate with a 640 px viewport); at 1280 px the button is not displayed.
- [ ] **Step 2: run, expect FAIL. Step 3: implement. Step 4: run, expect PASS** (also re-run `ChatE2E`). **Step 5: commit** `feat(server): off-canvas sidebar on narrow screens`.

---

### Task 7: Keyboard shortcuts

**Files:**
- Create: `src/main/jte/shortcuts.jte`
- Modify: `app.js`, `sidebar.jte` (the `[?] Shortcuts` link), `layout.jte`
- Test: `e2e/KeyboardE2E.java`

**Interfaces:**
- Enter sends, Shift+Enter newline, Esc stops or closes the top drawer or dialog (already in place, keep); Ctrl or Cmd + Shift + O new chat; Ctrl or Cmd + K focus the sidebar filter; Ctrl or Cmd + Shift + S toggle the sidebar; `?` opens a native `<dialog>` listing every shortcut. The list notes that the browser may keep Ctrl+Shift+O.
- A single `shortcuts` table in `app.js` maps key combinations to actions, so the dialog and the handlers cannot drift: the dialog is rendered from the same data attributes in `shortcuts.jte` and a test compares them.

- [ ] **Step 1: write the failing tests.** `KeyboardE2E`: each shortcut does its action; Review Focus 4: typing `?` inside the composer inserts the character and does not open the dialog; `?` with focus on the page body opens it and Esc closes it with focus restored; Ctrl+K inside the composer moves focus to the filter without clearing the draft; Enter during an IME composition (dispatch a `KeyboardEvent` with `isComposing=true`) does not send; a keyboard-only run completes new chat, send, stop, rename and delete using Tab, Enter, Esc and the shortcuts only (no mouse calls). A unit test (`TemplatesTest`) asserts the dialog lists exactly the actions registered in `app.js`.
- [ ] **Step 2: run, expect FAIL. Step 3: implement. Step 4: run, expect PASS. Step 5: commit** `feat(server): keyboard shortcuts and the shortcut list`.

---

### Task 8: Accessibility pass

**Files:**
- Modify: `app.css`, templates as the findings require
- Create: `e2e/A11yE2E.java`; add the `org.webjars.npm:axe-core` test dependency to the `e2e` profile (pin the newest version at least two weeks old, record it in the ledger)
- Test: `e2e/A11yE2E.java`

**Interfaces:**
- `A11yE2E.axe(Page)`: injects the WebJar's `axe.min.js` into a context created with `setBypassCSP(true)` (only this test class; the CSP is asserted elsewhere), runs `axe.run` and returns the violations whose impact is `serious` or `critical`.

- [ ] **Step 1: write the failing test.** For each of these states, in both themes, at 1280 px and at 360 px: empty chat, chat with an answer containing a code block and a table, the settings panel open, the shortcut dialog open, the loading panel, the failed panel, an alert shown, the delete confirm dialog: assert zero serious or critical violations. Also assert: exactly one `h1`; `nav` "Conversations", `main` and the composer `form` landmarks; `aria-current="page"` on the open conversation; every `button` and `a` has an accessible name; the streaming answer has `aria-live="off"` and the end announces "Answer complete" in the `role="status"` region; touch targets measured with `boundingBox()` are at least 44x44 for all buttons, links and inputs.
- [ ] **Step 2: run, expect FAIL** (record the violation list in the ledger). **Step 3: fix** each violation at its source (contrast tokens, labels, focus rings with `:focus-visible`, target sizes). **Step 4: run, expect PASS. Step 5: commit** `fix(server): accessibility to WCAG 2.2 AA (axe-core clean in both themes at 1280 and 360 px)`.

---

### Task 9: Model-free dev loop

**Files:**
- Modify: `ui/Templates.java`, `ui/UiBeans.java`
- Create: `src/test/java/.../server/DevServer.java`
- Test: `ui/TemplateDirTest.java`

**Interfaces:**
- When `bi4j.ui.template-dir` is set, `Templates` renders with a `DirectoryCodeResolver` over that folder in development mode (templates recompile when the file changes); when it is unset nothing changes (precompiled). A missing or non-directory value fails startup with `bi4j.ui.template-dir "X" is not a folder; unset it to use the built-in templates.`
- `DevServer.main(String[])` boots `ServerApplication` with a `FakeBi4j` (delay 40 ms, a canned multi-paragraph Markdown answer with a code block, `failAt` triggered by a message that says `fail`), `bi4j.ui.template-dir=bitubi-inference-4j-server/src/main/jte`, history in memory, port 8080 (or `--port`), and prints the URL.

- [ ] **Step 1: write the failing tests.** `TemplateDirTest`: (a) with a temp folder holding a copy of the templates, rendering `status` shows the change after the file is edited and saved (no restart); (b) Review Focus 5: a missing folder fails startup with the exact sentence above; (c) without the key a template edit has no effect (precompiled).
- [ ] **Step 2: run, expect FAIL. Step 3: implement. Step 4: run, expect PASS.** Then measure: `./mvnw -B -q -pl bitubi-inference-4j-server exec:java -Dexec.mainClass=ao.bitubi.inference4j.server.DevServer -Dexec.classpathScope=test` to the printed URL in under 10 s (record the number; if above, cut work, not the criterion). **Step 5: commit** `feat(server): bi4j.ui.template-dir hot reload and DevServer`.

---

### Task 10: Carry-overs from phase 2 that belong to polish

**Files:**
- Modify: `ui/UiController.java`, `chat/ConversationStore.java`, `chat/MemoryConversationStore.java`, `chat/FileConversationStore.java`, `chat/ChatTurns.java`, `ui/UiModels.java`; create `src/main/jte/notFound.jte`
- Test: `ui/UiRoutesTest.java`, `chat/ConversationStoreTest.java`, `chat/ChatTurnsTest.java`

**Interfaces:**
- `ConversationStore.update(UUID id, UnaryOperator<Conversation> change): Optional<Conversation>`, atomic per id (a lock striped by id inside the store); `rename`, `settings` PUT and `ChatTurns.run` use it instead of load-modify-save.
- Unknown routes outside `/v1`, `/v3`, `/docs`, `/swagger-ui` answer a JTE `notFound` page (status 404, "New chat" link, no stack trace).
- `ChatTurns.send` registers the `RunningTurn` before the session is reserved, so a 409 always has the running-answer link.
- After a restart, the first send on a conversation whose session must be rebuilt shows `Restoring context (N tokens)...` in `#status-live` and logs the time taken (`N` = replayed tokens).

- [ ] **Step 1: write the failing tests.** `ConversationStoreTest` (both stores): 32 threads each appending one message through `update` on one id leave 32 messages. `ChatTurnsTest`: a rename (`update`) racing a finishing turn keeps both the new title and the answer (repeat 200 times). `UiRoutesTest`: `GET /no/such/page` is 404 HTML with one `h1` and a New chat link, `/v1/nope` keeps the OpenAI JSON error; a second tab posting during the reservation gap gets the link (drive with `FakeBi4j.blockRestoreOn`); the restore notice appears and the time is logged once.
- [ ] **Step 2: run, expect FAIL. Step 3: implement. Step 4: run, expect PASS. Step 5: commit** `fix(server): atomic conversation updates, 404 page, running link on 409, restoring-context notice`.

---

### Task 11: Documentation, verified

**Files:**
- Create: `docs/server.md`, `docs/server-extending.md`, `src/test/java/.../server/prompts/PromptsController.java`, `src/test/jte/prompts.jte` (fixture copy of the guide's page), `PromptsGuideTest.java`
- Modify: `README.md` (link both), `CLAUDE.md` (one line per new doc and the dev loop command)

**Interfaces:**
- `docs/server.md`: running, the full `bi4j.*` and `BI4J_*` key table with defaults (every key in the code, checked by a test), data directory layout and privacy, security model (loopback default, host and origin guards, CSP), the API and UI side by side, health checks, troubleshooting by error message.
- `docs/server-extending.md`: the worked "Prompts" page (one `.jte`, one controller method, one test), the full-page versus fragment rule on `HX-Request`, the dev loop (`DevServer`), the SPI note.
- `PromptsGuideTest` boots the app with the fixture controller and asserts the page renders both as a full page and as a fragment; the guide's code blocks are the fixture files verbatim, and a test fails if a fenced block marked `<!-- verbatim: path -->` differs from that file.

- [ ] **Step 1: write the failing tests.** `PromptsGuideTest` (render both modes, escaping of a stored prompt `<b>`), `DocsKeysTest` (every property key declared in `Bi4jServerProperties` and `UiProperties` appears in the `docs/server.md` table with its default), `VerbatimBlocksTest` (the marker check above).
- [ ] **Step 2: run, expect FAIL. Step 3: write the docs and fixtures. Step 4: run, expect PASS. Step 5: commit** `docs: server guide and extending guide, verified by tests`.
- [ ] **Author acceptance (not automated):** one person follows `docs/server-extending.md` in a clean clone and adds the page in under 30 minutes. Record the time and the stumbles in the DX review; do not claim this criterion met without it.

---

### Task 12: Whole-phase verification

- [ ] **Step 1:** add the new E2E classes to the existing `ui-e2e` CI job (nothing to change if it already runs `*E2E`). **Step 2:** `./mvnw -B -Pallmodules clean package -Dtest='!TestSample' -Dmaven.test.failure.ignore=true`, read the failures: only the known ones remain. **Step 3:** run `*E2E` locally with `-Pe2e`. **Step 4:** real-model check with `bitubi-inference-4j-tests/models/tjake_Qwen2.5-0.5B-Instruct-JQ4` through a throw-away Playwright test (settings take effect: temperature 0 gives the same answer twice; dark theme screenshot; narrow-screen screenshot; keyboard run). **Step 5:** update the spec section 18 ("Phase 3 delivered") and `CLAUDE.md`; push; read the CI of all 8 jobs. **Step 6:** final whole-branch review by a fresh reviewer on the most capable model with the Review Focus above; fix Critical and Important with RED then GREEN tests, ledger the minors.
