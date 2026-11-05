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

/**
 * Options of a {@link ChatSession}.
 *
 * @param system system message, or null for none
 * @param temporary if true, the session forgets everything after each call and frees its memory
 * @param defaults generation settings used when a call does not give its own
 */
public record SessionOptions(String system, boolean temporary, GenerationSettings defaults) {

    public SessionOptions {
        java.util.Objects.requireNonNull(defaults, "defaults");
    }

    /** Mutable collector for the options; each setter returns the builder. */
    public static final class Builder {
        private String system;
        private boolean temporary;
        private GenerationSettings defaults = GenerationSettings.defaults();

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder temporary() {
            this.temporary = true;
            return this;
        }

        public Builder maxNewTokens(int maxNewTokens) {
            defaults = defaults.withMaxNewTokens(maxNewTokens);
            return this;
        }

        public Builder temperature(float temperature) {
            defaults = defaults.withTemperature(temperature);
            return this;
        }

        public Builder stop(String... stop) {
            defaults = defaults.withStop(List.of(stop));
            return this;
        }

        public SessionOptions build() {
            return new SessionOptions(system, temporary, defaults);
        }
    }
}
