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

/** Runs in a child JVM started without --add-modules, and prints what the flag check says. */
public final class EngineSetupProbe {
    public static void main(String[] args) {
        try {
            EngineSetup.check("panama");
            System.out.println("CHECK-PASSED");
        } catch (EngineSetupException e) {
            System.out.println(e.getMessage());
        }
    }
}
