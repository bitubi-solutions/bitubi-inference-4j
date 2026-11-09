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
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;

public class SettingsE2E extends E2EBase {

    @org.junit.jupiter.api.BeforeEach
    void noMotion() {
        // measured at rest, and it exercises the prefers-reduced-motion path
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
    }

    private void openPanel() {
        page.locator("#settings-open").click();
        page.locator("#settings-form").waitFor();
    }

    @Test
    void settingsAreSavedUsedAndReset() {
        fake.script("ok");
        page.navigate(url("/"));
        openPanel();
        page.locator("#s-temperature").fill("1.2");
        page.locator("#s-system").fill("Be brief");
        page.locator("#settings-form button[type=submit]").click();
        page.locator("#settings-note").getByText("Saved").waitFor();

        page.locator("[data-close-panel]").click();
        Locator box = page.locator("#text");
        box.fill("hello");
        box.press("Enter");
        page.locator("#messages .answer .footer").last().waitFor();
        assertEquals(1.2f, fake.lastSettings().temperature());

        openPanel();
        page.locator("#settings-form button.secondary").click();
        page.waitForFunction("() => document.querySelector('#s-temperature').value === '0.7'");
        assertEquals("", page.locator("#s-system").inputValue());
    }

    @Test
    void escClosesThePanelAndFocusReturnsToTheButton() {
        page.navigate(url("/"));
        openPanel();
        assertEquals("true", page.locator("#settings-open").getAttribute("aria-expanded"));
        page.keyboard().press("Escape");
        page.locator("#settings-panel:not(.open)").waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
        assertEquals("false", page.locator("#settings-open").getAttribute("aria-expanded"));
        assertTrue((Boolean) page.evaluate("() => document.activeElement && document.activeElement.id === 'settings-open'"));
        assertFalse(page.locator("#settings-panel").isVisible(), "a closed panel is out of sight and out of the tab order");
    }

    @Test
    void onANarrowScreenThePanelIsABottomSheet() {
        page.setViewportSize(390, 800);
        page.navigate(url("/"));
        openPanel();
        BoundingBox b = page.locator("#settings-panel").boundingBox();
        assertEquals(390, Math.round(b.width), "full width");
        assertTrue(b.y > 100, "it rises from the bottom, not from the top: y=" + b.y);
        assertEquals(800, Math.round(b.y + b.height), "it sits on the bottom edge");
    }

    @Test
    void onAWideScreenThePanelIsARightHandDrawer() {
        page.setViewportSize(1280, 800);
        page.navigate(url("/"));
        openPanel();
        BoundingBox b = page.locator("#settings-panel").boundingBox();
        assertEquals(1280, Math.round(b.x + b.width), "against the right edge");
        assertTrue(b.width < 500, "a drawer, not the whole page: " + b.width);
    }
}
