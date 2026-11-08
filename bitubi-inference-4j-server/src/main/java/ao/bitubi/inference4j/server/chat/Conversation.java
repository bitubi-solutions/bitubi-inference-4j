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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A stored conversation: the JSON file {@code <data-dir>/conversations/{id}.json}. Immutable; the {@code with*} methods copy. */
public record Conversation(
    int version,
    UUID id,
    String title,
    Instant createdAt,
    Instant updatedAt,
    String model,
    Settings settings,
    List<Msg> messages
) {
    public static final int VERSION = 1;
    private static final int TITLE_LENGTH = 40;

    public record Settings(float temperature, int maxNewTokens, String system) {}

    /** {@code role} is user or assistant; {@code status} is null, pending, stopped or interrupted. */
    public record Msg(
        String role,
        String text,
        Instant at,
        String finishReason,
        Integer promptTokens,
        Integer generatedTokens,
        Double tokensPerSecond,
        String status
    ) {
        public static Msg user(String text, Instant at, String status) {
            return new Msg("user", text, at, null, null, null, null, status);
        }

        public Msg withStatus(String newStatus) {
            return new Msg(role, text, at, finishReason, promptTokens, generatedTokens, tokensPerSecond, newStatus);
        }
    }

    public Conversation {
        messages = List.copyOf(messages);
        if (title == null) title = "";
    }

    public static Conversation create(UUID id, String model, Settings settings, Instant now) {
        return new Conversation(VERSION, id, "", now, now, model, settings, List.of());
    }

    /** Appends a message; the first user message names a conversation that has no title yet. */
    public Conversation withMessage(Msg m, Instant now) {
        List<Msg> next = new ArrayList<>(messages);
        next.add(m);
        String t = title;
        if (t.isBlank() && "user".equals(m.role())) t = titleFrom(m.text());
        return new Conversation(version, id, t, createdAt, now, model, settings, next);
    }

    public Conversation withLastMessage(Msg m, Instant now) {
        List<Msg> next = new ArrayList<>(messages);
        if (next.isEmpty()) next.add(m);
        else next.set(next.size() - 1, m);
        return new Conversation(version, id, title, createdAt, now, model, settings, next);
    }

    public Conversation withTitle(String newTitle, Instant now) {
        return new Conversation(version, id, newTitle, createdAt, now, model, settings, messages);
    }

    public Conversation withSettings(Settings s, Instant now) {
        return new Conversation(version, id, title, createdAt, now, model, s, messages);
    }

    static String titleFrom(String text) {
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_LENGTH ? oneLine : oneLine.substring(0, TITLE_LENGTH).stripTrailing();
    }
}
