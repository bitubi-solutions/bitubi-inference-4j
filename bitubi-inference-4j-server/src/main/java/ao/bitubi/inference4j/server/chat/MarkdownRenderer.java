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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/**
 * Model output to safe HTML, on the server: raw HTML in the answer is shown as text, link targets are limited to
 * http, https and mailto, links open in a new tab without a referrer, and fenced code gets a header with a Copy button.
 * An unclosed fence (a half-streamed answer) is a complete code block, so snapshots always look right.
 */
public final class MarkdownRenderer {
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_+-]{1,20}");
    private static final Set<String> SCHEMES = Set.of("http", "https", "mailto");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
            .extensions(extensions)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(new SchemeSanitizer())
            .attributeProviderFactory(ctx -> new LinkAttributes())
            .nodeRendererFactory(CodeBlockRenderer::new)
            .build();
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        return renderer.render(parser.parse(markdown));
    }

    /** Keeps only absolute URLs with an allowed scheme; everything else (javascript:, data:, relative) loses its href. */
    private static final class SchemeSanitizer implements org.commonmark.renderer.html.UrlSanitizer {
        @Override
        public String sanitizeLinkUrl(String url) {
            int colon = url.indexOf(':');
            if (colon <= 0) return ""; // relative links and bare words are not something a model answer needs
            String scheme = url.substring(0, colon).toLowerCase(java.util.Locale.ROOT).strip();
            return SCHEMES.contains(scheme) ? url : "";
        }

        @Override
        public String sanitizeImageUrl(String url) {
            return "";
        }
    }

    private static final class LinkAttributes implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Link) {
                attributes.put("rel", "noopener noreferrer");
                attributes.put("target", "_blank");
            }
        }
    }

    private static final class CodeBlockRenderer implements NodeRenderer {
        private final HtmlWriter html;

        CodeBlockRenderer(HtmlNodeRendererContext context) {
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String info = block.getInfo() == null ? "" : block.getInfo().strip();
            String first = info.isEmpty() ? "" : info.split("\\s+")[0];
            String lang = LANGUAGE.matcher(first).matches() ? first : "text";
            html.line();
            html.raw("<div class=\"code\"><span class=\"lang\">" + lang + "</span>");
            html.raw("<button type=\"button\" data-copy aria-label=\"Copy code\">Copy</button>");
            html.raw("<pre><code class=\"language-" + lang + "\">");
            html.text(block.getLiteral());
            html.raw("</code></pre></div>");
            html.line();
        }
    }
}
