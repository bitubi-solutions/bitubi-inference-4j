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

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.server.ui.ViewModels.*;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

public class TemplatesTest {
    private final Templates templates = new Templates(TemplateEngine.createPrecompiled(ContentType.Html));
    private final List<String> rendered = new ArrayList<>();
    private static final String XSS = "<img src=x onerror=alert(1)>";

    private static final ModelStatus READY = new ModelStatus("ready", "fake/model", "", 0, 0, "F32 | ctx 4096");

    private Document page(PageModel p) {
        String html = templates.render("page", Map.of("page", p));
        rendered.add(html);
        return Jsoup.parse(html);
    }

    private static ChatModel chat(UUID id, List<MessageModel> messages, RunningModel running, ContextModel ctx, boolean readOnly) {
        return new ChatModel(id, "A title", messages, running, ctx, new SettingsModel(0.7f, 512), readOnly, readOnly ? "This conversation is full." : null, null, List.of("Explain streams", "Write a haiku"));
    }

    private static PageModel pageOf(ChatModel chat, List<SidebarGroup> groups) {
        return new PageModel(READY, groups, "", chat, true, null);
    }

    private static MessageModel user(String text) {
        return new MessageModel("user", null, text, null, null, null);
    }

    private static MessageModel assistant(String html, String footer) {
        return new MessageModel("assistant", html, null, footer, null, null);
    }

