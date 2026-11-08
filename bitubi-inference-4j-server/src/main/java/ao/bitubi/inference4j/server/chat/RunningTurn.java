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
package ao.bitubi.inference4j.server.chat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One live generation of a conversation. It lives independently of any HTTP connection; viewers subscribe and leave. */
public final class RunningTurn {
    public sealed interface Event permits Snapshot, Final {}

    /** The whole answer so far (not a delta): late and re-attached viewers need no replay. */
    public record Snapshot(String text, int tokens) implements Event {}

    /** The turn is over: the stored assistant message, or the error that ended it (the message holds what was generated). */
    public record Final(Conversation.Msg message, Throwable error) implements Event {}

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final UUID id = UUID.randomUUID();
    private final UUID conversationId;
    private final long startedNanos = System.nanoTime();
    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private int tokens;
    private Final end;
    private volatile long lastSubscriberLeftNanos;
    final CompletableFuture<Void> primed = new CompletableFuture<>();
    final CompletableFuture<Void> userStored = new CompletableFuture<>();
    /** Set when send() reported a failure to its caller, so the run thread has nothing left to tell. */
    final java.util.concurrent.atomic.AtomicBoolean rejected = new java.util.concurrent.atomic.AtomicBoolean();

    RunningTurn(UUID conversationId) {
        this.conversationId = conversationId;
        this.lastSubscriberLeftNanos = startedNanos;
    }

    public UUID id() {
        return id;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public synchronized String text() {
        return text.toString();
    }

    public synchronized boolean finished() {
        return end != null;
    }

    public synchronized Conversation.Msg result() {
        return end == null ? null : end.message();
    }

    /** Delivers the current state to the listener at once, then every later event, on the generating thread. */
    public Subscription subscribe(Consumer<Event> listener) {
        synchronized (this) {
            subscribers.add(listener);
            listener.accept(end != null ? end : new Snapshot(text.toString(), tokens));
        }
        return () -> {
            if (subscribers.remove(listener)) lastSubscriberLeftNanos = System.nanoTime();
        };
    }

    boolean abandonedFor(long nanos) {
        return subscribers.isEmpty() && !finishedNow() && System.nanoTime() - lastSubscriberLeftNanos >= nanos;
    }

    private synchronized boolean finishedNow() {
        return end != null;
    }

    synchronized void append(String piece) {
        text.append(piece);
        tokens++;
    }

    synchronized void publishSnapshot() {
        Snapshot s = new Snapshot(text.toString(), tokens);
        for (Consumer<Event> l : subscribers) safely(l, s);
    }

    synchronized void finish(Final f) {
        end = f;
        for (Consumer<Event> l : subscribers) safely(l, f);
    }

    synchronized String currentText() {
        return text.toString();
    }

    private static void safely(Consumer<Event> l, Event e) {
        try {
            l.accept(e);
        } catch (RuntimeException ignored) {
            // a broken viewer must not stop the generation
        }
    }

    boolean awaitUserStored() {
        try {
            userStored.get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
