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

import static ao.bitubi.inference4j.server.ModelHostTest.await;
import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class ChatStreamingTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private FakeBi4j fake;
    private TestServer server;

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j();
        server = TestServer.start(ModelHost.ready(fake));
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    private HttpRequest request(String body, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(server.uri("/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) b.headers(headers);
        return b.build();
    }

    private List<String> sse(String body) throws Exception {
        HttpResponse<java.util.stream.Stream<String>> r = server.client().send(request(body), HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, r.statusCode());
        List<String> data = new ArrayList<>();
        r.body().filter(l -> l.startsWith("data: ")).forEach(l -> data.add(l.substring(6))); // OpenAI writes a space after "data:"
        return data;
    }

    private String content(String chunk) {
        return mapper.readTree(chunk).at("/choices/0/delta/content").asString("");
    }

    @Test
    public void streamsRoleTokensFinishAndDone() throws Exception {
        fake.script("Hel", "lo");
        List<String> data = sse(ChatCompletionsTest.body(true, "hi"));
        assertEquals("assistant", mapper.readTree(data.get(0)).at("/choices/0/delta/role").asString());
        StringBuilder text = new StringBuilder();
        for (String d : data) if (!d.equals("[DONE]")) text.append(content(d));
        assertEquals("Hello", text.toString());
        assertEquals("stop", mapper.readTree(data.get(data.size() - 2)).at("/choices/0/finish_reason").asString());
        assertEquals("[DONE]", data.get(data.size() - 1));
    }

    @Test
    public void usageChunkOnlyWhenAsked() throws Exception {
        String with = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true,\"stream_options\":{\"include_usage\":true}}";
        List<String> data = sse(with);
        var usage = mapper.readTree(data.get(data.size() - 2));
        assertEquals(0, usage.get("choices").size());
        assertTrue(usage.at("/usage/total_tokens").asInt() > 0);
        List<String> without = sse(ChatCompletionsTest.body(true, "hi"));
        assertFalse(without.stream().anyMatch(d -> d.contains("\"usage\"")));
    }

    @Test
    public void aDisconnectCancelsTheGenerationAndFreesTheSession() throws Exception {
        fake.script(java.util.Collections.nCopies(500, "t").toArray(String[]::new)).delayMs(20);
        String id = UUID.randomUUID().toString();
        String payload = ChatCompletionsTest.body(true, "hi");
        // A raw socket: closing it really drops the TCP connection (the JDK client may keep it pooled).
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", server.port())) {
            String req = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nX-Bi4j-Session: " + id
                + "\r\nContent-Type: application/json\r\nContent-Length: " + payload.length() + "\r\n\r\n" + payload;
            sock.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            int seen = 0;
            while (seen < 3) if (in.readLine().startsWith("data:")) seen++;
        }
        await(() -> fake.cancelCount() == 1);
        fake.script("ok").delayMs(0);
        await(() -> {
            try {
                return server.post("/v1/chat/completions", ChatCompletionsTest.body(false, "again"), "X-Bi4j-Session", id).statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    public void aContextErrorOnAStreamingRequestIsStill400() throws Exception {
        fake.contextLimit(4);
        var r = server.post("/v1/chat/completions", ChatCompletionsTest.body(true, "a very long prompt indeed"));
        assertEquals(400, r.statusCode());
        assertEquals("context_length_exceeded", mapper.readTree(r.body()).at("/error/code").asString());
    }

    @Test
    public void theTemporarySessionOfAStreamIsFreed() throws Exception {
        sse(ChatCompletionsTest.body(true, "hi"));
        await(() -> fake.liveSessions() == 0);
    }
}
