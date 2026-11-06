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
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class GenerationHandleTest {
    private static Bi4j bi4j;

    @BeforeClass
    public static void load() {
        bi4j = Bi4j.builder().model("tjake/Qwen2.5-0.5B-Instruct-JQ4").modelsDir(Path.of("./models")).contextLength(1024).build();
    }

    @AfterClass
    public static void close() {
        bi4j.close();
    }

    private static ChatSession session() {
        return bi4j.newSession(s -> s.temperature(0f).maxNewTokens(200));
    }

    @Test
    public void resultIsEmptyUntilTheEndAndThenHasTheTextAndUsage() {
        try (Generation g = bi4j.chat().user("Name three fruits.").maxNewTokens(12).temperature(0f).start()) {
            assertTrue(g.result().isEmpty());
            String text = g.tokens().map(Token::text).collect(Collectors.joining());
            ChatResponse r = g.result().orElseThrow();
            assertEquals(text, r.text());
            assertTrue(r.finishReason() == FinishReason.LENGTH || r.finishReason() == FinishReason.STOP);
            assertTrue(r.usage().generatedTokens() > 0);
            assertTrue(r.usage().promptTokens() > 0);
        }
    }

    @Test
    public void aStopSequenceShowsInTheResult() {
        ChatRequest request = bi4j.chat()
            .user("Count from 1 to 20, one number per line.")
            .stop("5")
            .maxNewTokens(64)
            .temperature(0f)
            .request();
        try (Generation g = bi4j.start(request)) {
            g.tokens().forEach(t -> {});
            assertEquals(FinishReason.STOP_SEQUENCE, g.result().orElseThrow().finishReason());
        }
    }

    @Test
    public void resultAfterACancelSaysCancelledAndKeepsTheTextSoFar() {
        try (ChatSession chat = session(); Generation g = chat.start("Count to 50.")) {
            Iterator<Token> it = g.tokens().iterator();
            StringBuilder read = new StringBuilder();
            for (int i = 0; i < 3; i++)
                read.append(it.next().text());
            g.cancel();
            while (it.hasNext())
                read.append(it.next().text()); // whatever was already on its way
            ChatResponse r = g.result().orElseThrow();
            assertEquals(FinishReason.CANCELLED, r.finishReason());
            // the result holds everything generated; after a cancel the last token or two may not have reached the reader
            assertTrue(r.text() + " does not start with " + read, r.text().startsWith(read.toString()));
        }
    }

    @Test(timeout = 120000)
    public void cancelFromAnotherThreadEndsTheReaderNormally() throws Exception {
        try (ChatSession chat = session(); Generation g = chat.start("Count to 100.")) {
            AtomicInteger seen = new AtomicInteger();
            CompletableFuture<String> reader = CompletableFuture.supplyAsync(
                () -> g.tokens().peek(t -> seen.incrementAndGet()).map(Token::text).collect(Collectors.joining())
            );
            while (seen.get() < 2)
                Thread.sleep(10);
            g.cancel(); // from this thread, while the reader thread is inside the stream
            reader.get(30, TimeUnit.SECONDS); // ends without an exception
            assertEquals(FinishReason.CANCELLED, g.result().orElseThrow().finishReason());
            assertNotNull("the session is free as soon as the reader is done", chat.send("Hi"));
        }
    }

    @Test
    public void cancelBeforeTheFirstReadGivesAnEmptyCancelledStream() {
        try (ChatSession chat = session()) {
            Generation g = chat.start("Hello");
            g.cancel();
            assertEquals(0, g.tokens().count());
            ChatResponse r = g.result().orElseThrow();
            assertEquals(FinishReason.CANCELLED, r.finishReason());
            assertEquals("", r.text());
            assertTrue(chat.history().isEmpty());
            assertNotNull(chat.send("Hi"));
        }
    }

    @Test
    public void cancelAfterTheEndAndTwiceIsHarmless() {
        try (Generation g = bi4j.chat().user("Hi").maxNewTokens(4).temperature(0f).start()) {
            g.tokens().forEach(t -> {});
            ChatResponse before = g.result().orElseThrow();
            g.cancel();
            g.cancel();
            assertEquals(before, g.result().orElseThrow());
        }
    }

    @Test
    public void closeStillCancelsAndWaits() {
        try (ChatSession chat = session()) {
            Generation g = chat.start("Count to 50.");
            Iterator<Token> it = g.tokens().iterator();
            assertTrue(it.hasNext());
            g.close();
            assertNotNull("close waited: the session is free", chat.send("Hi"));
        }
    }

    @Test
    public void startOnABusySessionFailsOnTheFirstRead() {
        try (ChatSession chat = session(); Generation first = chat.start("Count to 50.")) {
            Iterator<Token> it = first.tokens().iterator();
            assertTrue(it.hasNext());
            Generation second = chat.start("Hi");
            assertThrows(SessionBusyException.class, () -> second.tokens().findFirst());
        }
    }
}
