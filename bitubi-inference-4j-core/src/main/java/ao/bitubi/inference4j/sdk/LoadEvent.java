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

/** JFR event emitted once when a model is loaded. */
@Name("ao.bitubi.inference4j.Load")
@Label("Model load")
@Category({ "bitubi-inference-4j" })
final class LoadEvent extends Event {
    @Label("Model")
    String model;

    @Label("Weight type")
    String weights;

    @Label("Memory type")
    String memory;

    @Label("Activation type")
    String activations;

    @Label("Load time (ms)")
    long loadMillis;

    @Label("Estimated memory (bytes)")
    long estimateBytes;
}
