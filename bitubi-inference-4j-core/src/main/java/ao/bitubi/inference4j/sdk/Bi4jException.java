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
 * Base of every error the SDK raises on purpose. The message says what happened and what to do about it. The
 * hierarchy is sealed, so a {@code switch} over it needs no default branch.
 */
public abstract sealed class Bi4jException extends RuntimeException permits ModelNotFoundException, UnsupportedModelException,
    InsufficientMemoryException, EngineSetupException, SessionBusyException, ContextLengthExceededException, UnsupportedInputException {

    /** @param message what happened and what to do about it */
    protected Bi4jException(String message) {
        super(message);
    }

    /**
     * @param message what happened and what to do about it
     * @param cause the underlying error
     */
    protected Bi4jException(String message, Throwable cause) {
        super(message, cause);
    }
}
