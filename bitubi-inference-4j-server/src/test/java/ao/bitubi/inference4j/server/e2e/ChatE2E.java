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
package ao.bitubi.inference4j.server.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ChatE2E extends E2EBase {

    @Test
    void sendShowsTokensProgressively() {
        fake.script(java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "w" + i + " ").toArray(String[]::new)).delayMs(60);
        page.navigate(url("/"));
        // record every text the last answer shows, with a page-side clock, plus when the form was submitted
        page.evaluate("""
            () => {
              window.__seen = []; window.__submitted = null;
              document.addEventListener('submit', () => { window.__submitted = performance.now(); }, true);
              new MutationObserver(() => {
                const a = document.querySelector('#messages .answer:last-child, #messages li:last-child .answer');
                if (a && a.textContent.trim()) {
                  const t = a.textContent.trim();
                  const last = window.__seen[window.__seen.length - 1];
                  if (!last || last.text !== t) window.__seen.push({ text: t, at: performance.now() });
                }
              }).observe(document.getElementById('messages'), { subtree: true, childList: true, characterData: true });
            }
            """);
        Locator text = page.locator("#text");
        text.fill("hello");
        text.press("Enter");

        Locator footer = page.locator("#messages .answer .footer, #messages .answer footer").last();
        footer.waitFor();
        assertTrue(page.locator("#messages .answer").last().innerText().contains("w40"));
        assertEquals("", text.inputValue(), "composer is cleared");
        assertTrue((Boolean) page.evaluate("() => document.activeElement && document.activeElement.id === 'text'"), "composer has focus");
        assertTrue(page.locator("#send").isVisible(), "Send is back");
        assertEquals(0, page.locator("#stop").count());

        @SuppressWarnings("unchecked")
        List<Object> seen = (List<Object>) page.evaluate("() => window.__seen");
        assertTrue(seen.size() >= 3, "the answer grew over several DOM updates: " + seen);
        double submitted = ((Number) page.evaluate("() => window.__submitted")).doubleValue();
        double first = ((Number) page.evaluate("() => window.__seen[0].at")).doubleValue();
        assertTrue(first - submitted < 600, "first text " + (first - submitted) + " ms after the submit");
        assertTrue(footer.innerText().toLowerCase().contains("stop") || footer.innerText().toLowerCase().contains("token"), footer.innerText());
    }

    private static String[] words(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> "w" + i + " ").toArray(String[]::new);
    }

    private void send(com.microsoft.playwright.Page p, String text) {
        Locator box = p.locator("#text");
        box.fill(text);
        box.press("Enter");
    }

    private String conversationId(com.microsoft.playwright.Page p) {
        return p.locator("#chat").getAttribute("data-conversation");
    }

    @Test
    void stopEndsTheAnswerQuicklyAndTheChatContinues() {
        fake.script(words(300)).delayMs(40);
        page.navigate(url("/"));
        send(page, "go");
        page.locator("#messages .answer .md").last().waitFor();
        page.waitForFunction("() => document.querySelector('#messages li:last-child .answer').textContent.includes('w3')");
        page.locator("#stop").click();
        page.locator("#messages .answer .footer").last().waitFor();
        String footer = page.locator("#messages .answer .footer").last().innerText();
        assertTrue(footer.toLowerCase().contains("stopped"), footer);
        int length = page.locator("#messages .answer").last().innerText().length();
        page.waitForTimeout(300); // a stopped answer must not keep growing
        assertEquals(length, page.locator("#messages .answer").last().innerText().length());
        fake.script("again ", "ok");
        send(page, "next");
        page.waitForFunction("() => document.querySelector('#messages li:last-child .answer .footer') && document.querySelector('#messages li:last-child .answer').textContent.includes('again ok')");
    }

    @Test
    void reloadMidAnswerReattaches() {
        fake.script(words(60)).delayMs(60);
        page.navigate(url("/"));
        send(page, "go");
        page.waitForFunction("() => document.querySelector('#messages li:last-child .answer')?.textContent.includes('w3')");
        page.reload();
        assertTrue(page.url().contains("/c/"), page.url());
        page.waitForFunction("() => document.querySelector('#messages .answer')?.textContent.includes('w')");
        page.waitForFunction("() => document.querySelector('#messages li:last-child .answer .footer')", null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(15000));
        assertTrue(page.locator("#messages .answer").last().innerText().contains("w60"));
    }

    @Test
    void restartKeepsConversations() {
        fake.script("Hello ", "there");
        page.navigate(url("/"));
        send(page, "first question");
        page.locator("#messages .answer .footer").last().waitFor();
        String id = conversationId(page);
        context.close();
        server.close();

        fake = new ao.bitubi.inference4j.server.testing.FakeBi4j();
        server = startServer(ao.bitubi.inference4j.server.ModelHost.ready(fake));
        newPage();
        page.navigate(url("/c/" + id));
        assertTrue(page.locator("#messages").innerText().contains("first question"));
        assertTrue(page.locator("#messages").innerText().contains("Hello there"));
        fake.script("Still ", "here");
        send(page, "and a follow-up");
        page.waitForFunction("() => document.querySelector('#messages li:last-child .answer .footer') && document.querySelector('#messages li:last-child .answer').textContent.includes('Still here')");
    }

    @Test
    void renameAndDelete() {
        fake.script("ok");
        page.navigate(url("/"));
        send(page, "keep me");
        page.locator("#messages .answer .footer").last().waitFor();
        Locator item = page.locator("#conversation-list li.conv").first();
        item.hover();
        item.getByLabel("Rename conversation").click();
        Locator input = page.locator("[data-rename-input]");
        input.fill("Another name");
        input.press("Enter");
        page.locator("#conversation-list li.conv a").getByText("Another name").waitFor();
        // Escape leaves the name as it was
        page.locator("#conversation-list li.conv").first().hover();
        page.locator("#conversation-list li.conv").first().getByLabel("Rename conversation").click();
        page.locator("[data-rename-input]").fill("Discarded");
        page.locator("[data-rename-input]").press("Escape");
        page.locator("#conversation-list li.conv a").getByText("Another name").waitFor();
        assertEquals(0, page.locator("[data-rename-input]").count());
        // delete through the dialog
        page.locator("#conversation-list li.conv").first().hover();
        page.locator("#conversation-list li.conv").first().locator("button[aria-label=\"Delete conversation\"]").click();
        page.locator("dialog[open] .danger").click();
        page.waitForURL(url("/"));
        assertEquals(0, page.locator("#conversation-list li.conv").count());
    }

    @Test
    void busyAndContextFullAlerts() {
        fake.script(words(200)).delayMs(50);
        page.navigate(url("/"));
        send(page, "first");
        page.locator("#stop").waitFor();
        String id = conversationId(page);
        com.microsoft.playwright.Page other = context.newPage();
        watch(other);
        other.navigate(url("/c/" + id));
        send(other, "second tab text");
        other.locator("#alerts").getByText("answering in another tab").waitFor();
        assertEquals("second tab text", other.locator("#text").inputValue(), "the text stays");
        assertTrue(other.locator("#alerts a").isVisible());
        other.close();
        page.locator("#stop").click();
        page.locator("#messages .answer .footer").last().waitFor();

        fake.contextLimit(24); // above the "conversation is full" threshold, below the prompt
        page.navigate(url("/"));
        send(page, "a very long prompt " + "that cannot possibly fit ".repeat(30));
        Locator alert = page.locator("#alerts");
        alert.locator("a").getByText("New chat").waitFor();
        assertTrue(alert.innerText().matches("(?s).*\\d+.*"), alert.innerText());
        assertEquals("a very long prompt " + "that cannot possibly fit ".repeat(30), page.locator("#text").inputValue());
    }

    @Test
    void modelLoadingPanel() throws Exception {
        context.close();
        server.close();
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        var host = new ao.bitubi.inference4j.server.ModelHost(progress -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return fake;
        });
        server = startServer(host);
        host.start();
        newPage();
        page.navigate(url("/"));
        page.locator("#model-status").waitFor();
        assertTrue(page.locator("#model-status h2").innerText().toLowerCase().contains("loading"));
        assertTrue(page.locator("#send").count() == 0 || page.locator("#send").isDisabled());
        gate.countDown();
        page.locator("#composer").waitFor(new Locator.WaitForOptions().setTimeout(15000));
        assertTrue(page.locator("#text").isEnabled());
    }

    @Test
    void maliciousContentIsInert() {
        java.util.UUID id = java.util.UUID.randomUUID();
        java.time.Instant now = java.time.Instant.now();
        String evil = "<img src=x onerror=window.__xss=1>";
        var c = ao.bitubi.inference4j.server.chat.Conversation.create(id, "m", new ao.bitubi.inference4j.server.chat.Conversation.Settings(0.7f, 256, null), now)
            .withMessage(ao.bitubi.inference4j.server.chat.Conversation.Msg.user(evil, now, null), now)
            .withMessage(new ao.bitubi.inference4j.server.chat.Conversation.Msg("assistant",
                "<script>window.__xss=1</script><iframe src=\"javascript:window.__xss=1\"></iframe>\n\n[click](javascript:window.__xss=1) <b onmouseover=window.__xss=1>x</b>",
                now, "STOP", 5, 5, 10.0, null), now);
        new ao.bitubi.inference4j.server.chat.FileConversationStore(data).save(c);
        page.onDialog(d -> {
            problems.add("dialog opened: " + d.message());
            d.dismiss();
        });
        page.navigate(url("/c/" + id));
        page.locator("#messages .user-text").waitFor();
        assertEquals(evil, page.locator("#messages .user-text").innerText());
        assertEquals(0, page.locator("#messages img, #messages iframe, #messages script").count());
        assertTrue(page.locator("#conversation-list").innerText().contains("<img"), "the title is text");
        assertEquals(0, page.locator("#messages a[href^='javascript']").count());
        page.locator("#messages .answer a").first().click(new Locator.ClickOptions().setTimeout(1000).setTrial(true));
        assertNull(page.evaluate("() => window.__xss"));
    }

    @Test
    void markdownRenders() {
        fake.script("Here:\n\n```java\nint x = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nSee [site](https://example.com/x).");
        page.navigate(url("/"));
        send(page, "show");
        page.locator("#messages .answer .footer").last().waitFor();
        assertEquals("java", page.locator(".code .lang").innerText().trim());
        page.locator("[data-copy]").click();
        assertEquals("int x = 1;", ((String) page.evaluate("() => navigator.clipboard.readText()")).strip());
        assertEquals(1, page.locator("#messages .answer table").count());
        Locator link = page.locator("#messages .answer a[href='https://example.com/x']");
        assertEquals("_blank", link.getAttribute("target"));
        assertTrue(link.getAttribute("rel").contains("noopener"));
    }
}
