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
package ao.bitubi.inference4j.sdk;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One turn of a conversation: who said it and what, as one or more {@link Part parts}. */
public record Message(Role role, List<Part> parts) {

    /** Who says a message. */
    public enum Role {
        /** Instructions for the model. */
        SYSTEM,
        /** The person talking to the model. */
        USER,
        /** The model. */
        ASSISTANT
    }

    /** Copies the parts; rejects an empty list. */
    public Message {
        Objects.requireNonNull(role, "role");
        parts = List.copyOf(parts);
        if (parts.isEmpty()) throw new IllegalArgumentException("a message needs at least one part");
    }

    /** A system message with one text part. */
    public static Message system(String text) {
        return new Message(Role.SYSTEM, List.of(Part.text(text)));
    }

    /** A user message with one text part. */
    public static Message user(String text) {
        return new Message(Role.USER, List.of(Part.text(text)));
    }

    /** A user message made of the given parts. */
    public static Message user(Part... parts) {
        return new Message(Role.USER, List.of(parts));
    }

    /** An assistant message with one text part. */
    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(Part.text(text)));
    }

    /** The modalities this message uses, to check against what a model accepts before any work starts. */
    public Set<Modality> modalities() {
        Set<Modality> m = EnumSet.noneOf(Modality.class);
        parts.forEach(p -> m.add(p.modality()));
        return m;
    }

    /** The text parts joined with newlines; non-text parts are skipped. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof Part.Text(String t)) sb.append(sb.isEmpty() ? "" : "\n").append(t);
        }
        return sb.toString();
    }
}
