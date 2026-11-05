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

import ao.bitubi.inference4j.safetensors.DType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A loaded model. Immutable and thread-safe once built: any number of threads may use it at the same time. It
 * holds memory outside the heap, so close it when done (try-with-resources).
 *
 * <pre>{@code
 * try (Bi4j bi4j = Bi4j.load("tjake/Llama-3.2-1B-Instruct-JQ4")) {
 *     System.out.println(bi4j.capabilities());
 * }
 * }</pre>
 */
public interface Bi4j extends AutoCloseable {

    static Builder builder() {
        return new Builder();
    }

    /** Shortcut for {@code builder().model(modelId).build()}. */
    static Bi4j load(String modelId) {
        return builder().model(modelId).build();
    }

    /** Starts a fluent chat request: {@code bi4j.chat().user("Hi").generate()}. */
    ChatRequestBuilder chat();

    /**
     * Runs a request on a temporary session (nothing is kept afterwards) and waits for the answer. Safe to call from
     * many threads at once.
     *
     * @throws UnsupportedInputException a message has an input the model does not read
     * @throws ContextLengthExceededException the prompt does not fit the context window
     */
    ChatResponse generate(ChatRequest request);

    /**
     * Like {@link #generate} but hands the answer over token by token. The stream is lazy: generation starts when it is
     * first read. Closing it, or ending it early with {@code limit} or {@code takeWhile} inside try-with-resources,
     * stops the generation at the next token. Use it in try-with-resources: an open stream keeps its work running.
     *
     * @throws UnsupportedInputException a message has an input the model does not read (thrown here, not when read)
     */
    Stream<Token> stream(ChatRequest request);

    /** A conversation that keeps its history in the model's KV cache until it is closed. */
    ChatSession newSession();

    /** Like {@link #newSession()} with a system message, generation defaults or a temporary session. */
    ChatSession newSession(Consumer<SessionOptions.Builder> options);

    /** What this model can do. */
    Capabilities capabilities();

    /**
     * Embeds a text with the model's own pooling averaged over the tokens.
     *
     * @throws UnsupportedOperationException if the model does not produce embeddings
     */
    float[] embed(String text);

    /** Frees the model and every session created from it. Safe to call more than once. */
    @Override
    void close();

    /** Collects how to find and load a model. Not thread-safe; {@link #build()} makes the immutable {@link Bi4j}. */
    final class Builder {
        String modelId;
        Path modelPath;
        Path modelsDir = Path.of("models");
        Integer contextLength;
        WorkingTypes manualTypes;
        Integer threads;
        String authToken;
        Supplier<MemoryBudget.Available> available; // tests replace the machine measurement

        Builder() {}

        /** A Hugging Face id such as {@code owner/name}; it is downloaded into {@link #modelsDir(Path)} if missing. */
        public Builder model(String id) {
            this.modelId = Objects.requireNonNull(id, "id");
            this.modelPath = null;
            return this;
        }

        /** A local model folder (safetensors) or a {@code .gguf} file. */
        public Builder model(Path path) {
            this.modelPath = Objects.requireNonNull(path, "path");
            this.modelId = null;
            return this;
        }

        /** Where downloaded models are kept. Default: {@code ./models}. */
        public Builder modelsDir(Path dir) {
            this.modelsDir = Objects.requireNonNull(dir, "dir");
            return this;
        }

        /** The most tokens a session may hold. Default: 4096, or the model's maximum if that is smaller. */
        public Builder contextLength(int tokens) {
            if (tokens <= 0) throw new IllegalArgumentException("contextLength must be positive: " + tokens);
            this.contextLength = tokens;
            return this;
        }

        /**
         * Chooses the working types yourself, at your own risk: the SDK still refuses combinations with no kernel,
         * but only warns when the choice is slower than the automatic one or does not fit the estimate.
         */
        public Builder workingTypes(DType memory, DType activations) {
            this.manualTypes = new WorkingTypes(Objects.requireNonNull(memory), Objects.requireNonNull(activations));
            return this;
        }

        /** Number of threads for the math. Default: the physical cores. */
        public Builder threads(int threads) {
            if (threads <= 0) throw new IllegalArgumentException("threads must be positive: " + threads);
            this.threads = threads;
            return this;
        }

        /** Hugging Face access token for gated or private models. */
        public Builder authToken(String token) {
            this.authToken = token;
            return this;
        }

        Builder available(Supplier<MemoryBudget.Available> available) {
            this.available = available;
            return this;
        }

        /**
         * Checks the JVM flags, finds or downloads the model, picks the working types, checks the memory estimate and
         * loads it.
         *
         * @throws EngineSetupException a JVM flag is missing
         * @throws ModelNotFoundException the model cannot be found or downloaded
         * @throws UnsupportedModelException the architecture, format or working types are not supported
         * @throws InsufficientMemoryException the estimate does not fit and the types were chosen automatically
         */
        public Bi4j build() {
            return DefaultBi4j.create(this);
        }
    }
}
