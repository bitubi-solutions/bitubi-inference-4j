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

import ao.bitubi.inference4j.safetensors.DType;
import java.util.ArrayList;
import java.util.List;

/**
 * The types the engine computes with: {@code memory} for the KV cache and intermediate data, {@code activations}
 * for the quantization of the data fed to the matrix products. The weight type comes from the model file.
 */
record WorkingTypes(DType memory, DType activations) {

    /** The choice that is fastest on both backends for the given weights. */
    static WorkingTypes automatic(DType weights) {
        return weights == DType.Q4 ? new WorkingTypes(DType.F32, DType.I8) : new WorkingTypes(DType.F32, DType.F32);
    }

    /** Whether a kernel exists for these types and these weights; without one the model cannot run. */
    static boolean hasKernel(WorkingTypes t, DType weights) {
        boolean memory = t.memory() == DType.F32 || t.memory() == DType.BF16;
        boolean activations = t.activations() == DType.F32 || (t.activations() == DType.I8 && weights == DType.Q4);
        return memory && activations;
    }

    /** One sentence per way a manual choice is worse than the automatic one. Empty when there is none. */
    static List<String> risks(WorkingTypes chosen, DType weights, MemoryEstimate estimate, MemoryBudget.Available available) {
        List<String> risks = new ArrayList<>();
        for (String problem : MemoryBudget.problems(estimate, available))
            risks.add("does not fit: " + problem);
        WorkingTypes automatic = automatic(weights);
        if (!chosen.equals(automatic)) {
            risks.add("slower than automatic (" + automatic + ") for " + weights + " weights: " + chosen);
        }
        return risks;
    }
}
