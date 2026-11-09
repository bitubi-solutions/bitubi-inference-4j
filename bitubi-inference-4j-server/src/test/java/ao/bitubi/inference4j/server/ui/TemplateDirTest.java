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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TemplateDirTest {
    @TempDir
    Path dir;

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(target);
                else Files.copy(f, target);
            }
        }
    }

    @Test
    public void aSavedTemplateShowsOnTheNextRequestWithoutARestart() throws Exception {
        copy(Path.of("src/main/jte"), dir);
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.ui.template-dir=" + dir)) {
            assertTrue(s.get("/").body().contains("stored as plain text on this machine"));
            Path sidebar = dir.resolve("sidebar.jte");
            Files.writeString(sidebar, Files.readString(sidebar).replace("Conversations are stored as plain text on this machine.", "EDITED IN THE SOURCE TREE"));
            assertTrue(s.get("/").body().contains("EDITED IN THE SOURCE TREE"), "the edit must show on refresh");
        }
    }

    @Test
    public void aMissingFolderFailsAtStartupWithOneClearSentence() {
        Path missing = dir.resolve("no-such-folder");
        Throwable t = assertThrows(Throwable.class, () -> TestServer.start(ModelHost.ready(new FakeBi4j()), "bi4j.ui.template-dir=" + missing));
        while (t.getCause() != null) t = t.getCause();
        assertEquals("bi4j.ui.template-dir \"" + missing + "\" is not a folder; unset it to use the built-in templates.", t.getMessage());
    }

    @Test
    public void withoutTheKeyTheBuiltInTemplatesAreUsed() {
        try (TestServer s = TestServer.start(ModelHost.ready(new FakeBi4j()))) {
            assertNull(s.bean(UiProperties.class).templateDir());
        }
    }
}
