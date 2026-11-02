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
package ao.bitubi.inference4j.safetensors;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SafeTensorIndexTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void shardPathsCannotLeaveTheModelDirectory() throws Exception {
        Path model = temporary.newFolder("model").toPath();
        Files.writeString(temporary.getRoot().toPath().resolve("outside.safetensors"), "x");
        Files.writeString(
            model.resolve(SafeTensorIndex.MODEL_INDEX_JSON),
            "{\"metadata\":{},\"weight_map\":{\"probe.weight\":\"../outside.safetensors\"}}"
        );
        RuntimeException e = assertThrows(RuntimeException.class, () -> SafeTensorIndex.loadWithWeights(model));
        assertTrue(String.valueOf(e.getCause()), e.getCause().getMessage().startsWith("Shard outside the model directory"));
    }
}
