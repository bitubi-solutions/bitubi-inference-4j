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
package ao.bitubi.inference4j.examples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.Test;

/**
 * The SDK on the module path: the engine is a named module that exports only the sdk package, a model loads and
 * answers (Jackson and the template engine reach the engine through the declared opens), and nothing outside the
 * sdk package is reachable. The forked JVM gets no --add-modules flag: the module descriptor requires the Vector API.
 */
public class ModulePathTest {

    @Test
    public void theSdkWorksOnTheModulePathAndTheEngineIsHidden() throws Exception {
        File model = SafeTensorSupport.maybeDownloadModel("../bitubi-inference-4j-tests/models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(
            java,
            "--module-path", Path.of("target", "modules").toString(),
            "--add-modules", "ao.bitubi.inference4j",
            "-cp", Path.of("target", "test-classes").toString(),
            ModulePathProbe.class.getName(),
            model.getPath()
        ).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
        assertTrue(out, out.contains("MODULE=ao.bitubi.inference4j"));
        assertTrue(out, out.contains("ANSWER-OK"));
        assertTrue(out, out.contains("ENCAPSULATED"));
    }
}
