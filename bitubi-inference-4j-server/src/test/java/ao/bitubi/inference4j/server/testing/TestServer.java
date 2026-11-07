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
package ao.bitubi.inference4j.server.testing;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.ServerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Boots the real application (Tomcat on a free port) around a given {@link ModelHost}. */
public final class TestServer implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    public static TestServer start(ModelHost host, String... properties) {
        List<String> props = new ArrayList<>(List.of("server.port=0", "server.address=127.0.0.1"));
        props.addAll(List.of(properties));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ServerApplication.class).properties(props.toArray(String[]::new))
            .initializers(c -> c.getBeanFactory().registerSingleton("modelHost", host))
            .run();
        return new TestServer(ctx);
    }

    private TestServer(ConfigurableApplicationContext ctx) {
        this.context = ctx;
        this.port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
    }

    public int port() {
        return port;
    }

    public HttpClient client() {
        return client;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    public HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        context.close();
    }
}
