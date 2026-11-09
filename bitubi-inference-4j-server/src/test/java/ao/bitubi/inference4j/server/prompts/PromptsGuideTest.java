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
package ao.bitubi.inference4j.server.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import ao.bitubi.inference4j.server.testing.TestServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The page docs/server-extending.md builds really renders, as a full page and as a fragment. */
public class PromptsGuideTest {
    @TempDir
    Path templates;

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(f)) Files.copy(f, to.resolve(from.relativize(f).toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    public void thePromptsPageRendersFullAndAsAFragment() throws Exception {
        copy(Path.of("src/main/jte"), templates);
        copy(Path.of("src/test/jte"), templates); // the guide's templates sit beside the built-in ones, as a developer would put them
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.test.prompts=true", "bi4j.ui.template-dir=" + templates)) {
            var full = Jsoup.parse(s.get("/prompts").body());
            assertEquals("Prompts", full.selectFirst("h1").text());
            assertEquals(2, full.select("#prompts li").size());
            assertNotNull(full.selectFirst("link[rel=stylesheet]"), "a full page has the layout");

            var fragment = s.get("/prompts", "HX-Request", "true");
            assertTrue(fragment.body().trim().startsWith("<ul id=\"prompts\">"), fragment.body());
            assertFalse(fragment.body().contains("<html"), "a fragment has no layout");
            assertEquals("HX-Request", fragment.headers().firstValue("Vary").orElse(""));
        }
    }
}
