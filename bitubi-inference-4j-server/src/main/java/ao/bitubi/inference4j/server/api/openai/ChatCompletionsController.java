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
package ao.bitubi.inference4j.server.api.openai;

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.ChatResponse;
import ao.bitubi.inference4j.sdk.GenerationSettings;
import ao.bitubi.inference4j.sdk.Message;
import ao.bitubi.inference4j.sdk.Token;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.Turn;
import ao.bitubi.inference4j.server.TurnService;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ChatCompletionsController {
    static final String SESSION_HEADER = "X-Bi4j-Session";
    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    /** The session header is present but not a UUID. */
    static final class InvalidSessionException extends IllegalArgumentException {
        InvalidSessionException(String value) {
            super("X-Bi4j-Session must be a UUID, got: " + value);
        }
    }

    private final ModelHost host;
    private final TurnService turns;
    private final JsonMapper mapper;

    ChatCompletionsController(ModelHost host, TurnService turns, JsonMapper mapper) {
        this.host = host;
        this.turns = turns;
        this.mapper = mapper;
    }

    static Optional<UUID> sessionId(String header) {
        if (header == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(header));
        } catch (IllegalArgumentException e) {
            throw new InvalidSessionException(header);
        }
    }

    @Operation(summary = "Creates a model response for the given chat conversation (OpenAI compatible)")
    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object create(@RequestHeader(name = SESSION_HEADER, required = false) String sessionHeader, @RequestBody OpenAi.ChatCompletionRequest request) {
        Optional<UUID> session = sessionId(sessionHeader);
        Bi4j bi4j = host.require();
        List<Message> messages = OpenAiMapper.messages(request);
        GenerationSettings settings = OpenAiMapper.settings(request, bi4j.info().contextLength());
        Turn turn = turns.start(session, messages, settings);
        try {
            turn.prime(); // context and engine errors become HTTP errors here, before anything is streamed
        } catch (RuntimeException e) {
            turn.close();
            throw e;
        }
        String model = bi4j.info().name();
        if (Boolean.TRUE.equals(request.stream())) return stream(turn, request, model);
        try (turn) {
            Iterator<Token> it = turn.tokens();
            while (it.hasNext()) it.next();
            ChatResponse r = turn.result();
            return new OpenAi.ChatCompletion(
                id(turn),
                "chat.completion",
                now(),
                model,
                List.of(new OpenAi.Choice(0, new OpenAi.AssistantMessage("assistant", r.text()), OpenAiMapper.finishReason(r.finishReason()))),
                OpenAiMapper.usage(r)
            );
        }
    }

    private SseEmitter stream(Turn turn, OpenAi.ChatCompletionRequest request, String model) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout: generations can be long
        boolean usage = request.streamOptions() != null && Boolean.TRUE.equals(request.streamOptions().includeUsage());
        emitter.onError(e -> turn.cancel()); // the container reports a dropped connection here, before any write fails
        emitter.onTimeout(turn::cancel);
        Thread.ofVirtual().name("bi4j-sse-" + turn.id()).start(() -> {
            String id = id(turn);
            long created = now();
            try (turn) {
                send(emitter, chunk(id, created, model, new OpenAi.Delta("assistant", ""), null));
                Iterator<Token> it = turn.tokens();
                while (it.hasNext()) send(emitter, chunk(id, created, model, new OpenAi.Delta(null, it.next().text()), null));
                ChatResponse r = turn.result();
                send(emitter, chunk(id, created, model, new OpenAi.Delta(null, null), OpenAiMapper.finishReason(r.finishReason())));
                if (usage) {
                    send(emitter, new OpenAi.ChatCompletionChunk(id, "chat.completion.chunk", created, model, List.of(), OpenAiMapper.usage(r)));
                }
                emitter.send(SseEmitter.event().data(" [DONE]"));
                emitter.complete();
            } catch (IOException | IllegalStateException clientGone) {
                log.debug("client left the stream: {}", clientGone.toString());
                turn.cancel(); // the API cancels when the client leaves
                emitter.completeWithError(clientGone);
            } catch (RuntimeException e) {
                log.warn("stream failed after the first token", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // OpenAI writes "data: {json}" with a space; Spring writes "data:{json}" and some clients (jvm-openai) need the space.
    private void send(SseEmitter emitter, Object data) throws IOException {
        emitter.send(SseEmitter.event().data(" " + mapper.writeValueAsString(data)));
    }

    private static OpenAi.ChatCompletionChunk chunk(String id, long created, String model, OpenAi.Delta delta, String finish) {
        return new OpenAi.ChatCompletionChunk(id, "chat.completion.chunk", created, model, List.of(new OpenAi.ChunkChoice(0, delta, finish)), null);
    }

    private static String id(Turn turn) {
        return "chatcmpl-" + turn.id();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
