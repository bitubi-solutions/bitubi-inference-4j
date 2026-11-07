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
package ao.bitubi.inference4j.server.api.openai;

import ao.bitubi.inference4j.sdk.ChatResponse;
import ao.bitubi.inference4j.sdk.FinishReason;
import ao.bitubi.inference4j.sdk.GenerationSettings;
import ao.bitubi.inference4j.sdk.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps OpenAI requests onto SDK messages and settings, and SDK results back. */
public final class OpenAiMapper {
    private OpenAiMapper() {}

    /** A request the server understands but does not support; maps to 501 with {@link #code()}. */
    public static final class UnsupportedRequestException extends RuntimeException {
        private final String code;

        public UnsupportedRequestException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static List<Message> messages(OpenAi.ChatCompletionRequest r) {
        if (r.tools() != null && !r.tools().isEmpty()) {
            throw new UnsupportedRequestException("unsupported_feature", "tools are not supported yet: this model server has no tool calling");
        }
        if (r.n() != null && r.n() > 1) throw new IllegalArgumentException("n greater than 1 is not supported");
        if (r.messages() == null || r.messages().isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        List<Message> out = new ArrayList<>();
        for (OpenAi.ChatMessage m : r.messages()) {
            String text = text(m);
            String role = m.role() == null ? "" : m.role();
            switch (role) {
                case "system", "developer" -> out.add(Message.system(text));
                case "user" -> out.add(Message.user(text));
                case "assistant" -> out.add(Message.assistant(text));
                default -> throw new UnsupportedRequestException("unsupported_input", "messages with role '" + role + "' are not supported");
            }
        }
        return out;
    }

    private static String text(OpenAi.ChatMessage m) {
        Object c = m.content();
        if (c instanceof String s) return s;
        if (c instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> part) || !"text".equals(part.get("type")) || !(part.get("text") instanceof String t)) {
                    String type = p instanceof Map<?, ?> pm ? String.valueOf(pm.get("type")) : "?";
                    throw new UnsupportedRequestException("unsupported_input", "content parts of type '" + type + "' are not supported, only text");
                }
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t);
            }
            if (sb.isEmpty()) throw new IllegalArgumentException("message content must not be empty");
            return sb.toString();
        }
        throw new IllegalArgumentException("message content must be a string or a list of text parts");
    }

    public static GenerationSettings settings(OpenAi.ChatCompletionRequest r, int contextLength) {
        GenerationSettings s = GenerationSettings.defaults();
        Integer max = r.maxCompletionTokens() != null ? r.maxCompletionTokens() : r.maxTokens();
        s = s.withMaxNewTokens(max != null ? max : contextLength);
        if (r.temperature() != null) s = s.withTemperature(r.temperature());
        if (r.stop() instanceof String one) s = s.withStop(List.of(one));
        else if (r.stop() instanceof List<?> many) s = s.withStop(many.stream().map(String::valueOf).toList());
        return s;
    }

    public static String finishReason(FinishReason reason) {
        return reason == FinishReason.LENGTH ? "length" : "stop";
    }

    public static OpenAi.Usage usage(ChatResponse r) {
        int p = r.usage().promptTokens();
        int g = r.usage().generatedTokens();
        return new OpenAi.Usage(p, g, p + g);
    }
}
