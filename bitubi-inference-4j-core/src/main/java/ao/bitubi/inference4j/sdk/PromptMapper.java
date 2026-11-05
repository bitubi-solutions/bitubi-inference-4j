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

import ao.bitubi.inference4j.safetensors.prompt.PromptContext;
import ao.bitubi.inference4j.safetensors.prompt.PromptSupport;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns SDK messages into the engine's prompt. */
final class PromptMapper {

    private PromptMapper() {}

    /** Fails before any work starts if a message carries a kind of input the model does not read. */
    static void checkInputs(Capabilities capabilities, List<Message> messages) {
        Set<Modality> unsupported = EnumSet.noneOf(Modality.class);
        for (Message m : messages)
            unsupported.addAll(m.modalities());
        unsupported.removeAll(capabilities.inputs());
        if (!unsupported.isEmpty()) {
            throw new UnsupportedInputException("this model reads " + names(capabilities.inputs()) + " only; got " + names(unsupported));
        }
    }

    /**
     * @param continuation true when the model already holds the earlier turns in its KV cache: the preamble (system
     *     prompt and the like) is left out
     */
    static PromptContext toPrompt(Optional<PromptSupport> support, List<Message> messages, boolean continuation) {
        if (support.isEmpty()) {
            return PromptContext.of(messages.stream().map(Message::text).collect(Collectors.joining("\n\n")));
        }
        PromptSupport.Builder b = support.get().builder();
        if (continuation) b.stripPreamble();
        for (Message m : messages) {
            switch (m.role()) {
                case SYSTEM -> b.addSystemMessage(m.text());
                case USER -> b.addUserMessage(m.text());
                case ASSISTANT -> b.addAssistantMessage(m.text());
            }
        }
        return b.build();
    }

    private static String names(Set<Modality> modalities) {
        return modalities.stream().sorted().map(Enum::name).collect(Collectors.joining(", "));
    }
}
