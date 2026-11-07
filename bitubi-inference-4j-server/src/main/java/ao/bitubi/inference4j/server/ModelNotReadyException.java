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
package ao.bitubi.inference4j.server;

/** The model is still loading or failed to load. */
public final class ModelNotReadyException extends RuntimeException {
    private final transient ModelHost.Status status;

    public ModelNotReadyException(ModelHost.Status status) {
        super("model is " + status.state().name().toLowerCase() + ": " + status.detail());
        this.status = status;
    }

    public ModelHost.Status status() {
        return status;
    }
}
