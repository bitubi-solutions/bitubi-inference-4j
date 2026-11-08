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
import org.junit.jupiter.api.Test;

public class PrecompiledTemplatesTest {
    @Test
    public void aPrecompiledTemplateRendersAndTheHtmxBundleIsServed() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            var page = s.get("/hello");
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("<p>Hello bi4j</p>"), page.body());
            var js = s.get("/webjars/htmx.org/4.0.1/dist/htmax.min.js");
            assertEquals(200, js.statusCode());
            assertTrue(js.body().length() > 10_000);
            assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"), js.headers().toString());
        }
    }
}
