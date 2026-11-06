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
import ao.bitubi.inference4j.sdk.Token;
import java.util.stream.Stream;

/**
 * The shortest way to the first token. Run it with the JVM flag the engine needs:
 *
 * <pre>
 * java --add-modules jdk.incubator.vector -cp ... ao.bitubi.inference4j.examples.QuickStart \
 *     tjake/Llama-3.2-1B-Instruct-JQ4 "When is the best time to plant avocado?"
 * </pre>
 */
public final class QuickStart {

    public static void main(String[] args) {
        try (Bi4j bi4j = Bi4j.load(args[0]); Stream<Token> tokens = bi4j.chat().user(args[1]).stream()) {
            tokens.forEach(token -> System.out.print(token.text()));
        }
        System.out.println();
    }
}
