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
package ao.bitubi.inference4j.server.ui;

import ao.bitubi.inference4j.sdk.ContextLengthExceededException;
import ao.bitubi.inference4j.sdk.SessionBusyException;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.ModelNotReadyException;
import ao.bitubi.inference4j.server.SessionRegistry;
import ao.bitubi.inference4j.server.chat.ChatTurns;
import ao.bitubi.inference4j.server.chat.Conversation;
import ao.bitubi.inference4j.server.chat.ConversationStore;
import ao.bitubi.inference4j.server.chat.MarkdownRenderer;
import ao.bitubi.inference4j.server.chat.RunningTurn;
import ao.bitubi.inference4j.server.ui.ViewModels.*;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The chat pages and the htmx fragments (spec section 6). The UI calls the services in process, never the HTTP API. */
@Controller
@ConditionalOnProperty(name = "bi4j.ui.enabled", havingValue = "true", matchIfMissing = true)
public class UiController {
    private static final Logger log = LoggerFactory.getLogger(UiController.class);
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    private final ConversationStore store;
    private final ChatTurns chat;
    private final ModelHost host;
    private final MarkdownRenderer markdown;
    private final Templates templates;
    private final UiProperties props;
    private final UiModels models;

    UiController(
        ConversationStore store,
        ChatTurns chat,
        SessionRegistry sessions,
        ModelHost host,
        MarkdownRenderer markdown,
        Templates templates,
        UiProperties props
    ) {
        this.store = store;
        this.chat = chat;
        this.host = host;
        this.markdown = markdown;
        this.templates = templates;
        this.props = props;
        this.models = new UiModels(store, chat, sessions, host, markdown, Clock.systemDefaultZone());
    }

    // ---- pages -----------------------------------------------------------------------------------------------

    @GetMapping("/")
    ResponseEntity<String> index() {
        return html(HttpStatus.OK, page(UUID.randomUUID(), ""));
    }

    @GetMapping("/c/{id}")
    ResponseEntity<String> conversation(@PathVariable String id, @RequestHeader(name = "HX-Request", required = false) String htmx) {
        UUID uuid = parse(id);
        if (uuid == null) return notFound();
        if (htmx == null) return html(HttpStatus.OK, page(uuid, ""));
        ModelStatus status = models.status();
        return html(HttpStatus.OK, status.ready() ? templates.render("chat", Map.of("chat", models.chatModel(uuid))) : templates.render("status", Map.of("model", status)));
    }

    private String page(UUID id, String query) {
        return templates.render("page", Map.of("page", models.page(id, query, true)));
    }

    @GetMapping("/sidebar")
    ResponseEntity<String> sidebar(@RequestParam(name = "q", defaultValue = "") String q, @RequestHeader(name = "HX-Current-URL", required = false) String current) {
        return html(HttpStatus.OK, templates.render("sidebarList", Map.of("groups", models.sidebar(q, currentId(current)), "query", q)));
    }

    @GetMapping("/model/status")
    ResponseEntity<String> modelStatus(@RequestHeader(name = "HX-Current-URL", required = false) String current) {
        ModelStatus status = models.status();
        if (!status.ready()) return html(HttpStatus.OK, templates.render("status", Map.of("model", status)));
        UUID id = Optional.ofNullable(currentId(current)).orElseGet(UUID::randomUUID);
        String body = templates.render("chat", Map.of("chat", models.chatModel(id))) + templates.render("modelInfo", Map.of("info", status.info()));
        return html(HttpStatus.OK, body);
    }

    // ---- sending, streaming, stopping ----------------------------------------------------------------------------

