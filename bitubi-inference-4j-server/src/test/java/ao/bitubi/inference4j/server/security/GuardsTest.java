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
package ao.bitubi.inference4j.server.security;

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GuardsTest {
    private TestServer server;

    @BeforeEach
    public void setUp() {
        server = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.server.allowed-hosts=chat.lan");
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    /** Raw HTTP: the JDK client refuses to set Host and Origin freely. Returns the status code. */
    private int raw(String method, String path, String body, String... headers) throws Exception {
        StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        for (String h : headers) req.append(h).append("\r\n");
        if (body != null) req.append("Content-Type: application/json\r\nContent-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        req.append("Connection: close\r\n\r\n");
        if (body != null) req.append(body);
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            String text = buf.toString(StandardCharsets.UTF_8);
            return Integer.parseInt(text.split(" ")[1]);
        }
    }

    private static final String CHAT = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    public void aForeignHostIs421() throws Exception {
        assertEquals(421, raw("GET", "/healthz", null, "Host: evil.example"));
    }

    @Test
    public void localhostWithAPortIsAllowed() throws Exception {
        assertEquals(200, raw("GET", "/healthz", null, "Host: localhost:" + server.port()));
    }

    @Test
    public void anIpv6LoopbackIsAllowed() throws Exception {
        assertEquals(200, raw("GET", "/healthz", null, "Host: [::1]:" + server.port()));
    }

    @Test
    public void anAllowedHostFromTheProperty() throws Exception {
        assertEquals(200, raw("GET", "/healthz", null, "Host: chat.lan:8080"));
    }

    @Test
    public void aCrossSitePostIs403() throws Exception {
        assertEquals(403, raw("POST", "/v1/chat/completions", CHAT, "Host: 127.0.0.1", "Sec-Fetch-Site: cross-site"));
    }

    @Test
    public void aForeignOriginPostIs403() throws Exception {
        assertEquals(403, raw("POST", "/v1/chat/completions", CHAT, "Host: 127.0.0.1:" + server.port(), "Origin: http://evil.example"));
    }

    @Test
    public void aMatchingOriginPasses() throws Exception {
        String host = "127.0.0.1:" + server.port();
        assertEquals(200, raw("POST", "/v1/chat/completions", CHAT, "Host: " + host, "Origin: http://" + host));
    }

    @Test
    public void curlStylePostsWithoutBrowserHeadersPass() throws Exception {
        assertEquals(200, raw("POST", "/v1/chat/completions", CHAT, "Host: 127.0.0.1:" + server.port()));
    }

    @Test
    public void getRequestsAreNotOriginChecked() throws Exception {
        assertEquals(200, raw("GET", "/healthz", null, "Host: 127.0.0.1:" + server.port(), "Origin: http://evil.example"));
    }
}
