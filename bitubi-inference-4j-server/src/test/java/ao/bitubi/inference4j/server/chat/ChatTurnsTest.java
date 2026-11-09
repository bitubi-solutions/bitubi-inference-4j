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
package ao.bitubi.inference4j.server.chat;

import static ao.bitubi.inference4j.server.ModelHostTest.await;
import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.ContextLengthExceededException;
import ao.bitubi.inference4j.sdk.SessionBusyException;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.SessionRegistry;
import ao.bitubi.inference4j.server.TurnService;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ChatTurnsTest {
    private static final Conversation.Settings SETTINGS = new Conversation.Settings(0.7f, 512, null);

    private FakeBi4j fake;
    private MemoryConversationStore store;
    private SessionRegistry sessions;
    private ChatTurns chat;

    private ChatTurns make(Duration orphan) {
        return new ChatTurns(new TurnService(ModelHost.ready(fake), sessions), store, ModelHost.ready(fake), Clock.systemUTC(), orphan, 20);
    }

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j().script("Hel", "lo");
        store = new MemoryConversationStore();
        sessions = new SessionRegistry();
        chat = make(Duration.ofSeconds(30));
    }

    @AfterEach
    public void tearDown() {
        chat.close();
    }

    private static List<RunningTurn.Event> watch(RunningTurn t) {
        List<RunningTurn.Event> events = Collections.synchronizedList(new ArrayList<>());
        t.subscribe(events::add);
        return events;
    }

    private void awaitDone(RunningTurn t) throws Exception {
        await(t::finished);
    }

    @Test
    public void sendStoresPendingThenFinalMessages() throws Exception {
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        awaitDone(t);
        Conversation c = store.load(id).orElseThrow();
        assertEquals(2, c.messages().size());
        assertEquals("user", c.messages().get(0).role());
        assertNull(c.messages().get(0).status());
        assertEquals("Hello", c.messages().get(1).text());
        assertEquals("STOP", c.messages().get(1).finishReason());
        assertEquals("hi", c.title());
        assertEquals("fake/model", c.model());
    }

    @Test
    public void snapshotsAreCoalesced() throws Exception {
        fake.script(Collections.nCopies(200, "x").toArray(String[]::new)).delayMs(2);
        RunningTurn t = chat.send(UUID.randomUUID(), "hi", SETTINGS);
        List<RunningTurn.Event> events = watch(t);
        awaitDone(t);
        long snapshots = events.stream().filter(e -> e instanceof RunningTurn.Snapshot).count();
        assertTrue(snapshots < 60, "snapshots: " + snapshots);
        assertEquals("x".repeat(200), t.result().text());
        assertTrue(events.get(events.size() - 1) instanceof RunningTurn.Final);
    }

    @Test
    public void aLateSubscriberGetsTheCurrentSnapshotAtOnce() throws Exception {
        fake.script("a", "b", "c", "d", "e", "f", "g", "h").delayMs(60);
        RunningTurn t = chat.send(UUID.randomUUID(), "hi", SETTINGS);
        await(() -> t.text().length() >= 3);
        List<RunningTurn.Event> events = watch(t);
        assertEquals(1, events.size());
        RunningTurn.Snapshot first = (RunningTurn.Snapshot) events.get(0);
        assertTrue(first.text().length() >= 3);
        awaitDone(t);
        // a viewer arriving after the end gets the final state
        List<RunningTurn.Event> after = watch(t);
        assertTrue(after.get(0) instanceof RunningTurn.Final);
    }

    @Test
    public void stopEndsWithStoppedAndFreesTheSession() throws Exception {
        fake.script(Collections.nCopies(100, "t").toArray(String[]::new)).delayMs(20);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        await(() -> t.text().length() >= 2);
        chat.stop(id, t.id());
        awaitDone(t);
        Conversation c = store.load(id).orElseThrow();
        Conversation.Msg answer = c.messages().get(1);
        assertEquals("stopped", answer.status());
        assertEquals("CANCELLED", answer.finishReason());
        assertTrue(answer.text().length() < 100);
        fake.script("ok").delayMs(0);
        RunningTurn next = chat.send(id, "again", SETTINGS);
        awaitDone(next);
        assertEquals(4, store.load(id).orElseThrow().messages().size());
    }

    @Test
    public void contextFullIsRejectedBeforeStoring() {
        fake.contextLimit(4);
        UUID id = UUID.randomUUID();
        assertThrows(ContextLengthExceededException.class, () -> chat.send(id, "a very long prompt indeed", SETTINGS));
        assertTrue(store.load(id).isEmpty());
        assertTrue(chat.running(id).isEmpty());
    }

    @Test
    public void aBusyConversationIsRejectedAndTheRunningTurnCanBeFound() throws Exception {
        fake.script("a", "b", "c", "d", "e", "f").delayMs(80);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "one", SETTINGS);
        assertThrows(SessionBusyException.class, () -> chat.send(id, "two", SETTINGS));
        assertSame(t, chat.running(id).orElseThrow());
        assertSame(t, chat.find(id, t.id()).orElseThrow());
        assertTrue(chat.find(id, UUID.randomUUID()).isEmpty());
        awaitDone(t);
        assertEquals(2, store.load(id).orElseThrow().messages().size());
    }

    @Test
    public void noSubscriberCancelsAfterTheOrphanTimeout() throws Exception {
        chat.close();
        chat = make(Duration.ofMillis(200));
        fake.script(Collections.nCopies(100, "t").toArray(String[]::new)).delayMs(50);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        await(t::finished);
        Conversation.Msg answer = store.load(id).orElseThrow().messages().get(1);
        assertEquals("stopped", answer.status());
        assertTrue(answer.text().length() > 0 && answer.text().length() < 100);
        assertEquals(1, fake.cancelCount());
        fake.script("ok").delayMs(0);
        awaitDone(chat.send(id, "again", SETTINGS)); // the session is free
    }

    @Test
    public void aWatchedTurnIsNotCancelled() throws Exception {
        chat.close();
        chat = make(Duration.ofMillis(1000)); // wide margins: slow CI runners must subscribe well inside it
        fake.script("a", "b", "c", "d", "e", "f", "g", "h").delayMs(250); // the answer outlasts the timeout
        RunningTurn t = chat.send(UUID.randomUUID(), "hi", SETTINGS);
        watch(t); // stays subscribed
        awaitDone(t);
        assertEquals("abcdefgh", t.result().text());
        assertEquals(0, fake.cancelCount());
    }

    @Test
    public void anEngineErrorStoresThePartialAnswerAsInterrupted() throws Exception {
        fake.script("a", "b", "c", "d").failAt(2, new IllegalStateException("boom"));
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        List<RunningTurn.Event> events = watch(t);
        awaitDone(t);
        RunningTurn.Final f = (RunningTurn.Final) events.get(events.size() - 1);
        assertNotNull(f.error());
        Conversation c = store.load(id).orElseThrow();
        assertEquals("ab", c.messages().get(1).text());
        assertEquals("interrupted", c.messages().get(1).status());
        assertNull(c.messages().get(0).status());
    }

    @Test
    public void historyIsReplayedIntoTheModelAfterARestart() throws Exception {
        UUID id = UUID.randomUUID();
        awaitDone(chat.send(id, "one", SETTINGS));
        // a new server: a fresh session registry over the same store
        chat.close();
        sessions = new SessionRegistry();
        chat = make(Duration.ofSeconds(30));
        awaitDone(chat.send(id, "two", SETTINGS));
        List<ao.bitubi.inference4j.sdk.Message> h = sessions.find(id).orElseThrow().history();
        assertEquals(4, h.size());
        assertEquals("one", h.get(0).text());
    }

    @Test
    public void closeCancelsEverything() throws Exception {
        fake.script(Collections.nCopies(100, "t").toArray(String[]::new)).delayMs(50);
        RunningTurn t = chat.send(UUID.randomUUID(), "hi", SETTINGS);
        chat.close();
        assertTrue(t.finished());
    }

    @Test
    public void deletingARunningConversationDoesNotBringItBack() throws Exception {
        fake.script(Collections.nCopies(100, "t").toArray(String[]::new)).delayMs(20);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        store.delete(id);
        chat.stop(id, t.id());
        awaitDone(t);
        assertTrue(store.load(id).isEmpty(), "a deleted conversation stays deleted");
    }

    @Test
    public void aPrimeFailureAfterTheWaitWindowStillEndsTheTurnWithAnError() throws Exception {
        fake.contextLimit(4).startDelayMs(700);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "a very long prompt indeed", SETTINGS); // accepted: the failure comes later
        List<RunningTurn.Event> events = watch(t);
        awaitDone(t);
        RunningTurn.Final last = (RunningTurn.Final) events.get(events.size() - 1);
        assertNotNull(last.error());
        var stored = store.load(id).orElseThrow().messages();
        assertEquals("interrupted", stored.get(stored.size() - 1).status());
        assertNotEquals("pending", stored.get(0).status());
        fake.contextLimit(4096).startDelayMs(0);
        awaitDone(chat.send(id, "short", SETTINGS)); // the session is free again
    }

    @Test
    public void aGracefulShutdownStoresTheAnswerAsInterruptedNotStopped() throws Exception {
        fake.script(Collections.nCopies(100, "t").toArray(String[]::new)).delayMs(30);
        UUID id = UUID.randomUUID();
        RunningTurn t = chat.send(id, "hi", SETTINGS);
        await(() -> t.currentText().length() > 2);
        chat.close();
        assertEquals("interrupted", store.load(id).orElseThrow().messages().get(1).status());
    }

    @Test
    public void retryingALostMessageDoesNotDuplicateIt() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        store.save(Conversation.create(id, "fake/model", SETTINGS, now).withMessage(Conversation.Msg.user("hi", now, "pending"), now));
        awaitDone(chat.send(id, "hi", SETTINGS));
        var m = store.load(id).orElseThrow().messages();
        assertEquals(2, m.size());
        assertNull(m.get(0).status());
    }

    @Test
    public void aRenameRacingTheEndOfAnAnswerKeepsBoth() throws Exception {
        for (int i = 0; i < 40; i++) {
            fake.script("ok").delayMs(0);
            UUID id = UUID.randomUUID();
            RunningTurn t = chat.send(id, "hi", SETTINGS);
            store.update(id, c -> c.withTitle("Renamed", Instant.now()));
            awaitDone(t);
            Conversation c = store.load(id).orElseThrow();
            assertEquals("Renamed", c.title(), "round " + i);
            assertEquals(2, c.messages().size(), "round " + i);
            assertEquals("assistant", c.messages().get(1).role());
        }
    }

    @Test
    public void aSecondSendDuringARestoreCanLinkToTheRunningAnswer() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        fake.blockRestoreOn(gate);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        store.save(Conversation.create(id, "fake/model", SETTINGS, now)
            .withMessage(Conversation.Msg.user("one", now, null), now)
            .withMessage(new Conversation.Msg("assistant", "ok", now, "STOP", 1, 1, 1.0, null), now));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> chat.send(id, "two", SETTINGS));
        try {
            await(() -> {
                try {
                    chat.send(id, "again", SETTINGS);
                    return false;
                } catch (ao.bitubi.inference4j.sdk.SessionBusyException busy) {
                    return true;
                }
            });
            assertTrue(chat.running(id).isPresent(), "the 409 needs something to link to, even while the context is being restored");
        } finally {
            gate.countDown();
        }
        awaitDone(first.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
}
