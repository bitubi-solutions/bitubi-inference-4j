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
package ao.bitubi.inference4j.model;

import ao.bitubi.inference4j.sdk.ContextLengthExceededException;
import static org.junit.Assert.*;

import ao.bitubi.inference4j.model.functions.Generator;
import ao.bitubi.inference4j.safetensors.DType;
import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import ao.bitubi.inference4j.safetensors.prompt.PromptContext;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** ntokens counts new tokens only, a full context fails with a clear error, and an interrupt cancels between tokens. */
public class GenerationLimitsTest {
    private static AbstractModel model;

    @BeforeClass
    public static void load() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        model = ModelSupport.loadModel(dir, DType.F32, DType.I8);
    }

    @AfterClass
    public static void close() {
        model.close();
    }

    // A long prompt and a non-stopping completion, so EOS does not end the run early
    private static final String LONG_PROMPT = "one two three four five six seven eight nine ten ".repeat(10) + "eleven";

    @Test
    public void ntokensCountsOnlyNewTokensSoALongPromptStillGenerates() {
        Generator.Response r = model.generate(UUID.randomUUID(), PromptContext.of(LONG_PROMPT), 0f, 5, (t, f) -> {});
        assertTrue("prompt longer than ntokens is accepted: " + r.promptTokens, r.promptTokens > 5);
        assertEquals(Generator.FinishReason.MAX_TOKENS, r.finishReason);
        assertEquals(4, r.generatedTokens); // the first token comes from the prompt pass
    }

    @Test
    public void aSessionPastTheContextWindowFailsWithAClearError() {
        UUID session = UUID.randomUUID();
        int context = model.getConfig().contextLength;
        // Pretend the session already holds almost a full context (filling 32k tokens for real takes minutes)
        model.kvBufferCache.getKvBuffer(session).setCurrentContextPosition(context - 3);
        ContextLengthExceededException e = assertThrows(
            ContextLengthExceededException.class,
            () -> model.generate(session, PromptContext.of(LONG_PROMPT), 0f, 1, (t, f) -> {})
        );
        assertTrue(e.getMessage(), e.getMessage().contains("context window is " + context));
        model.releaseSession(session);
    }

    @Test
    public void anInterruptCancelsBetweenTokens() {
        AtomicInteger seen = new AtomicInteger();
        try {
            Generator.Response r = model.generate(UUID.randomUUID(), PromptContext.of(LONG_PROMPT), 0f, 50, (t, f) -> {
                if (seen.incrementAndGet() == 3) Thread.currentThread().interrupt();
            });
            assertEquals(Generator.FinishReason.CANCELLED, r.finishReason);
            assertTrue("stopped soon after the interrupt: " + r.generatedTokens, r.generatedTokens < 10);
        } finally {
            Thread.interrupted(); // clear the flag for the next test
        }
    }
}