    @PostMapping("/c/{id}/messages")
    ResponseEntity<String> send(
        @PathVariable String id,
        @RequestParam(name = "text", defaultValue = "") String text,
        @RequestParam(name = "temperature", required = false) Float temperature,
        @RequestParam(name = "maxTokens", required = false) Integer maxTokens
    ) {
        UUID uuid = parse(id);
        if (uuid == null) return notFound();
        if (text.isBlank()) return alert(HttpStatus.BAD_REQUEST, "Type a message first.", null, null);
        Conversation.Settings stored = store.load(uuid).map(Conversation::settings).orElse(UiModels.DEFAULT_SETTINGS);
        float t = temperature == null ? stored.temperature() : Math.max(0f, Math.min(2f, temperature));
        int ctx = host.bi4j().map(b -> b.info().contextLength()).orElse(Integer.MAX_VALUE);
        int max = maxTokens == null ? stored.maxNewTokens() : Math.max(1, Math.min(ctx, maxTokens));
        try {
            RunningTurn turn = chat.send(uuid, text, new Conversation.Settings(t, max, stored.system()));
            MessageModel user = new MessageModel("user", null, text, null, null, null);
            Map<String, Object> params = new HashMap<>();
            params.put("conversationId", uuid);
            params.put("turnId", turn.id());
            params.put("userMessage", user);
            return html(HttpStatus.OK, templates.render("sent", params));
        } catch (SessionBusyException e) {
            boolean running = chat.running(uuid).isPresent();
            return alert(HttpStatus.CONFLICT, "This conversation is answering in another tab or API call.", running ? "/c/" + uuid : null, "Open the running answer");
        } catch (ContextLengthExceededException e) {
            return alert(HttpStatus.BAD_REQUEST, e.getMessage(), "/", "New chat");
        } catch (ModelNotReadyException e) {
            String why = e.status().state() == ModelHost.State.FAILED ? "The model failed to load: " + e.status().detail() : "The model is still loading. Try again in a moment.";
            return alert(HttpStatus.SERVICE_UNAVAILABLE, why, null, null);
        } catch (IllegalArgumentException e) {
            return alert(HttpStatus.BAD_REQUEST, e.getMessage(), null, null);
        }
    }

