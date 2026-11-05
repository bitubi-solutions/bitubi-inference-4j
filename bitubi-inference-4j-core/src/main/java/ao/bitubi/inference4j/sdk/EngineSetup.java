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

/** Checks that the JVM was started with what the engine needs, and explains how to fix it when it was not. */
final class EngineSetup {

    private EngineSetup() {}

    /**
     * @param backend the value of {@code bi4j.backend}: {@code "native"} also needs native access
     * @throws EngineSetupException with the flag and where to put it
     */
    static void check(String backend) {
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            throw new EngineSetupException(
                "The JVM was started without the Vector API module, which the engine needs. Add this flag:\n"
                    + "  --add-modules jdk.incubator.vector\n"
                    + "Where to put it:\n"
                    + "  - java command line: java --add-modules jdk.incubator.vector -jar app.jar\n"
                    + "  - environment: JAVA_TOOL_OPTIONS=\"--add-modules jdk.incubator.vector\"\n"
                    + "  - Maven: <argLine>--add-modules jdk.incubator.vector</argLine> in the configuration of "
                    + "maven-surefire-plugin (tests) or exec-maven-plugin (run)"
            );
        }
        if ("native".equals(backend) && !EngineSetup.class.getModule().isNativeAccessEnabled()) {
            throw new EngineSetupException(
                "bi4j.backend=native needs native access for this code. Add this flag:\n"
                    + "  --enable-native-access=ALL-UNNAMED\n"
                    + "(java command line, JAVA_TOOL_OPTIONS, or <argLine> in maven-surefire-plugin)"
            );
        }
    }
}
