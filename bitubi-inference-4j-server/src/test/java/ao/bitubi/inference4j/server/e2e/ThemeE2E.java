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

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

public class ThemeE2E extends E2EBase {
    private static final String DARK_BG = "rgb(13, 18, 32)";
    private static final String LIGHT_BG = "rgb(246, 247, 249)";

    private String theme() {
        return (String) page.evaluate("() => document.documentElement.dataset.theme");
    }

    private String bodyBackground() {
        return (String) page.evaluate("() => getComputedStyle(document.body).backgroundColor");
    }

    @Test
    void aStoredDarkThemeIsAppliedBeforeTheFirstPaint() {
        context.addInitScript("localStorage.setItem('bi4j.theme', 'dark'); document.addEventListener('DOMContentLoaded', () => { window.__atDcl = document.documentElement.dataset.theme; });");
        page.navigate(url("/"));
        assertEquals("dark", page.evaluate("() => window.__atDcl"), "already dark at DOMContentLoaded: no flash of light");
        assertEquals(DARK_BG, bodyBackground());
    }

    @Test
    void theToggleCyclesSystemLightDarkAndSurvivesAReload() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
        page.navigate(url("/"));
        assertEquals("light", theme());
        page.locator("#theme-toggle").click(); // system -> light
        assertEquals("Theme: Light", page.locator("#theme-toggle").innerText());
        page.locator("#theme-toggle").click(); // light -> dark
        assertEquals("dark", theme());
        assertEquals(DARK_BG, bodyBackground());
        page.reload();
        assertEquals("dark", theme());
        assertEquals("Theme: Dark", page.locator("#theme-toggle").innerText());
        page.locator("#theme-toggle").click(); // dark -> system
        assertEquals("Theme: System", page.locator("#theme-toggle").innerText());
        assertEquals("light", theme(), "system follows the browser, which is light here");
        assertEquals(LIGHT_BG, bodyBackground());
    }

    @Test
    void withNothingStoredTheBrowsersDarkPreferenceWins() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        page.navigate(url("/"));
        assertEquals("dark", theme());
    }

    @Test
    void aBlockedOrGarbageStorageFallsBackToSystemWithoutErrors() {
        context.addInitScript("Storage.prototype.getItem = function () { throw new Error('blocked'); }; Storage.prototype.setItem = function () { throw new Error('blocked'); };");
        page.navigate(url("/"));
        assertEquals("light", theme());
        page.locator("#theme-toggle").click(); // write throws: the page still switches, in memory
        page.locator("#theme-toggle").click();
        assertEquals("dark", theme());

        // garbage in a working storage
        BrowserCtx.fresh(this);
        context.addInitScript("localStorage.setItem('bi4j.theme', 'pink');");
        page.navigate(url("/"));
        assertEquals("light", theme());
        assertEquals("Theme: System", page.locator("#theme-toggle").innerText());
    }

    /** A second page in a clean context, watched for console errors like the first. */
    private static final class BrowserCtx {
        static void fresh(ThemeE2E t) {
            t.context.close();
            t.newPage();
        }
    }
}
