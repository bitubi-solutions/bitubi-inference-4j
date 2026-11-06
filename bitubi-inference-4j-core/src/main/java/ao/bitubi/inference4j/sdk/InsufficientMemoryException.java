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

/** The model does not fit the available memory or disk. */
public final class InsufficientMemoryException extends Bi4jException {

    /** @param message what does not fit and what to change */
    public InsufficientMemoryException(String message) {
        super(message);
    }

    /**
     * @param message what does not fit and what to change
     * @param cause the underlying error
     */
    public InsufficientMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
