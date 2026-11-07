# Server phase 1 (server on the SDK) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new `bitubi-inference-4j-server` module (Spring Boot 4.1.2, bytecode 25) that serves the OpenAI-compatible API under `/v1` on top of the SDK (`Bi4j`, `ChatSession`, `Generation`), started by `bi4j serve`, with HTTP listening before the model loads.

**Architecture:** `ModelHost` owns the one `Bi4j` and its load state; `SessionRegistry` + `TurnService` map the `X-Bi4j-Session` header to SDK sessions and reserve one turn per session; hand-written records and one `@RestController` speak OpenAI JSON; two servlet filters guard host and origin. The CLI (`ServeCommand`) builds the `ModelHost`, registers it as a singleton and starts the Boot context first, then loads the model on a virtual thread. `net` keeps only gRPC and topology and moves to Boot 4 / bytecode 25. Tests run the real Tomcat on a random port against a scripted `FakeBi4j` (the `Bi4j` interface is public, so a fake is cheap) and use `java.net.http.HttpClient`, so they do not depend on Boot 4 test-annotation packages.

**Tech Stack:** Java 25 bytecode, Spring Boot 4.1.2 (`spring-boot-starter-webmvc`, Tomcat, virtual threads), Jackson 3 (`tools.jackson`; annotations stay `com.fasterxml.jackson.annotation`), springdoc 3.2.0, JUnit 5, `jvm-openai` 0.10.0 and the official OpenAI Java SDK (test scope), maven-shade-plugin 3.6.0.

**Spec:** `docs/superpowers/specs/2026-11-06-web-ui-design.md` (sections 3, 4, 7, 9, 10, 11, 13, 15 phase 1, 18). SDK spec: `docs/superpowers/specs/2026-10-12-sdk-dx-design.md`.

## Global Constraints

