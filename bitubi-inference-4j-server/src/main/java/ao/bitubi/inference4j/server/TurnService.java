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
package ao.bitubi.inference4j.server;

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.ChatRequest;
import ao.bitubi.inference4j.sdk.ChatSession;
import ao.bitubi.inference4j.sdk.Generation;
import ao.bitubi.inference4j.sdk.GenerationSettings;
import ao.bitubi.inference4j.sdk.Message;
import ao.bitubi.inference4j.sdk.SessionBusyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts turns. Without a session id the request is temporary (nothing is kept). With one, the id is reserved for
 * the duration of the turn (a second turn gets {@link SessionBusyException}); the messages after the last assistant
 * message are the new turn, the earlier ones only matter when the session is new (the client resends the history).
 */
public final class TurnService {
    private final ModelHost host;
    private final SessionRegistry sessions;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Set<Turn> live = ConcurrentHashMap.newKeySet();

    public TurnService(ModelHost host, SessionRegistry sessions) {
        this.host = host;
        this.sessions = sessions;
    }

    public Turn start(Optional<UUID> sessionId, List<Message> messages, GenerationSettings settings) {
        Bi4j bi4j = host.require();
        if (sessionId.isEmpty()) {
            UUID id = UUID.randomUUID();
            return track(id, bi4j.start(new ChatRequest(messages, settings)), () -> {});
        }
        UUID id = sessionId.get();
        if (!active.add(id)) throw new SessionBusyException(id);
        try {
            int lastAssistant = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).role() == Message.Role.ASSISTANT) lastAssistant = i;
            }
            String system = !messages.isEmpty() && messages.get(0).role() == Message.Role.SYSTEM ? messages.get(0).text() : null;
            List<Message> earlier = new ArrayList<>();
            for (int i = 0; i <= lastAssistant; i++) {
                if (messages.get(i).role() != Message.Role.SYSTEM) earlier.add(messages.get(i));
            }
            List<String> turn = new ArrayList<>();
            for (int i = lastAssistant + 1; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.role() == Message.Role.SYSTEM && i == 0) continue;
                if (m.role() != Message.Role.USER) throw new IllegalArgumentException("the last messages must come from the user");
                turn.add(m.text());
            }
            if (turn.isEmpty()) throw new IllegalArgumentException("the last messages must come from the user");
            // The client is the source of truth when it resends history: if it differs from what the session holds
            // (an edit, or a trim after a context error), rebuild the session from it.
            if (!earlier.isEmpty()) {
                sessions.find(id).ifPresent(existing -> {
                    if (!sameHistory(existing.history(), earlier)) sessions.remove(id);
                });
            }
            ChatSession session = sessions.getOrCreate(id, bi4j, system, earlier);
            Generation g = session.start(String.join("\n\n", turn), settings);
            return track(id, g, () -> active.remove(id));
        } catch (RuntimeException e) {
            active.remove(id);
            throw e;
        }
    }

    private Turn track(UUID id, Generation g, Runnable release) {
        Turn[] box = new Turn[1];
        Turn t = new Turn(id, g, () -> {
            live.remove(box[0]);
            release.run();
        });
        box[0] = t;
        live.add(t);
        return t;
    }

    private static boolean sameHistory(List<Message> held, List<Message> sent) {
        if (held.size() != sent.size()) return false;
        for (int i = 0; i < held.size(); i++) {
            if (held.get(i).role() != sent.get(i).role() || !held.get(i).text().trim().equals(sent.get(i).text().trim())) return false;
        }
        return true;
    }

    /** Cancels every running turn and waits briefly for them to let go, so shutdown does not close sessions under them. */
    public void cancelAll() {
        live.forEach(Turn::cancel);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
