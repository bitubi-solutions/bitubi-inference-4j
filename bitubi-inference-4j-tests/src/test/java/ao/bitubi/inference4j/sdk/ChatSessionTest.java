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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChatSessionTest {
    private static final String MODEL = "tjake/Qwen2.5-0.5B-Instruct-JQ4";
    private static Bi4j bi4j;

    @BeforeClass
    public static void load() {
        bi4j = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(1024).build();
    }

    @AfterClass
    public static void close() {
        bi4j.close();
    }

    private static long pageFiles(String idPrefix) throws Exception {
        try (Stream<Path> files = Files.list(((DefaultBi4j) bi4j).workDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith(idPrefix) && p.toString().endsWith(".page")).count();
        }
    }

    @Test
    public void remembersEarlierTurns() {
        try (ChatSession chat = bi4j.newSession(s -> s.system("Answer in one short sentence.").temperature(0f).maxNewTokens(24))) {
            chat.send("My name is Ana.");
            assertTrue(chat.send("What is my name?").text().contains("Ana"));
        }
    }

    @Test
    public void historyHasBothSidesOfEveryTurn() {
        try (ChatSession chat = bi4j.newSession(s -> s.system("Be brief.").temperature(0f).maxNewTokens(8))) {
            ChatResponse first = chat.send("Hello");
            chat.send("Again");
            List<Message> history = chat.history();
            assertEquals(4, history.size());
            assertEquals(
                List.of(Message.Role.USER, Message.Role.ASSISTANT, Message.Role.USER, Message.Role.ASSISTANT),
                history.stream().map(Message::role).toList()
            );
            assertEquals(first.text(), history.get(1).text());
            assertThrows(UnsupportedOperationException.class, () -> history.add(Message.user("x")));
        }
    }

    @Test
    public void aSecondCallWhileOneRunsIsBusy() throws Exception {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(300))) {
            CompletableFuture<ChatResponse> running = CompletableFuture.supplyAsync(() -> chat.send("Write a long story about a dragon."));
            Thread.sleep(500);
            assertThrows(SessionBusyException.class, () -> chat.send("Hi"));
            running.get(); // the first call is not harmed by the rejected one
        }
    }

    @Test
    public void aTemporarySessionForgetsAndLeavesNoPages() throws Exception {
        try (ChatSession chat = bi4j.newSession(s -> s.temporary().temperature(0f).maxNewTokens(8))) {
            chat.send("My name is Ana.");
            assertEquals(0, pageFiles(chat.id().toString()));
            chat.send("What is my name?");
            assertEquals(0, pageFiles(chat.id().toString()));
            assertTrue(chat.history().isEmpty());
        }
    }

    @Test
    public void aSessionPastTheContextWindowFailsClearly() {
        try (
            Bi4j small = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(64).build();
            ChatSession chat = small.newSession()
        ) {
            String longMessage = "word ".repeat(200);
            ContextLengthExceededException e = assertThrows(ContextLengthExceededException.class, () -> chat.send(longMessage));
            assertTrue(e.getMessage(), e.getMessage().contains("context window is 64"));
            assertTrue(chat.history().isEmpty()); // a failed call leaves no half turn behind
        }
    }

    @Test
    public void generationNeverRunsPastTheSdkContextLength() {
        try (Bi4j small = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(64).build()) {
            ChatResponse r = small.chat().user("Write a very long story about a dragon.").maxNewTokens(500).temperature(0f).generate();
            assertTrue("prompt + generated stays inside 64: " + r.usage(), r.usage().promptTokens() + r.usage().generatedTokens() <= 64);
        }
    }

    @Test
    public void closeReleasesThePages() throws Exception {
        ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(8));
        chat.send("Hello");
        assertTrue(pageFiles(chat.id().toString()) > 0);
        chat.close();
        assertEquals(0, pageFiles(chat.id().toString()));
        chat.close(); // twice is fine
    }

    @Test
    public void aClosedSessionRefusesCalls() {
        ChatSession chat = bi4j.newSession();
        chat.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> chat.send("Hi"));
        assertTrue(e.getMessage(), e.getMessage().contains("ChatSession is closed"));
    }

    @Test
    public void closingBi4jClosesItsSessions() {
        Bi4j other = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(256).build();
        ChatSession chat = other.newSession();
        other.close();
        assertThrows(IllegalStateException.class, () -> chat.send("Hi"));
    }

    @Test(timeout = 60000)
    public void closingBi4jWhileASendRunsCancelsItAndWaits() throws Exception {
        Bi4j other = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(512).build();
        Path workDir = ((DefaultBi4j) other).workDir();
        ChatSession chat = other.newSession(s -> s.temperature(0f).maxNewTokens(400));
        CompletableFuture<ChatResponse> running = CompletableFuture.supplyAsync(() -> chat.send("Write a long story about a dragon."));
        Thread.sleep(1000);
        other.close();
        ChatResponse r = running.get(); // the send ends with a response, it is not left running on freed memory
        assertEquals(FinishReason.CANCELLED, r.finishReason());
        assertFalse("work directory removed", Files.exists(workDir));
    }

    @Test
    public void sessionIdsAreDistinct() {
        try (ChatSession a = bi4j.newSession(); ChatSession b = bi4j.newSession()) {
            assertNotEquals(a.id(), b.id());
            assertTrue(a.id() instanceof UUID);
        }
    }
}
