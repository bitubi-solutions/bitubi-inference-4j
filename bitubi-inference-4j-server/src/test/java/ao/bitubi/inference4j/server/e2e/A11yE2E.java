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

import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.chat.Conversation;
import ao.bitubi.inference4j.server.chat.FileConversationStore;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** axe-core (WCAG 2.0 to 2.2 A and AA plus best practices): zero serious or critical violations in every state, both themes, two widths. */
public class A11yE2E extends E2EBase {
    private static final String AXE = load("/META-INF/resources/webjars/axe-core/4.11.3/axe.min.js");

    private static String load(String path) {
        try (var in = A11yE2E.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("axe-core webjar missing: " + path, e);
        }
    }

    @Override
    Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions().setBypassCSP(true); // only to inject axe; the CSP itself is asserted by the other E2E classes
    }

    private UUID seed() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Conversation c = Conversation.create(id, "fake/model", new Conversation.Settings(0.7f, 512, ""), now)
            .withMessage(Conversation.Msg.user("an older question", now, null), now)
            .withMessage(new Conversation.Msg("assistant", "an older answer:\n\n```java\nint x = 1;\n```", now, "STOP", 3, 3, 10.0, null), now);
        new FileConversationStore(data).save(c);
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<String> violations() {
        page.addScriptTag(new Page.AddScriptTagOptions().setContent(AXE));
        return (List<String>) page.evaluate("""
            async () => {
              const r = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] } });
              return r.violations.filter(v => v.impact === 'serious' || v.impact === 'critical')
                .map(v => v.id + ' (' + v.impact + '): ' + v.help + ' -> ' + v.nodes.slice(0, 3).map(n => n.target.join(' ')).join(' | '));
            }
            """);
    }

    private void theme(String mode) {
        page.evaluate("(m) => window.bi4jTheme.set(m)", mode);
    }

    private void audit(List<String> out, String state, String themeName, int width) {
        for (String v : violations()) out.add(state + " / " + themeName + " / " + width + "px: " + v);
    }

    /** Runs {@code reach} (which puts the page in a state) for each theme and width and audits it. */
    private void eachCombination(List<String> out, String state, Runnable reach) {
        for (int width : new int[] { 1280, 360 }) {
            for (String themeName : new String[] { "light", "dark" }) {
                page.setViewportSize(width, width == 360 ? 740 : 800);
                reach.run();
                theme(themeName);
                audit(out, state, themeName, width);
            }
        }
    }

    @Test
    void noSeriousOrCriticalViolationsInAnyStateOrTheme() {
        List<String> all = new ArrayList<>();
        UUID seeded = seed();
        fake.script("Here is code:\n\n```java\nint x = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nAnd a [link](https://example.com/x).");

        eachCombination(all, "empty chat", () -> page.navigate(url("/")));

        eachCombination(all, "chat with answer", () -> {
            page.navigate(url("/"));
            page.locator("#text").fill("show me");
            page.locator("#text").press("Enter");
            page.locator("#messages .answer .footer").last().waitFor();
        });

        eachCombination(all, "settings open", () -> {
            page.navigate(url("/"));
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
            page.locator("#settings-open").click();
            page.locator("#settings-form").waitFor();
        });

        eachCombination(all, "shortcut list", () -> {
            page.navigate(url("/"));
            page.evaluate("() => document.activeElement.blur()"); // a "?" typed in the composer is text, not the shortcut
            page.keyboard().press("Shift+?");
            page.locator("dialog#shortcuts").waitFor();
        });

        eachCombination(all, "conversation list and delete dialog", () -> {
            page.navigate(url("/c/" + seeded));
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
            if (page.locator("#sidebar-toggle").isVisible()) page.locator("#sidebar-toggle").click();
            Locator item = page.locator("#conversation-list li.conv").first();
            item.hover();
            item.locator("button[aria-label='Delete conversation']").click();
            page.locator("dialog[open]").waitFor();
        });

        eachCombination(all, "context-full alert", () -> {
            fake.contextLimit(24);
            page.navigate(url("/"));
            page.locator("#text").fill("a very long prompt " + "that cannot possibly fit ".repeat(30));
            page.locator("#text").press("Enter");
            page.locator("#alerts a").waitFor();
            fake.contextLimit(4096);
        });

        assertTrue(all.isEmpty(), "serious or critical accessibility violations:\n" + String.join("\n", all));
    }

    @Test
    void theLoadingAndFailedPanelsAreAccessibleToo() throws Exception {
        List<String> all = new ArrayList<>();
        context.close();
        server.close();
        CountDownLatch gate = new CountDownLatch(1);
        ModelHost loading = new ModelHost(progress -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return fake;
        });
        server = startServer(loading);
        loading.start();
        newPage();
        eachCombination(all, "loading panel", () -> {
            page.navigate(url("/"));
            page.locator("#model-status").waitFor();
        });
        gate.countDown();

        context.close();
        server.close();
        ModelHost failed = new ModelHost(progress -> {
            throw new IllegalStateException("The model could not be found: /x");
        });
        server = startServer(failed);
        failed.start();
        newPage();
        eachCombination(all, "failed panel", () -> {
            page.navigate(url("/"));
            page.locator(".error-detail").waitFor();
        });
        assertTrue(all.isEmpty(), "serious or critical accessibility violations:\n" + String.join("\n", all));
    }

    @Test
    void structureNamesAndTargets() {
        UUID seeded = seed();
        page.setViewportSize(1280, 800);
        page.navigate(url("/c/" + seeded));
        assertEquals(1, page.locator("h1").count(), "exactly one h1");
        assertEquals(1, page.locator("nav[aria-label='Conversations']").count());
        assertEquals(1, page.locator("main").count());
        assertEquals(1, page.locator("form#composer[aria-label]").count(), "the composer form is a named landmark");
        assertEquals("page", page.locator("#conversation-list a").first().getAttribute("aria-current"));
        assertEquals("off", page.locator("#messages").getAttribute("aria-live") == null ? "off" : page.locator("#messages").getAttribute("aria-live"));

        // every control has an accessible name
        @SuppressWarnings("unchecked")
        List<String> unnamed = (List<String>) page.evaluate("""
            () => [...document.querySelectorAll('button, a[href], input, textarea, select')]
              .filter(e => !e.closest('[hidden]'))
              .filter(e => !(e.getAttribute('aria-label') || (e.labels && e.labels.length) || e.textContent.trim() || e.getAttribute('title') || e.getAttribute('aria-labelledby')))
              .map(e => e.outerHTML.slice(0, 80))
            """);
        assertTrue(unnamed.isEmpty(), "controls without an accessible name: " + unnamed);

        // touch targets: at least 44 x 44 for every visible control (hover first so the row buttons show)
        page.locator("#conversation-list li.conv").first().hover();
        @SuppressWarnings("unchecked")
        List<String> small = (List<String>) page.evaluate("""
            () => [...document.querySelectorAll('button, a.new-chat, #conversation-list a, input:not([type=hidden]), textarea, select')]
              .filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; })
              .map(e => { const r = e.getBoundingClientRect(); return { e, w: Math.round(r.width), h: Math.round(r.height) }; })
              .filter(x => x.w < 44 || x.h < 44)
              .map(x => x.e.tagName.toLowerCase() + '.' + x.e.className + '#' + x.e.id + ' ' + x.w + 'x' + x.h)
            """);
        assertTrue(small.isEmpty(), "targets under 44 px: " + small);
    }

    @Test
    void theEndOfAnAnswerIsAnnouncedAndTheStreamIsNotLive() {
        fake.script("done");
        page.navigate(url("/"));
        page.locator("#text").fill("hi");
        page.locator("#text").press("Enter");
        page.locator("#messages .answer .footer").last().waitFor();
        assertEquals("off", page.locator("#messages .answer").last().getAttribute("aria-live") == null ? "off" : page.locator("#messages .answer").last().getAttribute("aria-live"));
        assertEquals("Answer complete", page.locator("#status-live").innerText());
    }
}
