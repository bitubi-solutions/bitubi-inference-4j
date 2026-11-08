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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileConversationStoreTest {
    @TempDir
    Path dir;

    private Conversation sample(UUID id) {
        return ConversationStoreTest.sample(id, Instant.parse("2026-11-08T10:00:00Z"));
    }

    @Test
    void filesAreNamedByIdAndNoTempFileRemains() throws Exception {
        FileConversationStore store = new FileConversationStore(dir);
        UUID id = UUID.randomUUID();
        store.save(sample(id));
        try (var files = Files.list(dir.resolve("conversations"))) {
            assertEquals(java.util.List.of(id + ".json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void unreadableOrForeignFilesAreSkippedAndTheRestIsListed() throws Exception {
        FileConversationStore store = new FileConversationStore(dir);
        UUID good = UUID.randomUUID();
        store.save(sample(good));
        Path conversations = dir.resolve("conversations");
        Files.writeString(conversations.resolve("not-a-uuid.json"), "{}");
        UUID corrupt = UUID.randomUUID();
        Files.writeString(conversations.resolve(corrupt + ".json"), "{ this is not json");
        UUID future = UUID.randomUUID();
        String json = Files.readString(conversations.resolve(good + ".json")).replace("\"version\":1", "\"version\":99").replace(good.toString(), future.toString());
        Files.writeString(conversations.resolve(future + ".json"), json);
        UUID liar = UUID.randomUUID(); // the file name says one id, the content another
        Files.writeString(conversations.resolve(liar + ".json"), Files.readString(conversations.resolve(good + ".json")));
        assertEquals(java.util.List.of(good), store.list().stream().map(Conversation::id).toList());
        assertTrue(store.load(corrupt).isEmpty());
        assertTrue(store.load(future).isEmpty());
        assertTrue(store.load(liar).isEmpty());
    }

    @Test
    void aFailedSaveFlipsWritableAndTheNextSuccessFlipsItBack() throws Exception {
        Path ro = Files.createDirectories(dir.resolve("data"));
        FileConversationStore store = new FileConversationStore(ro);
        Files.createDirectories(ro.resolve("conversations"));
        Assumptions.assumeTrue(ro.resolve("conversations").toFile().setWritable(false), "cannot make a directory read-only here");
        Assumptions.assumeFalse(Files.isWritable(ro.resolve("conversations")), "running as a user that ignores permissions");
        try {
            UUID id = UUID.randomUUID();
            assertDoesNotThrow(() -> store.save(sample(id)));
            assertFalse(store.writable());
            assertTrue(store.lastError().isPresent());
        } finally {
            ro.resolve("conversations").toFile().setWritable(true);
        }
        store.save(sample(UUID.randomUUID()));
        assertTrue(store.writable());
        assertTrue(store.lastError().isEmpty());
    }

    @Test
    void aSaveThatFailsForAnotherReasonAlsoReportsInsteadOfThrowing() throws Exception {
        Path blocked = Files.writeString(dir.resolve("data"), "a file where the directory should be");
        FileConversationStore store = new FileConversationStore(blocked);
        assertDoesNotThrow(() -> store.save(sample(UUID.randomUUID())));
        assertFalse(store.writable());
    }

    @Test
    void twoThreadsSavingTheSameIdLeaveOneValidFile() throws Exception {
        FileConversationStore store = new FileConversationStore(dir);
        UUID id = UUID.randomUUID();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            int n = i;
            ts[i] = Thread.ofVirtual().start(() -> {
                for (int k = 0; k < 20; k++) store.save(sample(id).withTitle("t" + n, Instant.now()));
            });
        }
        for (Thread t : ts) t.join();
        assertTrue(store.load(id).orElseThrow().title().startsWith("t"));
        try (var files = Files.list(dir.resolve("conversations"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void anAccessDeniedMoveIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        FileConversationStore store = new FileConversationStore(dir, (from, to) -> {
            if (calls.incrementAndGet() <= 2) throw new AccessDeniedException(to.toString());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        });
        UUID id = UUID.randomUUID();
        store.save(sample(id));
        assertEquals(3, calls.get());
        assertTrue(store.writable());
        assertTrue(store.load(id).isPresent());
    }

    @Test
    void aMoveThatKeepsFailingGivesUpAndReports() {
        FileConversationStore store = new FileConversationStore(dir, (from, to) -> {
            throw new AccessDeniedException(to.toString());
        });
        store.save(sample(UUID.randomUUID()));
        assertFalse(store.writable());
    }
}
