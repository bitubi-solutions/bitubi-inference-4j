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
import java.util.List;
import org.junit.Test;

public class WorkingTypesTest {
    private static final MemoryBudget.Available ROOMY = new MemoryBudget.Available(64L << 30, 64L << 30, 1L << 40);

    @Test
    public void automaticFollowsTheSpecTable() {
        assertEquals(new WorkingTypes(DType.F32, DType.I8), WorkingTypes.automatic(DType.Q4));
        assertEquals(new WorkingTypes(DType.F32, DType.F32), WorkingTypes.automatic(DType.I8)); // GGUF Q8_0
        assertEquals(new WorkingTypes(DType.F32, DType.F32), WorkingTypes.automatic(DType.BF16));
        assertEquals(new WorkingTypes(DType.F32, DType.F32), WorkingTypes.automatic(DType.F16));
        assertEquals(new WorkingTypes(DType.F32, DType.F32), WorkingTypes.automatic(DType.F32));
    }

    @Test
    public void kernelsExistOnlyForSupportedCombinations() {
        assertFalse(WorkingTypes.hasKernel(new WorkingTypes(DType.F32, DType.I8), DType.BF16));
        assertFalse(WorkingTypes.hasKernel(new WorkingTypes(DType.Q4, DType.F32), DType.Q4));
        assertTrue(WorkingTypes.hasKernel(new WorkingTypes(DType.BF16, DType.F32), DType.Q4));
        assertTrue(WorkingTypes.hasKernel(new WorkingTypes(DType.F32, DType.I8), DType.Q4));
    }

    @Test
    public void aManualChoiceThatIsSlowerIsReported() {
        MemoryEstimate e = new MemoryEstimate(1L << 30, 0, 1L << 30);
        List<String> risks = WorkingTypes.risks(new WorkingTypes(DType.F32, DType.F32), DType.Q4, e, ROOMY);
        assertEquals(risks.toString(), 1, risks.size());
        assertTrue(risks.get(0), risks.get(0).contains("slower than automatic"));
        assertTrue(WorkingTypes.risks(WorkingTypes.automatic(DType.Q4), DType.Q4, e, ROOMY).isEmpty());
    }

    @Test
    public void aManualChoiceThatDoesNotFitIsReported() {
        MemoryEstimate e = new MemoryEstimate(1L << 30, 8L << 30, 1L << 30);
        List<String> risks = WorkingTypes.risks(
            WorkingTypes.automatic(DType.Q4),
            DType.Q4,
            e,
            new MemoryBudget.Available(64L << 30, 1L << 30, 1L << 40)
        );
        assertEquals(risks.toString(), 1, risks.size());
        assertTrue(risks.get(0), risks.get(0).startsWith("does not fit"));
    }
}
