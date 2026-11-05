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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Fluent way to put a {@link ChatRequest} together and run it. The builder is mutable and not thread-safe; every call
 * that ends it ({@link #request()}, {@link #generate()}) makes a new immutable value.
 */
public final class ChatRequestBuilder {
    private final Bi4j bi4j;
    private final List<Message> messages = new ArrayList<>();
    private GenerationSettings settings = GenerationSettings.defaults();

    ChatRequestBuilder(Bi4j bi4j) {
        this.bi4j = bi4j;
    }

    public ChatRequestBuilder system(String text) {
        messages.add(Message.system(text));
        return this;
    }

    public ChatRequestBuilder user(String text) {
        messages.add(Message.user(text));
        return this;
    }

    /** A user message made of several parts, for example text and an image. */
    public ChatRequestBuilder user(Part... parts) {
        messages.add(Message.user(parts));
        return this;
    }

    public ChatRequestBuilder assistant(String text) {
        messages.add(Message.assistant(text));
        return this;
    }

    public ChatRequestBuilder maxNewTokens(int maxNewTokens) {
        settings = settings.withMaxNewTokens(maxNewTokens);
        return this;
    }

    public ChatRequestBuilder temperature(float temperature) {
        settings = settings.withTemperature(temperature);
        return this;
    }

    public ChatRequestBuilder stop(String... stop) {
        settings = settings.withStop(List.of(Objects.requireNonNull(stop)));
        return this;
    }

    /** The request as an immutable value. */
    public ChatRequest request() {
        return new ChatRequest(messages, settings);
    }

    /** Runs the request on a temporary session and streams the answer; see {@link Bi4j#stream}. */
    public Stream<Token> stream() {
        return bi4j.stream(request());
    }

    /** Runs the request on a temporary session and waits for the whole answer. */
    public ChatResponse generate() {
        return bi4j.generate(request());
    }
}
