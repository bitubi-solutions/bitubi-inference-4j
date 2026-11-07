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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The OpenAI JSON subset the server speaks, as plain records (the generated models broke under Jackson 3). */
public final class OpenAi {
    private OpenAi() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        Float temperature,
        Boolean stream,
        Object stop,
        Integer n,
        List<Object> tools,
        @JsonProperty("stream_options") StreamOptions streamOptions
    ) {}

    /** {@code content} is a String, or a list of {@code {type, text}} parts. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(String role, Object content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletion(String id, String object, long created, String model, List<Choice> choices, Usage usage) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(int index, AssistantMessage message, @JsonProperty("finish_reason") String finishReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssistantMessage(String role, String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionChunk(String id, String object, long created, String model, List<ChunkChoice> choices, Usage usage) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(int index, Delta delta, @JsonProperty("finish_reason") @JsonInclude(JsonInclude.Include.ALWAYS) String finishReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {}

    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {}

    public record ModelList(String object, List<ModelEntry> data) {}

    public record ModelEntry(String id, String object, long created, @JsonProperty("owned_by") String ownedBy) {}

    public record ErrorBody(ErrorDetail error) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorDetail(String message, String type, String param, String code) {}
}