    @GetMapping(path = "/c/{id}/turns/{turn}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String id, @PathVariable String turn) {
        UUID conversationId = parse(id);
        UUID turnId = parse(turn);
        SseEmitter emitter = new SseEmitter(0L);
        if (conversationId == null || turnId == null) {
            emitter.complete();
            return emitter;
        }
        Optional<RunningTurn> running = chat.find(conversationId, turnId);
        long heartbeatMs = props.heartbeat().toMillis();
        Thread.ofVirtual().name("bi4j-ui-stream-" + turnId).start(() -> {
            try {
                if (running.isEmpty()) { // finished before the viewer arrived (or after a restart): tell what was stored
                    store.load(conversationId).flatMap(c -> c.messages().isEmpty() ? Optional.<Conversation.Msg>empty() : Optional.of(c.messages().get(c.messages().size() - 1)))
                        .ifPresent(m -> {
                            if ("assistant".equals(m.role())) sendQuietly(emitter, null, finalHtml(conversationId, m, null));
                            else { // the question was stored but the answer never was: the server restarted under this page
                                Conversation.Msg lost = new Conversation.Msg("assistant", "", java.time.Instant.now(), null, null, null, null, "interrupted");
                                sendQuietly(emitter, null, finalHtml(conversationId, lost, new IllegalStateException("the server restarted while answering. Reload the page to retry.")));
                            }
                        });
                    sendDone(emitter);
                    return;
                }
                Pump pump = new Pump();
                try (RunningTurn.Subscription sub = running.get().subscribe(pump::offer)) {
                    while (true) {
                        RunningTurn.Event e = pump.take(heartbeatMs);
                        if (e == null) {
                            emitter.send(SseEmitter.event().comment("keep-alive"));
                        } else if (e instanceof RunningTurn.Snapshot s) {
                            sendTo(emitter, null, templates.render("answerBody", Map.of("html", markdown.render(s.text()), "footer", "")));
                        } else if (e instanceof RunningTurn.Final f) {
                            sendTo(emitter, null, finalHtml(conversationId, f.message(), f.error()));
                            sendDone(emitter);
                            return;
                        }
                    }
                }
            } catch (IOException | InterruptedException gone) {
                // the viewer left; the generation goes on (the orphan timeout decides) and is stored either way
                if (gone instanceof InterruptedException) Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("ui stream failed: {}", e.toString());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private String finalHtml(UUID conversationId, Conversation.Msg m, Throwable error) {
        ChatModel after = models.chatModel(conversationId);
        Map<String, Object> params = new HashMap<>();
        params.put("conversationId", conversationId);
        params.put("html", markdown.render(m.text()));
        params.put("footer", ViewModels.footer(m.finishReason(), m.generatedTokens(), m.tokensPerSecond(), m.status()));
        params.put("error", error == null ? null : "The answer stopped early: " + error.getMessage());
        params.put("groups", models.sidebar("", conversationId));
        params.put("contextLabel", after.context().label());
        params.put("contextWarn", after.context().warn());
        return templates.render("turnEnd", params);
    }

    /** One SSE event; every line of the HTML is its own {@code data:} line (a newline would end the event). */
    private static void sendTo(SseEmitter emitter, String name, String htmlText) throws IOException {
        SseEmitter.SseEventBuilder b = SseEmitter.event();
        if (name != null) b.name(name);
        for (String line : htmlText.split("\n", -1)) b.data(" " + line);
        emitter.send(b);
    }

    private static void sendQuietly(SseEmitter emitter, String name, String htmlText) {
        try {
            sendTo(emitter, name, htmlText);
        } catch (IOException ignored) {
            // nobody listening
        }
    }

    private static void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(" "));
        } catch (IOException ignored) {
            // nobody listening
        }
        emitter.complete();
    }

    /** Hands the newest snapshot to the sending thread, so a slow viewer never slows the generation. */
    private static final class Pump {
        private RunningTurn.Event pending;

        synchronized void offer(RunningTurn.Event e) {
            if (pending instanceof RunningTurn.Final) return;
            pending = e;
            notifyAll();
        }

        synchronized RunningTurn.Event take(long timeoutMs) throws InterruptedException {
            if (pending == null) wait(Math.max(1, timeoutMs));
            RunningTurn.Event e = pending;
            pending = null;
            return e;
        }
    }

    @PostMapping("/c/{id}/turns/{turn}/stop")
    ResponseEntity<Void> stop(@PathVariable String id, @PathVariable String turn) {
        UUID c = parse(id);
        UUID t = parse(turn);
        if (c != null && t != null) chat.stop(c, t);
        return ResponseEntity.noContent().build();
    }

    // ---- rename, delete ---------------------------------------------------------------------------------------

    @GetMapping("/c/{id}/rename")
    ResponseEntity<String> renameForm(@PathVariable String id) {
        UUID uuid = parse(id);
        return uuid == null ? notFound() : html(HttpStatus.OK, templates.render("renameForm", Map.of("item", models.item(uuid, null))));
    }

    @GetMapping("/c/{id}/item")
    ResponseEntity<String> item(@PathVariable String id, @RequestHeader(name = "HX-Current-URL", required = false) String current) {
        UUID uuid = parse(id);
        return uuid == null ? notFound() : html(HttpStatus.OK, templates.render("sidebarItem", Map.of("item", models.item(uuid, currentId(current)))));
    }

    @PatchMapping("/c/{id}")
    ResponseEntity<String> rename(@PathVariable String id, @RequestParam(name = "title", defaultValue = "") String title, @RequestHeader(name = "HX-Current-URL", required = false) String current) {
        UUID uuid = parse(id);
        if (uuid == null) return notFound();
        String clean = title.strip().replaceAll("\\s+", " ");
        Optional<Conversation> c = store.load(uuid);
        if (clean.isEmpty() || c.isEmpty()) return html(HttpStatus.BAD_REQUEST, templates.render("renameForm", Map.of("item", models.item(uuid, null))));
        store.save(c.get().withTitle(clean.length() > 120 ? clean.substring(0, 120) : clean, java.time.Instant.now()));
        return html(HttpStatus.OK, templates.render("sidebarItem", Map.of("item", models.item(uuid, currentId(current)))));
    }

    @DeleteMapping("/c/{id}")
    ResponseEntity<String> delete(@PathVariable String id, @RequestHeader(name = "HX-Current-URL", required = false) String current) {
        UUID uuid = parse(id);
        if (uuid == null) return notFound();
        chat.running(uuid).ifPresent(t -> chat.stop(uuid, t.id()));
        store.delete(uuid);
        UUID open = currentId(current);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(HTML).header("Vary", "HX-Request");
        if (uuid.equals(open)) b.header("HX-Redirect", "/");
        return b.body(templates.render("sidebarList", Map.of("groups", models.sidebar("", open), "query", "")));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private ResponseEntity<String> alert(HttpStatus status, String message, String href, String linkText) {
        Map<String, Object> params = new HashMap<>();
        params.put("message", message);
        params.put("linkHref", href);
        params.put("linkText", linkText);
        return html(status, templates.render("alert", params));
    }

    private ResponseEntity<String> notFound() {
        return html(HttpStatus.NOT_FOUND, templates.render("error", Map.of("heading", "Conversation not found", "message", "There is no conversation with that address.")));
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).header("Vary", "HX-Request").body(body);
    }

    static UUID parse(String s) {
        try {
            UUID u = UUID.fromString(s);
            return u.toString().equals(s) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The conversation open in the browser, from the {@code HX-Current-URL} header htmx sends. */
    static UUID currentId(String url) {
        if (url == null) return null;
        int i = url.indexOf("/c/");
        if (i < 0) return null;
        String rest = url.substring(i + 3);
        int end = 0;
        while (end < rest.length() && (Character.digit(rest.charAt(end), 16) >= 0 || rest.charAt(end) == '-')) end++;
        return parse(rest.substring(0, end));
    }
}
