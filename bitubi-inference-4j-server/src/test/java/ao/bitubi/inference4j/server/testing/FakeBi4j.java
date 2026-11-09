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
package ao.bitubi.inference4j.server.testing;

import ao.bitubi.inference4j.sdk.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** A scripted {@link Bi4j}: streams canned pieces with an optional delay, honours cancel, busy and context limits. */
public final class FakeBi4j implements Bi4j {
    private volatile List<String> script = List.of("Hello");
    private volatile long delayMs;
    private volatile long startDelayMs;
    private volatile GenerationSettings lastSettings;
    private volatile int failIndex = -1;
    private volatile RuntimeException failure;
    private volatile int contextLimit = 4096;
    private final AtomicInteger cancels = new AtomicInteger();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile java.util.concurrent.CountDownLatch restoreGate;

    public FakeBi4j script(String... pieces) {
        this.script = List.of(pieces);
        return this;
    }

    /** Waits this long before the first token (the prompt is being processed); the context check comes after it. */
    public FakeBi4j startDelayMs(long ms) {
        this.startDelayMs = ms;
        return this;
    }

    /** The settings of the most recent session turn (null before any). */
    public GenerationSettings lastSettings() {
        return lastSettings;
    }

    public FakeBi4j delayMs(long ms) {
        this.delayMs = ms;
        return this;
    }

    public FakeBi4j failAt(int index, RuntimeException e) {
        this.failIndex = index;
        this.failure = e;
        return this;
    }

    public FakeBi4j contextLimit(int tokens) {
        this.contextLimit = tokens;
        return this;
    }

    /** Makes {@code restore} wait on the latch, to test what happens while a prefill is slow. */
    public FakeBi4j blockRestoreOn(java.util.concurrent.CountDownLatch gate) {
        this.restoreGate = gate;
        return this;
    }

    public int cancelCount() {
        return cancels.get();
    }

    public int liveSessions() {
        return (int) sessions.stream().filter(s -> !s.closed).count();
    }

    public boolean isClosed() {
        return closed;
    }

    private static int estimate(String text) {
        return text.length() / 4 + 1;
    }

    @Override
    public ChatRequestBuilder chat() {
        throw new UnsupportedOperationException("the fake does not build fluent requests");
    }

    @Override
    public ChatResponse generate(ChatRequest request) {
        try (Generation g = start(request)) {
            g.tokens().forEach(t -> {});
            return g.result().orElseThrow();
        }
    }

    @Override
    public Generation start(ChatRequest request) {
        Session temp = new Session(UUID.randomUUID(), true);
        sessions.add(temp);
        String prompt = request.messages().get(request.messages().size() - 1).text();
        Gen g = new Gen(temp, prompt, request.settings());
        g.closeSession = true;
        return g;
    }

    @Override
    public Stream<Token> stream(ChatRequest request) {
        return start(request).tokens();
    }

    @Override
    public ChatSession newSession() {
        return newSession(o -> {});
    }

    @Override
    public ChatSession newSession(Consumer<SessionOptions.Builder> options) {
        SessionOptions.Builder b = new SessionOptions.Builder();
        options.accept(b);
        Session s = new Session(UUID.randomUUID(), b.build().temporary());
        sessions.add(s);
        return s;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(Set.of(Modality.TEXT), true, false, contextLimit);
    }

    @Override
    public ModelInfo info() {
        return new ModelInfo("fake/model", "fake", "F32", "F32", "F32", 0L, contextLimit);
    }

    @Override
    public float[] embed(String text) {
        throw new UnsupportedOperationException("the fake has no embeddings");
    }

    @Override
    public void close() {
        closed = true;
        sessions.forEach(Session::close);
    }

    private final class Session implements ChatSession {
        final UUID id;
        final boolean temporary;
        final AtomicBoolean busy = new AtomicBoolean();
        final List<Message> history = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        Session(UUID id, boolean temporary) {
            this.id = id;
            this.temporary = temporary;
        }

        @Override
        public ChatResponse send(String userText) {
            return send(userText, GenerationSettings.defaults());
        }

        @Override
        public Generation start(String userText) {
            return start(userText, GenerationSettings.defaults());
        }

        @Override
        public Generation start(String userText, GenerationSettings settings) {
            if (!busy.compareAndSet(false, true)) throw new SessionBusyException(id);
            lastSettings = settings;
            return new Gen(this, userText, settings);
        }

        @Override
        public Stream<Token> stream(String userText) {
            return start(userText).tokens();
        }

        @Override
        public ChatResponse send(String userText, GenerationSettings settings) {
            try (Generation g = start(userText, settings)) {
                g.tokens().forEach(t -> {});
                return g.result().orElseThrow();
            }
        }

        @Override
        public ChatResponse send(Message message) {
            return send(message.text());
        }

        @Override
        public ChatResponse send(Message message, GenerationSettings settings) {
            return send(message.text(), settings);
        }

        @Override
        public int restore(List<Message> earlier) {
            if (restoreGate != null) {
                try {
                    restoreGate.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            history.addAll(earlier);
            return earlier.stream().mapToInt(m -> estimate(m.text())).sum();
        }

        @Override
        public int contextTokens() {
            return history.stream().mapToInt(m -> estimate(m.text())).sum();
        }

        @Override
        public int contextRemaining() {
            return contextLimit - contextTokens();
        }

        @Override
        public List<Message> history() {
            return List.copyOf(history);
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final class Gen implements Generation {
        final Session session;
        final String prompt;
        final GenerationSettings settings;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        volatile ChatResponse result;
        volatile boolean exhausted;
        volatile boolean closeSession;

        Gen(Session session, String prompt, GenerationSettings settings) {
            this.session = session;
            this.prompt = prompt;
            this.settings = settings;
        }

        @Override
        public Stream<Token> tokens() {
            Iterator<Token> it = new Iterator<>() {
                int i;
                boolean checked;

                @Override
                public boolean hasNext() {
                    if (!checked) {
                        checked = true;
                        if (startDelayMs > 0) {
                            try {
                                Thread.sleep(startDelayMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        int held = session.contextTokens();
                        int add = estimate(prompt);
                        if (held + add > contextLimit) {
                            fail();
                            throw new ContextLengthExceededException(session.id, held, add, contextLimit);
                        }
                    }
                    if (cancelled.get() || i >= script.size() || i >= settings.maxNewTokens()) {
                        exhausted = !cancelled.get();
                        finish();
                        return false;
                    }
                    return true;
                }

                @Override
                public Token next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    sleep(delayMs); // a cancel during the wait still delivers this token: the stream ends at the next hasNext
                    if (i == failIndex) {
                        fail();
                        throw failure;
                    }
                    String piece = script.get(i);
                    text.append(piece);
                    return new Token(piece, i++);
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false).onClose(this::finish);
        }

        private void fail() {
            if (finished.compareAndSet(false, true)) {
                session.busy.set(false);
                if (closeSession) session.close();
            }
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            FinishReason reason = exhausted ? FinishReason.STOP : FinishReason.CANCELLED;
            if (reason == FinishReason.CANCELLED) cancels.incrementAndGet(); // counts generations that ended early
            result = new ChatResponse(
                text.toString(),
                reason,
                new Usage(estimate(prompt), text.length(), Duration.ZERO, 0)
            );
            if (!session.temporary) {
                session.history.add(Message.user(prompt));
                session.history.add(Message.assistant(text.toString()));
            }
            session.busy.set(false);
            if (closeSession) session.close();
        }

        @Override
        public Optional<ChatResponse> result() {
            return Optional.ofNullable(result);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public void close() {
            cancelled.set(true);
            finish();
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
