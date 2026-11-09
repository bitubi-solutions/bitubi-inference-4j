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

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.ChatResponse;
import ao.bitubi.inference4j.sdk.FinishReason;
import ao.bitubi.inference4j.sdk.GenerationSettings;
import ao.bitubi.inference4j.sdk.Message;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.Turn;
import ao.bitubi.inference4j.server.TurnService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the UI's generations. Each turn has its own virtual thread, so closing the browser does not stop it: it ends when
 * the model is done, on Stop, or when nobody has watched for {@code orphanTimeout}. The result is always stored.
 */
public final class ChatTurns implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatTurns.class);
    private static final long PRIME_WAIT_MS = 300;

    private final TurnService turns;
    private final ConversationStore store;
    private final ModelHost host;
    private final Clock clock;
    private final long orphanNanos;
    private final long snapshotIntervalNanos;
    private final Map<UUID, RunningTurn> running = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private final Map<UUID, Turn> sdkTurns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("bi4j-orphan-watch").unstarted(r));

    public ChatTurns(TurnService turns, ConversationStore store, ModelHost host, Clock clock, Duration orphanTimeout, int snapshotsPerSecond) {
        this.turns = turns;
        this.store = store;
        this.host = host;
        this.clock = clock;
        this.orphanNanos = orphanTimeout.toNanos();
        this.snapshotIntervalNanos = 1_000_000_000L / Math.max(1, snapshotsPerSecond);
        watcher.scheduleWithFixedDelay(this::cancelOrphans, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts a turn for {@code text}. The context and busy checks happen first (the same exceptions as
     * {@link TurnService#start}); nothing is stored when they fail.
     */
    public RunningTurn send(UUID conversationId, String text, Conversation.Settings settings) {
        Bi4j bi4j = host.require();
        Conversation c = store.load(conversationId).orElseGet(() -> Conversation.create(conversationId, bi4j.info().name(), settings, clock.instant()));
        List<Message> messages = new ArrayList<>();
        if (settings.system() != null && !settings.system().isBlank()) messages.add(Message.system(settings.system()));
        for (Conversation.Msg m : c.messages()) {
            if ("pending".equals(m.status()) || m.text() == null || m.text().isEmpty()) continue; // a lost turn is not replayed
            messages.add("user".equals(m.role()) ? Message.user(m.text()) : Message.assistant(m.text()));
        }
        messages.add(Message.user(text));
        GenerationSettings gs = GenerationSettings.defaults().withTemperature(settings.temperature()).withMaxNewTokens(settings.maxNewTokens());

        // Registered before the session is reserved: a second send during a slow restore finds something to link to.
        RunningTurn rt = new RunningTurn(conversationId);
        if (running.putIfAbsent(conversationId, rt) != null) throw new ao.bitubi.inference4j.sdk.SessionBusyException(conversationId);
        Turn turn;
        try {
            turn = turns.start(Optional.of(conversationId), messages, gs);
        } catch (RuntimeException e) {
            running.remove(conversationId, rt);
            rt.rejected.set(true);
            rt.userStored.complete(null);
            rt.finish(new RunningTurn.Final(new Conversation.Msg("assistant", "", clock.instant(), null, null, null, null, "interrupted"), e));
            throw e;
        }
        sdkTurns.put(rt.id(), turn);
        Conversation base = c.withSettings(settings, clock.instant());
        Thread.ofVirtual().name("bi4j-turn-" + rt.id()).start(() -> run(rt, turn, base, text));
        try {
            rt.primed.get(PRIME_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowStart) {
            // the model is still working on the prompt: accept; a later failure becomes an error event
        } catch (ExecutionException e) {
            rt.rejected.set(true);
            running.remove(conversationId, rt);
            sdkTurns.remove(rt.id());
            rt.userStored.complete(null);
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            turn.cancel();
        }
        Conversation.Msg sent = Conversation.Msg.user(text, clock.instant(), "pending");
        List<Conversation.Msg> before = c.messages();
        boolean retry = !before.isEmpty() && isLostCopyOf(before.get(before.size() - 1), text);
        java.util.function.UnaryOperator<Conversation> addSent = cur -> retry ? cur.withSettings(settings, clock.instant()).withLastMessage(sent, clock.instant()) : cur.withSettings(settings, clock.instant()).withMessage(sent, clock.instant());
        if (store.update(conversationId, addSent).isEmpty()) store.save(addSent.apply(base));
        rt.userStored.complete(null);
        return rt;
    }

    /** Retry after a restart sends the same text again: it replaces the lost message instead of repeating it. */
    private static boolean isLostCopyOf(Conversation.Msg m, String text) {
        return "user".equals(m.role()) && "pending".equals(m.status()) && text.equals(m.text());
    }

    private void run(RunningTurn rt, Turn turn, Conversation base, String userText) {
        Conversation.Msg assistant;
        Throwable error = null;
        try (turn) {
            boolean primed = false;
            try {
                turn.prime();
                rt.primed.complete(null);
                primed = true;
            } catch (RuntimeException e) {
                rt.primed.completeExceptionally(e);
                rt.awaitUserStored();
                if (rt.rejected.get()) return; // send() reported it: nothing was stored
                log.warn("generation failed while reading the prompt: {}", e.toString());
                error = e;
            }
            if (primed) {
                long last = System.nanoTime();
                try {
                    Iterator<ao.bitubi.inference4j.sdk.Token> it = turn.tokens();
                    while (it.hasNext()) {
                        rt.append(it.next().text());
                        long now = System.nanoTime();
                        if (now - last >= snapshotIntervalNanos) {
                            rt.publishSnapshot();
                            last = now;
                        }
                    }
                    ChatResponse r = turn.result();
                    // a server shutting down interrupts the answer (Retry offered); the user's Stop is "stopped"
                    String status = r.finishReason() == FinishReason.CANCELLED ? (closing ? "interrupted" : "stopped") : null;
                    assistant = new Conversation.Msg(
                        "assistant",
                        rt.currentText(),
                        clock.instant(),
                        r.finishReason().name(),
                        r.usage().promptTokens(),
                        r.usage().generatedTokens(),
                        r.usage().tokensPerSecond(),
                        status
                    );
                } catch (RuntimeException e) {
                    log.warn("generation failed: {}", e.toString());
                    error = e;
                    assistant = new Conversation.Msg("assistant", rt.currentText(), clock.instant(), null, null, null, null, "interrupted");
                }
            } else {
                assistant = new Conversation.Msg("assistant", "", clock.instant(), null, null, null, null, "interrupted");
            }
            rt.awaitUserStored();
            try {
                Conversation.Msg answer = assistant;
                Optional<Conversation> stored = store.update(base.id(), cur -> {
                    Conversation done = cur;
                    int lastIndex = done.messages().size() - 1;
                    if (lastIndex >= 0 && "user".equals(done.messages().get(lastIndex).role())) {
                        done = done.withLastMessage(done.messages().get(lastIndex).withStatus(null), clock.instant());
                    }
                    return done.withMessage(answer, clock.instant());
                });
                if (stored.isEmpty()) { // deleted while it was answering: it stays deleted
                    log.info("{} was deleted during the answer; not storing it", base.id());
                }
            } catch (RuntimeException e) {
                log.warn("cannot store the answer of {}: {}", base.id(), e.toString());
            }
            rt.finish(new RunningTurn.Final(assistant, error));
        } finally {
            running.remove(base.id(), rt);
            sdkTurns.remove(rt.id());
        }
    }

    public Optional<RunningTurn> running(UUID conversationId) {
        return Optional.ofNullable(running.get(conversationId));
    }

    public Optional<RunningTurn> find(UUID conversationId, UUID turnId) {
        return running(conversationId).filter(t -> t.id().equals(turnId));
    }

    /** Cancels the turn; it ends through the normal path and is stored as stopped. */
    public void stop(UUID conversationId, UUID turnId) {
        find(conversationId, turnId).ifPresent(t -> {
            Turn sdk = sdkTurns.get(t.id());
            if (sdk != null) sdk.cancel();
        });
    }

    private void cancelOrphans() {
        for (RunningTurn t : running.values()) {
            if (t.abandonedFor(orphanNanos)) {
                Turn sdk = sdkTurns.get(t.id());
                if (sdk != null) {
                    log.info("cancelling turn {}: nobody watched for {} ms", t.id(), orphanNanos / 1_000_000);
                    sdk.cancel();
                }
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        watcher.shutdownNow();
        sdkTurns.values().forEach(Turn::cancel);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!running.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
