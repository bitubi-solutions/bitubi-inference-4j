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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Conversations kept in memory only ({@code --no-history}, and tests). */
public final class MemoryConversationStore implements ConversationStore {
    private final Map<UUID, Conversation> map = new ConcurrentHashMap<>();

    @Override
    public List<Conversation> list() {
        return map.values().stream().sorted(Comparator.comparing(Conversation::updatedAt).reversed()).toList();
    }

    @Override
    public Optional<Conversation> load(UUID id) {
        return Optional.ofNullable(map.get(id));
    }

    @Override
    public void save(Conversation c) {
        map.put(c.id(), c);
    }

    @Override
    public void delete(UUID id) {
        map.remove(id);
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public Optional<String> lastError() {
        return Optional.empty();
    }
}
