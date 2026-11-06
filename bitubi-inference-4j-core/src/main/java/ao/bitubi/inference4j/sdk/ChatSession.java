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

    /**
     * Sends a user message and waits for the whole answer.
     *
     * <p>If a call is cut short (a stream closed early, or a stop sequence), the model's KV cache keeps what it had
     * generated up to that point, which can be a few tokens more than the text you received; {@link #history()}
     * holds the text you received.
     */
    ChatResponse send(String userText);

    /**
     * Like {@link #stream(String)} but returns a {@link Generation}: the tokens, the outcome and
     * {@link Generation#cancel()} from any thread.
     */
    Generation start(String userText);

    /** Like {@link #start(String)} with settings for this call only; the session's defaults are not changed. */
    Generation start(String userText, GenerationSettings settings);

    /**
     * Sends a user message and streams the answer. The session is busy from the first read until the stream is closed
     * or ends; closing it early stops the generation and the session can be used again. See {@link Bi4j#stream}.
     */
    Stream<Token> stream(String userText);

    /** Like {@link #send(String)} with settings for this call only; the session's defaults are not changed. */
    ChatResponse send(String userText, GenerationSettings settings);

    /**
     * @param message a message with role USER
     * @throws UnsupportedInputException the message has an input the model does not read
     * @throws ContextLengthExceededException the session plus this message do not fit the context window
     * @throws SessionBusyException another call on this session is running
     */
    ChatResponse send(Message message);

    /** Like {@link #send(Message)} with settings for this call only; the session's defaults are not changed. */
    ChatResponse send(Message message, GenerationSettings settings);

    /**
     * Feeds earlier messages into this fresh session without generating anything, so that the next {@link #send}
     * continues that conversation (for example after a restart). They also become part of {@link #history()}.
     *
     * @param earlier USER and ASSISTANT messages, oldest first; the session's own system message comes from its options
     * @return the tokens processed
     * @throws IllegalStateException the session is temporary or already holds a conversation
     * @throws IllegalArgumentException a message has the role SYSTEM
     * @throws ContextLengthExceededException the messages do not fit; the session is left unchanged
     */
    int restore(List<Message> earlier);

    /** Tokens this session holds in the model's KV cache: its history and answers; 0 for a temporary session. */
    int contextTokens();

    /** The room left for new tokens up to the context length of the {@link Bi4j}. */
    int contextRemaining();

    /** The user and assistant messages so far, oldest first; empty for a temporary session. Immutable copy. */
    List<Message> history();

    /** The id of this session in the engine; it also names its KV page files. */
    UUID id();

    /** Frees the session's KV cache and deletes its page files. Safe to call more than once. */
    @Override
    void close();
}
