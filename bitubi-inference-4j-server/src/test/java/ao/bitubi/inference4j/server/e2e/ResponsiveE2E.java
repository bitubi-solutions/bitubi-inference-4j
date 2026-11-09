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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ReducedMotion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ResponsiveE2E extends E2EBase {
    private UUID seeded;

    @BeforeEach
    void seedAndStill() {
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        seeded = UUID.randomUUID();
        Instant now = Instant.now();
        Conversation c = Conversation.create(seeded, "fake/model", new Conversation.Settings(0.7f, 512, ""), now)
            .withMessage(Conversation.Msg.user("an older question", now, null), now)
            .withMessage(new Conversation.Msg("assistant", "an older answer", now, "STOP", 3, 3, 10.0, null), now);
        new FileConversationStore(data).save(c);
    }

    private boolean noSideScroll() {
        return (Boolean) page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth");
    }

    @Test
    void onANarrowScreenTheSidebarIsADrawerBehindTheMenuButton() {
        page.setViewportSize(390, 800);
        page.navigate(url("/"));
        assertFalse(page.locator("#sidebar").isVisible(), "off-canvas until asked for");
        BoundingBox composer = page.locator("#composer").boundingBox();
        assertTrue(composer.y + composer.height <= 800 && composer.x >= 0 && composer.x + composer.width <= 390, "the whole composer is on screen");

        page.locator("#sidebar-toggle").click();
        assertTrue(page.locator("#sidebar").isVisible());
        assertEquals("true", page.locator("#sidebar-toggle").getAttribute("aria-expanded"));
        page.keyboard().press("Escape");
        assertFalse(page.locator("#sidebar").isVisible());
        assertEquals("false", page.locator("#sidebar-toggle").getAttribute("aria-expanded"));
        assertTrue((Boolean) page.evaluate("() => document.activeElement.id === 'sidebar-toggle'"), "focus goes back to the button");

        page.locator("#sidebar-toggle").click();
        page.locator("#backdrop").click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(380, 400));
        assertFalse(page.locator("#sidebar").isVisible(), "a click outside closes it");

        page.locator("#sidebar-toggle").click();
        page.locator("#sidebar a", new Page.LocatorOptions().setHasText("an older question")).click();
        page.waitForURL(url("/c/" + seeded));
        assertFalse(page.locator("#sidebar").isVisible(), "choosing a conversation closes the drawer");
    }

    @Test
    void thereIsNoHorizontalScrollAt360PxOrAt200PercentZoom() {
        page.navigate(url("/c/" + seeded));
        page.setViewportSize(360, 740);
        assertTrue(noSideScroll(), "360 px");
        page.setViewportSize(640, 800); // 1280 px at 200 percent zoom
        assertTrue(noSideScroll(), "200 percent zoom");
    }

    @Test
    void onAWideScreenTheSidebarIsAlwaysThereAndThereIsNoMenuButton() {
        page.setViewportSize(1280, 800);
        page.navigate(url("/"));
        assertTrue(page.locator("#sidebar").isVisible());
        assertFalse(page.locator("#sidebar-toggle").isVisible());
    }
}
