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

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.stefanbratanov.jvm.openai.ChatClient;
import io.github.stefanbratanov.jvm.openai.ChatMessage;
import io.github.stefanbratanov.jvm.openai.CreateChatCompletionRequest;
import io.github.stefanbratanov.jvm.openai.OpenAI;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The clients people actually use, pointed at the server. */
public class OfficialClientsTest {
    private FakeBi4j fake;
    private TestServer server;

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j().script("Hel", "lo", " there");
        server = TestServer.start(ModelHost.ready(fake));
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.port() + "/v1";
    }

    private ChatClient jvmOpenAi() {
        return OpenAI.newBuilder("key").baseUrl(base()).build().chatClient();
    }

    private OpenAIClient official() {
        return OpenAIOkHttpClient.builder().baseUrl(base()).apiKey("key").build();
    }

    @Test
    public void jvmOpenAiCompletesAStreamingChat() {
        var req = CreateChatCompletionRequest.newBuilder().model("m").stream(true).message(ChatMessage.userMessage("hi")).build();
        String text = jvmOpenAi().streamChatCompletion(req)
            .flatMap(c -> c.choices().stream())
            .map(ch -> ch.delta() == null || ch.delta().content() == null ? "" : ch.delta().content())
            .collect(Collectors.joining());
        assertEquals("Hello there", text);
    }

    @Test
    public void jvmOpenAiNonStreaming() {
        var req = CreateChatCompletionRequest.newBuilder().model("m").stream(false).message(ChatMessage.userMessage("hi")).build();
        assertEquals("Hello there", jvmOpenAi().createChatCompletion(req).choices().get(0).message().content());
    }

    @Test
    public void theOfficialJavaSdkStreams() {
        var params = ChatCompletionCreateParams.builder().model("m").addUserMessage("hi").build();
        StringBuilder text = new StringBuilder();
        try (var stream = official().chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> chunk.choices().forEach(c -> c.delta().content().ifPresent(text::append)));
        }
        assertEquals("Hello there", text.toString());
    }

    @Test
    public void theOfficialJavaSdkNonStreaming() {
        var params = ChatCompletionCreateParams.builder().model("m").addUserMessage("hi").build();
        var completion = official().chat().completions().create(params);
        assertEquals("Hello there", completion.choices().get(0).message().content().orElseThrow());
    }

    @Test
    public void theOfficialJavaSdkListsTheModel() {
        var page = official().models().list();
        assertEquals("fake/model", page.data().get(0).id());
    }
}
