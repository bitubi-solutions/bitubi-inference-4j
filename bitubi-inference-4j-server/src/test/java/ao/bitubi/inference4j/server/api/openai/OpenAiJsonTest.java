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

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.FinishReason;
import ao.bitubi.inference4j.sdk.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class OpenAiJsonTest {
    // The mapper Boot 4 puts behind @RequestBody: the path that broke the generated oneOf models.
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private OpenAi.ChatCompletionRequest parse(String json) {
        return mapper.readValue(json, OpenAi.ChatCompletionRequest.class);
    }

    @Test
    public void contentAsAStringAndAsAPartsArrayBothParse() {
        var plain = parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals("hi", OpenAiMapper.messages(plain).get(0).text());
        var parts = parse(
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}]}"
        );
        assertEquals("a\nb", OpenAiMapper.messages(parts).get(0).text());
    }

    @Test
    public void snakeCaseFieldsAndUnknownFieldsAreHandled() {
        var r = parse(
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"max_tokens\":7,\"seed\":1,\"logprobs\":false,\"stream_options\":{\"include_usage\":true}}"
        );
        assertEquals(7, r.maxTokens());
        assertTrue(r.streamOptions().includeUsage());
        assertEquals(7, OpenAiMapper.settings(r, 100).maxNewTokens());
    }

    @Test
    public void maxTokensDefaultsToTheContextAndCompletionTokensWin() {
        var none = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertEquals(4096, OpenAiMapper.settings(none, 4096).maxNewTokens());
        var both = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"max_tokens\":5,\"max_completion_tokens\":9}");
        assertEquals(9, OpenAiMapper.settings(both, 4096).maxNewTokens());
    }

    @Test
    public void stopAsStringOrArray() {
        var one = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stop\":\"END\"}");
        assertEquals(List.of("END"), OpenAiMapper.settings(one, 10).stop());
        var many = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stop\":[\"a\",\"b\"]}");
        assertEquals(List.of("a", "b"), OpenAiMapper.settings(many, 10).stop());
    }

    @Test
    public void aNonTextPartIsUnsupportedNotSilentlyDropped() {
        var r = parse("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]}");
        var e = assertThrows(OpenAiMapper.UnsupportedRequestException.class, () -> OpenAiMapper.messages(r));
        assertEquals("unsupported_input", e.code());
    }

    @Test
    public void toolsAreRejectedWhenNonEmpty() {
        var some = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tools\":[{\"type\":\"function\"}]}");
        assertEquals("unsupported_feature", assertThrows(OpenAiMapper.UnsupportedRequestException.class, () -> OpenAiMapper.messages(some)).code());
        var empty = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tools\":[]}");
        assertEquals(1, OpenAiMapper.messages(empty).size());
    }

    @Test
    public void developerRoleIsASystemMessageAndToolRoleIsUnsupported() {
        var dev = parse("{\"messages\":[{\"role\":\"developer\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"x\"}]}");
        assertEquals(Message.Role.SYSTEM, OpenAiMapper.messages(dev).get(0).role());
        var tool = parse("{\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}");
        assertThrows(OpenAiMapper.UnsupportedRequestException.class, () -> OpenAiMapper.messages(tool));
    }

    @Test
    public void emptyMessagesAndNGreaterThanOneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiMapper.messages(parse("{\"messages\":[]}")));
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiMapper.messages(parse("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"n\":2}"))
        );
    }

    @Test
    public void responsesUseOpenAiFieldNames() {
        String json = mapper.writeValueAsString(
            new OpenAi.ChatCompletion(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "m",
                List.of(new OpenAi.Choice(0, new OpenAi.AssistantMessage("assistant", "hi"), "stop")),
                new OpenAi.Usage(3, 2, 5)
            )
        );
        assertTrue(json.contains("\"finish_reason\":\"stop\""), json);
        assertTrue(json.contains("\"prompt_tokens\":3"), json);
    }

    @Test
    public void aChunkAlwaysCarriesFinishReasonEvenWhenNull() {
        String json = mapper.writeValueAsString(
            new OpenAi.ChatCompletionChunk("id", "chat.completion.chunk", 1L, "m", List.of(new OpenAi.ChunkChoice(0, new OpenAi.Delta(null, "x"), null)), null)
        );
        assertTrue(json.contains("\"finish_reason\":null"), json);
        assertFalse(json.contains("\"usage\""), json);
    }

    @Test
    public void finishReasonMapping() {
        assertEquals("length", OpenAiMapper.finishReason(FinishReason.LENGTH));
        assertEquals("stop", OpenAiMapper.finishReason(FinishReason.STOP_SEQUENCE));
    }
}
