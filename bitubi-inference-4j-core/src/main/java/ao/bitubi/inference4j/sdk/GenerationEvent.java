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

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** JFR event emitted when a generation ends. */
@Name("ao.bitubi.inference4j.Generation")
@Label("Generation")
@Category({ "bitubi-inference-4j" })
final class GenerationEvent extends Event {
    @Label("Session")
    String session;

    @Label("Prompt tokens")
    int promptTokens;

    @Label("Generated tokens")
    int generatedTokens;

    @Label("Time to first token (ms)")
    long timeToFirstTokenMillis;

    @Label("Tokens per second")
    double tokensPerSecond;

    @Label("Finish reason")
    String finishReason;
}
