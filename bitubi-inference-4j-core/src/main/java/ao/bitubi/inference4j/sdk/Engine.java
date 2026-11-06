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

import ao.bitubi.inference4j.model.AbstractModel;
import ao.bitubi.inference4j.model.functions.Generator;
import ao.bitubi.inference4j.safetensors.prompt.PromptContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** The only place that calls {@link AbstractModel#generate}: maps settings, stop sequences, usage and events. */
final class Engine {
    private final AbstractModel model;
    private final int contextLength;

    /** @param contextLength the most tokens a session may hold; may be below the model's own maximum */
    Engine(AbstractModel model, int contextLength) {
        this.model = model;
        this.contextLength = contextLength;
    }

    /**
     * Runs one generation on an engine session. Tokens go to {@code onToken} as they are produced, minus any text
     * held back while it could still be the start of a stop sequence.
     */
    ChatResponse run(UUID session, PromptContext prompt, GenerationSettings s, Consumer<Token> onToken) {
        int promptTokens = model.getTokenizer().encode(prompt.getPrompt()).length;
        int heldTokens = model.sessionPosition(session);
        if (heldTokens + promptTokens >= contextLength) {
            throw new ContextLengthExceededException(session, heldTokens, promptTokens, contextLength);
        }
        int maxNewTokens = Math.min(s.maxNewTokens(), contextLength - heldTokens - promptTokens);

        StopMatcher matcher = new StopMatcher(s.stop());
        StringBuilder text = new StringBuilder();
        int[] emitted = { 0 };
        int[] pieces = { 0 };
        long start = System.nanoTime();
        long[] first = { 0 };
        Consumer<String> emit = safe -> {
            if (safe.isEmpty()) return;
            text.append(safe);
            onToken.accept(new Token(safe, emitted[0]++));
        };

        Generator.Response r = model.generate(session, prompt, s.temperature(), maxNewTokens, (piece, ms) -> {
            if (pieces[0]++ == 0) first[0] = System.nanoTime();
            emit.accept(matcher.accept(piece));
            // Interrupt only after the text before the stop was handed over: a blocked consumer would reject it
            if (matcher.stopped()) Thread.currentThread().interrupt();
        });

        if (matcher.stopped()) {
            Thread.interrupted(); // the flag was ours: clear it so the caller's thread is left as it was
        } else {
            emit.accept(matcher.flush());
        }

        FinishReason reason = switch (r.finishReason) {
            case STOP_TOKEN, TOOL_CALL -> FinishReason.STOP;
            case MAX_TOKENS -> FinishReason.LENGTH;
            case CANCELLED -> FinishReason.CANCELLED;
            case ERROR -> throw new IllegalStateException("The engine reported an error for session " + session);
        };
        // The stop wins however the engine ended: on the last allowed token or on its own stop token it would
        // otherwise be reported as LENGTH or STOP
        if (matcher.stopped()) reason = FinishReason.STOP_SEQUENCE;

        long end = System.nanoTime();
        Duration firstToken = Duration.ofNanos(first[0] == 0 ? 0 : first[0] - start);
        double afterFirst = first[0] == 0 ? 0 : (end - first[0]) / 1e9;
        double tps = pieces[0] > 1 && afterFirst > 0 ? (pieces[0] - 1) / afterFirst : 0;
        Usage usage = new Usage(r.promptTokens, pieces[0], firstToken, tps);

        GenerationEvent event = new GenerationEvent();
        event.session = session.toString();
        event.promptTokens = usage.promptTokens();
        event.generatedTokens = usage.generatedTokens();
        event.timeToFirstTokenMillis = firstToken.toMillis();
        event.tokensPerSecond = tps;
        event.finishReason = reason.name();
        event.commit();

        return new ChatResponse(text.toString(), reason, usage);
    }

    /**
     * Finds stop sequences in text that arrives in pieces. Text that could still turn into a stop sequence is held
     * back; once a stop sequence completes, {@link #stopped()} is true and nothing more is emitted. Not thread-safe:
     * one matcher per generation.
     */
    static final class StopMatcher {
        private final List<String> stops;
        private final StringBuilder pending = new StringBuilder();
        private boolean stopped;

        StopMatcher(List<String> stops) {
            this.stops = stops;
        }

        /** @return the text that is safe to emit now */
        String accept(String piece) {
            if (stopped) return "";
            if (stops.isEmpty()) return piece;
            pending.append(piece);
            String s = pending.toString();

            int hit = -1;
            for (String stop : stops) {
                int i = s.indexOf(stop);
                if (i >= 0 && (hit < 0 || i < hit)) hit = i;
            }
            if (hit >= 0) {
                stopped = true;
                pending.setLength(0);
                return s.substring(0, hit);
            }

            int hold = 0; // longest tail of s that is the beginning of some stop sequence
            for (String stop : stops) {
                for (int len = Math.min(stop.length() - 1, s.length()); len > hold; len--) {
                    if (s.endsWith(stop.substring(0, len))) {
                        hold = len;
                        break;
                    }
                }
            }
            pending.setLength(0);
            pending.append(s, s.length() - hold, s.length());
            return s.substring(0, s.length() - hold);
        }

        boolean stopped() {
            return stopped;
        }

        /** The held-back text, when generation ended without a stop sequence. */
        String flush() {
            if (stopped) return "";
            String rest = pending.toString();
            pending.setLength(0);
            return rest;
        }
    }
}
