/*
 * Copyright 2026 Bitubi Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package ao.bitubi.inference4j.server.ui;

import static ao.bitubi.inference4j.server.ModelHostTest.await;
import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.LoadProgress;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.chat.Conversation;
import ao.bitubi.inference4j.server.chat.FileConversationStore;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UiRoutesTest {
    /** One server-sent event: its name, its data lines joined by newlines, and the comments seen before it. */
    record Sse(String name, String data, List<String> comments) {}

    private final List<String> seenComments = new ArrayList<>();
    private FakeBi4j fake;
    private TestServer server;

    @TempDir
    Path dir;

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j().script("Hel", "lo");
        server = TestServer.start(ModelHost.ready(fake), "bi4j.ui.heartbeat=200ms");
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    private static Document doc(HttpResponse<String> r) {
        return Jsoup.parse(r.body());
    }

    private HttpResponse<String> send(TestServer s, UUID id, String text) throws Exception {
        return s.request("POST", "/c/" + id + "/messages", "text=" + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID turnOf(HttpResponse<String> sent) {
        String shell = doc(sent).selectFirst("article.answer").id();
        return UUID.fromString(shell.substring("turn-".length()));
    }

    private List<Sse> events(TestServer s, UUID id, UUID turn) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(s.uri("/c/" + id + "/turns/" + turn + "/stream")).header("Accept", "text/event-stream").build();
        HttpResponse<Stream<String>> r = s.client().send(req, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, r.statusCode());
        seenComments.clear();
        List<Sse> out = new ArrayList<>();
        String[] name = { null };
        List<String> data = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        r.body().forEach(line -> {
            if (line.isEmpty()) {
                seenComments.addAll(comments); // a block with only comments (the keep-alive) has no event
                if (name[0] != null || !data.isEmpty()) out.add(new Sse(name[0], String.join("\n", data), List.copyOf(comments)));
                name[0] = null;
                data.clear();
                comments.clear();
            } else if (line.startsWith(":")) comments.add(line.substring(1).strip());
            else if (line.startsWith("event:")) name[0] = line.substring(6).strip();
            else if (line.startsWith("data:")) data.add(line.substring(5).startsWith(" ") ? line.substring(6) : line.substring(5));
        });
        return out;
    }

    private void finish(TestServer s, UUID id, String text) throws Exception {
        events(s, id, turnOf(send(s, id, text)));
    }

    @Test
    public void theIndexIsANewChatWithAFreshIdAndStarterPrompts() throws Exception {
        var r = server.get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        Document d = doc(r);
        assertEquals(1, d.select("h1").size());
        assertTrue(d.selectFirst("form#composer").attr("hx-post").matches("/c/[0-9a-f-]{36}/messages"));
        assertEquals(4, d.select("button[data-starter]").size());
        assertEquals("fake/model", d.selectFirst("h1").text());
    }

    @Test
    public void unknownIdsGetTheNotFoundPageNeverAStackTrace() throws Exception {
        var r = server.get("/c/not-a-uuid");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("New chat"));
        assertFalse(r.body().contains("Exception"));
        assertEquals(404, server.request("DELETE", "/c/zzz", null).statusCode());
    }

    @Test
    public void anHtmxRequestGetsOnlyTheChatFragment() throws Exception {
        UUID id = UUID.randomUUID();
        var r = server.get("/c/" + id, "HX-Request", "true");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().startsWith("<main id=\"chat\""), r.body().substring(0, 40));
        assertFalse(r.body().contains("<html"));
        assertTrue(r.headers().allValues("Vary").stream().anyMatch(v -> v.contains("HX-Request")));
        assertTrue(server.get("/c/" + id).body().contains("<html"));
    }

    @Test
    public void sendingReturnsTheBubbleTheShellAndTheOutOfBandParts() throws Exception {
        UUID id = UUID.randomUUID();
        var r = send(server, id, "hi there");
        assertEquals(200, r.statusCode(), r.body());
        Document d = doc(r);
        assertEquals("hi there", d.selectFirst("li.user .user-text").text());
        var shell = d.selectFirst("article.answer");
        assertEquals("/c/" + id + "/turns/" + shell.id().substring(5) + "/stream", shell.attr("hx-sse:connect"));
        assertEquals("true", d.selectFirst("#composer-actions").attr("hx-swap-oob"));
        assertNotNull(d.selectFirst("#composer-actions #stop"));
        assertEquals("true", d.selectFirst("textarea#text").attr("hx-swap-oob"));
        finish(server, id, "x"); // let the first turn end cleanly
    }

    @Test
    public void theStreamSendsSnapshotsThenTheFinalEventThenDone() throws Exception {
        fake.script("Hel", "lo").delayMs(40);
        UUID id = UUID.randomUUID();
        UUID turn = turnOf(send(server, id, "hi"));
        List<Sse> events = events(server, id, turn);
        Sse done = events.get(events.size() - 1);
        assertEquals("done", done.name());
        Sse last = events.get(events.size() - 2);
        assertNull(last.name());
        Document fin = Jsoup.parseBodyFragment(last.data());
        assertEquals("Hello", fin.selectFirst(".md").text());
        assertTrue(fin.selectFirst(".footer").text().contains("end of text"), fin.body().html());
        assertNotNull(fin.selectFirst("#composer-actions #send"));
        assertNotNull(fin.selectFirst("#context-counter"));
        assertEquals("Answer complete", fin.selectFirst("#status-live").text());
        assertTrue(events.stream().limit(events.size() - 2).allMatch(e -> e.data().contains("class=\"md\"")));
    }

    @Test
    public void multiLineHtmlArrivesIntactInOneEvent() throws Exception {
        fake.script("```java\n", "int a = 1;\n", "int b = 2;\n", "```\n");
        UUID id = UUID.randomUUID();
        List<Sse> events = events(server, id, turnOf(send(server, id, "code")));
        Document fin = Jsoup.parseBodyFragment(events.get(events.size() - 2).data());
        assertEquals("int a = 1;\nint b = 2;\n", fin.selectFirst("pre code").wholeText());
    }

    @Test
    public void stopEndsTheAnswerThroughTheNormalPathAndTheChatContinues() throws Exception {
        fake.script(Collections.nCopies(300, "t").toArray(String[]::new)).delayMs(15);
        UUID id = UUID.randomUUID();
        UUID turn = turnOf(send(server, id, "go"));
        Thread stopper = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                assertEquals(204, server.post("/c/" + id + "/turns/" + turn + "/stop", "").statusCode());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        List<Sse> events = events(server, id, turn);
        stopper.join();
        Document fin = Jsoup.parseBodyFragment(events.get(events.size() - 2).data());
        assertTrue(fin.selectFirst(".footer").text().contains("stopped"), fin.body().html());
        assertTrue(fin.selectFirst(".md").text().length() < 300);
        Document page = doc(server.get("/c/" + id));
        assertEquals(2, page.select("#messages > li").size());
        fake.script("ok").delayMs(0);
        finish(server, id, "again");
        assertEquals(4, doc(server.get("/c/" + id)).select("#messages > li").size());
    }

    @Test
    public void aBusyConversationGetsTheConflictAlertWithALinkToTheRunningAnswer() throws Exception {
        fake.script("a", "b", "c", "d", "e", "f").delayMs(100);
        UUID id = UUID.randomUUID();
        UUID turn = turnOf(send(server, id, "one"));
        var second = send(server, id, "two");
        assertEquals(409, second.statusCode());
        Document d = doc(second);
        assertTrue(d.selectFirst("#alerts").text().contains("answering in another tab or API call"));
        assertEquals("/c/" + id, d.selectFirst("#alerts a").attr("href"));
        assertEquals("Open the running answer", d.selectFirst("#alerts a").text());
        // attaching to the running turn shows the current state and then the end
        List<Sse> events = events(server, id, turn);
        assertEquals("done", events.get(events.size() - 1).name());
    }

    @Test
    public void aFullContextIsRefusedWithTheNumbersAndNothingIsStored() throws Exception {
        fake.contextLimit(4);
        UUID id = UUID.randomUUID();
        var r = send(server, id, "a very long prompt indeed");
        assertEquals(400, r.statusCode());
        Document d = doc(r);
        assertTrue(d.selectFirst("#alerts p").text().contains("4"), d.body().html());
        assertEquals("/", d.selectFirst("#alerts a").attr("href"));
        assertEquals("New chat", d.selectFirst("#alerts a").text());
        assertTrue(doc(server.get("/c/" + id)).select("#messages > li").isEmpty());
        assertEquals(400, send(server, id, "   ").statusCode());
    }

    @Test
    public void whileTheModelLoadsThePageShowsTheStatusAndSendingIsRefused() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ModelHost host = new ModelHost(p -> {
            p.accept(new LoadProgress(LoadProgress.Phase.DOWNLOADING, "weights.bin", 30, 100));
            try {
                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new FakeBi4j();
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.DOWNLOADING);
        try (TestServer s = TestServer.start(host)) {
            Document d = doc(s.get("/"));
            assertEquals("30", d.selectFirst("section#model-status progress").attr("value"));
            assertNull(d.selectFirst("form#composer"));
            assertEquals(503, send(s, UUID.randomUUID(), "hi").statusCode());
            assertTrue(doc(s.get("/model/status")).selectFirst("section#model-status") != null);
            release.countDown();
            await(() -> host.status().state() == ModelHost.State.READY);
            var ready = s.get("/model/status", "HX-Current-URL", "http://localhost/");
            assertTrue(ready.body().contains("<main id=\"chat\""));
            assertTrue(ready.body().contains("id=\"model-info\""));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aFailedModelIsExplainedNotHidden() throws Exception {
        ModelHost host = new ModelHost(p -> {
            throw new ao.bitubi.inference4j.sdk.ModelNotFoundException("Cannot read a model at /x");
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.FAILED);
        try (TestServer s = TestServer.start(host)) {
            Document d = doc(s.get("/"));
            assertEquals("Cannot read a model at /x", d.selectFirst("section#model-status pre").text());
            var sent = send(s, UUID.randomUUID(), "hi");
            assertEquals(503, sent.statusCode());
            assertTrue(doc(sent).selectFirst("#alerts").text().contains("failed to load"));
        }
    }

    @Test
    public void conversationsSurviveARestartAndTheNextMessageWorks() throws Exception {
        UUID id = UUID.randomUUID();
        FakeBi4j first = new FakeBi4j().script("Hel", "lo");
        try (TestServer s = TestServer.start(ModelHost.ready(first), "bi4j.server.history=true", "bi4j.server.data-dir=" + dir)) {
            finish(s, id, "remember me");
        }
        FakeBi4j second = new FakeBi4j().script("Again");
        try (TestServer s = TestServer.start(ModelHost.ready(second), "bi4j.server.history=true", "bi4j.server.data-dir=" + dir)) {
            Document page = doc(s.get("/c/" + id));
            assertEquals("remember me", page.selectFirst("li.user .user-text").text());
            assertEquals("Hello", page.selectFirst("li.assistant .md").text());
            assertEquals("remember me", page.selectFirst("nav a[aria-current]").text());
            finish(s, id, "and now?");
            assertEquals(4, doc(s.get("/c/" + id)).select("#messages > li").size());
            assertEquals(1, second.liveSessions()); // rebuilt from the stored history
        }
    }

    @Test
    public void aTurnLostToACrashIsShownAsInterruptedWithRetry() throws Exception {
        UUID id = UUID.randomUUID();
        Conversation c = Conversation.create(id, "fake/model", UiModels.DEFAULT_SETTINGS, Instant.now())
            .withMessage(Conversation.Msg.user("what is a stream?", Instant.now(), "pending"), Instant.now());
        new FileConversationStore(dir).save(c);
        try (TestServer s = TestServer.start(ModelHost.ready(fake), "bi4j.server.history=true", "bi4j.server.data-dir=" + dir)) {
            Document page = doc(s.get("/c/" + id));
            assertEquals("Answer interrupted.", page.selectFirst(".interrupted p").text());
            assertEquals("what is a stream?", page.selectFirst(".interrupted input[name=text]").attr("value"));
        }
    }

    @Test
    public void aStreamReconnectingAfterARestartTellsTheAnswerWasInterrupted() throws Exception {
        UUID id = UUID.randomUUID();
        Conversation c = Conversation.create(id, "fake/model", UiModels.DEFAULT_SETTINGS, Instant.now())
            .withMessage(Conversation.Msg.user("hello?", Instant.now(), "pending"), Instant.now());
        new FileConversationStore(dir).save(c);
        try (TestServer s = TestServer.start(ModelHost.ready(fake), "bi4j.server.history=true", "bi4j.server.data-dir=" + dir)) {
            List<Sse> events = events(s, id, UUID.randomUUID());
            assertTrue(events.stream().anyMatch(e -> e.data().contains("restarted") && e.data().contains("id=\"send\"")), events.toString());
        }
    }

    @Test
    public void renameSearchAndDelete() throws Exception {
        UUID id = UUID.randomUUID();
        try (TestServer s = TestServer.start(ModelHost.ready(fake), "bi4j.server.history=true", "bi4j.server.data-dir=" + dir)) {
            finish(s, id, "plan the avocado orchard");
            var renamed = s.request("PATCH", "/c/" + id, "title=Orchard+plan");
            assertEquals(200, renamed.statusCode());
            assertTrue(renamed.body().contains("Orchard plan"));
            assertEquals(400, s.request("PATCH", "/c/" + id, "title=+++").statusCode());
            assertTrue(s.get("/c/" + id + "/rename").body().contains("value=\"Orchard plan\""));
            assertTrue(s.get("/sidebar?q=ORCHARD").body().contains("Orchard plan"));
            assertTrue(s.get("/sidebar?q=zzz").body().contains("No conversations match"));
            var deleted = s.request("DELETE", "/c/" + id, null, "HX-Current-URL", "http://localhost/c/" + id);
            assertEquals(200, deleted.statusCode());
            assertEquals("/", deleted.headers().firstValue("HX-Redirect").orElse(""));
            assertFalse(deleted.body().contains("Orchard plan"));
            assertTrue(doc(s.get("/c/" + id)).select("#messages > li").isEmpty());
        }
    }

    @Test
    public void maliciousContentIsRenderedInert() throws Exception {
        fake.script("<script>alert(1)</script>", " and [x](javascript:alert(1))");
        UUID id = UUID.randomUUID();
        List<Sse> events = events(server, id, turnOf(send(server, id, "<img src=x onerror=alert(1)>")));
        Document fin = Jsoup.parseBodyFragment(events.get(events.size() - 2).data());
        assertTrue(fin.select("script").isEmpty());
        assertTrue(fin.select("a[href^=javascript]").isEmpty());
        Document page = doc(server.get("/c/" + id));
        assertTrue(page.select("#messages script, #messages img").isEmpty());
        assertEquals("<img src=x onerror=alert(1)>", page.selectFirst("li.user .user-text").text());
        assertEquals("<img src=x onerror=alert(1)>", page.selectFirst("nav a[aria-current]").text());
    }

    @Test
    public void aStreamForAFinishedTurnTellsTheStoredAnswer() throws Exception {
        UUID id = UUID.randomUUID();
        UUID turn = turnOf(send(server, id, "hi"));
        events(server, id, turn);
        ModelHostWait.pause();
        List<Sse> again = events(server, id, turn);
        assertEquals("done", again.get(again.size() - 1).name());
        assertEquals("Hello", Jsoup.parseBodyFragment(again.get(0).data()).selectFirst(".md").text());
    }

    @Test
    public void aSlowAnswerKeepsTheConnectionAliveWithComments() throws Exception {
        fake.script("a", "b", "c").delayMs(500);
        UUID id = UUID.randomUUID();
        List<Sse> events = events(server, id, turnOf(send(server, id, "slow")));
        assertTrue(seenComments.contains("keep-alive"), "no keep-alive comment: " + events);
    }

    /** The turn's last event is sent before the hub forgets the turn; give it a moment. */
    static final class ModelHostWait {
        static void pause() throws InterruptedException {
            Thread.sleep(300);
        }
    }
}
