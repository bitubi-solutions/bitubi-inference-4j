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

import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ModelInfoTest {
    private static final String MODEL = "tjake/Qwen2.5-0.5B-Instruct-JQ4";

    @Test
    public void infoDescribesTheLoadedModel() {
        try (Bi4j bi4j = Bi4j.builder().model(MODEL).modelsDir(Path.of("./models")).contextLength(1024).build()) {
            ModelInfo info = bi4j.info();
            assertEquals(MODEL, info.name());
            assertEquals("QWEN2", info.architecture());
            assertEquals("Q4", info.weightType());
            assertEquals("F32", info.memoryType());
            assertEquals("I8", info.activationType());
            assertEquals(1024, info.contextLength());
            assertTrue("about 300 MB: " + info.weightBytes(), info.weightBytes() > 200L << 20 && info.weightBytes() < 400L << 20);
        }
    }

    @Test
    public void infoOfALocalPathUsesTheFolderName() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", MODEL);
        try (Bi4j bi4j = Bi4j.builder().model(dir.toPath()).contextLength(512).build()) {
            assertEquals(dir.getName(), bi4j.info().name());
        }
    }

    @Test
    public void infoOfAClosedInstanceIsRefused() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", MODEL);
        Bi4j bi4j = Bi4j.builder().model(dir.toPath()).contextLength(256).build();
        bi4j.close();
        assertThrows(IllegalStateException.class, bi4j::info);
    }

    @Test
    public void progressReportsThePhasesInOrder() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", MODEL);
        List<LoadProgress> seen = new ArrayList<>();
        Bi4j.builder().model(dir.toPath()).contextLength(256).onProgress(seen::add).build().close();
        assertEquals(
            seen.toString(),
            List.of(LoadProgress.Phase.CHECKING, LoadProgress.Phase.LOADING, LoadProgress.Phase.READY),
            seen.stream().map(LoadProgress::phase).collect(Collectors.toList())
        );
        seen.forEach(p -> assertEquals(0, p.total()));
    }

    @Test
    public void aCallbackThatThrowsDoesNotBreakTheLoad() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", MODEL);
        try (
            Bi4j bi4j = Bi4j.builder()
                .model(dir.toPath())
                .contextLength(256)
                .onProgress(p -> { throw new IllegalStateException("boom"); })
                .build()
        ) {
            assertNotNull(bi4j.capabilities());
        }
    }

    @Test
    public void aFailedLoadNeverReportsReady() {
        List<LoadProgress> seen = new ArrayList<>();
        assertThrows(ModelNotFoundException.class, () -> Bi4j.builder().model(Path.of("does/not/exist")).onProgress(seen::add).build());
        assertTrue(seen.toString(), seen.stream().noneMatch(p -> p.phase() == LoadProgress.Phase.READY));
    }
}