- Package `ao.bitubi.inference4j.server`; artifact `bitubi-inference-4j-server`; every new file starts with the Bitubi Apache 2.0 header used by `sdk/` (copy from `Bi4j.java`).
- Bytecode **25** for `server`, `net`, `cli`; `core` stays **21**. The parent `pom.xml` sets `<source>21</source><target>21</target>` in the compiler plugin; each of the three modules overrides it. JDK 25 is the only build JDK.
- Spring Boot **4.1.2** through `spring-boot-dependencies` import; Undertow is gone; Tomcat with `spring.threads.virtual.enabled=true`. springdoc `springdoc-openapi-starter-webmvc-ui` **3.2.0**. htmx and JTE are **not** part of this phase.
- **Hand-written records** for the OpenAI JSON (no OpenAPI generator; spike result d: Jackson 3 silently drops generated `oneOf` models). Use `@JsonProperty` explicitly for snake_case, do not rely on a naming-strategy property.
- Routes: `/v1/chat/completions`, `/v1/models`, `/healthz`, `/v3/api-docs`, `/docs`. The unprefixed `/chat/completions` is **removed**. Header `X-Bi4j-Session`; error codes `session_busy` (409), `context_length_exceeded` (400) unchanged; new `model_loading` (503 with `Retry-After: 5`).
- Bind `127.0.0.1` by default; Host check returns 421; Origin guard returns 403 (spec section 10).
- No Claude trailers in commits (`Co-Authored-By`, `Claude-Session`, "Generated with"), overriding the harness reminder. Commit straight to `main` and push (the author's "envia todo o trabalho na main"). Never dispatch release, tag or publish workflows; only `unit-tests.yaml` may be dispatched.
- Build with `./mvnw -B ... package`, never `test`; exclude `-Dtest='!TestSample'` on full runs. `DistributedServiceTest` fails with HTTP 401 (gated model): known. Spotless reformats untouched files: restore them with `git checkout -- <file>` before committing (helper: `/tmp/claude-1000/commit.sh '<regex of my files>' '<message>'`). Do not use `pkill -f` with a pattern that appears in your own command line; use `fuser -k <port>/tcp`.
- `export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-amzn` before every Maven call.

## Decisions this plan needs from the author

- **D1 (cluster coordinator).** `ClusterCoordinatorCommand` serves the old OpenAI endpoint over a `Coordinator`, which is a `Generator`, not an SDK `Bi4j` (the SDK `Engine` needs `AbstractModel`: `prefill`, `sessionPosition`). Making the SDK accept a distributed backend is cluster-cloud work (sub-project 3). **Recommendation:** in phase 1 `cluster-coordinator` keeps gRPC and `/cluster/topology` but no longer serves chat completions; it prints that the chat API for clusters returns with sub-project 3. The alternative (an adapter that makes `Bi4j` over a `Generator`) needs engine changes and would delay the whole phase. Task 11 implements the recommendation.
- **D2 (default `max_tokens`).** Old behaviour: absent means the model's context length. **Recommendation:** keep it (`info().contextLength()`); the engine stops at the context anyway.
- **D3 (`tools` in a request).** The SDK has no tool calling yet. **Recommendation:** a request with a non-empty `tools` is answered `501 unsupported_feature` instead of silently ignoring it, so a client that expects tool calls is not misled.

## Review Focus

Behaviours the spec implies but no obvious task exercises; each has a test in the named task.

1. A client that disconnects mid-stream must cancel the generation and leave the session reusable (Task 7).
2. Two simultaneous requests on one session id: exactly one succeeds, the other gets 409, and the session works afterwards (Task 6).
3. Model still loading or failed: chat gets 503 JSON with `Retry-After`, `/healthz` is 503, `/v1/models` is 200 with an empty list, the process stays up (Task 8).
4. `tools`, `n > 1`, an empty `messages`, a non-text content part, a non-UUID session header: clean 4xx/501 JSON errors, never a stack trace or a 500 (Task 6).
5. A request whose `Host` header is not loopback (DNS rebinding) and a cross-site `POST`: refused before any controller runs (Task 9).

## File structure

```
bitubi-inference-4j-server/
  pom.xml
  src/main/java/ao/bitubi/inference4j/server/
    ServerApplication.java          @SpringBootApplication, @ConfigurationPropertiesScan
    Bi4jServerProperties.java       bi4j.server.allowed-hosts
    ModelHost.java                  load state machine around one Bi4j
    ModelNotReadyException.java     -> 503
    SessionRegistry.java            UUID -> ChatSession
    Turn.java                       one running generation (iterator, cancel, close)
    TurnService.java                reserve a session, split the history, start a Turn
    HealthController.java           /healthz
    api/openai/OpenAi.java          the JSON records
    api/openai/OpenAiMapper.java    request -> SDK messages and settings
    api/openai/ChatCompletionsController.java
    api/openai/ModelsController.java
    api/openai/OpenAiErrors.java    @RestControllerAdvice
    security/HostGuardFilter.java
    security/OriginGuardFilter.java
  src/test/java/ao/bitubi/inference4j/server/
    testing/FakeBi4j.java           scripted Bi4j (also the future DevServer's engine)
    testing/TestServer.java         boots the app on a free port with a FakeBi4j
    ...one test class per task
bitubi-inference-4j-net/            slimmed (Task 11)
bitubi-inference-4j-cli/            ServeCommand, shade transformers (Task 12)
bitubi-inference-4j-tests/          JarBootTest (Task 13)
```

---

### Task 1: Module skeleton, Boot 4.1.2 and bytecode 25

**Files:**
- Create: `bitubi-inference-4j-server/pom.xml`
- Create: `bitubi-inference-4j-server/src/main/java/ao/bitubi/inference4j/server/ServerApplication.java`
- Create: `bitubi-inference-4j-server/src/main/java/ao/bitubi/inference4j/server/Bi4jServerProperties.java`
- Create: `bitubi-inference-4j-server/src/main/resources/application.properties`
- Modify: `pom.xml` (add `<module>bitubi-inference-4j-server</module>` to the `allmodules` and `release` profiles, after `net`)
- Test: `bitubi-inference-4j-server/src/test/java/ao/bitubi/inference4j/server/ModuleSmokeTest.java`

**Interfaces:**
- Produces: `ServerApplication` (Boot entry the CLI starts), `Bi4jServerProperties(List<String> allowedHosts)`.

- [ ] **Step 1: Write the pom.** Parent `ao.bitubi:bitubi-inference-4j-parent:${revision}`. `dependencyManagement` imports `org.springframework.boot:spring-boot-dependencies:4.1.2` (type pom, scope import). Dependencies: `ao.bitubi:bitubi-inference-4j-core:${project.version}`; `org.springframework.boot:spring-boot-starter-webmvc`; `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0`; test scope: `org.springframework.boot:spring-boot-starter-test`, `io.github.stefanbratanov:jvm-openai:0.10.0`. Build: `maven-compiler-plugin` configuration `<source>25</source><target>25</target>` (this overrides the parent's 21 by config merge). The parent declares `junit-jupiter-*` 5.9.2 as direct dependencies; Boot 4 expects JUnit 6, so redeclare `junit-jupiter-api`, `junit-jupiter-engine`, `junit-jupiter-params` here **without** a version (taken from the Boot BOM) and remove `junit-vintage-engine` with an `<exclusions>` on `spring-boot-starter-test` if it appears. If Maven reports a conflict, resolve it here, never in the parent.

- [ ] **Step 2: Write `application.properties`.**

```properties
spring.application.name=bi4j-server
spring.threads.virtual.enabled=true
spring.main.banner-mode=off
server.address=127.0.0.1
springdoc.swagger-ui.path=/docs
springdoc.api-docs.path=/v3/api-docs
```

- [ ] **Step 3: Write `ServerApplication` and `Bi4jServerProperties`.**

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {}
```

```java
@ConfigurationProperties("bi4j.server")
public record Bi4jServerProperties(@DefaultValue List<String> allowedHosts) {}
```

(`DefaultValue` is `org.springframework.boot.context.properties.bind.DefaultValue`.)

- [ ] **Step 4: Write the failing test** `ModuleSmokeTest`: starts `ServerApplication` with `new SpringApplicationBuilder(ServerApplication.class).properties("server.port=0").run()`, reads `local.server.port` from the context environment, asserts the port is greater than 0, then closes the context. It fails now because nothing compiles.

- [ ] **Step 5: Run it.**
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-server -am package -Dtest=ModuleSmokeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the context starts; bytecode check in step 6).

- [ ] **Step 6: Check the bytecode level.**
Run: `javap -v -cp bitubi-inference-4j-server/target/classes ao.bitubi.inference4j.server.ServerApplication | grep 'major version'`
Expected: `major version: 69`. Also confirm `core` is still 65: `javap -v` on `ao.bitubi.inference4j.sdk.Bi4j` from the core jar.

- [ ] **Step 7: Commit** `feat(server): module skeleton on Spring Boot 4.1.2 with bytecode 25`.

---

### Task 2: FakeBi4j and TestServer (test fixtures)

**Files:**
- Create: `bitubi-inference-4j-server/src/test/java/ao/bitubi/inference4j/server/testing/FakeBi4j.java`
- Create: `bitubi-inference-4j-server/src/test/java/ao/bitubi/inference4j/server/testing/TestServer.java`
- Test: `bitubi-inference-4j-server/src/test/java/ao/bitubi/inference4j/server/testing/FakeBi4jTest.java`

**Interfaces:**
- Consumes: SDK `Bi4j`, `ChatSession`, `Generation`, `Token`, `ChatResponse`, `Usage`, `FinishReason`, `Message`, `GenerationSettings`, `SessionOptions`, `ChatRequest`, `Capabilities`, `ModelInfo`, `SessionBusyException(UUID)`, `ContextLengthExceededException(UUID, int sessionTokens, int promptTokens, int contextLength)`.
- Produces: `FakeBi4j.script(String... pieces)`, `.delayMs(long)`, `.failAt(int index, RuntimeException e)`, `.contextLimit(int tokens)`, `.cancelCount()`, `.liveSessions()`; `TestServer.start(ModelHost host, String... props)` with `.port()`, `.close()`, `.get(path)`, `.post(path, json, String... headers)`.

`FakeBi4j` is a test class, but Phase 3's `DevServer` reuses it, so keep it free of JUnit imports.

- [ ] **Step 1: Write the failing test** `FakeBi4jTest` (plain JUnit, no Spring):

```java
class FakeBi4jTest {
    @Test void aSessionStreamsTheScriptAndRemembersTheTurn() {
        FakeBi4j fake = new FakeBi4j().script("Hel", "lo");
        ChatSession s = fake.newSession(o -> {});
        Generation g = s.start("hi", GenerationSettings.defaults());
        String text = g.tokens().map(Token::text).collect(Collectors.joining());
        assertEquals("Hello", text);
        assertEquals(FinishReason.STOP, g.result().orElseThrow().finishReason());
        assertEquals(2, s.history().size());
    }

    @Test void cancelEndsTheStreamWithCancelledAndFreesTheSession() {
        FakeBi4j fake = new FakeBi4j().script("a", "b", "c").delayMs(20);
        ChatSession s = fake.newSession(o -> {});
        Generation g = s.start("hi", GenerationSettings.defaults());
        Iterator<Token> it = g.tokens().iterator();
        it.next();
        g.cancel();
        while (it.hasNext()) it.next();
        assertEquals(FinishReason.CANCELLED, g.result().orElseThrow().finishReason());
        assertEquals(1, fake.cancelCount());
        s.start("again", GenerationSettings.defaults()).close(); // not busy any more
    }

    @Test void aSecondGenerationOnTheSameSessionIsBusy() {
        FakeBi4j fake = new FakeBi4j().script("a", "b").delayMs(50);
        ChatSession s = fake.newSession(o -> {});
        Generation first = s.start("one", GenerationSettings.defaults());
        first.tokens().iterator().next();
        assertThrows(SessionBusyException.class, () -> s.start("two", GenerationSettings.defaults()));
        first.close();
    }

    @Test void contextLimitThrowsTheSdkException() {
        FakeBi4j fake = new FakeBi4j().script("x").contextLimit(4);
        ChatSession s = fake.newSession(o -> {});
        assertThrows(ContextLengthExceededException.class, () -> s.start("a very long prompt indeed", GenerationSettings.defaults()).tokens().iterator().hasNext());
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure** (`FakeBi4j` missing).
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-server -am package -Dtest=FakeBi4jTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement `FakeBi4j`.** One file with nested `Session` and `Gen` classes. Rules the tests above pin:
  - Tokens come from `script`, one per piece, `Token(piece, index)`, sleeping `delayMs` before each; `tokens()` returns a lazy `Stream` built from `Spliterators.spliteratorUnknownSize` over an iterator; closing the stream calls `finish()`.
  - `Gen.cancel()` sets a flag, increments the fake's cancel counter once, and the iterator ends at its next step; `finish()` is idempotent: it builds the `ChatResponse` (text so far; `FinishReason.STOP` when the script was exhausted, `CANCELLED` otherwise; `Usage(promptTokens = chars/4, generatedTokens, Duration.ZERO, 0)`), appends the user and assistant `Message`s to the session history, and releases the session's busy flag.
  - `Session.start` takes the busy flag with `compareAndSet(false, true)` and throws `new SessionBusyException(id)` if it was taken.
  - The context check runs at the **first read** of the iterator (not in `start`): tokens of the session so far plus `text.length()/4 + 1` greater than `contextLimit` throws `new ContextLengthExceededException(id, sessionTokens, promptTokens, contextLimit)` and releases the busy flag. This pins the SDK-agnostic "prime before streaming" design of Task 5.
  - `failAt(index, e)` throws `e` from the iterator at that index (the busy flag is released first).
  - `Bi4j.start(ChatRequest)` is a temporary session: same generation class, history not kept. `generate(ChatRequest)` drains `start`. `stream` returns `start(...).tokens()`. `chat()` throws `UnsupportedOperationException` (the builder constructor is package-private in the SDK; the server never calls it). `embed` throws `UnsupportedOperationException`. `capabilities()` returns `new Capabilities(Set.of(Modality.TEXT), true, false, contextLimit)`; `info()` returns `new ModelInfo("fake/model", "fake", "F32", "F32", "F32", 0L, contextLimit)`; `close()` closes every live session. `liveSessions()` counts sessions not yet closed.
  - `ChatSession` methods the server does not use (`send(...)`, `stream`, `restore`, `contextTokens`, `contextRemaining`) are still implemented simply: `restore(list)` appends to history and returns the token estimate; `contextTokens()` is the estimate of the history; `contextRemaining()` is `contextLimit - contextTokens()`; `send` drains `start`.

- [ ] **Step 4: Implement `TestServer`.**

```java
public final class TestServer implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    public static TestServer start(ModelHost host, String... properties) {
        List<String> props = new ArrayList<>(List.of("server.port=0", "server.address=127.0.0.1"));
        props.addAll(List.of(properties));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ServerApplication.class)
            .properties(props.toArray(String[]::new))
            .initializers(c -> c.getBeanFactory().registerSingleton("modelHost", host))
            .run();
        return new TestServer(ctx);
    }

    private TestServer(ConfigurableApplicationContext ctx) {
        this.context = ctx;
        this.port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
    }

    public int port() { return port; }

    public HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() { context.close(); }
}
```

(`ModelHost` arrives in Task 3; until then `TestServer` is compiled but unused. Write `TestServer` now and let Task 3's commit be the first one that compiles it: do Tasks 2 and 3 in one working session, commit `FakeBi4j` + its test first, `TestServer` with Task 3.)

- [ ] **Step 5: Run `FakeBi4jTest`.** Expected: 4 PASS.

- [ ] **Step 6: Commit** `test(server): scripted FakeBi4j with cancel, busy and context-limit behaviour`.

---

### Task 3: ModelHost

**Files:**
- Create: `.../server/ModelHost.java`, `.../server/ModelNotReadyException.java`
- Create: `.../src/test/.../testing/TestServer.java` (from Task 2, step 4)
- Test: `.../ModelHostTest.java`

**Interfaces:**
- Produces:
  - `public final class ModelHost implements AutoCloseable` with `ModelHost(Function<Consumer<LoadProgress>, Bi4j> loader)`, `static ModelHost ready(Bi4j bi4j)` (tests), `void start()`, `Status status()`, `Bi4j require()` (throws `ModelNotReadyException`), `Optional<Bi4j> bi4j()`, `close()`.
  - `public enum State { DOWNLOADING, LOADING, READY, FAILED }`, `public record Status(State state, String detail, long done, long total, Throwable cause)`.
  - `public final class ModelNotReadyException extends RuntimeException` with `Status status()`.
  - `start()` is idempotent and never blocks: the loader runs on a virtual thread named `bi4j-model-load`.

- [ ] **Step 1: Write the failing tests.**

```java
class ModelHostTest {
    @Test void goesThroughLoadingToReadyAndExposesTheModel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeBi4j fake = new FakeBi4j();
        ModelHost host = new ModelHost(progress -> {
            progress.accept(new LoadProgress(LoadProgress.Phase.DOWNLOADING, "model.bin", 50, 100));
            awaitQuietly(release);
            progress.accept(new LoadProgress(LoadProgress.Phase.LOADING, "weights", 0, 0));
            return fake;
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.DOWNLOADING);
        assertEquals(50, host.status().done());
        assertThrows(ModelNotReadyException.class, host::require);
        release.countDown();
        await(() -> host.status().state() == ModelHost.State.READY);
        assertSame(fake, host.require());
    }

    @Test void aLoaderFailureIsKeptWithItsMessage() throws Exception {
        ModelHost host = new ModelHost(p -> { throw new ModelNotFoundException("no such model: /x"); });
        host.start();
        await(() -> host.status().state() == ModelHost.State.FAILED);
        assertTrue(host.status().detail().contains("no such model: /x"));
        ModelNotReadyException e = assertThrows(ModelNotReadyException.class, host::require);
        assertEquals(ModelHost.State.FAILED, e.status().state());
    }

    @Test void closingWhileLoadingClosesTheLateModel() throws Exception {
        CountDownLatch inLoad = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeBi4j fake = new FakeBi4j();
        ModelHost host = new ModelHost(p -> { inLoad.countDown(); awaitQuietly(release); return fake; });
        host.start();
        assertTrue(inLoad.await(10, TimeUnit.SECONDS));
        host.close();
        release.countDown();
        await(() -> fake.isClosed());
    }
}
```

`await(BooleanSupplier)` polls every 10 ms for up to 10 s and fails with a message; `awaitQuietly(CountDownLatch)` waits up to 30 s. Add `isClosed()` to `FakeBi4j` (a volatile flag set by `close()`).

- [ ] **Step 2: Run, expect a compile failure.**

- [ ] **Step 3: Implement.** `status` is an `AtomicReference<Status>` starting at `Status(LOADING, "waiting to start", 0, 0, null)`. The progress consumer maps `Phase.DOWNLOADING` to `State.DOWNLOADING`, `CHECKING` and `LOADING` to `LOADING`, `READY` to `READY`-pending (only `READY` after the loader returns). The thread:

```java
Thread.ofVirtual().name("bi4j-model-load").start(() -> {
    try {
        Bi4j loaded = loader.apply(this::onProgress);
        synchronized (this) {
            if (closed) { loaded.close(); return; }
            this.model = loaded;
            status.set(new Status(State.READY, loaded.info().name(), 0, 0, null));
        }
    } catch (Throwable t) {
        log.error("model load failed", t);
        status.set(new Status(State.FAILED, t.getMessage() == null ? t.toString() : t.getMessage(), 0, 0, t));
    }
});
```

`close()` sets `closed` under the same lock and closes the model if present. The `detail` of `FAILED` is the exception message verbatim (the SDK messages already say what to do).

- [ ] **Step 4: Run `ModelHostTest`.** Expected: 3 PASS.

- [ ] **Step 5: Commit** `feat(server): ModelHost with the download, load, ready and failed states`.

---

### Task 4: OpenAI records and the request mapper

**Files:**
- Create: `.../server/api/openai/OpenAi.java`
- Create: `.../server/api/openai/OpenAiMapper.java`
- Test: `.../server/api/openai/OpenAiJsonTest.java`

**Interfaces:**
- Consumes: SDK `Message`, `GenerationSettings`, `FinishReason`, `Usage`.
- Produces (all `public` nested in `OpenAi`):
  - Request: `ChatCompletionRequest(String model, List<ChatMessage> messages, @JsonProperty("max_tokens") Integer maxTokens, @JsonProperty("max_completion_tokens") Integer maxCompletionTokens, Float temperature, Boolean stream, Object stop, Integer n, List<Object> tools, @JsonProperty("stream_options") StreamOptions streamOptions)`, `ChatMessage(String role, Object content)`, `StreamOptions(@JsonProperty("include_usage") Boolean includeUsage)`. Each with `@JsonIgnoreProperties(ignoreUnknown = true)`.
  - Response: `ChatCompletion`, `Choice`, `AssistantMessage`, `ChatCompletionChunk`, `ChunkChoice`, `Delta`, `Usage`, `ModelList`, `ModelEntry`, `ErrorBody`, `ErrorDetail`, with the OpenAI field names.
  - `OpenAiMapper.messages(ChatCompletionRequest)` returns `List<Message>`; `settings(ChatCompletionRequest, int contextLength)` returns `GenerationSettings`; `finishReason(FinishReason)` returns `"stop"` or `"length"`; `usage(ChatResponse)` returns `OpenAi.Usage`. It throws `UnsupportedRequestException` (nested, `extends RuntimeException`, carries the `code`) for non-text parts, roles other than system/developer/user/assistant, and non-empty `tools`; and `IllegalArgumentException` for `n > 1`, null content, empty `messages`.

- [ ] **Step 1: Write the failing tests.** `OpenAiJsonTest` uses a `tools.jackson.databind.json.JsonMapper` built exactly as Boot builds it (`JsonMapper.builder().findAndAddModules().build()`) so it exercises the Jackson 3 path that broke the generated models:

```java
@Test void contentAsAStringAndAsAPartsArrayBothParse() {
    var plain = mapper.readValue("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", OpenAi.ChatCompletionRequest.class);
    assertEquals("hi", OpenAiMapper.messages(plain).get(0).text());
    var parts = mapper.readValue("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}]}", OpenAi.ChatCompletionRequest.class);
    assertEquals("a\nb", OpenAiMapper.messages(parts).get(0).text());
}

@Test void snakeCaseFieldsAndUnknownFieldsAreHandled() {
    var r = mapper.readValue("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"max_tokens\":7,\"seed\":1,\"logprobs\":false,\"stream_options\":{\"include_usage\":true}}", OpenAi.ChatCompletionRequest.class);
    assertEquals(7, r.maxTokens());
    assertTrue(r.streamOptions().includeUsage());
}

@Test void stopAsStringOrArray() { /* "stop":"END" -> List.of("END"); "stop":["a","b"] -> List.of("a","b") via OpenAiMapper.settings */ }

@Test void aNonTextPartIsUnsupportedNotSilentlyDropped() {
    // content part {"type":"image_url",...} -> UnsupportedRequestException with code "unsupported_input"
}

@Test void toolsAreRejectedWhenNonEmpty() {
    // "tools":[{"type":"function"}] -> UnsupportedRequestException code "unsupported_feature"; "tools":[] is fine
}

@Test void developerRoleIsASystemMessageAndToolRoleIsUnsupported() { }

@Test void responsesUseOpenAiFieldNames() {
    String json = mapper.writeValueAsString(new OpenAi.ChatCompletion("chatcmpl-1", "chat.completion", 1L, "m",
        List.of(new OpenAi.Choice(0, new OpenAi.AssistantMessage("assistant", "hi"), "stop")), new OpenAi.Usage(3, 2, 5)));
    assertTrue(json.contains("\"finish_reason\":\"stop\""));
    assertTrue(json.contains("\"prompt_tokens\":3"));
}

@Test void aChunkAlwaysCarriesFinishReasonEvenWhenNull() {
    String json = mapper.writeValueAsString(new OpenAi.ChatCompletionChunk("id", "chat.completion.chunk", 1L, "m",
        List.of(new OpenAi.ChunkChoice(0, new OpenAi.Delta(null, "x"), null)), null));
    assertTrue(json.contains("\"finish_reason\":null"));
    assertFalse(json.contains("\"usage\""));
}

@Test void emptyMessagesAndNGreaterThanOneAreRejected() { }
```

Fill every test body (they are short); the three commented ones are single assertions on the mapper.

- [ ] **Step 2: Run, expect a compile failure.**

- [ ] **Step 3: Implement.** Response records carry `@JsonInclude(JsonInclude.Include.NON_NULL)`, except `ChunkChoice` (its `finishReason` component gets `@JsonInclude(JsonInclude.Include.ALWAYS)`) and `ErrorDetail` (always writes `param`). Mapper rules: the content `Object` is a `String`, or a `List` of maps with `type` and `text`; parts joined with `"\n"` (same as `Message.text()`); `developer` maps to `SYSTEM`; `max_completion_tokens` wins over `max_tokens`; absent `max_tokens` becomes `contextLength` (D2); absent `temperature` stays the SDK default (`GenerationSettings.defaults().temperature()`); `stop` as `String` or `List<String>`; `Message.system/user/assistant` factories; `FinishReason.LENGTH` maps to `"length"`, everything else to `"stop"`. `usage(ChatResponse r)` builds `new OpenAi.Usage(u.promptTokens(), u.generatedTokens(), sum)`.

- [ ] **Step 4: Run `OpenAiJsonTest`.** Expected: all PASS. If `messages` parse to null/empty here, the Jackson 3 trap is back: stop and fix the records, do not weaken the test.

- [ ] **Step 5: Commit** `feat(server): OpenAI records and request mapper (Jackson 3, hand-written)`.

---

### Task 5: SessionRegistry, Turn and TurnService

**Files:**
- Create: `.../server/SessionRegistry.java`, `.../server/Turn.java`, `.../server/TurnService.java`
- Test: `.../server/TurnServiceTest.java`

**Interfaces:**
- Consumes: `ModelHost.require()`, SDK types, `FakeBi4j`.
- Produces:
  - `SessionRegistry`: `ChatSession getOrCreate(UUID id, Bi4j bi4j, String system, List<Message> earlier)`, `Optional<ChatSession> find(UUID id)`, `void remove(UUID id)`, `int size()`, `void closeAll()` (a `@PreDestroy`-style `close` bean method).
  - `Turn implements AutoCloseable`: `Iterator<Token> tokens()`, `void prime()`, `void cancel()`, `ChatResponse result()`, `UUID id()`, `close()` (idempotent; runs the release callback exactly once).
  - `TurnService(ModelHost host, SessionRegistry sessions)`: `Turn start(Optional<UUID> sessionId, List<Message> messages, GenerationSettings settings)` throwing `ModelNotReadyException`, `SessionBusyException`, `IllegalArgumentException`.

Rules (spec 7 and 9). Without a session id the request is temporary: `bi4j.start(new ChatRequest(messages, settings))`. With one: reserve the id in `active` (a second reservation throws `SessionBusyException`), then split `messages` at the last `ASSISTANT`: everything up to it is `earlier` (the system message, if first, is passed separately as `system`), everything after it is the new turn and must be `USER` messages only (their `text()` joined with `"\n\n"`), otherwise `IllegalArgumentException("the last messages must come from the user")`. A new session id creates the session (`bi4j.newSession(o -> { if (system != null) o.system(system); })`) and `restore(earlier-without-system)` when non-empty; a known id keeps its KV cache and ignores `earlier`. The reservation is released by `Turn.close()`, and also immediately if starting throws.

- [ ] **Step 1: Write the failing tests** (`TurnServiceTest`, no Spring, `ModelHost.ready(fake)`):

```java
@Test void withoutAnIdTheRequestIsTemporaryAndKeepsNothing() {
    try (Turn t = service.start(Optional.empty(), List.of(Message.user("hi")), GenerationSettings.defaults())) {
        drain(t);
    }
    assertEquals(0, sessions.size());
}

@Test void aSecondTurnWithTheSameIdOnlySendsTheNewMessage() {
    UUID id = UUID.randomUUID();
    run(id, List.of(Message.user("one")));
    run(id, List.of(Message.user("one"), Message.assistant("Hello"), Message.user("two")));
    assertEquals(4, sessions.find(id).orElseThrow().history().size()); // not 6: "one" was not sent twice
}

@Test void aNewIdWithAHistoryRestoresItFirst() {
    UUID id = UUID.randomUUID();
    run(id, List.of(Message.system("be brief"), Message.user("a"), Message.assistant("b"), Message.user("c")));
    List<Message> h = sessions.find(id).orElseThrow().history();
    assertEquals("a", h.get(0).text());   // restored
    assertEquals("b", h.get(1).text());
    assertEquals("c", h.get(2).text());   // the new turn
}

@Test void aBusySessionIsRejectedAndFreedAfterTheTurn() {
    UUID id = UUID.randomUUID();
    Turn first = service.start(Optional.of(id), List.of(Message.user("x")), GenerationSettings.defaults());
    assertThrows(SessionBusyException.class, () -> service.start(Optional.of(id), List.of(Message.user("y")), GenerationSettings.defaults()));
    first.close();
    service.start(Optional.of(id), List.of(Message.user("z")), GenerationSettings.defaults()).close();
}

@Test void aStartThatFailsFreesTheReservation() { /* messages ending in an assistant message -> IllegalArgumentException, then the same id works */ }

@Test void primeSurfacesAContextErrorBeforeAnyStreaming() {
    FakeBi4j small = new FakeBi4j().script("x").contextLimit(4);
    // start succeeds, prime() throws ContextLengthExceededException
}

@Test void tenThreadsOnOneIdGiveExactlyOneTurn() { /* CountDownLatch gate, 10 virtual threads, count successes == 1, rest SessionBusyException */ }
```

`run(id, messages)` is a helper: `try (Turn t = service.start(...)) { drain(t); }`.

- [ ] **Step 2: Run, expect a compile failure.**

- [ ] **Step 3: Implement.** `Turn` wraps the `Generation`: `Stream<Token> stream = generation.tokens(); Iterator<Token> it = stream.iterator();` and `prime()` calls `it.hasNext()` (the first read is what starts the engine, and it is where `ContextLengthExceededException` can surface). `result()` is `generation.result().orElseThrow(() -> new IllegalStateException("generation has no result yet"))`. `close()`: `AtomicBoolean closed`; on first call `stream.close(); generation.close();` then `release.run()` in `finally`. `SessionRegistry` is a `ConcurrentHashMap<UUID, ChatSession>`; `getOrCreate` uses `computeIfAbsent` with the creation inside (creation includes `restore`). `closeAll` closes each session, swallowing and logging exceptions.

- [ ] **Step 4: Run `TurnServiceTest`.** Expected: all PASS.

- [ ] **Step 5: Commit** `feat(server): TurnService reserves a session per turn and replays client history on a new id`.

---

### Task 6: Chat completions (non-streaming) and OpenAI errors

**Files:**
- Create: `.../api/openai/ChatCompletionsController.java`, `.../api/openai/OpenAiErrors.java`, `.../server/ServerBeans.java` (the `@Configuration` that declares `SessionRegistry` and `TurnService` beans and a `@Bean(destroyMethod = "close") ModelHost` is NOT here: the host comes from the caller, see Task 1/12)
- Test: `.../api/openai/ChatCompletionsTest.java`

**Interfaces:**
- Consumes: `TurnService`, `ModelHost`, `OpenAiMapper`, `OpenAi.*`.
- Produces: `POST /v1/chat/completions` (JSON in, JSON or SSE out); the `OpenAiErrors` mapping table below.

Error table (body `{"error":{"message","type","param","code"}}`):

| Exception | Status | type | code |
|---|---|---|---|
| `SessionBusyException` | 409 | `invalid_request_error` | `session_busy` |
| `ContextLengthExceededException` | 400 | `invalid_request_error` | `context_length_exceeded` (param `messages`) |
| `ModelNotReadyException` loading | 503 + `Retry-After: 5` | `server_error` | `model_loading` |
| `ModelNotReadyException` failed | 503 | `server_error` | `model_failed` (message = the load error) |
| `IllegalArgumentException`, unreadable body | 400 | `invalid_request_error` | `invalid_request` |
| bad `X-Bi4j-Session` (not a UUID) | 400 | `invalid_request_error` | `invalid_session` |
| `UnsupportedRequestException` | 501 | `invalid_request_error` | its code (`unsupported_input`, `unsupported_feature`) |
| `UnsupportedInputException` (SDK) | 501 | `invalid_request_error` | `unsupported_input` |
| any other `Bi4jException` | 500 | `server_error` | `engine_error` |

- [ ] **Step 1: Write the failing tests** (`TestServer` with `ModelHost.ready(fake)`):

```java
@Test void aPlainRequestReturnsAChatCompletion() throws Exception {
    fake.script("Hel", "lo");
    var r = server.post("/v1/chat/completions", body(false, "hi"));
    assertEquals(200, r.statusCode());
    JsonNode j = mapper.readTree(r.body());
    assertEquals("chat.completion", j.get("object").asText());
    assertEquals("Hello", j.at("/choices/0/message/content").asText());
    assertEquals("stop", j.at("/choices/0/finish_reason").asText());
    assertTrue(j.at("/usage/total_tokens").asInt() > 0);
}

@Test void theSessionHeaderKeepsTheConversation() { /* two POSTs with the same X-Bi4j-Session, the second sends the whole history; fake session history size 4 */ }

@Test void aNonUuidSessionHeaderIs400WithJson() { /* code invalid_session */ }

@Test void aBusySessionIs409BeforeAnyStream() {
    // fake.delayMs(300); a streaming POST on a thread, then a second POST with the same header after the first has started -> 409 session_busy
}

@Test void tenSimultaneousRequestsOnOneSessionGiveOneSuccess() { /* CompletableFutures; count 200 == 1, 409 == 9; then a new request works */ }

@Test void contextLengthIs400WithTheNumbers() { /* contextLimit(4), long prompt -> 400 context_length_exceeded, message contains "4" */ }

@Test void toolsAndNAndEmptyMessagesAreCleanErrors() { /* tools non-empty -> 501 unsupported_feature; n=2 -> 400; messages [] -> 400; image part -> 501 unsupported_input; role tool -> 501 */ }

@Test void aMalformedBodyIs400Json() { /* "{" -> 400 with the error envelope, not Spring's default HTML or Problem JSON */ }

@Test void anEngineFailureIs500JsonWithoutAStackTrace() { /* fake.failAt(0, new EngineSetupException(...)) -> 500, body has no "at ao." */ }
```

`body(stream, text)` builds the JSON string with `"model":"m"`, `"messages":[{"role":"user","content":text}]`, `"stream":stream`, `"max_tokens":16`.

- [ ] **Step 2: Run, expect failures** (404 on the route).

- [ ] **Step 3: Implement the controller** for the non-streaming path:

```java
@RestController
public class ChatCompletionsController {
    static final String SESSION_HEADER = "X-Bi4j-Session";

    @PostMapping(path = "/v1/chat/completions", consumes = "application/json")
    Object create(@RequestHeader(name = SESSION_HEADER, required = false) String sessionHeader,
                  @RequestBody OpenAi.ChatCompletionRequest request) {
        Optional<UUID> session = sessionId(sessionHeader);          // IllegalArgumentException -> invalid_session
        Bi4j bi4j = host.require();
        List<Message> messages = OpenAiMapper.messages(request);
        GenerationSettings settings = OpenAiMapper.settings(request, bi4j.info().contextLength());
        Turn turn = turns.start(session, messages, settings);
        try {
            turn.prime();                                            // context and engine errors become HTTP errors here
        } catch (RuntimeException e) { turn.close(); throw e; }
        if (Boolean.TRUE.equals(request.stream())) return stream(turn, request);   // Task 7
        try (turn) {
            Iterator<Token> it = turn.tokens();
            while (it.hasNext()) it.next();
            ChatResponse r = turn.result();
            return new OpenAi.ChatCompletion(id(turn), "chat.completion", now(), bi4j.info().name(),
                List.of(new OpenAi.Choice(0, new OpenAi.AssistantMessage("assistant", r.text()), OpenAiMapper.finishReason(r.finishReason()))),
                OpenAiMapper.usage(r));
        }
    }
}
```

Give `sessionId(String)` package-private and static so a unit test can call it. Until Task 7, `stream(...)` throws `UnsupportedOperationException` (the compile placeholder is replaced in the next task; do not commit the placeholder, commit after Task 7 if the stream test is part of the same session, or implement the stream path in this commit). `OpenAiErrors` is a `@RestControllerAdvice` with one `@ExceptionHandler` per row of the table, building the envelope with a helper `ResponseEntity<OpenAi.ErrorBody> error(HttpStatus, String type, String code, String param, String message, HttpHeaders)`. Handle `HttpMessageNotReadableException` (`tools.jackson.core.JacksonException` is wrapped in it by Spring) as `invalid_request`. Declare `SessionRegistry` and `TurnService` as beans in `ServerBeans` (`@Configuration`, constructor `TurnService(ModelHost, SessionRegistry)`; `SessionRegistry` gets `destroyMethod = "closeAll"`). The `ModelHost` bean comes from the outside (`registerSingleton`), so `ServerBeans` just injects it.

- [ ] **Step 4: Run `ChatCompletionsTest`.** Expected: all PASS except the stream-related ones, which come in Task 7.

- [ ] **Step 5: Commit** `feat(server): /v1/chat/completions non-streaming with OpenAI error bodies`.

---

### Task 7: Streaming and client disconnect

**Files:**
- Modify: `.../api/openai/ChatCompletionsController.java` (the `stream` method)
- Test: `.../api/openai/ChatStreamingTest.java`

**Interfaces:**
- Consumes: `Turn`, `OpenAi.ChatCompletionChunk`, `OpenAi.ChunkChoice`, `OpenAi.Delta`.
- Produces: SSE events `data: {chunk}` ... `data: [DONE]`.

Wire format: first chunk `delta {"role":"assistant","content":""}`; one chunk per token with `delta {"content": piece}`; a final chunk with an empty delta `{}` and `finish_reason`; if `stream_options.include_usage` is true, one more chunk with `"choices":[]` and `usage`; then `data: [DONE]`. All chunks share one `id` (`chatcmpl-<turn id>`) and `created`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void streamsRoleTokensFinishAndDone() throws Exception {
    fake.script("Hel", "lo");
    List<String> data = sse(server, body(true, "hi"));         // lines starting with "data: ", prefix removed
    assertEquals("assistant", mapper.readTree(data.get(0)).at("/choices/0/delta/role").asText());
    assertEquals("Hello", data.stream().filter(d -> !d.equals("[DONE]")).map(this::content).collect(joining()));
    assertEquals("stop", finishOf(data.get(data.size() - 2)));
    assertEquals("[DONE]", data.get(data.size() - 1));
}

@Test void usageChunkOnlyWhenAsked() { /* stream_options include_usage true -> a chunk with empty choices and usage before [DONE]; absent -> none */ }

@Test void aDisconnectCancelsTheGenerationAndFreesTheSession() throws Exception {
    fake.script(repeat("t", 200)).delayMs(20);
    UUID id = UUID.randomUUID();
    // open the stream with a raw HttpClient request, read three data lines, then close the response body stream
    awaitCondition(() -> fake.cancelCount() == 1);
    // the session works again
    fake.script("ok").delayMs(0);
    assertEquals(200, server.post("/v1/chat/completions", body(false, "again"), "X-Bi4j-Session", id.toString()).statusCode());
}

@Test void aContextErrorOnAStreamingRequestIsStill400() { /* contextLimit small, stream:true -> 400 JSON, not an SSE body: this is the prime() design */ }

@Test void theTemporarySessionOfAStreamIsFreed() { /* no header: fake.liveSessions() returns 0 after the stream ends */ }
```

For the disconnect test use `HttpClient.send(request, BodyHandlers.ofInputStream())`, read lines with a `BufferedReader`, then `close()` the stream (the server's next `emitter.send` throws `IOException`; Tomcat may need one more write, the fake's 20 ms delay provides it).

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement `stream`.**

```java
private SseEmitter stream(Turn turn, OpenAi.ChatCompletionRequest request, String model) {
    SseEmitter emitter = new SseEmitter(0L);                       // no timeout: generations can be long
    boolean usage = request.streamOptions() != null && Boolean.TRUE.equals(request.streamOptions().includeUsage());
    Thread.ofVirtual().name("bi4j-sse-" + turn.id()).start(() -> {
        String id = id(turn);
        long created = now();
        try (turn) {
            send(emitter, chunk(id, created, model, new OpenAi.Delta("assistant", ""), null));
            Iterator<Token> it = turn.tokens();
            while (it.hasNext()) send(emitter, chunk(id, created, model, new OpenAi.Delta(null, it.next().text()), null));
            ChatResponse r = turn.result();
            send(emitter, chunk(id, created, model, new OpenAi.Delta(null, null), OpenAiMapper.finishReason(r.finishReason())));
            if (usage) send(emitter, new OpenAi.ChatCompletionChunk(id, "chat.completion.chunk", created, model, List.of(), OpenAiMapper.usage(r)));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException | IllegalStateException clientGone) {
            turn.cancel();                                          // the API cancels when the client leaves
            emitter.completeWithError(clientGone);
        } catch (RuntimeException e) {
            log.warn("stream failed after the first token", e);
            emitter.completeWithError(e);
        }
    });
    return emitter;
}
```

`send` calls `emitter.send(SseEmitter.event().data(obj, MediaType.APPLICATION_JSON))`. The `try (turn)` closes the generation and releases the reservation exactly once, including on the cancel path.

- [ ] **Step 4: Run `ChatStreamingTest` and re-run `ChatCompletionsTest`.** Expected: PASS.

- [ ] **Step 5: Commit** `feat(server): streaming chat completions with [DONE], usage chunk and cancel on disconnect`.

---

### Task 8: Models, health and API docs

**Files:**
- Create: `.../api/openai/ModelsController.java`, `.../server/HealthController.java`
- Test: `.../server/HealthAndModelsTest.java`

**Interfaces:**
- Consumes: `ModelHost.status()`, `ModelHost.bi4j()`.
- Produces: `GET /v1/models`, `GET /healthz`; springdoc serves `/v3/api-docs` and `/docs`.

Behaviour: `/healthz` is `200 {"status":"ready","model":"<name>"}` only when READY; otherwise `503 {"status":"downloading|loading|failed","detail":"...","done":N,"total":N}` (no stack trace; `detail` is the load error message for `failed`). `/v1/models` is `200 {"object":"list","data":[{"id":"<name>","object":"model","created":<epoch>,"owned_by":"bitubi"}]}` when READY and `{"object":"list","data":[]}` otherwise (it never fails).

- [ ] **Step 1: Write the failing tests.**

```java
@Test void whileLoadingHealthIs503ModelsIsEmptyAndChatIs503WithRetryAfter() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ModelHost host = new ModelHost(p -> { p.accept(new LoadProgress(LoadProgress.Phase.DOWNLOADING, "w", 30, 100)); awaitQuietly(release); return new FakeBi4j(); });
    host.start();
    try (TestServer s = TestServer.start(host)) {
        await(() -> host.status().state() == ModelHost.State.DOWNLOADING);
        var h = s.get("/healthz");
        assertEquals(503, h.statusCode());
        assertEquals("downloading", mapper.readTree(h.body()).get("status").asText());
        assertEquals(30, mapper.readTree(h.body()).get("done").asInt());
        assertEquals(0, mapper.readTree(s.get("/v1/models").body()).get("data").size());
        var c = s.post("/v1/chat/completions", "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertEquals(503, c.statusCode());
        assertEquals("5", c.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("model_loading", mapper.readTree(c.body()).at("/error/code").asText());
        release.countDown();
    }
}

@Test void aFailedModelKeepsTheServerUpAndSaysWhy() { /* loader throws ModelNotFoundException("no such model: /x"): /healthz 503 failed with detail, chat 503 model_failed with the message, no Retry-After */ }

@Test void readyHealthAndModelsNameTheModel() { /* ModelHost.ready(new FakeBi4j()) -> 200, "fake/model" */ }

@Test void apiDocsListTheChatRoute() {
    var r = server.get("/v3/api-docs");
    assertEquals(200, r.statusCode());
    assertTrue(r.body().contains("/v1/chat/completions"));
    assertEquals(200, server.get("/docs/index.html").statusCode());   // swagger-ui page; if springdoc redirects, follow it
}
```

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement.** Both controllers are `@RestController`s returning `ResponseEntity<Map<String,Object>>` / `OpenAi.ModelList`. Add `@Operation(summary = ...)` (`io.swagger.v3.oas.annotations`) on the chat method so the docs read well; springdoc scans the `ao.bitubi.inference4j.server` package by default.

- [ ] **Step 4: Run `HealthAndModelsTest`.** Expected: PASS. If `/docs/index.html` is not the right path for springdoc 3.2.0, read the redirect `Location` from `GET /docs` and assert on it instead (the spike confirmed the Swagger UI is served).

- [ ] **Step 5: Commit** `feat(server): /healthz, /v1/models and API docs, answering while the model loads`.

---

### Task 9: Host and origin guards

**Files:**
- Create: `.../server/security/HostGuardFilter.java`, `.../server/security/OriginGuardFilter.java`
- Test: `.../server/security/GuardsTest.java`

**Interfaces:**
- Consumes: `Bi4jServerProperties.allowedHosts()`.
- Produces: two `jakarta.servlet.Filter` beans (`@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)` and `+1`).

Rules (spec 10). Host guard: take the `Host` header, strip a trailing `:port` (an IPv6 literal keeps its brackets), compare case-insensitively with `localhost`, `127.0.0.1`, `[::1]` and `allowedHosts`; no match returns `421` with an OpenAI-shaped JSON error (`code` `host_not_allowed`). Origin guard, for `POST`, `PUT`, `PATCH`, `DELETE` only: `Sec-Fetch-Site` equal to `cross-site` or `same-site` returns 403; an `Origin` header whose authority (`host[:port]`) differs from the `Host` header returns 403; requests without those headers pass. Both write JSON directly (`response.setContentType("application/json")`), they do not throw.

- [ ] **Step 1: Write the failing tests.** `HttpClient` refuses to set `Host` and `Origin`-style restricted headers on some JDKs, so write raw HTTP over a `java.net.Socket` in a helper `raw(port, "POST /v1/chat/completions HTTP/1.1\r\nHost: evil.example\r\n...")` that returns the status line:

```java
@Test void aForeignHostIs421() { assertEquals(421, status(raw(port, request("GET", "/healthz", "Host: evil.example")))); }
@Test void localhostWithAPortIsAllowed() { assertEquals(200, status(raw(port, request("GET", "/healthz", "Host: localhost:" + port)))); }
@Test void anIpv6LoopbackIsAllowed() { /* Host: [::1]:port */ }
@Test void anAllowedHostFromTheProperty() { /* TestServer.start(host, "bi4j.server.allowed-hosts=chat.lan"), Host: chat.lan -> not 421 */ }
@Test void aCrossSitePostIs403() { /* Sec-Fetch-Site: cross-site */ }
@Test void aForeignOriginPostIs403() { /* Origin: http://evil.example with Host: 127.0.0.1:port */ }
@Test void curlStylePostsWithoutBrowserHeadersPass() { /* no Origin, no Sec-Fetch-Site -> 200 */ }
@Test void getRequestsAreNotOriginChecked() { /* GET with Origin foreign -> 200 */ }
```

(`/healthz` returns 200 with a ready host, so the tests use `ModelHost.ready(new FakeBi4j())`.)

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement both filters** as `OncePerRequestFilter` subclasses (`org.springframework.web.filter`), registered by `@Component`. A short comment on the allow-list: loopback names only, because a request addressed to any other name reaching a loopback-bound server is a DNS-rebinding attempt.

- [ ] **Step 4: Run `GuardsTest` and the whole server test set.**
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-server -am package -Dtest='!TestSample' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit** `feat(server): host and origin guards against DNS rebinding and cross-site posts`.

---

### Task 10: Official clients against the fake

**Files:**
- Modify: `bitubi-inference-4j-server/pom.xml` (test dependency on the OpenAI Java SDK)
- Test: `.../api/openai/OfficialClientsTest.java`

**Interfaces:**
- Consumes: `TestServer`, `FakeBi4j`.

- [ ] **Step 1: Pin the SDK version.** Look up the latest release of `com.openai:openai-java` in `https://repo1.maven.org/maven2/com/openai/openai-java/maven-metadata.xml` and use that exact version (older than two weeks is not required for Maven, but do not float). Add it with test scope. If its Jackson requirement collides with Jackson 3 on the test classpath, the SDK pulls Jackson 2 for itself, which is fine in test scope; if resolution fails, record the error and use only `jvm-openai` here, then raise it with the author (the acceptance line asks for both).

- [ ] **Step 2: Write the tests.**

```java
@Test void jvmOpenAiCompletesAStreamingChat() {
    fake.script("Hel", "lo", " there");
    OpenAI openAI = OpenAI.newBuilder("key").baseUrl("http://127.0.0.1:" + port + "/v1").build();
    ChatClient client = openAI.chatClient();
    var req = CreateChatCompletionRequest.newBuilder().model("m").stream(true).message(ChatMessage.userMessage("hi")).build();
    String text = client.streamChatCompletion(req).map(c -> c.choices().get(0).delta() == null ? "" : String.valueOf(c.choices().get(0).delta().content()))...  // use the accessor names of 0.10.0
    assertEquals("Hello there", text);
}

@Test void jvmOpenAiNonStreaming() { }
@Test void theOfficialJavaSdkCompletesStreamingAndNonStreaming() { }
@Test void theOfficialJavaSdkReadsTheModelList() { }
```

Read each client's accessor names from its jar (they differ between versions); the assertions are: the concatenated deltas equal the script, the client sees a normal end of stream (no exception), `/v1/models` lists `fake/model`. Note: `jvm-openai` 0.10.0's `baseUrl` already ends in `/v1` for the default OpenAI host; pass the base **with** `/v1` and confirm by the request log if the first call 404s.

- [ ] **Step 3: Run `OfficialClientsTest`.** Expected: PASS. A 404 means the base URL convention is wrong, not the server.

- [ ] **Step 4: Commit** `test(server): jvm-openai and the official OpenAI Java SDK against the fake`.

---

### Task 11: `net` loses the OpenAI package and moves to Boot 4

**Files:**
- Delete: `bitubi-inference-4j-net/src/main/java/ao/bitubi/inference4j/net/openai/` (the whole package), `bitubi-inference-4j-net/openai_spec.yaml`
- Delete: `bitubi-inference-4j-net/src/test/java/ao/bitubi/inference4j/net/openai/` (`ChatApiTest`, `MockedOpenAIServer`, `OpenAIServiceTests`, `SessionConflictTest`, `SessionHeaderTest`), once Task 6/7 tests cover the same behaviour
- Modify: `bitubi-inference-4j-net/pom.xml`
- Modify: `bitubi-inference-4j-net/src/main/java/ao/bitubi/inference4j/net/grpc/TopologyService.java` (only if Boot 4 forces an import change)
- Modify: `bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/commands/ClusterCoordinatorCommand.java`

**Interfaces:**
- Consumes: decision D1.
- Produces: `net` = gRPC (`Coordinator`, `Worker`, `grpc/*`) + `TopologyService`, Boot 4, bytecode 25.

- [ ] **Step 1: Map the old tests to the new ones.** For each deleted test, confirm in the plan table that its assertion lives on: `SessionConflictTest` -> Task 6 (`aBusySessionIs409BeforeAnyStream`, `tenSimultaneousRequests...`), `SessionHeaderTest` -> Task 6 (`aNonUuidSessionHeaderIs400WithJson`, and the removed `X-Jlama-Session` is ignored: add `theRemovedJlamaHeaderIsIgnored` to `ChatCompletionsTest`, the legacy header with a bad value must not be parsed), `ChatApiTest` -> Task 10. Write the missing one first and see it pass.

- [ ] **Step 2: Rewrite `net/pom.xml`.** Keep: `core`, the gRPC artifacts (1.59.0), `annotations-api`, the protobuf plugin and the `os-maven-plugin` extension. Replace Spring with `spring-boot-starter-webmvc` and the Boot 4.1.2 import; compiler `<source>25</source><target>25</target>`. Remove: Undertow, `spring-data-commons`, springdoc 1.6.14, the three Jackson 2.15 pins, `jackson-databind-nullable`, all Jersey artifacts, hibernate-validator (both), `jakarta.servlet-api`, `jsr305`, `jvm-openai`, `spring-boot-starter-validation`, the `build-helper-maven-plugin` and `openapi-generator-maven-plugin`. Keep `spring-boot-starter-test` only if a remaining test needs it (`Bi4jServiceTest`, `DistributedServiceTest` use plain JUnit and gRPC testing; check with `grep -l springframework` and drop it if unused).

- [ ] **Step 3: Cluster coordinator (D1).** In `ClusterCoordinatorCommand`: remove the `ApiServiceCommand.m = c` hand-off and the `scanBasePackages` entry for `net.openai`; keep the scan of `net.grpc` and the topology service; the `TopologyService` needs a `Generator` bean, so register the `Coordinator` as that bean in the command's own `@Bean` method (copy the pattern of the old `ApiServiceCommand.getModelBean`). After the server starts, print `Chat API: not available in cluster mode yet (it returns with the cluster sub-project); topology at /cluster/topology`.

- [ ] **Step 4: Build `net` and run its tests.**
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-net -am package -Dtest='!TestSample'`
Expected: compiles at 25; `Bi4jServiceTest` passes; `DistributedServiceTest` fails with HTTP 401 only (known).

- [ ] **Step 5: Commit** `refactor(net): drop the OpenAI package, move to Spring Boot 4 and bytecode 25`.

---

### Task 12: `bi4j serve`, model-first HTTP and the shaded jar

**Files:**
- Create: `bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/commands/ServeCommand.java`
- Delete: `bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/commands/ApiServiceCommand.java`
- Modify: `bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/Bi4jCli.java` (register `serve`; the `restapi` alias)
- Modify: `bitubi-inference-4j-cli/pom.xml` (depends on `server`; bytecode 25; shade `AppendingTransformer`s)
- Modify: `bitubi-inference-4j-cli/src/main/webapp/ui/api.js` (`/chat/completions` becomes `/v1/chat/completions`)
- Test: `bitubi-inference-4j-cli/src/test/java/ao/bitubi/inference4j/cli/commands/ServeCommandTest.java`

**Interfaces:**
- Consumes: `ModelHost`, `ServerApplication`, `Bi4j.builder()`.
- Produces: `bi4j serve <model> [--host 127.0.0.1] [--port 8080] [--allowed-host H ...] [--context-length N] [--threads N] [--working-dtype T --working-qtype T] [--model-cache DIR] [--auto-download] [--auth-token T]`, `restapi` as a picocli alias.

Rules. `ServeCommand extends SimpleBaseCommand` (not `BaseCommand`: its picocli defaults `F32`/`I8` would force the manual working types and turn off the SDK's automatic estimate; here the two flags have no default and are applied only when **both** are given). Model resolution: `Files.exists(Path.of(arg))` uses `model(Path)`, a string shaped `owner/name` uses `model(String)` (the SDK downloads into `--model-cache`; `--auto-download` false and a missing model gives the SDK's `ModelNotFoundException` through the FAILED state, which is the desired UX), anything else uses `model(Path.of(arg))` so the error names the path. Start order: (1) build the `ModelHost` with the loader, (2) start the Boot context with `SpringApplicationBuilder(ServerApplication.class).properties("server.address=" + host, "server.port=" + port, "bi4j.server.allowed-hosts=" + join(",", allowed)).initializers(c -> c.getBeanFactory().registerSingleton("modelHost", hostBean)).run()`, (3) in an `ApplicationReadyEvent` listener print the URLs (using `local.server.port`) and call `modelHost.start()`, then block with `context.registerShutdownHook()` and a wait on the main thread (Boot keeps the JVM alive through Tomcat's non-daemon threads; keep the existing `System.exit(2)` on startup failure). Printed lines: `Chat UI: http://H:P` is NOT printed in this phase (no UI yet); print `OpenAI API: http://H:P/v1`, `API docs: http://H:P/docs`, `Model: <id> (loading)`. A WARN line when `--host` is not a loopback address (spec 10 text, plus how to allow the name: `--allowed-host`).

- [ ] **Step 1: Write the failing test** `ServeCommandTest` (picocli level, no real model): parse `new CommandLine(new ServeCommand()).parseArgs("/nonexistent", "--port", "0")` and assert the fields; assert `restapi` resolves to the same command via `cli.getSubcommands()`; assert `--working-dtype F32` without `--working-qtype` is rejected with a clear message (`ParameterException`).

- [ ] **Step 2: Run, expect a compile failure.**

- [ ] **Step 3: Implement `ServeCommand`**, register it in `Bi4jCli.main` as `cli.addSubcommand("serve", new ServeCommand())` with `@Command(name = "serve", aliases = {"restapi"}, ...)`, update the help sections (`sections.put("Inference", asList("chat", "serve", "complete"))`), delete `ApiServiceCommand`, and fix the one URL in the old static UI (`api.js`) so it still talks to the server until Phase 2 replaces it. Serve the old UI from the `server` module? No: the CLI keeps the `/ui/**` resource handler through a small `WebMvcConfigurer` bean in `ServeCommand`'s nested `@Configuration` class registered via `SpringApplicationBuilder.sources(...)`. The `maven-resources-plugin` copy step stays until Phase 2.

- [ ] **Step 4: cli `pom.xml`.** Add `ao.bitubi:bitubi-inference-4j-server:${project.version}`; `<source>25</source><target>25</target>`; shade transformers in addition to the existing two:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
  <resource>META-INF/spring.factories</resource>
</transformer>
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
  <resource>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports</resource>
</transformer>
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
  <resource>META-INF/spring/org.springframework.boot.EnvironmentPostProcessor.imports</resource>
</transformer>
```

After the first shaded build run `unzip -p bitubi-inference-4j-cli/target/bitubi-inference-4j-cli.jar META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports | wc -l` (many lines, from several modules) and `unzip -l ... | grep -E 'META-INF/spring/.*imports'` to list any other `.imports` files that several jars contribute; add an `AppendingTransformer` for each. This is the failure mode the jar boot test (Task 13) exists to catch.

- [ ] **Step 5: Run the CLI tests and build the jar.**
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-cli -am package -Dtest='!TestSample'`
Expected: PASS; `bitubi-inference-4j-cli/target/bitubi-inference-4j-cli.jar` exists.

- [ ] **Step 6: Commit** `feat(cli): bi4j serve (restapi kept as an alias), HTTP first, model on a virtual thread`.

---

### Task 13: Jar boot test on four systems

**Files:**
- Create: `bitubi-inference-4j-tests/src/test/java/ao/bitubi/inference4j/server/JarBootTest.java`
- Modify: `bitubi-inference-4j-tests/pom.xml` only if the jar path needs a property

**Interfaces:**
- Consumes: the shaded jar `bitubi-inference-4j-cli/target/bitubi-inference-4j-cli.jar`, built earlier in the same reactor (the `tests` module is listed after `cli` in the `allmodules` profile).

- [ ] **Step 1: Write the test.** It launches `java <flags> -jar <jar> serve /nonexistent --port <free port>` with `ProcessBuilder` (flags copied from `run-cli.sh`: `--add-modules jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`, and the other `--add-exports` lines it uses), discarding nothing: stdout and stderr go to a temp file so a failure prints them. A free port comes from `new ServerSocket(0)` closed just before the launch. Assertions: within 15 seconds `GET /healthz` answers (any status); the status is 503 and the body has `"status":"failed"` once the load fails (poll up to 30 s); `GET /v1/models` is 200 with an empty `data`; `GET /v3/api-docs` is 200; the process is still alive; then `destroy()` and wait for exit. Print the captured output on failure.

```java
@Test void theShadedJarBootsAndReportsAFailedModel() throws Exception {
    Path jar = Path.of("..", "bitubi-inference-4j-cli", "target", "bitubi-inference-4j-cli.jar").toAbsolutePath().normalize();
    assumeTrue(Files.exists(jar), "build the cli module first: " + jar);
    ...
}
```

- [ ] **Step 2: Run it.**
Run: `./mvnw -B -Pallmodules -pl bitubi-inference-4j-tests -am package -Dtest=JarBootTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on Linux. A `NoSuchBeanDefinition`/missing-autoconfiguration error in the captured output means a missing `AppendingTransformer` (Task 12 step 4).

- [ ] **Step 3: Commit** `test: boot the shaded jar and check the failed-model path`.

- [ ] **Step 4: CI.** Push to main; the existing `unit-tests.yaml` matrix (ubuntu, windows, macos-latest, macos-15-intel) runs it. Read the four results with `gh run list --workflow unit-tests.yaml --limit 1` then `gh run view <id> --json jobs`. Fix what the other systems show (typical: a Windows path in the jar lookup, a Tomcat port race on macOS). A cancelled macOS job (runner capacity) is re-run with `gh run rerun <id> --failed`.

---

### Task 14: Docs, CLAUDE.md and the full build

**Files:**
- Modify: `CLAUDE.md` (bytecode line)
- Modify: `docs/migration-from-jlama.md` (serve rename, `/v1`, header unchanged, `max_tokens` default, `tools` 501, HTTP before model, cluster coordinator without chat API, host/origin guards, bind address)
- Modify: `README.md` (replace the `restapi` text with `serve`)
- Modify: `docs/superpowers/specs/2026-11-06-web-ui-design.md` (append a short "Phase 1 delivered" note to section 18 with the commit range)
- Modify: `.claude/agents/server-api.md` only if it names `restapi` (it stays in Portuguese)

- [ ] **Step 1: CLAUDE.md.** Replace the bytecode sentence with: "Bytecode: 21 no `core` (o SDK é uma biblioteca para apps em JDK 21+), 25 nos módulos `server`, `net` e `cli`."

- [ ] **Step 2: Migration guide and README** with the list above, in English, with a before/after of the curl line (`POST /chat/completions` becomes `POST /v1/chat/completions`).

- [ ] **Step 3: Full build.**
Run: `./mvnw -B -Pallmodules clean package -Dtest='!TestSample'`
Expected: green except the known local failures (3 `TestCorrectness`, 2 `TestModels` missing local models, `DistributedServiceTest` 401, the 3 pre-existing tokenizer tests). Any other failure is a regression of this phase: fix it.

- [ ] **Step 4: Commit and push** `docs: server phase 1 (serve, /v1, bytecode 25, migration notes)`; confirm the CI matrix is green on four systems.

- [ ] **Step 5: Real-model check (opt-in, one time).** With a small local model: `java <flags> -jar bitubi-inference-4j-cli/target/bitubi-inference-4j-cli.jar serve <model> --auto-download`; the URLs print in under 2 s; `curl -s localhost:8080/healthz` goes 503 -> 200; `curl -N -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' -d '{"model":"m","stream":true,"messages":[{"role":"user","content":"Say hi"}]}'` streams tokens then `[DONE]`; repeat with `X-Bi4j-Session` to see turn 2 remember turn 1. Record the result in the final report; stop the server with `fuser -k 8080/tcp`.

---

## Self-review

- **Spec coverage (phase 1 acceptance, section 15):** `net` tests ported with `/v1` (Tasks 6, 7, 11); `jvm-openai` and the OpenAI Java SDK against the fake (Task 10); `/v1/models` and `/healthz` while loading (Task 8); jar boot test on four labels (Tasks 12, 13); `./mvnw -B clean package` green, CLAUDE.md and migration guide (Task 14). Section 3: module, bytecode, shade transformers (Tasks 1, 12); section 4: `ModelHost`, `SessionRegistry`, `TurnService` (Tasks 3, 5); section 7: model-loading and failed rows (Tasks 3, 8), busy and context rows (Tasks 5, 6); section 9: `/v1`, header semantics (Tasks 5, 6); section 10: bind, Host, Origin (Tasks 9, 12). Not in this phase by design: `ConversationStore`, UI, JTE, htmx, eviction (needs the store), Anthropic, embeddings, `bi4j.api.enabled`/`bi4j.ui.enabled` switches (no UI yet), `--data-dir`, `--open`.
- **Placeholders:** the bodies marked `/* ... */` in test snippets are one-assertion tests whose full input and expected value are stated in the comment; the executor writes them in the style of the fully written neighbour. The Task 6 controller names the stream method that Task 7 completes: Tasks 6 and 7 are one working session with one commit if the compile placeholder would otherwise be committed.
- **Type consistency:** `Turn`, `TurnService.start(Optional<UUID>, List<Message>, GenerationSettings)`, `ModelHost.require()/status()/bi4j()`, `OpenAi.*` record names and `OpenAiMapper.messages/settings/finishReason/usage` are used with the same signatures in Tasks 4 to 8. `FakeBi4j.cancelCount()/liveSessions()/isClosed()/contextLimit()/delayMs()/failAt()/script()` are defined in Tasks 2 and 3 and used in Tasks 5 to 8.
- **Unverified at plan time (each has a step that checks it):** the JUnit 5.9.2/6 clash in the parent (Task 1 step 1); the `jvm-openai`/OpenAI Java SDK accessor names and `baseUrl` convention (Task 10); the springdoc Swagger UI path (Task 8); the full list of `.imports` files to append in the shade (Task 12 step 4); where `ContextLengthExceededException` is thrown in the real SDK (the `prime()` design makes the server independent of it; `StreamingTest`/`ChatSessionTest` in the `tests` module pin the real behaviour).
