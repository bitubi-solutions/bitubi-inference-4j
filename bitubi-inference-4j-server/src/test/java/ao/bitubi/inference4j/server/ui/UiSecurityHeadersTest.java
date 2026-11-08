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

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class UiSecurityHeadersTest {
    private static void assertHardened(java.net.http.HttpResponse<?> r, String what) {
        assertEquals(UiSecurityHeaders.CSP, r.headers().firstValue("Content-Security-Policy").orElse(null), what);
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null), what);
        assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(null), what);
    }

    @Test
    public void pagesFragmentsAndAssetsCarryThePolicy() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            UUID id = UUID.randomUUID();
            assertHardened(s.get("/"), "page");
            assertHardened(s.get("/c/" + id, "HX-Request", "true"), "fragment");
            assertHardened(s.get("/sidebar"), "sidebar");
            assertHardened(s.get("/webjars/htmx.org/4.0.1/dist/htmax.min.js"), "htmx");
            assertHardened(s.post("/c/" + id + "/messages", "{}"), "post");
            assertTrue(UiSecurityHeaders.CSP.contains("script-src 'self'") && !UiSecurityHeaders.CSP.contains("unsafe"));
        }
    }

    @Test
    public void theJsonApiAndTheSwaggerUiAreLeftAlone() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            for (String path : new String[] { "/healthz", "/v1/models", "/v3/api-docs" }) {
                assertNull(s.get(path).headers().firstValue("Content-Security-Policy").orElse(null), path);
            }
        }
    }
}
