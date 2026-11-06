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
package ao.bitubi.inference4j.sdk;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionRestoreTest {
    private static final String MODEL = "tjake/Qwen2.5-0.5B-Instruct-JQ4";
    private static final List<Message> EARLIER = List.of(Message.user("My name is Ana."), Message.assistant("Nice to meet you, Ana."));
    private static Bi4j bi4j;

    @BeforeClass
    public static void load() {
        bi4j = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(1024).build();
    }

    @AfterClass
    public static void close() {
        bi4j.close();
    }

    @Test
    public void aRestoredSessionRemembersTheHistory() {
        try (ChatSession chat = bi4j.newSession(s -> s.system("Answer in one short sentence.").temperature(0f).maxNewTokens(24))) {
            chat.restore(EARLIER);
            assertTrue(chat.send("What is my name?").text().contains("Ana"));
            assertEquals(4, chat.history().size());
        }
    }

    @Test
    public void restoreProcessesTokensAndCountsThem() {
        try (ChatSession chat = bi4j.newSession()) {
            int tokens = chat.restore(EARLIER);
            assertTrue(tokens > 0);
            assertEquals(tokens, chat.contextTokens());
            assertEquals(1024 - tokens, chat.contextRemaining());
            assertEquals(EARLIER, chat.history());
        }
    }

    @Test
    public void restoreOnAUsedSessionFails() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(4))) {
            chat.send("Hello");
            assertThrows(IllegalStateException.class, () -> chat.restore(EARLIER));
            assertEquals(2, chat.history().size());
        }
    }

    @Test
    public void restoreOnATemporarySessionFails() {
        try (ChatSession chat = bi4j.newSession(s -> s.temporary())) {
            assertThrows(IllegalStateException.class, () -> chat.restore(EARLIER));
        }
    }

    @Test
    public void restoreRejectsASystemMessage() {
        try (ChatSession chat = bi4j.newSession()) {
            assertThrows(IllegalArgumentException.class, () -> chat.restore(List.of(Message.system("Be brief."))));
            assertEquals(0, chat.contextTokens());
        }
    }

    @Test
    public void restoreThatDoesNotFitFailsAndLeavesTheSessionUsable() {
        try (
            Bi4j small = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(64).build();
            ChatSession chat = small.newSession()
        ) {
            List<Message> tooLong = List.of(Message.user("word ".repeat(200)), Message.assistant("ok"));
            assertThrows(ContextLengthExceededException.class, () -> chat.restore(tooLong));
            assertEquals(0, chat.contextTokens());
            assertTrue(chat.history().isEmpty());
            assertNotNull(chat.send("Hi"));
        }
    }

    @Test
    public void restoreWithAnEmptyListDoesNothing() {
        try (ChatSession chat = bi4j.newSession()) {
            assertEquals(0, chat.restore(List.of()));
            assertTrue(chat.restore(List.of(Message.user("Hi"), Message.assistant("Hello"))) > 0); // still restorable
        }
    }

    @Test
    public void contextAccountingGrowsWithEachTurn() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(8))) {
            assertEquals(0, chat.contextTokens());
            chat.send("Hello");
            int first = chat.contextTokens();
            assertTrue(first > 0);
            assertEquals(1024 - first, chat.contextRemaining());
            chat.send("Again");
            assertTrue(chat.contextTokens() > first);
        }
    }

    @Test
    public void aTemporarySessionHoldsNothingAndHasAllTheRoom() {
        try (ChatSession chat = bi4j.newSession(s -> s.temporary().temperature(0f).maxNewTokens(4))) {
            chat.send("Hello");
            assertEquals(0, chat.contextTokens());
            assertEquals(1024, chat.contextRemaining());
        }
    }
}
