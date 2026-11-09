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

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.SessionRegistry;
import ao.bitubi.inference4j.server.chat.ChatTurns;
import ao.bitubi.inference4j.server.chat.Conversation;
import ao.bitubi.inference4j.server.chat.ConversationStore;
import ao.bitubi.inference4j.server.chat.MarkdownRenderer;
import ao.bitubi.inference4j.server.chat.RunningTurn;
import ao.bitubi.inference4j.server.ui.ViewModels.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Builds the records the templates print, from the store, the live turns and the model state. */
final class UiModels {
    static final List<String> STARTERS = List.of(
        "Explain Java streams in simple terms",
        "Write a short poem about autumn",
        "Give me three ideas for a weekend project",
        "Summarize how a hash map works"
    );
    static final Conversation.Settings DEFAULT_SETTINGS = new Conversation.Settings(0.7f, 512, "");
    private static final int MIN_ROOM = 8;

    private final ConversationStore store;
    private final ChatTurns chat;
    private final SessionRegistry sessions;
    private final ModelHost host;
    private final MarkdownRenderer markdown;
    private final Clock clock;

    private final Conversation.Settings defaults;

    UiModels(ConversationStore store, ChatTurns chat, SessionRegistry sessions, ModelHost host, MarkdownRenderer markdown, Clock clock, Conversation.Settings defaults) {
        this.defaults = defaults;
        this.store = store;
        this.chat = chat;
        this.sessions = sessions;
        this.host = host;
        this.markdown = markdown;
        this.clock = clock;
    }

    Conversation.Settings defaults() {
        return defaults;
    }

    ModelStatus status() {
        ModelHost.Status s = host.status();
        String state = s.state().name().toLowerCase(Locale.ROOT);
        Optional<Bi4j> bi4j = host.bi4j();
        String name = bi4j.map(b -> b.info().name()).orElse("Loading model...");
        String info = bi4j.map(b -> b.info().weightType() + " | ctx " + b.info().contextLength()).orElse("");
        return new ModelStatus(state, name, s.state() == ModelHost.State.READY ? "" : s.detail(), s.done(), s.total(), info);
    }

    PageModel page(UUID id, String query, boolean includeChat) {
        ModelStatus status = status();
        ChatModel chatModel = status.ready() && includeChat ? chatModel(id) : null;
        return new PageModel(status, sidebar(query, id), query == null ? "" : query, chatModel, store.writable(), store.lastError().orElse(""));
    }

    ChatModel chatModel(UUID id) {
        Conversation c = store.load(id).orElse(null);
        Optional<RunningTurn> running = chat.running(id);
        int total = host.bi4j().map(b -> b.info().contextLength()).orElse(0);
        int used = sessions.find(id).map(s -> s.contextTokens()).orElseGet(() -> c == null ? 0 : estimate(c));
        boolean readOnly = total > 0 && used + MIN_ROOM > total;
        List<MessageModel> messages = new ArrayList<>();
        String banner = null;
        Conversation.Settings settings = defaults;
        String title = "";
        if (c != null) {
            title = c.title();
            settings = c.settings();
            String current = host.bi4j().map(b -> b.info().name()).orElse(c.model());
            if (c.model() != null && !c.model().equals(current)) banner = "Created with " + c.model() + ", now answering with " + current + ".";
            String lastUser = null;
            for (Conversation.Msg m : c.messages()) {
                if ("user".equals(m.role())) {
                    lastUser = m.text();
                    String status = "pending".equals(m.status()) && running.isEmpty() ? "pending" : null;
                    messages.add(new MessageModel("user", null, m.text(), null, status, status == null ? null : m.text()));
                } else {
                    String footer = ViewModels.footer(m.finishReason(), m.generatedTokens(), m.tokensPerSecond(), m.status());
                    String status = "interrupted".equals(m.status()) ? "interrupted" : null;
                    messages.add(new MessageModel("assistant", markdown.render(m.text()), null, footer, status, lastUser));
                }
            }
        }
        RunningModel runningModel = running.map(r -> new RunningModel(r.id(), markdown.render(r.text()))).orElse(null);
        return new ChatModel(
            id,
            title,
            messages,
            runningModel,
            new ContextModel(used, total),
            new SettingsModel(settings.temperature(), settings.maxNewTokens()),
            readOnly,
            readOnly ? "This conversation is full. Start a new chat." : null,
            banner,
            STARTERS
        );
    }

    private static int estimate(Conversation c) {
        return c.messages().stream().mapToInt(m -> m.text().length() / 4 + 1).sum();
    }

    List<SidebarGroup> sidebar(String query, UUID current) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Map<String, List<SidebarItem>> groups = new LinkedHashMap<>();
        for (String label : List.of("Today", "Yesterday", "Previous 7 days", "Older")) groups.put(label, new ArrayList<>());
        for (Conversation c : store.list()) {
            if (c.messages().isEmpty()) continue; // a new chat is not stored until its first message
            if (!q.isEmpty() && !c.title().toLowerCase(Locale.ROOT).contains(q)) continue;
            groups.get(label(c.updatedAt(), today, zone)).add(new SidebarItem(c.id(), c.title(), c.id().equals(current)));
        }
        List<SidebarGroup> out = new ArrayList<>();
        groups.forEach((label, items) -> {
            if (!items.isEmpty()) out.add(new SidebarGroup(label, items));
        });
        return out;
    }

    private static String label(Instant at, LocalDate today, ZoneId zone) {
        long days = today.toEpochDay() - at.atZone(zone).toLocalDate().toEpochDay();
        if (days <= 0) return "Today";
        if (days == 1) return "Yesterday";
        if (days <= 7) return "Previous 7 days";
        return "Older";
    }

    SidebarItem item(UUID id, UUID current) {
        String title = store.load(id).map(Conversation::title).orElse("");
        return new SidebarItem(id, title, id.equals(current));
    }
}
