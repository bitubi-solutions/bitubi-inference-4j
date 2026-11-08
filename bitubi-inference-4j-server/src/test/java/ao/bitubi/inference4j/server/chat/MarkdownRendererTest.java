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
package ao.bitubi.inference4j.server.chat;

import static org.junit.jupiter.api.Assertions.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

public class MarkdownRendererTest {
    private final MarkdownRenderer md = new MarkdownRenderer();

    private Document doc(String markdown) {
        return Jsoup.parseBodyFragment(md.render(markdown));
    }

    @Test
    void basicMarkdown() {
        Document d = doc("# Title\n\nSome *emphasis* and **bold** and `code`.\n\n- one\n- two\n\n1. a\n2. b\n");
        assertEquals("Title", d.selectFirst("h1").text());
        assertEquals("emphasis", d.selectFirst("em").text());
        assertEquals("bold", d.selectFirst("strong").text());
        assertEquals("code", d.selectFirst("p code").text());
        assertEquals(2, d.select("ul li").size());
        assertEquals(2, d.select("ol li").size());
    }

    @Test
    void tablesRender() {
        Document d = doc("| a | b |\n|---|---|\n| 1 | 2 |\n");
        assertEquals(2, d.select("table th").size());
        assertEquals("1", d.selectFirst("table td").text());
    }

    @Test
    void aFencedBlockGetsTheLanguageLabelAndACopyButton() {
        String html = md.render("```java\nvar s = 1 < 2;\n```\n");
        assertTrue(html.contains("<div class=\"code\"><span class=\"lang\">java</span><button type=\"button\" data-copy aria-label=\"Copy code\">Copy</button>"), html);
        Document d = Jsoup.parseBodyFragment(html);
        assertEquals("var s = 1 < 2;\n", d.selectFirst("pre code.language-java").wholeText());
    }

    @Test
    void aFenceWithoutALanguageIsText() {
        assertTrue(md.render("```\nplain\n```\n").contains("<span class=\"lang\">text</span>"));
    }

    @Test
    void anUnclosedFenceIsStillAWholeCodeBlock() {
        Document d = doc("Here:\n\n```python\nprint('half-streamed'\n");
        assertEquals(1, d.select("div.code pre code.language-python").size());
        assertTrue(d.selectFirst("pre code").wholeText().contains("half-streamed"));
    }

    @Test
    void emptyAndNullAreEmpty() {
        assertEquals("", md.render(""));
        assertEquals("", md.render(null));
    }

    @Test
    void aVeryLongLineIsFastAndUnicodeSurvives() {
        long t = System.nanoTime();
        String html = md.render("x".repeat(100_000));
        assertTrue((System.nanoTime() - t) / 1_000_000 < 500, "too slow");
        assertTrue(html.length() > 100_000);
        assertTrue(md.render("emoji 😀 and 日本語").contains("😀 and 日本語"));
    }

    @Test
    void scriptsAreShownAsTextNotRun() {
        Document d = doc("<script>alert(1)</script>\n\nText <b>bold?</b>");
        assertTrue(d.select("script").isEmpty());
        assertTrue(d.select("b").isEmpty());
        assertTrue(d.text().contains("<script>alert(1)</script>"), d.text());
    }

    @Test
    void imagesWithHandlersAndIframesAreInert() {
        Document d = doc("<img src=x onerror=alert(1)>\n\n<iframe src=\"https://evil.example\"></iframe>\n\n<!-- hidden -->");
        assertTrue(d.select("img, iframe").isEmpty());
        assertTrue(d.getAllElements().stream().noneMatch(e -> e.attributes().asList().stream().anyMatch(a -> a.getKey().startsWith("on"))));
    }

    @Test
    void dangerousLinkSchemesLoseTheirHref() {
        for (String url : new String[] { "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "vbscript:x", "/relative", "//evil.example" }) {
            Document d = doc("[x](" + url + ")");
            var a = d.selectFirst("a");
            assertTrue(a == null || !a.hasAttr("href") || a.attr("href").isEmpty(), url + " -> " + d.body().html());
        }
    }

    @Test
    void goodLinksKeepTheirHrefAndOpenSafely() {
        Document d = doc("[x](https://ok.example/a?b=1) and [m](mailto:a@b.c)");
        var links = d.select("a");
        assertEquals("https://ok.example/a?b=1", links.get(0).attr("href"));
        assertEquals("noopener noreferrer", links.get(0).attr("rel"));
        assertEquals("_blank", links.get(0).attr("target"));
        assertEquals("mailto:a@b.c", links.get(1).attr("href"));
    }

    @Test
    void markdownImagesAreNotLoaded() {
        Document d = doc("![alt](https://tracker.example/pixel.png)");
        var img = d.selectFirst("img");
        assertTrue(img == null || !img.hasAttr("src") || img.attr("src").isEmpty(), d.body().html());
    }

    @Test
    void codeCannotCloseItsOwnBlock() {
        Document d = doc("```\n</code></pre><script>alert(1)</script>\n```\n");
        assertTrue(d.select("script").isEmpty());
        assertTrue(d.selectFirst("pre code").wholeText().contains("</code></pre><script>"));
    }

    @Test
    void anOddLanguageTagCannotInjectAttributes() {
        String html = md.render("```\" onmouseover=\"x\nfoo\n```\n");
        assertFalse(html.contains("onmouseover"), html);
    }
}
