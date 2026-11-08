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
package ao.bitubi.inference4j.server.e2e;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real server (scripted fake model, conversations in a temp directory) and a headless Chromium. Any console error,
 * including a CSP violation or an uncaught script error, fails the test.
 */
abstract class E2EBase {
    static Playwright playwright;
    static Browser browser;

    @TempDir
    Path data;

    FakeBi4j fake;
    TestServer server;
    BrowserContext context;
    Page page;
    final List<String> problems = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        // BI4J_E2E_CHROMIUM: use an installed Chromium instead of the one Playwright downloads (offline or slow networks)
        String exe = System.getenv("BI4J_E2E_CHROMIUM");
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions();
        if (exe != null && !exe.isBlank()) options.setExecutablePath(Path.of(exe));
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void open() {
        fake = new FakeBi4j();
        server = startServer(ModelHost.ready(fake));
        newPage();
    }

    TestServer startServer(ModelHost host) {
        return TestServer.start(host, "bi4j.server.history=true", "bi4j.server.data-dir=" + data);
    }

    void newPage() {
        context = browser.newContext();
        context.grantPermissions(List.of("clipboard-read", "clipboard-write"));
        page = context.newPage();
        watch(page);
    }

    void watch(Page p) {
        p.onConsoleMessage(m -> {
            // a 4xx/5xx answered on purpose (busy, context full) is logged by the browser as a failed resource
            if ("error".equals(m.type()) && !m.text().startsWith("Failed to load resource")) problems.add(m.text());
        });
        p.onPageError(e -> problems.add("page error: " + e));
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    @AfterEach
    void close() {
        try {
            context.close();
            server.close();
        } finally {
            assertTrue(problems.isEmpty(), "browser reported: " + problems);
        }
    }
}
