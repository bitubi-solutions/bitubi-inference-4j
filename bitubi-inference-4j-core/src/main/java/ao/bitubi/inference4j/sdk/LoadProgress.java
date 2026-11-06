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
 * A step in loading a model, passed to {@code Bi4j.Builder#onProgress}. The callback runs on the thread that calls
 * {@code build()}, so it should be quick.
 *
 * @param phase where the load is
 * @param detail what is being worked on, for example a file name; may be empty
 * @param done bytes done in this phase; 0 when the phase has no byte count
 * @param total bytes expected in this phase; 0 when unknown
 */
public record LoadProgress(Phase phase, String detail, long done, long total) {

    /** The phases of a load, in order. */
    public enum Phase {
        /** Fetching the model files; only when the model is given as an id and is not local yet. */
        DOWNLOADING,
        /** Reading the model's headers, choosing the working types and checking the memory. */
        CHECKING,
        /** Reading the weights and building the model. */
        LOADING,
        /** The model is ready; {@code build()} is about to return. */
        READY
    }
}
