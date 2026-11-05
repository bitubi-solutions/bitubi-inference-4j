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
package ao.bitubi.inference4j.model;

import java.util.UUID;

/** Thrown when a session's tokens plus the new prompt do not fit in the model's context window. */
public class ContextLengthExceededException extends IllegalArgumentException {

    public ContextLengthExceededException(UUID sessionId, int sessionTokens, int promptTokens, int contextLength) {
        super(
            "Session "
                + sessionId
                + " holds "
                + sessionTokens
                + " tokens and the prompt adds "
                + promptTokens
                + ", but the context window is "
                + contextLength
                + " tokens. Release the session or start a new one, or shorten the prompt."
        );
    }
}
