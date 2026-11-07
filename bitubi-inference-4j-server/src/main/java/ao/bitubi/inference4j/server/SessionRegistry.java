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
import ao.bitubi.inference4j.sdk.ChatSession;
import ao.bitubi.inference4j.sdk.Message;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Live chat sessions by conversation id (the {@code X-Bi4j-Session} value). */
public final class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private final Map<UUID, ChatSession> sessions = new ConcurrentHashMap<>();

    /** The session for {@code id}; a new one is seeded with {@code system} and {@code earlier} (prefill only). */
    public ChatSession getOrCreate(UUID id, Bi4j bi4j, String system, List<Message> earlier) {
        return sessions.computeIfAbsent(id, k -> {
            ChatSession s = bi4j.newSession(o -> {
                if (system != null) o.system(system);
            });
            if (!earlier.isEmpty()) {
                try {
                    s.restore(earlier);
                } catch (RuntimeException e) {
                    s.close();
                    throw e;
                }
            }
            return s;
        });
    }

    public Optional<ChatSession> find(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public void remove(UUID id) {
        ChatSession s = sessions.remove(id);
        if (s != null) s.close();
    }

    public int size() {
        return sessions.size();
    }

    public void closeAll() {
        sessions.values().forEach(s -> {
            try {
                s.close();
            } catch (RuntimeException e) {
                log.warn("closing session {} failed", s.id(), e);
            }
        });
        sessions.clear();
    }
}
