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

import java.util.List;
import java.util.Objects;

/**
 * How one generation runs. Validated when built, so a bad value fails at the call site and not inside the engine.
 *
 * @param maxNewTokens most new tokens to generate; the prompt does not count against it
 * @param temperature 0 picks the most likely token every time; higher values are more varied
 * @param stop texts that end the generation; the matching text is not part of the response
 */
public record GenerationSettings(int maxNewTokens, float temperature, List<String> stop) {

    public GenerationSettings {
        if (maxNewTokens <= 0) throw new IllegalArgumentException("maxNewTokens must be positive: " + maxNewTokens);
        if (Float.isNaN(temperature) || temperature < 0) {
            throw new IllegalArgumentException("temperature must be 0 or more: " + temperature);
        }
        stop = List.copyOf(stop); // rejects a null list or a null element
        for (String s : stop) {
            Objects.requireNonNull(s, "stop");
            if (s.isEmpty()) throw new IllegalArgumentException("a stop sequence cannot be empty");
        }
    }

    /** 512 new tokens, temperature 0.7, no stop sequences. */
    public static GenerationSettings defaults() {
        return new GenerationSettings(512, 0.7f, List.of());
    }

    public GenerationSettings withMaxNewTokens(int maxNewTokens) {
        return new GenerationSettings(maxNewTokens, temperature, stop);
    }

    public GenerationSettings withTemperature(float temperature) {
        return new GenerationSettings(maxNewTokens, temperature, stop);
    }

    public GenerationSettings withStop(List<String> stop) {
        return new GenerationSettings(maxNewTokens, temperature, stop);
    }
}
