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

import java.util.Optional;
import java.util.stream.Stream;

/**
 * One answer being generated: its tokens, its outcome, and a way to stop it from any thread.
 *
 * <p>Nothing happens until {@link #tokens()} is first read. A generation is read once.
 *
 * <pre>{@code
 * try (Generation g = chat.start("Tell me a story")) {
 *     g.tokens().forEach(t -> System.out.print(t.text()));   // on this thread
 *     // elsewhere, e.g. when a user presses Stop:  g.cancel()
 *     System.out.println(g.result().orElseThrow().usage());
 * }
 * }</pre>
 */
public interface Generation extends AutoCloseable {

    /**
     * The generated text, token by token. The stream is lazy and can be read once. A failure of the generation is
     * thrown from the read. {@link #cancel()} ends it normally; closing the stream cancels the generation too.
     */
    Stream<Token> tokens();

    /**
     * The outcome: everything generated, why it ended and what it cost. Empty until the generation ended (or when it
     * failed), also set after {@link #cancel()}, with the finish reason CANCELLED. After a cancel the text can be a token
     * or two longer than what the reader of {@link #tokens()} received; it matches what the session's cache holds.
     */
    Optional<ChatResponse> result();

    /**
     * Asks the generation to stop at its next token. Safe from any thread and harmless when repeated or when the
     * generation has ended. The thread reading {@link #tokens()} sees the stream end, without an exception.
     */
    void cancel();

    /** Cancels the generation if it is still running and waits (up to 5 s) until it has let go of its session. */
    @Override
    void close();
}
