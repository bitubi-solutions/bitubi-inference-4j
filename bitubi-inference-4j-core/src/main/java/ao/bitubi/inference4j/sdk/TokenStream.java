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

import java.time.Duration;
import java.util.Optional;
import java.util.Spliterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Hands the tokens of a generation to a reader, one at a time. The generation runs on a virtual thread and waits when
 * the reader is slow (a small bounded queue). Nothing starts until the first read. Closing the stream interrupts the
 * generation, which stops at its next token.
 */
final class TokenStream implements Spliterator<Token> {
    private static final Logger logger = LoggerFactory.getLogger(Bi4j.class);
    private static final int QUEUE_SIZE = 16;

    private record End(Throwable error) {
    }

    private final DefaultBi4j owner;
    private final Runnable begin;
    private final Function<Consumer<Token>, ChatResponse> run;
    private final Runnable end;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile ChatResponse result;
    private volatile Thread producer;
    private boolean started;
    private boolean finished;

    private TokenStream(DefaultBi4j owner, Runnable begin, Function<Consumer<Token>, ChatResponse> run, Runnable end) {
        this.owner = owner;
        this.begin = begin;
        this.run = run;
        this.end = end;
    }

    /**
     * @param begin runs on the reader's thread when the stream is first read; it may throw (for example when the session
     *     is busy), and then nothing starts
     * @param run the generation: feeds every token to the consumer and returns the final response
     * @param end always runs exactly once if {@code begin} succeeded, after the generation stopped and before the end
     *     of the stream is seen by the reader
     */
    static Stream<Token> open(DefaultBi4j owner, Runnable begin, Function<Consumer<Token>, ChatResponse> run, Runnable end) {
        return create(owner, begin, run, end).stream();
    }

    /** Like {@link #open} but keeps the object, for the result and for a graceful stop. */
    static TokenStream create(DefaultBi4j owner, Runnable begin, Function<Consumer<Token>, ChatResponse> run, Runnable end) {
        TokenStream s = new TokenStream(owner, begin, run, end);
        owner.streams().add(s);
        return s;
    }

    /** The tokens as a stream; closing it cancels the generation. Read it once. */
    Stream<Token> stream() {
        return StreamSupport.stream(this, false).onClose(this::cancel);
    }

    /** The response, once the generation ended (also after a stop); empty before, and when the generation failed. */
    Optional<ChatResponse> result() {
        return Optional.ofNullable(result);
    }

    /**
     * Asks the generation to stop at its next token. Unlike {@link #cancel()} the reader sees the stream end normally,
     * with the tokens produced so far, and the result says CANCELLED. Safe from any thread, harmless more than once.
     */
    void stop() {
        if (!stopRequested.compareAndSet(false, true)) return;
        Thread t = producer;
        if (t != null) t.interrupt();
    }

    private String closedMessage() {
        return owner.isClosed() ? "Bi4j is closed: the stream was cancelled" : "The stream is closed";
    }

    @Override
    public boolean tryAdvance(Consumer<? super Token> action) {
        if (finished) return false;
        if (cancelled.get()) throw new IllegalStateException(closedMessage());
        if (!started) start();
        Object item;
        try {
            item = queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the reader was interrupted: stop reading and stop generating
            cancel();
            return false;
        }
        if (item instanceof Token t) {
            action.accept(t);
            return true;
        }
        finished = true;
        owner.streams().remove(this);
        Throwable error = ((End) item).error();
        if (cancelled.get() && error == null) throw new IllegalStateException(closedMessage());
        if (error instanceof Bi4jException || error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            throw (RuntimeException) error;
        }
        if (error != null) throw new IllegalStateException("Generation failed: " + error.getMessage(), error);
        return false;
    }

    private void start() {
        begin.run(); // throws to the reader if the stream cannot start
        started = true;
        producer = Thread.ofVirtual().name("bi4j-stream-", 0).unstarted(this::produce);
        producer.start();
    }

    private void produce() {
        Throwable error = null;
        try {
            if (cancelled.get() || stopRequested.get()) { // stopped before it began: never generate
                result = new ChatResponse("", FinishReason.CANCELLED, new Usage(0, 0, Duration.ZERO, 0));
            } else {
                result = run.apply(this::offer);
            }
        } catch (Throwable t) {
            error = t;
        } finally {
            try {
                end.run();
            } catch (Throwable t) {
                if (error == null) error = t;
            }
        }
        Thread.interrupted(); // the flag asked us to stop; it has done its job
        End last = new End(error);
        while (true) {
            try {
                if (queue.offer(last, 100, TimeUnit.MILLISECONDS)) break;
                if (cancelled.get()) { // a full queue means the reader is not waiting; otherwise it must be woken
                    queue.offer(last);
                    break;
                }
            } catch (InterruptedException e) {
                // a stop or a cancel arrived after the generation ended: the end marker must still get through
                if (cancelled.get()) {
                    queue.offer(last);
                    break;
                }
            }
        }
    }

    private void offer(Token token) {
        try {
            queue.put(token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the engine looks at this flag before the next token
        }
    }

    /** Stops the generation and waits (up to 5 s) until it has let go of its session. Safe to call more than once. */
    void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        owner.streams().remove(this);
        Thread t = producer;
        if (t == null) return;
        t.interrupt();
        boolean interrupted = Thread.interrupted(); // an interrupted closer must still wait, then get its flag back
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (t.isAlive()) logger.warn("A stream did not stop within 5 s; its generation is still running");
    }

    @Override
    public Spliterator<Token> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
        return ORDERED | NONNULL;
    }
}
