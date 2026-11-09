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
    static final int DEFAULT_MAX_LIVE = 4;
    private final Map<UUID, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUsed = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final int maxLive;
    private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();

    public SessionRegistry() {
        this(DEFAULT_MAX_LIVE);
    }

    /** {@code maxLive}: how many sessions stay open; over it the least recently used idle one is closed (its KV cache freed). */
    public SessionRegistry(int maxLive) {
        this.maxLive = Math.max(1, maxLive);
    }

    /** A turn is running on this id: it is never evicted. */
    public void markBusy(UUID id) {
        busy.add(id);
        markUsed(id);
    }

    /** The turn ended: the session may be evicted, and a cap that was exceeded while everything was busy shrinks back. */
    public void markIdle(UUID id) {
        busy.remove(id);
        markUsed(id);
        evictOverCap(null);
    }

    public void markUsed(UUID id) {
        lastUsed.put(id, clock.incrementAndGet());
    }

    /**
     * The session for {@code id}; a new one is seeded with {@code system} and {@code earlier} (prefill only). The
     * prefill runs outside the map (it can take minutes): callers hold the id through {@code TurnService}, so two
     * threads never create the same id at once.
     */
    public ChatSession getOrCreate(UUID id, Bi4j bi4j, String system, List<Message> earlier) {
        ChatSession existing = sessions.get(id);
        if (existing != null) return existing;
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
        sessions.put(id, s);
        markUsed(id);
        evictOverCap(id);
        return s;
    }

    private void evictOverCap(UUID keep) {
        while (sessions.size() > maxLive) {
            UUID victim = null;
            long oldest = Long.MAX_VALUE;
            for (UUID id : sessions.keySet()) {
                if (id.equals(keep) || busy.contains(id)) continue;
                long used = lastUsed.getOrDefault(id, 0L);
                if (used < oldest) {
                    oldest = used;
                    victim = id;
                }
            }
            if (victim == null) return; // everything is busy: exceed the cap rather than cut a running answer
            log.info("closing idle session {} (more than {} live sessions)", victim, maxLive);
            remove(victim);
        }
    }

    public Optional<ChatSession> find(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public void remove(UUID id) {
        ChatSession s = sessions.remove(id);
        lastUsed.remove(id);
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
        lastUsed.clear();
    }
}
