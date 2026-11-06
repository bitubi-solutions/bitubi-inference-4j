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

import static org.junit.Assert.*;

import ao.bitubi.inference4j.safetensors.DType;
import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import ao.bitubi.inference4j.safetensors.prompt.PromptContext;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The KV cache of a session lives across calls and its page files are deleted on release and on close. */
public class KvCacheReleaseTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pageFilesAreDeletedOnReleaseAndClose() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        Path work = temporary.newFolder("work").toPath();
        UUID kept = UUID.randomUUID(), released = UUID.randomUUID();

        try (AbstractModel model = ModelSupport.loadModel(dir, work.toFile(), DType.F32, DType.I8, Optional.empty(), Optional.empty())) {
            model.generate(released, PromptContext.of("Hello"), 0f, 3, (t, f) -> {});
            long pages = pageFiles(work, released);
            assertTrue("pages of the session are on disk", pages > 0);

            // A second call continues the same session instead of reopening closed pages
            model.generate(released, PromptContext.of(" again"), 0f, 3, (t, f) -> {});
            assertEquals(pages, pageFiles(work, released));

            model.generate(kept, PromptContext.of("Hi"), 0f, 3, (t, f) -> {});
            assertTrue(model.releaseSession(released));
            assertEquals(0, pageFiles(work, released));
            assertFalse(model.releaseSession(released));
            assertTrue(pageFiles(work, kept) > 0);
        }
        assertEquals("close deletes what was never released", 0, pageFiles(work, kept));
    }

    @Test
    public void creatingAPageOnAnInterruptedThreadDoesNotFailAndKeepsTheFlag() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        Path work = temporary.newFolder("work2").toPath();
        try (AbstractModel model = ModelSupport.loadModel(dir, work.toFile(), DType.F32, DType.I8, Optional.empty(), Optional.empty())) {
            var kv = model.kvBufferCache.getKvBuffer(UUID.randomUUID());
            Thread.currentThread().interrupt(); // a cancel can arrive while the next page is being mapped
            try {
                kv.getKeyTensorForPosition(0, 0); // maps a file: an interrupted thread would close its channel
                assertTrue("the interrupt is still pending", Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test(timeout = 60000)
    public void pagesCreatedWhileInterruptsKeepArrivingAreStillCreated() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        Path work = temporary.newFolder("work3").toPath();
        try (AbstractModel model = ModelSupport.loadModel(dir, work.toFile(), DType.F32, DType.I8, Optional.empty(), Optional.empty())) {
            var kv = model.kvBufferCache.getKvBuffer(UUID.randomUUID());
            Thread main = Thread.currentThread();
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            Thread interrupter = new Thread(() -> {
                while (!done.get()) {
                    main.interrupt(); // a cancel can land at any moment, including while a page file is being mapped
                    java.util.concurrent.locks.LockSupport.parkNanos(200_000); // an interrupt every 0.2 ms
                }
            });
            interrupter.start();
            try {
                for (int position = 0; position < 32768; position += 512) {
                    for (int layer : new int[] { 0, 23 })
                        kv.getKeyTensorForPosition(layer, position);
                }
            } finally {
                done.set(true);
                for (boolean joined = false; !joined;) {
                    try {
                        interrupter.join(); // the interrupter may still be interrupting us: wait until it has stopped
                        joined = true;
                    } catch (InterruptedException ignored) {
                        // try again
                    }
                }
                Thread.interrupted();
            }
        }
    }

    private static long pageFiles(Path dir, UUID session) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(session + "-")).count();
        }
    }
}
