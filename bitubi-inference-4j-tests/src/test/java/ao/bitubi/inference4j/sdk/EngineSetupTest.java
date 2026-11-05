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
package ao.bitubi.inference4j.sdk;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.Test;

public class EngineSetupTest {

    @Test
    public void missingVectorModuleNamesTheFlag() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), EngineSetupProbe.class.getName())
            .redirectErrorStream(true)
            .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
        assertTrue(out, out.contains("--add-modules jdk.incubator.vector"));
        assertTrue(out, out.contains("JAVA_TOOL_OPTIONS"));
        assertTrue(out, out.contains("maven-surefire-plugin"));
    }

    @Test
    public void presentModuleDoesNotThrow() {
        EngineSetup.check("panama"); // this JVM runs with the flag
    }

    @Test
    public void nativeBackendNeedsNativeAccess() {
        // The test JVM has no --enable-native-access for the unnamed module of this class: the check must say so
        // or pass, but it must never fail with anything other than EngineSetupException.
        try {
            EngineSetup.check("native");
        } catch (EngineSetupException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("--enable-native-access=ALL-UNNAMED"));
        }
    }
}
