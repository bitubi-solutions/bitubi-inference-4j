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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Boots the shaded jar with a model that does not exist. No download and no model needed: it guards the shade merge
 * of the Spring metadata, Boot's autoconfiguration, and the failed-model path (the server stays up and says why).
 */
public class JarBootTest {

    @Test
    public void theShadedJarBootsAndReportsAFailedModel() throws Exception {
        Path jar = Path.of("..", "bitubi-inference-4j-cli", "target", "bitubi-inference-4j-cli.jar").toAbsolutePath().normalize();
        assumeTrue(Files.exists(jar), "build the cli module first: " + jar);
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Path log = Files.createTempFile("bi4j-jar-boot", ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(
            java,
            "--add-modules=jdk.incubator.vector",
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            jar.toString(),
            "serve",
            "/nonexistent-model",
            "--port",
            String.valueOf(port),
            "--no-history"
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> health = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                assertTrue(p.isAlive(), "the process died:\n" + Files.readString(log));
                try {
                    health = get(client, port, "/healthz");
                    if (health.body().contains("\"failed\"")) break;
                } catch (IOException notUpYet) {
                    // still starting
                }
                Thread.sleep(200);
            }
            assertNotNull(health, "no answer from /healthz:\n" + Files.readString(log));
            assertEquals(503, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"status\":\"failed\""), health.body() + "\n" + Files.readString(log));
            HttpResponse<String> models = get(client, port, "/v1/models");
            assertEquals(200, models.statusCode(), models.body());
            assertTrue(models.body().contains("\"data\":[]"), models.body());
            HttpResponse<String> docs = get(client, port, "/v3/api-docs");
            assertEquals(200, docs.statusCode(), docs.body() + "\n" + Files.readString(log));
            // the chat UI: the failed-model panel explains itself, assets are served, the old UI is gone
            HttpResponse<String> page = get(client, port, "/");
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("model-status"), page.body());
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
            assertEquals(200, get(client, port, "/webjars/htmx.org/4.0.1/dist/htmax.min.js").statusCode());
            assertEquals(200, get(client, port, "/app.js").statusCode());
            assertEquals(404, get(client, port, "/ui/index.html").statusCode());
            assertTrue(p.isAlive());
        } finally {
            p.destroy();
            p.waitFor(20, TimeUnit.SECONDS);
            p.destroyForcibly();
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
