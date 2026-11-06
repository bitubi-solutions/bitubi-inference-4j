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
import static org.junit.Assume.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class OneShotChatTest {
    private static Bi4j bi4j;

    @BeforeClass
    public static void load() {
        bi4j = Bi4j.builder().model("tjake/Qwen2.5-0.5B-Instruct-JQ4").modelsDir(Path.of("./models")).contextLength(1024).build();
    }

    @AfterClass
    public static void close() {
        bi4j.close();
    }

    @Test
    public void generatesWithUsage() {
        ChatResponse r = bi4j.chat().user("Say hello.").maxNewTokens(16).temperature(0f).generate();
        assertFalse(r.text().isBlank());
        assertTrue(r.usage().generatedTokens() <= 16);
        assertTrue(r.usage().promptTokens() > 0);
        assertNotNull(r.usage().timeToFirstToken());
    }

    @Test
    public void anEmptyMessageStillAnswers() {
        assertNotNull(bi4j.chat().user("").maxNewTokens(4).generate());
    }

    @Test
    public void aRequestIsAValueThatCanBeSentTwice() {
        ChatRequest request = bi4j.chat().system("Answer with one word.").user("Name a color.").maxNewTokens(8).temperature(0f).request();
        assertEquals(bi4j.generate(request).text(), bi4j.generate(request).text());
    }

    @Test
    public void eightThreadsGetTheSameAnswerAsOne() throws Exception {
        ChatRequest request = bi4j.chat().user("Name three fruits.").maxNewTokens(24).temperature(0f).request();
        String alone = bi4j.generate(request).text();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                results.add(pool.submit((Callable<String>) () -> bi4j.generate(request).text()));
            for (Future<String> f : results)
                assertEquals(alone, f.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void aStopSequenceEndsTheAnswerAndIsNotInIt() {
        ChatResponse r = bi4j.chat().user("Count from 1 to 20, one number per line.").stop("5").maxNewTokens(64).temperature(0f).generate();
        assertEquals(r.text(), FinishReason.STOP_SEQUENCE, r.finishReason());
        assertFalse(r.text(), r.text().contains("5"));
        assertTrue(r.text(), r.text().contains("4"));
    }

    @Test
    public void aStopSpanningSeveralTokensStillStops() {
        ChatRequest probe = bi4j.chat()
            .user("Count from 1 to 20, one number per line.")
            .stop("5")
            .maxNewTokens(64)
            .temperature(0f)
            .request();
        String before = bi4j.generate(probe).text(); // ends just before the "5"
        assertTrue(before, before.length() >= 2);
        String tail = before.substring(before.length() - 2); // a few characters of the previous tokens
        ChatResponse r = bi4j.generate(new ChatRequest(probe.messages(), probe.settings().withStop(List.of(tail + "5"))));
        assertEquals(r.text(), FinishReason.STOP_SEQUENCE, r.finishReason());
        assertEquals(before.substring(0, before.length() - 2), r.text());
    }

    @Test
    public void aStopOnTheLastAllowedTokenIsStillReportedAsAStop() {
        String first = bi4j.chat().user("Say hello.").maxNewTokens(1).temperature(0f).generate().text();
        assumeFalse("the first token has text", first.isEmpty());
        ChatResponse r = bi4j.chat().user("Say hello.").maxNewTokens(1).temperature(0f).stop(first).generate();
        assertEquals(FinishReason.STOP_SEQUENCE, r.finishReason());
        assertEquals("", r.text());
    }

    @Test
    public void anImageOnATextOnlyModelFailsBeforeAnyWork() {
        Part image = Part.Image.of(new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 });
        UnsupportedInputException e = assertThrows(
            UnsupportedInputException.class,
            () -> bi4j.chat().user(Part.text("What is this?"), image).generate()
        );
        assertTrue(e.getMessage(), e.getMessage().contains("TEXT only"));
        assertTrue(e.getMessage(), e.getMessage().contains("IMAGE"));
    }

    @Test
    public void oneShotCallsLeaveNoPageFilesBehind() throws Exception {
        bi4j.chat().user("Hi").maxNewTokens(4).generate();
        try (Stream<Path> files = Files.list(((DefaultBi4j) bi4j).workDir())) {
            assertEquals(0, files.filter(p -> p.toString().endsWith(".page")).count());
        }
    }

    @Test
    public void aClosedInstanceRefusesToGenerate() {
        Bi4j other = Bi4j.builder().model("tjake/Qwen2.5-0.5B-Instruct-JQ4").modelsDir(Path.of("./models")).contextLength(256).build();
        ChatRequestBuilder chat = other.chat();
        other.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> chat.user("x").generate());
        assertTrue(e.getMessage(), e.getMessage().contains("closed"));
    }
}
