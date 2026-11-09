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

import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;

/**
 * The chat UI on a scripted fake model, with templates and static files read from the source tree: edit, save, refresh.
 * No model, no download, no JVM flag for the vector module.
 *
 * <pre>
 * ./dev-server.sh            # about 9 seconds to the printed URL; the first page adds about 2.5 s while templates compile
 * </pre>
 *
 * Options: {@code --port N} (default 8080), {@code --delay-ms N} per token (default 40), {@code --fail-at N} makes every
 * answer fail after N tokens (to see the error states).
 */
public final class DevServer {
    private static final String ANSWER = """
        Here is a **short answer** with a list:

        - the first point
        - the second point

        And a code block:

        ```java
        var bi4j = Bi4j.load("tjake/Llama-3.2-1B-Instruct-JQ4");
        System.out.println(bi4j.chat().user("Hi").generate().text());
        ```

        | model | size |
        |---|---|
        | fake | 0 |

        That is all.
        """;

    private DevServer() {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        long delay = 40;
        int failAt = -1;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--delay-ms" -> delay = Long.parseLong(args[i + 1]);
                case "--fail-at" -> failAt = Integer.parseInt(args[i + 1]);
                default -> {}
            }
        }
        FakeBi4j fake = new FakeBi4j().script(ANSWER.split("(?<=\\s)")).delayMs(delay);
        if (failAt >= 0) fake.failAt(failAt, new IllegalStateException("scripted failure (--fail-at " + failAt + ")"));
        TestServer server = TestServer.start(
            ModelHost.ready(fake),
            "server.port=" + port,
            "bi4j.ui.template-dir=bitubi-inference-4j-server/src/main/jte",
            "spring.web.resources.static-locations=file:bitubi-inference-4j-server/src/main/resources/static/",
            "spring.web.resources.cache.period=0",
            "spring.main.lazy-initialization=true", // beans are built when first used: the page is up sooner
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false"
        );
        System.out.println("Dev server: http://127.0.0.1:" + server.port() + "  (templates and static files are read from the source tree)");
        Thread.currentThread().join();
    }
}
