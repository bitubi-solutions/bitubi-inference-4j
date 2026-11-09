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

import ao.bitubi.inference4j.server.chat.Conversation;
import ao.bitubi.inference4j.server.chat.FileConversationStore;
import com.microsoft.playwright.Locator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class KeyboardE2E extends E2EBase {
    private UUID seeded;

    @BeforeEach
    void seed() {
        seeded = UUID.randomUUID();
        Instant now = Instant.now();
        Conversation c = Conversation.create(seeded, "fake/model", new Conversation.Settings(0.7f, 512, ""), now)
            .withMessage(Conversation.Msg.user("an older question", now, null), now)
            .withMessage(new Conversation.Msg("assistant", "an older answer", now, "STOP", 3, 3, 10.0, null), now);
        new FileConversationStore(data).save(c);
    }

    private boolean focused(String selector) {
        return (Boolean) page.evaluate("(sel) => !!document.activeElement && document.activeElement.matches(sel)", selector);
    }

    /** Tab until the focus is on something matching the selector (keyboard only: no pointer involved). */
    private void tabTo(String selector) {
        for (int i = 0; i < 60 && !focused(selector); i++) page.keyboard().press("Tab");
        assertTrue(focused(selector), "could not reach " + selector + " by keyboard");
    }

    @Test
    void ctrlShiftONewChatAndCtrlKFilterAndCtrlShiftSSidebar() {
        page.setViewportSize(1280, 800);
        page.navigate(url("/c/" + seeded));
        page.locator("#text").fill("a draft");
        page.keyboard().press("Control+K");
        assertTrue(focused("#search input"), "Ctrl+K moves focus to the filter");
        assertEquals("a draft", page.locator("#text").inputValue(), "the draft is untouched");
        page.keyboard().press("Control+Shift+S");
        assertFalse(page.locator("#sidebar").isVisible(), "Ctrl+Shift+S hides the sidebar");
        page.keyboard().press("Control+Shift+S");
        assertTrue(page.locator("#sidebar").isVisible(), "and shows it again");
        page.keyboard().press("Control+Shift+O");
        page.waitForURL(url("/"));
    }

    @Test
    void questionMarkOpensTheListFromThePageButNotFromTheComposer() {
        page.navigate(url("/"));
        page.locator("#text").focus();
        page.keyboard().type("why?");
        assertEquals("why?", page.locator("#text").inputValue(), "a question mark in the composer is just text");
        assertFalse(page.locator("dialog#shortcuts").isVisible());

        page.evaluate("() => document.activeElement.blur()");
        page.keyboard().press("Shift+?");
        assertTrue(page.locator("dialog#shortcuts").isVisible());
        assertTrue(page.locator("dialog#shortcuts").innerText().contains("New chat"));
        page.keyboard().press("Escape");
        assertFalse(page.locator("dialog#shortcuts").isVisible());
    }

    @Test
    void enterDuringAnImeCompositionDoesNotSend() {
        page.navigate(url("/"));
        page.locator("#text").fill("nihon");
        Object notCancelled = page.evaluate("() => document.getElementById('text').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true }))");
        assertEquals(Boolean.TRUE, notCancelled, "the composition keeps its Enter");
        assertEquals(0, page.locator("#messages li").count());
        assertEquals("nihon", page.locator("#text").inputValue());
    }

    @Test
    void aWholeSessionByKeyboardOnly() {
        fake.script(java.util.stream.IntStream.rangeClosed(1, 200).mapToObj(i -> "w" + i + " ").toArray(String[]::new)).delayMs(40);
        page.setViewportSize(1280, 800);
        page.navigate(url("/c/" + seeded));
        page.keyboard().press("Control+Shift+O"); // new chat
        page.waitForURL(url("/"));
        tabTo("#text");
        page.keyboard().type("keyboard only");
        page.keyboard().press("Enter"); // send
        page.locator("#stop").waitFor();
        page.keyboard().press("Escape"); // stop
        page.locator("#messages .answer .footer").last().waitFor();
        assertTrue(page.locator("#messages .answer .footer").last().innerText().toLowerCase().contains("stopped"));

        // rename: reach the first conversation, then its Rename button
        tabTo("#conversation-list li.conv a");
        page.keyboard().press("Tab");
        assertTrue(focused("button[aria-label='Rename conversation']"));
        page.keyboard().press("Enter");
        page.locator("[data-rename-input]").waitFor();
        page.keyboard().press("Control+A");
        page.keyboard().type("Keyboard chat");
        page.keyboard().press("Enter");
        page.locator("#conversation-list li.conv a").getByText("Keyboard chat").waitFor();

        // delete through the dialog, by keyboard
        String openId = page.locator("#chat").getAttribute("data-conversation");
        tabTo("#conv-" + openId + " button[aria-label='Delete conversation']");
        page.keyboard().press("Enter");
        page.locator("dialog[open]").waitFor();
        tabTo("dialog[open] .danger");
        page.keyboard().press("Enter");
        try {
            page.waitForURL(url("/"), new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(8000));
        } catch (RuntimeException e) {
            fail("no redirect after the delete: url=" + page.url() + " list=" + page.locator("#conversation-list").evaluate("e => e.outerHTML") + " dialogOpen=" + page.locator("dialog[open]").count());
        }
        assertEquals(1, page.locator("#conversation-list li.conv").count(), "only the seeded conversation is left");
    }
}
