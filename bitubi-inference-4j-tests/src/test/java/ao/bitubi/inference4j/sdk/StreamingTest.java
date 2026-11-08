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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class StreamingTest {
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

    @Test
    public void limitThreeGivesThreeTokens() {
        try (Stream<Token> tokens = bi4j.chat().user("Count to 50.").maxNewTokens(200).temperature(0f).stream()) {
            assertEquals(3, tokens.limit(3).toList().size());
        }
    }

    @Test
    public void aCancelledSessionIsUsableAgain() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(200))) {
            try (Stream<Token> tokens = chat.stream("Count to 50.")) {
                assertEquals(3, tokens.limit(3).toList().size());
            }
            assertFalse(chat.send("Hi").text().isBlank());
        }
    }

    @Test
    public void aStreamingSessionIsBusyUntilTheStreamIsClosed() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(200))) {
            try (Stream<Token> tokens = chat.stream("Count to 50.")) {
                Iterator<Token> it = tokens.iterator();
                assertTrue(it.hasNext());
                it.next();
                assertThrows(SessionBusyException.class, () -> chat.send("Hi"));
            }
            assertNotNull(chat.send("Hi")); // closing the stream freed the session
        }
    }

    @Test
    public void nothingStartsUntilTheStreamIsConsumed() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(8))) {
            Stream<Token> tokens = chat.stream("Hello");
            tokens.close();
            assertTrue(chat.history().isEmpty());
        }
    }

    @Test
    public void theStreamSaysWhatGenerateSays() {
        ChatRequest request = bi4j.chat().user("Name three fruits.").maxNewTokens(24).temperature(0f).request();
        try (Stream<Token> tokens = bi4j.stream(request)) {
            assertEquals(bi4j.generate(request).text(), tokens.map(Token::text).collect(Collectors.joining()));
        }
    }

    @Test
    public void aStopSequenceKeepsTheTextBeforeItAndDropsTheRest() {
        ChatRequest request = bi4j.chat()
            .user("Count from 1 to 20, one number per line.")
            .stop("5")
            .maxNewTokens(64)
            .temperature(0f)
            .request();
        try (Stream<Token> tokens = bi4j.stream(request)) {
            String streamed = tokens.map(Token::text).collect(Collectors.joining());
            assertFalse(streamed, streamed.contains("5"));
            assertTrue(streamed, streamed.contains("4"));
            assertEquals(bi4j.generate(request).text(), streamed);
        }
    }

    @Test
    public void anUnsupportedInputFailsWhereTheStreamIsCreated() {
        Part image = Part.Image.of(new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 });
        assertThrows(UnsupportedInputException.class, () -> bi4j.chat().user(Part.text("What is this?"), image).stream());
    }

    @Test
    public void aFailureInTheGenerationSurfacesToTheReader() {
        try (
            Bi4j small = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(64).build();
            Stream<Token> tokens = small.chat().user("word ".repeat(200)).stream()
        ) {
            assertThrows(ContextLengthExceededException.class, tokens::toList);
        }
    }

    @Test(timeout = 20000)
    public void aStreamCancelledWhileStartingNeverRunsAndDoesNotHangTheReader() {
        DefaultBi4j owner = (DefaultBi4j) bi4j;
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        // begin() runs on the reader's thread just before the producer starts: cancel the stream right there, the way
        // Bi4j.close() on another thread would in that window
        Stream<Token> stream = TokenStream.open(owner, () -> List.copyOf(owner.streams()).forEach(TokenStream::cancel), onToken -> {
            ran.set(true);
            return null;
        }, () -> ended.set(true));
        assertThrows(IllegalStateException.class, () -> stream.iterator().hasNext());
        assertFalse("a cancelled stream must not generate", ran.get());
    }

    @Test
    public void closingTheStreamWaitsEvenIfTheClosingThreadIsInterrupted() {
        try (ChatSession chat = bi4j.newSession(s -> s.temperature(0f).maxNewTokens(200))) {
            Stream<Token> tokens = chat.stream("Count to 50.");
            Iterator<Token> it = tokens.iterator();
            assertTrue(it.hasNext());
            it.next();
            Thread.currentThread().interrupt();
            tokens.close();
            assertTrue("the caller's interrupt is kept", Thread.interrupted());
            assertNotNull("close waited for the generation to let go of the session", chat.send("Hi"));
        }
    }

    @Test
    public void closingBi4jReportsAStreamLeftOpen() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Bi4j.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Path jfr = Files.createTempFile("bi4j", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("ao.bitubi.inference4j.UnclosedStream");
            recording.start();
            Bi4j other = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(256).build();
            Iterator<Token> it = other.chat().user("Count to 100.").maxNewTokens(100).stream().iterator();
            assertTrue(it.hasNext());
            other.close();
            // the reader is told why its stream ended, not just that it did
            IllegalStateException closedBehindIt = assertThrows(IllegalStateException.class, () -> {
                while (true) it.next();
            });
            assertTrue(closedBehindIt.getMessage(), closedBehindIt.getMessage().contains("Bi4j is closed"));
            recording.stop();
            recording.dump(jfr);
            // user-defined events are enabled by default, so Load and Generation are in the file too: count only ours
            long unclosed = RecordingFile.readAllEvents(jfr)
                .stream()
                .filter(e -> e.getEventType().getName().equals("ao.bitubi.inference4j.UnclosedStream"))
                .count();
            assertEquals(1, unclosed);
        } finally {
            logger.detachAppender(appender);
            Files.deleteIfExists(jfr);
        }
        assertTrue(
            appender.list.toString(),
            appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("still open"))
        );
    }

    @Test
    public void closingBi4jWithAnOpenStreamIsQuickAndCleansUp() throws Exception {
        Bi4j other = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(512).build();
        Path workDir = ((DefaultBi4j) other).workDir();
        Stream<Token> tokens = other.chat().user("Count to 100.").maxNewTokens(300).temperature(0f).stream();
        Iterator<Token> it = tokens.iterator();
        assertTrue(it.hasNext());
        it.next();

        long start = System.nanoTime();
        other.close();
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("close took " + millis + " ms", millis < 5000);
        assertFalse("work directory removed", Files.exists(workDir));
        assertThrows(IllegalStateException.class, it::hasNext);
    }
}