    @Test
    public void thePageHasLandmarksOneH1AndTheCurrentConversationMarked() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Document d = page(pageOf(chat(id, List.of(user("hi"), assistant("<p>Hello</p>", "2 tokens, 9.0 tok/s, end of text")), null, new ContextModel(10, 4096), false),
            List.of(new SidebarGroup("Today", List.of(new SidebarItem(id, "A title", true), new SidebarItem(other, "Other", false))))));
        assertEquals(1, d.select("h1").size());
        assertEquals(1, d.select("nav[aria-label=Conversations]").size());
        assertEquals(1, d.select("main#chat").size());
        assertEquals(1, d.select("form#composer").size());
        assertEquals("A title", d.selectFirst("a[aria-current=page]").text());
        assertEquals(1, d.select("a[aria-current]").size());
        assertEquals("Hello", d.selectFirst("li.assistant .md p").text());
        assertEquals("2 tokens, 9.0 tok/s, end of text", d.selectFirst("li.assistant .footer").text());
        assertEquals("/c/" + id + "/messages", d.selectFirst("form#composer").attr("hx-post"));
        assertNotNull(d.selectFirst("#alerts[role=alert]"));
        assertNotNull(d.selectFirst("#status-live[role=status]"));
        assertNotNull(d.selectFirst("#context-counter"));
        assertTrue(d.selectFirst("script[src*=htmax.min.js]") != null);
    }

    @Test
    public void theAnswerShellHasEverythingHtmxNeeds() {
        UUID id = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        Document d = page(pageOf(chat(id, List.of(user("hi")), new RunningModel(turn, "<p>so far</p>"), new ContextModel(10, 4096), false), List.of()));
        var shell = d.selectFirst("article#turn-" + turn);
        assertNotNull(shell);
        assertTrue(shell.hasClass("answer"));
        assertEquals("/c/" + id + "/turns/" + turn + "/stream", shell.attr("hx-sse:connect"));
        assertEquals("done", shell.attr("hx-sse:close"));
        assertEquals("this", shell.attr("hx-target"));
        assertEquals("innerMorph", shell.attr("hx-swap"));
        assertEquals("true", shell.attr("aria-busy"));
        assertEquals("off", shell.attr("aria-live"));
        assertEquals("so far", shell.text());
    }

    @Test
    public void userTextAndTitlesAreEscaped() {
        UUID id = UUID.randomUUID();
        ChatModel c = new ChatModel(id, XSS, List.of(user(XSS)), null, new ContextModel(1, 10), new SettingsModel(0.7f, 512), false, null, null, List.of());
        Document d = page(pageOf(c, List.of(new SidebarGroup("Today", List.of(new SidebarItem(id, XSS, true))))));
        assertTrue(d.select("img").stream().noneMatch(i -> i.attr("src").equals("x")), d.html());
        assertTrue(d.getAllElements().stream().noneMatch(e -> e.hasAttr("onerror")));
        assertEquals(XSS, d.selectFirst("li.user .user-text").text());
        assertEquals(XSS, d.selectFirst("nav a[aria-current]").text());
    }

    @Test
    public void aReadOnlyConversationHasADisabledComposerAndTheReason() {
        UUID id = UUID.randomUUID();
        Document d = page(pageOf(chat(id, List.of(user("a")), null, new ContextModel(4090, 4096), true), List.of()));
        assertTrue(d.selectFirst("textarea#text").hasAttr("disabled"));
        assertEquals("This conversation is full.", d.selectFirst("textarea#text").attr("placeholder"));
        assertTrue(d.selectFirst("#send").hasAttr("disabled"));
        assertEquals("/", d.selectFirst(".readonly-note a").attr("href"));
    }

    @Test
    public void theContextCounterTurnsAmberAtEightyFivePercent() {
        UUID id = UUID.randomUUID();
        Document warn = page(pageOf(chat(id, List.of(), null, new ContextModel(3482, 4096), false), List.of())); // 85.0 %
        Document calm = page(pageOf(chat(id, List.of(), null, new ContextModel(3441, 4096), false), List.of())); // 84.0 %
        assertTrue(warn.selectFirst("#context-counter").hasClass("warn"));
        assertFalse(calm.selectFirst("#context-counter").hasClass("warn"));
        assertEquals("3,482 / 4,096 tokens", warn.selectFirst("#context-counter").text());
    }

    @Test
    public void anEmptyConversationOffersStarterPrompts() {
        Document d = page(pageOf(chat(UUID.randomUUID(), List.of(), null, new ContextModel(0, 4096), false), List.of()));
        assertEquals(2, d.select("button[data-starter]").size());
        assertEquals("Explain streams", d.selectFirst("button[data-starter]").attr("data-starter"));
    }

    @Test
    public void theModelPanelPollsWhileLoadingAndStopsWhenFailed() {
        for (ModelStatus s : List.of(
            new ModelStatus("loading", "m", "reading weights", 0, 0, ""),
            new ModelStatus("downloading", "m", "model.safetensors", 30, 100, ""),
            new ModelStatus("failed", "m", "Cannot read a model at /x: Model location does not exist: /x", 0, 0, ""))) {
            String html = templates.render("page", Map.of("page", new PageModel(s, List.of(), "", null, true, null)));
            rendered.add(html);
            Document d = Jsoup.parse(html);
            var panel = d.selectFirst("section#model-status");
            assertNotNull(panel, s.state());
            assertEquals("role", "role"); // the panel is a live region
            assertEquals("status", panel.attr("role"));
            if (s.failed()) {
                assertFalse(panel.hasAttr("hx-trigger"));
                assertEquals(s.detail(), panel.selectFirst("pre").text());
            } else {
                assertEquals("every 1s", panel.attr("hx-trigger"));
                assertEquals("/model/status", panel.attr("hx-get"));
            }
            if (s.downloading()) assertEquals("30", panel.selectFirst("progress").attr("value"));
            assertNull(d.selectFirst("form#composer"));
        }
    }

    @Test
    public void aStatusWithHtmlInItsDetailIsEscaped() {
        String html = templates.render("status", Map.of("model", new ModelStatus("failed", "m", "<script>alert(1)</script>", 0, 0, "")));
        rendered.add(html);
        assertTrue(Jsoup.parse(html).select("script").isEmpty());
    }

    @Test
    public void anInterruptedAnswerOffersRetryWithTheSameText() {
        UUID id = UUID.randomUUID();
        MessageModel lost = new MessageModel("user", null, "what is a stream?", null, "pending", "what is a stream?");
        Document d = page(pageOf(chat(id, List.of(lost), null, new ContextModel(1, 10), false), List.of()));
        var retry = d.selectFirst(".interrupted form");
        assertEquals("/c/" + id + "/messages", retry.attr("hx-post"));
        assertEquals("what is a stream?", retry.selectFirst("input[name=text]").attr("value"));
        assertEquals("Answer interrupted.", d.selectFirst(".interrupted p").text());
    }

    @Test
    public void sidebarListShowsGroupsAndTheEmptyStates() {
        UUID id = UUID.randomUUID();
        String full = templates.render("sidebarList", Map.of("groups", List.of(new SidebarGroup("Yesterday", List.of(new SidebarItem(id, "Plan", false)))), "query", ""));
        Document d = Jsoup.parse(full);
        assertEquals("Yesterday", d.selectFirst("h2.group-label").text());
        assertEquals("/c/" + id, d.selectFirst("li.conv a").attr("href"));
        assertNotNull(d.selectFirst("li.conv dialog form"));
        String none = templates.render("sidebarList", Map.of("groups", List.<SidebarGroup>of(), "query", "zzz"));
        assertTrue(Jsoup.parse(none).text().contains("No conversations match"));
        rendered.add(full);
        rendered.add(none);
    }

    @Test
    public void noRenderedPageHasInlineStyleOrInlineScript() {
        thePageHasLandmarksOneH1AndTheCurrentConversationMarked();
        theAnswerShellHasEverythingHtmxNeeds();
        userTextAndTitlesAreEscaped();
        theModelPanelPollsWhileLoadingAndStopsWhenFailed();
        for (String html : rendered) {
            Document d = Jsoup.parse(html);
            assertTrue(d.select("[style]").isEmpty(), "inline style in " + html);
            assertTrue(d.select("script:not([src])").isEmpty(), "inline script in " + html);
            assertTrue(d.getAllElements().stream().noneMatch(e -> e.attributes().asList().stream().anyMatch(a -> a.getKey().startsWith("on"))), "inline handler");
        }
    }

    @Test
    public void footerWording() {
        assertEquals("212 tokens, 14.1 tok/s, end of text", ViewModels.footer("STOP", 212, 14.1, null));
        assertEquals("5 tokens, reached max tokens", ViewModels.footer("LENGTH", 5, 0.0, null));
        assertEquals("3 tokens, 2.0 tok/s, stopped", ViewModels.footer("CANCELLED", 3, 2.0, "stopped"));
        assertEquals("", ViewModels.footer(null, null, null, null));
    }
}
