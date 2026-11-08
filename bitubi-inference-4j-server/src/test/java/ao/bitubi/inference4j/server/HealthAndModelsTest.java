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
package ao.bitubi.inference4j.server;

import static ao.bitubi.inference4j.server.ModelHostTest.await;
import static ao.bitubi.inference4j.server.ModelHostTest.awaitQuietly;
import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.LoadProgress;
import ao.bitubi.inference4j.sdk.ModelNotFoundException;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class HealthAndModelsTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String CHAT = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}";

    @Test
    public void whileLoadingHealthIs503ModelsIsEmptyAndChatIs503WithRetryAfter() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ModelHost host = new ModelHost(p -> {
            p.accept(new LoadProgress(LoadProgress.Phase.DOWNLOADING, "w", 30, 100));
            awaitQuietly(release);
            return new FakeBi4j();
        });
        host.start();
        try (TestServer s = TestServer.start(host)) {
            await(() -> host.status().state() == ModelHost.State.DOWNLOADING);
            var h = s.get("/healthz");
            assertEquals(503, h.statusCode());
            assertEquals("downloading", mapper.readTree(h.body()).get("status").asString());
            assertEquals(30, mapper.readTree(h.body()).get("done").asInt());
            assertEquals(0, mapper.readTree(s.get("/v1/models").body()).get("data").size());
            var c = s.post("/v1/chat/completions", CHAT);
            assertEquals(503, c.statusCode());
            assertEquals("5", c.headers().firstValue("Retry-After").orElseThrow());
            assertEquals("model_loading", mapper.readTree(c.body()).at("/error/code").asString());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aFailedModelKeepsTheServerUpAndSaysWhy() throws Exception {
        ModelHost host = new ModelHost(p -> {
            throw new ModelNotFoundException("no such model: /x");
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.FAILED);
        try (TestServer s = TestServer.start(host)) {
            var h = s.get("/healthz");
            assertEquals(503, h.statusCode());
            assertEquals("failed", mapper.readTree(h.body()).get("status").asString());
            assertTrue(mapper.readTree(h.body()).get("detail").asString().contains("no such model: /x"));
            var c = s.post("/v1/chat/completions", CHAT);
            assertEquals(503, c.statusCode());
            assertEquals("model_failed", mapper.readTree(c.body()).at("/error/code").asString());
            assertTrue(c.headers().firstValue("Retry-After").isEmpty());
        }
    }

    @Test
    public void readyHealthAndModelsNameTheModel() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            var h = s.get("/healthz");
            assertEquals(200, h.statusCode());
            assertEquals("fake/model", mapper.readTree(h.body()).get("model").asString());
            assertEquals("fake/model", mapper.readTree(s.get("/v1/models").body()).at("/data/0/id").asString());
        }
    }

    @Test
    public void apiDocsListTheChatRoute() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            var r = s.get("/v3/api-docs");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("/v1/chat/completions"));
            var docs = s.get("/docs");
            assertTrue(docs.statusCode() == 200 || docs.statusCode() == 302, "docs status " + docs.statusCode());
        }
    }
}
