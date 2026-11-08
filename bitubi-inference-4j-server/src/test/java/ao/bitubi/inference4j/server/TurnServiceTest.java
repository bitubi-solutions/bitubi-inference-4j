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
package ao.bitubi.inference4j.server;

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.*;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TurnServiceTest {
    private FakeBi4j fake;
    private SessionRegistry sessions;
    private TurnService service;

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j().script("Hello");
        sessions = new SessionRegistry();
        service = new TurnService(ModelHost.ready(fake), sessions);
    }

    private static void drain(Turn t) {
        Iterator<Token> it = t.tokens();
        while (it.hasNext()) it.next();
    }

    private void run(UUID id, List<Message> messages) {
        try (Turn t = service.start(Optional.of(id), messages, GenerationSettings.defaults())) {
            drain(t);
        }
    }

    @Test
    public void withoutAnIdTheRequestIsTemporaryAndKeepsNothing() {
        try (Turn t = service.start(Optional.empty(), List.of(Message.user("hi")), GenerationSettings.defaults())) {
            drain(t);
        }
        assertEquals(0, sessions.size());
        assertEquals(0, fake.liveSessions());
    }

    @Test
    public void aSecondTurnWithTheSameIdOnlySendsTheNewMessage() {
        UUID id = UUID.randomUUID();
        run(id, List.of(Message.user("one")));
        run(id, List.of(Message.user("one"), Message.assistant("Hello"), Message.user("two")));
        assertEquals(4, sessions.find(id).orElseThrow().history().size()); // not 6: "one" was not sent twice
    }

    @Test
    public void aNewIdWithAHistoryRestoresItFirst() {
        UUID id = UUID.randomUUID();
        run(id, List.of(Message.system("be brief"), Message.user("a"), Message.assistant("b"), Message.user("c")));
        List<Message> h = sessions.find(id).orElseThrow().history();
        assertEquals("a", h.get(0).text());
        assertEquals("b", h.get(1).text());
        assertEquals("c", h.get(2).text());
    }

    @Test
    public void aBusySessionIsRejectedAndFreedAfterTheTurn() {
        UUID id = UUID.randomUUID();
        Turn first = service.start(Optional.of(id), List.of(Message.user("x")), GenerationSettings.defaults());
        assertThrows(
            SessionBusyException.class,
            () -> service.start(Optional.of(id), List.of(Message.user("y")), GenerationSettings.defaults())
        );
        first.close();
        service.start(Optional.of(id), List.of(Message.user("z")), GenerationSettings.defaults()).close();
    }

    @Test
    public void aStartThatFailsFreesTheReservation() {
        UUID id = UUID.randomUUID();
        assertThrows(
            IllegalArgumentException.class,
            () -> service.start(Optional.of(id), List.of(Message.user("a"), Message.assistant("b")), GenerationSettings.defaults())
        );
        run(id, List.of(Message.user("fine")));
    }

    @Test
    public void primeSurfacesAContextErrorBeforeAnyStreaming() {
        FakeBi4j small = new FakeBi4j().script("x").contextLimit(4);
        TurnService s = new TurnService(ModelHost.ready(small), new SessionRegistry());
        Turn t = s.start(Optional.of(UUID.randomUUID()), List.of(Message.user("a very long prompt indeed")), GenerationSettings.defaults());
        assertThrows(ContextLengthExceededException.class, t::prime);
        t.close();
    }

    @Test
    public void tenThreadsOnOneIdGiveExactlyOneTurn() throws Exception {
        fake.delayMs(100);
        UUID id = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        List<Turn> turns = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    gate.await();
                    turns.add(service.start(Optional.of(id), List.of(Message.user("x")), GenerationSettings.defaults()));
                    ok.incrementAndGet();
                } catch (SessionBusyException e) {
                    busy.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        gate.countDown();
        for (Thread t : threads) t.join();
        assertEquals(1, ok.get());
        assertEquals(9, busy.get());
        turns.forEach(Turn::close);
    }

    @Test
    public void aSlowRestoreDoesNotBlockAnotherSessionInTheSameBin() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeBi4j slow = new FakeBi4j().blockRestoreOn(gate);
        SessionRegistry registry = new SessionRegistry();
        // equal UUID hash codes: the same bin of the registry's map
        UUID a = new UUID(0, 1);
        UUID b = new UUID(1, 0);
        assertEquals(a.hashCode(), b.hashCode());
        Thread restoring = Thread.ofVirtual().start(() -> registry.getOrCreate(a, slow, null, List.of(Message.user("x"), Message.assistant("y"))));
        Thread.sleep(300); // let it enter restore
        var other = java.util.concurrent.CompletableFuture.supplyAsync(() -> registry.getOrCreate(b, slow, null, List.of()));
        try {
            other.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            gate.countDown();
            restoring.join();
        }
    }

    @Test
    public void aClientThatEditsItsHistoryGetsTheSessionRebuiltFromIt() {
        UUID id = UUID.randomUUID();
        run(id, List.of(Message.user("one")));
        run(id, List.of(Message.user("ONE EDITED"), Message.assistant("Hello"), Message.user("two")));
        List<Message> h = sessions.find(id).orElseThrow().history();
        assertEquals("ONE EDITED", h.get(0).text());
        assertEquals(4, h.size());
    }

    @Test
    public void aTrimmedHistoryAfterAContextErrorRecoversUnderTheSameId() {
        UUID id = UUID.randomUUID();
        run(id, List.of(Message.user("one")));
        run(id, List.of(Message.user("three"))); // the client dropped the first exchange
        assertEquals("three", sessions.find(id).orElseThrow().history().get(2).text());
        run(id, List.of(Message.user("x"), Message.assistant("y"), Message.user("z")));
        assertEquals("x", sessions.find(id).orElseThrow().history().get(0).text());
    }

    @Test
    public void cancelAllEndsRunningTurnsAndFreesTheirSessions() throws Exception {
        fake.script("a", "b", "c", "d", "e", "f", "g", "h", "i", "j").delayMs(100);
        UUID id = UUID.randomUUID();
        Turn t = service.start(Optional.of(id), List.of(Message.user("x")), GenerationSettings.defaults());
        Thread reader = Thread.ofVirtual().start(() -> {
            try (t) {
                drain(t);
            }
        });
        Thread.sleep(150);
        service.cancelAll();
        reader.join(5000);
        assertFalse(reader.isAlive());
        assertEquals(1, fake.cancelCount());
        fake.script("ok").delayMs(0);
        run(id, List.of(Message.user("again")));
    }
}
