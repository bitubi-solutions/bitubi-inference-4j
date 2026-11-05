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

import ao.bitubi.inference4j.model.ModelSupport;
import ao.bitubi.inference4j.safetensors.DType;
import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import java.io.File;
import org.junit.Test;

public class ModelInspectionTest {

    @Test
    public void readsASafetensorsModelWithoutLoadingIt() throws Exception {
        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        ModelInspection m = ModelInspection.of(dir.toPath());
        assertEquals(DType.Q4, m.weights());
        assertFalse(m.gguf());
        assertEquals(ModelSupport.ModelType.QWEN2, m.type());
        assertEquals(24, m.layers());
        assertEquals(32768, m.maxContext());
        assertTrue("weights are about 300 MB: " + m.weightBytes(), m.weightBytes() > 200L << 20 && m.weightBytes() < 400L << 20);

        MemoryEstimate e = MemoryEstimate.of(m, WorkingTypes.automatic(m.weights()), 4096);
        assertEquals(2L * 24 * 4096 * m.kvLength() * 4, e.kvBytes()); // F32 KV cache
        assertEquals(0, e.directBytes());
    }

    @Test
    public void aMissingFolderIsAnIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ModelInspection.of(new File("does/not/exist").toPath()));
    }
}
