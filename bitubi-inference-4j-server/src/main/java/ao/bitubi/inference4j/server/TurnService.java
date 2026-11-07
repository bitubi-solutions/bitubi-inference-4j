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

    public TurnService(ModelHost host, SessionRegistry sessions) {
        this.host = host;
        this.sessions = sessions;
    }

    public Turn start(Optional<UUID> sessionId, List<Message> messages, GenerationSettings settings) {
        Bi4j bi4j = host.require();
        if (sessionId.isEmpty()) {
            UUID id = UUID.randomUUID();
            return new Turn(id, bi4j.start(new ChatRequest(messages, settings)), () -> {});
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
            ChatSession session = sessions.getOrCreate(id, bi4j, system, earlier);
            Generation g = session.start(String.join("\n\n", turn), settings);
            return new Turn(id, g, () -> active.remove(id));
        } catch (RuntimeException e) {
            active.remove(id);
            throw e;
        }
    }
}
