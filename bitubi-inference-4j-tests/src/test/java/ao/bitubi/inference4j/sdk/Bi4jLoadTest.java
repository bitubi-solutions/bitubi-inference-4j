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

import ao.bitubi.inference4j.safetensors.DType;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class Bi4jLoadTest {
    private static final String MODEL = "tjake/Qwen2.5-0.5B-Instruct-JQ4";

    private static Bi4j.Builder qwen() {
        return Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(1024);
    }

    @Test
    public void loadsAndReportsCapabilities() {
        try (Bi4j bi4j = qwen().build()) {
            assertEquals(new Capabilities(Set.of(Modality.TEXT), true, false, 1024), bi4j.capabilities());
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> bi4j.embed("hello"));
            assertTrue(e.getMessage(), e.getMessage().contains("does not produce embeddings"));
        }
    }

    @Test
    public void missingPathIsModelNotFound() {
        ModelNotFoundException e = assertThrows(
            ModelNotFoundException.class,
            () -> Bi4j.builder().model(Path.of("does/not/exist")).build()
        );
        assertTrue(e.getMessage(), e.getMessage().contains("does/not/exist"));
    }

    @Test
    public void oversizedContextIsInsufficientMemory() {
        InsufficientMemoryException e = assertThrows(
            InsufficientMemoryException.class,
            () -> qwen().contextLength(32768).available(() -> new MemoryBudget.Available(1L << 20, 1L << 40, 1L << 40)).build()
        );
        assertTrue(e.getMessage(), e.getMessage().contains("lower contextLength"));
    }

    @Test
    public void contextLengthAboveTheModelMaximumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> qwen().contextLength(1 << 30).build());
    }

    @Test
    public void manualTypesWithoutKernelFail() {
        UnsupportedModelException e = assertThrows(UnsupportedModelException.class, () -> qwen().workingTypes(DType.Q4, DType.F32).build());
        assertTrue(e.getMessage(), e.getMessage().contains("Q4"));
    }

    @Test
    public void manualTypesWithRisksStillLoad() {
        Logger logger = (Logger) LoggerFactory.getLogger(Bi4j.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (Bi4j bi4j = qwen().workingTypes(DType.F32, DType.F32).build()) {
            assertNotNull(bi4j.capabilities());
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(
            appender.list.toString(),
            appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("slower than automatic"))
        );
    }

    @Test
    public void closedInstanceRefusesCallsAndCloseIsIdempotent() {
        Bi4j bi4j = qwen().build();
        bi4j.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, bi4j::capabilities);
        assertTrue(e.getMessage(), e.getMessage().contains("closed"));
        bi4j.close();
    }
}
