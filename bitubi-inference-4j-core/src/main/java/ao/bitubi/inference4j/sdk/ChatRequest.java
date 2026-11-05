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

import java.util.List;

/** A chat to run: the messages and how to generate. An immutable value: build it once, send it as often as needed. */
public record ChatRequest(List<Message> messages, GenerationSettings settings) {

    public ChatRequest {
        messages = List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("a chat request needs at least one message");
        java.util.Objects.requireNonNull(settings, "settings");
    }
}
