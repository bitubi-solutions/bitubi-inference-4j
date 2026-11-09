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

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import org.junit.jupiter.api.Test;

public class ConfigBindingTest {
    @Test
    public void theApiSwitchRemovesV1AndKeepsTheRest() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.api.enabled=false")) {
            assertEquals(404, s.get("/v1/models").statusCode());
            assertEquals(200, s.get("/healthz").statusCode());
            assertEquals(200, s.get("/").statusCode());
        }
    }

    @Test
    public void theUiSwitchRemovesTheChatAndKeepsTheApi() throws Exception {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.ui.enabled=false")) {
            assertEquals(404, s.get("/").statusCode());
            assertEquals(200, s.get("/v1/models").statusCode());
        }
    }

    @Test
    public void maxLiveSessionsBindsFromTheProperty() {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.server.max-live-sessions=2")) {
            assertEquals(2, s.bean(Bi4jServerProperties.class).maxLiveSessions());
        }
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            assertEquals(4, s.bean(Bi4jServerProperties.class).maxLiveSessions());
        }
    }
}
