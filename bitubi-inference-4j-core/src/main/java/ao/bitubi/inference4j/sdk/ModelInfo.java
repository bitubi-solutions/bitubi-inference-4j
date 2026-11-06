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
 * What a loaded model is, in plain values (no engine types).
 *
 * @param name the id given to the builder, or the folder or file name of a local model
 * @param architecture the model family, for example {@code QWEN2}
 * @param weightType how the weights are stored, for example {@code Q4}
 * @param memoryType the type of the KV cache and intermediate data
 * @param activationType the type the activations are quantized to
 * @param weightBytes size of the weights
 * @param contextLength the most tokens a session may hold
 */
public record ModelInfo(String name, String architecture, String weightType, String memoryType, String activationType, long weightBytes,
    int contextLength) {
}
