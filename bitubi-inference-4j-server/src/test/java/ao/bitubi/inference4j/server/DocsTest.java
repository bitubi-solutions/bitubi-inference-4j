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

import ao.bitubi.inference4j.server.ui.UiProperties;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The documentation says what the code does: every property is in the table, every code block of the guide is a real file. */
public class DocsTest {
    private static final Path DOCS = Path.of("..", "docs");

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static void keys(String prefix, Class<? extends Record> type, List<String[]> out) {
        RecordComponent[] components = type.getRecordComponents();
        java.lang.reflect.Parameter[] parameters = type.getDeclaredConstructors()[0].getParameters(); // @DefaultValue sits on the constructor parameters
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            DefaultValue d = parameters[i].getAnnotation(DefaultValue.class);
            if (Record.class.isAssignableFrom(c.getType())) {
                @SuppressWarnings("unchecked")
                Class<? extends Record> nested = (Class<? extends Record>) c.getType();
                keys(prefix + "." + kebab(c.getName()), nested, out);
            } else {
                String value = d != null && d.value().length > 0 ? d.value()[0] : "";
                out.add(new String[] { prefix + "." + kebab(c.getName()), value });
            }
        }
    }

    @Test
    public void everyPropertyIsInTheServerGuideWithItsDefault() throws IOException {
        List<String[]> keys = new ArrayList<>();
        keys("bi4j.server", Bi4jServerProperties.class, keys);
        keys("bi4j.ui", UiProperties.class, keys);
        keys.add(new String[] { "bi4j.api.enabled", "true" });
        String guide = Files.readString(DOCS.resolve("server.md"));
        for (String[] key : keys) {
            String row = guide.lines().filter(l -> l.startsWith("|") && l.contains("`" + key[0] + "`")).findFirst().orElse(null);
            assertNotNull(row, "docs/server.md has no table row for " + key[0]);
            boolean hasDefault = key[1].isEmpty() ? row.contains("empty") || row.contains("unset") : row.contains("`" + key[1] + "`");
            assertTrue(hasDefault, "the row of " + key[0] + " must show its default (" + (key[1].isEmpty() ? "empty or unset" : key[1]) + "): " + row);
            String env = "BI4J_" + key[0].substring("bi4j.".length()).replace('.', '_').replace('-', '_').toUpperCase();
            assertTrue(row.contains(env), "the row of " + key[0] + " must name its environment variable " + env);
        }
    }

    @Test
    public void everyVerbatimBlockOfTheExtendingGuideIsTheRealFile() throws IOException {
        String guide = Files.readString(DOCS.resolve("server-extending.md"));
        Matcher m = Pattern.compile("<!-- verbatim: (\\S+) -->\\R+```[a-z]*\\R(.*?)\\R```", Pattern.DOTALL).matcher(guide);
        int seen = 0;
        while (m.find()) {
            seen++;
            Path file = Path.of("..").resolve(m.group(1));
            assertTrue(Files.isRegularFile(file), "the guide names a file that does not exist: " + m.group(1));
            String actual = Files.readString(file);
            if (actual.startsWith("/*")) actual = actual.substring(actual.indexOf("*/") + 2).stripLeading(); // the licence header is not shown
            assertEquals(actual.strip(), m.group(2).strip(), "the guide's block differs from " + m.group(1));
        }
        assertTrue(seen >= 3, "the worked example shows the controller and both templates verbatim; found " + seen);
    }
}
