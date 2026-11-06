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
import ao.bitubi.inference4j.sdk.ChatSession;
import java.nio.file.Path;

/** A conversation: the session keeps the history in the model, so each turn only processes the new message. */
public final class Conversation {

    public static void main(String[] args) {
        try (
            Bi4j bi4j = Bi4j.builder().model(args[0]).modelsDir(Path.of("models")).contextLength(4096).build();
            ChatSession chat = bi4j.newSession(s -> s.system("Answer in one short sentence.").temperature(0.2f))
        ) {
            chat.send("My name is Ana.");
            ChatResponse answer = chat.send("What is my name?");
            System.out.println(answer.text());
            System.out.printf("%d tokens at %.1f tokens/s%n", answer.usage().generatedTokens(), answer.usage().tokensPerSecond());
        }
    }
}
