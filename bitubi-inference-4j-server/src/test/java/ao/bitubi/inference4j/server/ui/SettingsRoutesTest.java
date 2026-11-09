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
package ao.bitubi.inference4j.server.ui;

import static ao.bitubi.inference4j.server.ModelHostTest.await;
import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SettingsRoutesTest {
    private FakeBi4j fake;
    private TestServer server;

    @BeforeEach
    public void setUp() {
        fake = new FakeBi4j().script("Hel", "lo");
        server = TestServer.start(ModelHost.ready(fake), "bi4j.ui.defaults.temperature=0.3");
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    private static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append('&');
            b.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private HttpResponse<String> put(UUID id, String... kv) throws Exception {
        return server.request("PUT", "/c/" + id + "/settings", form(kv));
    }

    private static Document doc(HttpResponse<String> r) {
        return Jsoup.parse(r.body());
    }

    private void sendAndFinish(UUID id, String text) throws Exception {
        assertEquals(200, server.request("POST", "/c/" + id + "/messages", form("text", text)).statusCode());
        await(() -> {
            try {
                return server.get("/c/" + id).body().contains("class=\"footer\"");
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    public void aNewConversationShowsTheConfiguredDefaults() throws Exception {
        Document d = doc(server.get("/c/" + UUID.randomUUID() + "/settings"));
        assertEquals("0.3", d.selectFirst("input[name=temperature]").attr("value"));
        assertEquals("512", d.selectFirst("input[name=maxTokens]").attr("value"));
        assertEquals("", d.selectFirst("textarea[name=system]").text());
        assertNotNull(d.selectFirst("label[for=s-temperature]"));
    }

    @Test
    public void savedSettingsReachTheNextTurn() throws Exception {
        UUID id = UUID.randomUUID();
        assertEquals(200, put(id, "temperature", "1.5", "maxTokens", "64", "system", "").statusCode());
        sendAndFinish(id, "hi");
        assertEquals(1.5f, fake.lastSettings().temperature());
        assertEquals(64, fake.lastSettings().maxNewTokens());
    }

    @Test
    public void outOfRangeValuesAreRefusedWithTheRange() throws Exception {
        UUID id = UUID.randomUUID();
        var r = put(id, "temperature", "3", "maxTokens", "64", "system", "");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("between 0 and 2"), r.body());
        assertEquals(400, put(id, "temperature", "1", "maxTokens", "0", "system", "").statusCode());
        assertEquals(400, put(id, "temperature", "abc", "maxTokens", "64", "system", "").statusCode());
    }

    @Test
    public void changingTheSystemPromptAfterMessagesRebuildsTheSessionAndSaysSo() throws Exception {
        UUID id = UUID.randomUUID();
        sendAndFinish(id, "hi");
        assertEquals(1, fake.liveSessions());
        var r = put(id, "temperature", "0.7", "maxTokens", "512", "system", "Be brief");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("System prompt changed"), r.body());
        assertEquals(0, fake.liveSessions());
        fake.script("again");
        sendAndFinish(id, "and now?");
        assertEquals(1, fake.liveSessions());
    }

    @Test
    public void whileAnAnswerRunsTemperatureIsStoredForNextTimeButTheSystemPromptIsBusy() throws Exception {
        fake.script(java.util.Collections.nCopies(200, "t").toArray(String[]::new)).delayMs(25);
        UUID id = UUID.randomUUID();
        assertEquals(200, server.request("POST", "/c/" + id + "/messages", form("text", "go")).statusCode());
        assertEquals(409, put(id, "temperature", "0.7", "maxTokens", "512", "system", "new prompt").statusCode());
        assertEquals(200, put(id, "temperature", "1.1", "maxTokens", "512", "system", "").statusCode());
        assertEquals(0.3f, fake.lastSettings().temperature(), "the running turn keeps the value it started with");
    }

    @Test
    public void resetGoesBackToTheDefaults() throws Exception {
        UUID id = UUID.randomUUID();
        put(id, "temperature", "1.5", "maxTokens", "64", "system", "x");
        var r = server.request("DELETE", "/c/" + id + "/settings", null);
        assertEquals(200, r.statusCode());
        Document d = doc(r);
        assertEquals("0.3", d.selectFirst("input[name=temperature]").attr("value"));
        assertEquals("", d.selectFirst("textarea[name=system]").text());
    }

    @Test
    public void aSystemPromptIsShownAsTextNotMarkup() throws Exception {
        UUID id = UUID.randomUUID();
        put(id, "temperature", "0.7", "maxTokens", "512", "system", "<script>alert(1)</script>");
        Document d = doc(server.get("/c/" + id + "/settings"));
        assertEquals("<script>alert(1)</script>", d.selectFirst("textarea[name=system]").text());
        assertEquals(0, d.select("script").size());
    }

    @Test
    public void aBadIdIsNotFound() throws Exception {
        assertEquals(404, server.get("/c/not-a-uuid/settings").statusCode());
    }
}
