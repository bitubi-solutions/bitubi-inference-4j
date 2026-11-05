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
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A conversation with a model. The history stays in the model's KV cache, so each turn only processes the new
 * message. One call at a time per session; calls from different threads on one session are rejected with
 * {@link SessionBusyException}, while different sessions run in parallel.
 *
 * <p>A persistent session keeps its KV cache until {@link #close()}. A temporary session
 * ({@code SessionOptions.Builder#temporary()}) frees it after every call and starts each call from its system message.
 */
public interface ChatSession extends AutoCloseable {

    /** Sends a user message and waits for the whole answer. */
    ChatResponse send(String userText);

    /**
     * Sends a user message and streams the answer. The session is busy from the first read until the stream is closed
     * or ends; closing it early stops the generation and the session can be used again. See {@link Bi4j#stream}.
     */
    Stream<Token> stream(String userText);

    /**
     * @param message a message with role USER
     * @throws UnsupportedInputException the message has an input the model does not read
     * @throws ContextLengthExceededException the session plus this message do not fit the context window
     * @throws SessionBusyException another call on this session is running
     */
    ChatResponse send(Message message);

    /** The user and assistant messages so far, oldest first; empty for a temporary session. Immutable copy. */
    List<Message> history();

    UUID id();

    /** Frees the session's KV cache and deletes its page files. Safe to call more than once. */
    @Override
    void close();
}
