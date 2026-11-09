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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConversationStoreTest {
    static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");
    static final Conversation.Settings SETTINGS = new Conversation.Settings(0.7f, 512, "be brief");

    static Stream<Supplier<ConversationStore>> stores() throws Exception {
        Path dir = Files.createTempDirectory("bi4j-store");
        return Stream.of(MemoryConversationStore::new, () -> new FileConversationStore(dir));
    }

    static Conversation sample(UUID id, Instant at) {
        Conversation.Msg answer = new Conversation.Msg("assistant", "Hi there", at, "STOP", 12, 3, 14.1, null);
        return Conversation.create(id, "fake/model", SETTINGS, at).withMessage(Conversation.Msg.user("Hello there, friend", at, null), at).withMessage(answer, at);
    }

    @ParameterizedTest
    @MethodSource("stores")
    void savedConversationsRoundTripEveryField(Supplier<ConversationStore> make) {
        ConversationStore store = make.get();
        UUID id = UUID.randomUUID();
        Conversation c = sample(id, T0);
        store.save(c);
        assertEquals(c, store.load(id).orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("stores")
    void listIsNewestFirstAndDeleteRemoves(Supplier<ConversationStore> make) {
        ConversationStore store = make.get();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.save(sample(a, T0));
        store.save(sample(b, T0.plusSeconds(60)));
        assertEquals(List.of(b, a), store.list().stream().map(Conversation::id).toList());
        store.delete(b);
        assertEquals(List.of(a), store.list().stream().map(Conversation::id).toList());
        assertTrue(store.load(b).isEmpty());
        assertTrue(store.load(UUID.randomUUID()).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void theFirstUserMessageTitlesTheConversationAndARenameSticks() {
        Conversation c = Conversation.create(UUID.randomUUID(), "m", SETTINGS, T0);
        c = c.withMessage(Conversation.Msg.user("How do I plant an avocado tree\nin late winter, please tell me everything", T0, null), T0);
        assertEquals("How do I plant an avocado tree in late w", c.title());
        assertTrue(c.title().length() <= 40);
        c = c.withTitle("Avocado", T0).withMessage(Conversation.Msg.user("again", T0, null), T0);
        assertEquals("Avocado", c.title());
    }

    @org.junit.jupiter.api.Test
    void withLastMessageReplacesThePendingUserMessage() {
        Conversation c = Conversation.create(UUID.randomUUID(), "m", SETTINGS, T0).withMessage(Conversation.Msg.user("hi", T0, "pending"), T0);
        c = c.withLastMessage(c.messages().get(0).withStatus(null), T0);
        assertNull(c.messages().get(0).status());
        assertEquals(1, c.messages().size());
    }

    @ParameterizedTest
    @MethodSource("stores")
    void concurrentUpdatesOfOneConversationAreNotLost(Supplier<ConversationStore> make) throws Exception {
        ConversationStore store = make.get();
        UUID id = UUID.randomUUID();
        store.save(sample(id, T0));
        int threads = 32;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            done.add(pool.submit(() -> {
                go.await();
                store.update(id, c -> c.withMessage(Conversation.Msg.user("m" + n, T0, null), T0));
                return null;
            }));
        }
        go.countDown();
        for (var f : done) f.get();
        pool.shutdown();
        assertEquals(2 + threads, store.load(id).orElseThrow().messages().size());
    }

    @ParameterizedTest
    @MethodSource("stores")
    void updateOfAMissingConversationDoesNothing(Supplier<ConversationStore> make) {
        ConversationStore store = make.get();
        UUID id = UUID.randomUUID();
        assertTrue(store.update(id, c -> c.withTitle("x", T0)).isEmpty());
        assertTrue(store.load(id).isEmpty());
    }
}
