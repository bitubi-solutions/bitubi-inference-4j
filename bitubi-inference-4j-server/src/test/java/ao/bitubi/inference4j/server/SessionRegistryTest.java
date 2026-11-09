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

import ao.bitubi.inference4j.sdk.GenerationSettings;
import ao.bitubi.inference4j.sdk.Message;
import ao.bitubi.inference4j.sdk.Token;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SessionRegistryTest {
    private final FakeBi4j fake = new FakeBi4j().script("ok");

    private void create(SessionRegistry r, UUID id) {
        r.getOrCreate(id, fake, null, List.of());
    }

    @Test
    public void theLeastRecentlyUsedIdleSessionIsClosedOverTheCap() {
        SessionRegistry r = new SessionRegistry(2);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        create(r, a);
        create(r, b);
        create(r, c);
        assertEquals(2, fake.liveSessions());
        assertTrue(r.find(a).isEmpty());
        assertTrue(r.find(b).isPresent() && r.find(c).isPresent());
    }

    @Test
    public void usingASessionKeepsItFromBeingTheVictim() {
        SessionRegistry r = new SessionRegistry(2);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        create(r, a);
        create(r, b);
        r.markUsed(a);
        create(r, c);
        assertTrue(r.find(a).isPresent());
        assertTrue(r.find(b).isEmpty());
    }

    @Test
    public void aBusySessionIsNeverEvicted() {
        SessionRegistry r = new SessionRegistry(2);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        create(r, a);
        r.markBusy(a);
        create(r, b);
        create(r, c);
        assertTrue(r.find(a).isPresent());
        assertTrue(r.find(b).isEmpty());
        assertEquals(2, fake.liveSessions());
    }

    @Test
    public void whenEverySessionIsBusyTheCapIsExceededThenShrinksBack() {
        SessionRegistry r = new SessionRegistry(1);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        create(r, a);
        r.markBusy(a);
        r.markBusy(b);
        create(r, b);
        assertEquals(2, r.size());
        r.markIdle(a);
        assertEquals(1, r.size());
        assertTrue(r.find(b).isPresent(), "the one still busy stays");
    }

    @Test
    public void anEvictedConversationIsRebuiltFromItsHistoryOnTheNextTurn() {
        SessionRegistry r = new SessionRegistry(1);
        TurnService service = new TurnService(ModelHost.ready(fake), r);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<Message> history = List.of(Message.user("one"), Message.assistant("ok"), Message.user("two"));
        run(service, a, history);
        run(service, b, List.of(Message.user("other")));
        assertTrue(r.find(a).isEmpty(), "a was evicted by b");
        run(service, a, List.of(Message.user("one"), Message.assistant("ok"), Message.user("two"), Message.assistant("ok"), Message.user("three")));
        List<Message> held = r.find(a).orElseThrow().history();
        assertEquals(6, held.size(), "restored two turns, then answered the third");
        assertEquals("one", held.get(0).text());
        assertEquals(1, fake.liveSessions());
    }

    private static void run(TurnService s, UUID id, List<Message> messages) {
        try (Turn t = s.start(Optional.of(id), messages, GenerationSettings.defaults())) {
            Iterator<Token> it = t.tokens();
            while (it.hasNext()) it.next();
        }
    }
}
