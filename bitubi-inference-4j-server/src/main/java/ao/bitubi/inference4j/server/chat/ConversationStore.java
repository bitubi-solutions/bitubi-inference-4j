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
import java.util.Optional;
import java.util.UUID;

/** Where conversations live. The first extension point of the server: an enterprise store can replace the default. */
public interface ConversationStore {
    /** Newest {@code updatedAt} first; entries that cannot be read are skipped. */
    List<Conversation> list();

    Optional<Conversation> load(UUID id);

    /** Atomic. A failure does not throw: it flips {@link #writable()} and sets {@link #lastError()}. */
    void save(Conversation conversation);

    void delete(UUID id);

    /**
     * Changes a stored conversation: load, apply {@code change}, save. Empty (and nothing is written) when there is no such
     * conversation. Concurrent updates of one conversation do not lose each other's changes.
     */
    default Optional<Conversation> update(UUID id, java.util.function.UnaryOperator<Conversation> change) {
        synchronized (IdLocks.of(id)) {
            Optional<Conversation> c = load(id);
            if (c.isEmpty()) return c;
            Conversation next = change.apply(c.get());
            save(next);
            return Optional.of(next);
        }
    }

    /** False after a failed save, true again after a successful one. */
    boolean writable();

    Optional<String> lastError();
}
