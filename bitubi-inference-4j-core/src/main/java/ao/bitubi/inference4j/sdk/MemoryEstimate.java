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

/**
 * What a model needs once loaded. {@code weightBytes} are mapped from the file, {@code directBytes} is extra
 * off-heap memory (F16 weights are widened to F32), and {@code kvBytes} is the KV cache of one session at the
 * full context length, kept in page files.
 */
record MemoryEstimate(long weightBytes, long directBytes, long kvBytes) {

    static MemoryEstimate of(ModelInspection m, WorkingTypes types, int contextLength) {
        long kv = 2L * m.layers() * contextLength * m.kvLength() * types.memory().size();
        long direct = m.weights() == ao.bitubi.inference4j.safetensors.DType.F16 ? 2 * m.weightBytes() : 0;
        return new MemoryEstimate(m.weightBytes(), direct, kv);
    }
}
