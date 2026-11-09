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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Static checks of the shipped assets: nothing remote, nothing that the strict CSP would reject or that evals text. */
public class AssetsTest {
    private static String read(String name) throws IOException {
        return Files.readString(Path.of("src/main/resources/static", name));
    }

    @Test
    public void cssLoadsNothingFromElsewhere() throws IOException {
        String css = read("app.css");
        assertFalse(css.contains("http://") || css.contains("https://"), "remote URL in app.css");
        assertFalse(css.contains("@import"));
        assertFalse(css.contains("expression("));
    }

    @Test
    public void jsIsStrictAndDoesNotEvalOrWriteHtml() throws IOException {
        String js = read("app.js");
        assertTrue(js.startsWith("'use strict';"));
        for (String banned : new String[] { "eval(", "new Function", ".innerHTML", ".outerHTML", "document.write", "setAttribute('style'", "setAttribute(\"style\"", "insertAdjacentHTML" }) {
            assertFalse(js.contains(banned), banned);
        }
        assertFalse(js.contains("http://") || js.contains("https://"));
    }

    @Test
    public void themeJsIsStrictSmallAndBlockingInTheHead() throws Exception {
        String js = read("theme.js");
        assertTrue(js.startsWith("'use strict';"));
        for (String banned : new String[] { "eval(", "new Function", ".innerHTML", "document.write", "http://", "https://" }) assertFalse(js.contains(banned), banned);
        try (ao.bitubi.inference4j.server.testing.TestServer s = ao.bitubi.inference4j.server.testing.TestServer.start(ao.bitubi.inference4j.server.ModelHost.ready(new ao.bitubi.inference4j.server.testing.FakeBi4j()))) {
            var r = s.get("/theme.js");
            assertEquals(200, r.statusCode());
            assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("javascript"), r.headers().toString());
            org.jsoup.nodes.Element first = org.jsoup.Jsoup.parse(s.get("/").body()).head().selectFirst("script");
            assertEquals("/theme.js", first.attr("src"), "theme.js is the first script of the head");
            assertFalse(first.hasAttr("defer") || first.hasAttr("async"), "it must block, so the theme is set before the first paint");
        }
    }

    @Test
    public void logosCarryNoScript() throws IOException {
        for (String f : new String[] { "logo.svg", "favicon.svg" }) {
            String svg = read(f);
            assertFalse(svg.toLowerCase().contains("<script"), f);
            assertFalse(svg.toLowerCase().contains("onload"), f);
        }
    }
}
