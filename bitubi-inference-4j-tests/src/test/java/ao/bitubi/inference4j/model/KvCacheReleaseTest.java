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

    private static long pageFiles(Path dir, UUID session) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(session + "-")).count();
        }
    }
}
