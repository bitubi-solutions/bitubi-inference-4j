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

import ao.bitubi.inference4j.sdk.EngineSetupException;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class ChatCompletionsTest {
    static final String SESSION = "X-Bi4j-Session";
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

    static String body(boolean stream, String text) {
        return "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}],\"max_tokens\":16,\"stream\":" + stream + "}";
    }

    private JsonNode json(HttpResponse<String> r) {
        return mapper.readTree(r.body());
    }

    @Test
    public void aPlainRequestReturnsAChatCompletion() throws Exception {
        fake.script("Hel", "lo");
        var r = server.post("/v1/chat/completions", body(false, "hi"));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode j = json(r);
        assertEquals("chat.completion", j.get("object").asString());
        assertEquals("Hello", j.at("/choices/0/message/content").asString());
        assertEquals("stop", j.at("/choices/0/finish_reason").asString());
        assertTrue(j.at("/usage/total_tokens").asInt() > 0);
    }

    @Test
    public void theSessionHeaderKeepsTheConversation() throws Exception {
        String id = UUID.randomUUID().toString();
        assertEquals(200, server.post("/v1/chat/completions", body(false, "one"), SESSION, id).statusCode());
        String second = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"one\"},{\"role\":\"assistant\",\"content\":\"Hello\"},{\"role\":\"user\",\"content\":\"two\"}]}";
        assertEquals(200, server.post("/v1/chat/completions", second, SESSION, id).statusCode());
        assertEquals(1, fake.liveSessions());
    }

    @Test
    public void aNonUuidSessionHeaderIs400WithJson() throws Exception {
        var r = server.post("/v1/chat/completions", body(false, "hi"), SESSION, "nope");
        assertEquals(400, r.statusCode());
        assertEquals("invalid_session", json(r).at("/error/code").asString());
    }

    @Test
    public void theRemovedJlamaHeaderIsIgnored() throws Exception {
        var r = server.post("/v1/chat/completions", body(false, "hi"), "X-Jlama-Session", "nope");
        assertEquals(200, r.statusCode());
    }

    @Test
    public void aBusySessionIs409BeforeAnyStream() throws Exception {
        fake.script("a", "b", "c", "d").delayMs(300);
        String id = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return server.post("/v1/chat/completions", body(false, "one"), SESSION, id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        await(() -> fake.liveSessions() == 1);
        var second = server.post("/v1/chat/completions", body(true, "two"), SESSION, id);
        assertEquals(409, second.statusCode());
        assertEquals("session_busy", json(second).at("/error/code").asString());
        assertEquals(200, first.get().statusCode());
    }

    @Test
    public void tenSimultaneousRequestsOnOneSessionGiveOneSuccess() throws Exception {
        fake.script("a", "b").delayMs(200);
        String id = UUID.randomUUID().toString();
        var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); // the common pool may have 1 thread on small runners
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return server.post("/v1/chat/completions", body(false, "x"), SESSION, id).statusCode();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, pool));
        }
        long ok = 0;
        long busy = 0;
        for (var c : calls) {
            int s = c.get();
            if (s == 200) ok++;
            else if (s == 409) busy++;
        }
        assertEquals(1, ok);
        assertEquals(9, busy);
        fake.delayMs(0);
        assertEquals(200, server.post("/v1/chat/completions", body(false, "again"), SESSION, id).statusCode());
    }

    @Test
    public void contextLengthIs400WithTheNumbers() throws Exception {
        fake.contextLimit(4);
        var r = server.post("/v1/chat/completions", body(false, "a very long prompt indeed"));
        assertEquals(400, r.statusCode());
        assertEquals("context_length_exceeded", json(r).at("/error/code").asString());
        assertTrue(json(r).at("/error/message").asString().contains("4"));
    }

    @Test
    public void toolsAndNAndEmptyMessagesAreCleanErrors() throws Exception {
        String user = "{\"role\":\"user\",\"content\":\"x\"}";
        var tools = server.post("/v1/chat/completions", "{\"messages\":[" + user + "],\"tools\":[{\"type\":\"function\"}]}");
        assertEquals(501, tools.statusCode());
        assertEquals("unsupported_feature", json(tools).at("/error/code").asString());
        assertEquals(400, server.post("/v1/chat/completions", "{\"messages\":[" + user + "],\"n\":2}").statusCode());
        assertEquals(400, server.post("/v1/chat/completions", "{\"messages\":[]}").statusCode());
        var image = server.post("/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\"}]}]}");
        assertEquals(501, image.statusCode());
        assertEquals(501, server.post("/v1/chat/completions", "{\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}").statusCode());
    }

    @Test
    public void aMalformedBodyIs400Json() throws Exception {
        var r = server.post("/v1/chat/completions", "{");
        assertEquals(400, r.statusCode());
        assertEquals("invalid_request", json(r).at("/error/code").asString());
    }

    @Test
    public void anEngineFailureIs500JsonWithoutAStackTrace() throws Exception {
        fake.failAt(0, new EngineSetupException("--add-modules jdk.incubator.vector is missing"));
        var r = server.post("/v1/chat/completions", body(false, "hi"));
        assertEquals(500, r.statusCode());
        assertEquals("engine_error", json(r).at("/error/code").asString());
        assertFalse(r.body().contains("at ao."));
    }

    @Test
    public void aNullMessageIs400NotAnNpe() throws Exception {
        var r = server.post("/v1/chat/completions", "{\"messages\":[null]}");
        assertEquals(400, r.statusCode());
        assertEquals("invalid_request", json(r).at("/error/code").asString());
    }

    @Test
    public void anUnexpectedExceptionIsAnOpenAiShaped500() throws Exception {
        fake.failAt(0, new IllegalStateException("boom"));
        var r = server.post("/v1/chat/completions", body(false, "hi"));
        assertEquals(500, r.statusCode());
        assertEquals("server_error", json(r).at("/error/type").asString());
        assertFalse(r.body().contains("at ao."));
    }

    @Test
    public void wrongMethodAndMediaTypeAreOpenAiShaped() throws Exception {
        var get = server.get("/v1/chat/completions");
        assertEquals(405, get.statusCode());
        assertEquals("method_not_allowed", json(get).at("/error/code").asString());
        var req = java.net.http.HttpRequest.newBuilder(server.uri("/v1/chat/completions"))
            .header("Content-Type", "text/plain")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("hi"))
            .build();
        var text = server.client().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(415, text.statusCode());
        assertEquals("unsupported_media_type", json(text).at("/error/code").asString());
    }

    @Test
    public void aNullStopAndATextPartWithoutTextAreCleanErrors() throws Exception {
        var stop = server.post("/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stop\":[\"a\",null]}");
        assertEquals(400, stop.statusCode());
        var part = server.post("/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\"}]}]}");
        assertEquals(400, part.statusCode());
        assertEquals("invalid_request", json(part).at("/error/code").asString());
    }
}
