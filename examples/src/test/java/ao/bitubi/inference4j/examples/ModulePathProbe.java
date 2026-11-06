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

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.ChatResponse;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs on the module path, with the engine as a named module: prints what it saw, for {@link ModulePathTest}. */
public final class ModulePathProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("MODULE=" + Bi4j.class.getModule().getName());
        try (Bi4j bi4j = Bi4j.builder().model(Path.of(args[0])).contextLength(512).build()) {
            ChatResponse r = bi4j.chat().user("Say hello.").maxNewTokens(8).temperature(0f).generate();
            System.out.println(r.text().isBlank() ? "ANSWER-EMPTY" : "ANSWER-OK");
        }
        // The engine is not exported: reflection from outside the module is refused before the call is made
        Class<?> engine = Class.forName("ao.bitubi.inference4j.model.ModelSupport");
        Method loadModel = Arrays.stream(engine.getMethods()).filter(m -> m.getName().equals("loadModel")).findFirst().orElseThrow();
        try {
            loadModel.invoke(null);
            System.out.println("ENGINE-REACHABLE");
        } catch (IllegalAccessException e) {
            System.out.println("ENCAPSULATED");
        } catch (IllegalArgumentException e) {
            System.out.println("ENGINE-REACHABLE"); // got as far as checking the arguments
        }
    }
}
