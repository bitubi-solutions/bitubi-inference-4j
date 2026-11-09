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

import ao.bitubi.inference4j.server.Bi4jServerProperties;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.SessionRegistry;
import ao.bitubi.inference4j.server.TurnService;
import ao.bitubi.inference4j.server.chat.ChatTurns;
import ao.bitubi.inference4j.server.chat.ConversationStore;
import ao.bitubi.inference4j.server.chat.FileConversationStore;
import ao.bitubi.inference4j.server.chat.MarkdownRenderer;
import ao.bitubi.inference4j.server.chat.MemoryConversationStore;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UiProperties.class)
@ConditionalOnProperty(name = "bi4j.ui.enabled", havingValue = "true", matchIfMissing = true)
public class UiBeans {
    @Bean
    MarkdownRenderer markdownRenderer() {
        return new MarkdownRenderer();
    }

    /**
     * The precompiled templates; with {@code bi4j.ui.template-dir} set (development only) they are read from that folder
     * and recompiled when a file changes.
     */
    @Bean
    Templates templates(ObjectProvider<TemplateEngine> precompiled, UiProperties ui) {
        Path dir = ui.templateDir();
        if (dir == null) return new Templates(precompiled.getObject());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("bi4j.ui.template-dir \"" + dir + "\" is not a folder; unset it to use the built-in templates.");
        }
        return new Templates(devEngine(dir));
    }

    /** Compiles on demand from {@code dir}; the classes go to a temp folder and the compiler gets the classpath this JVM runs with. */
    private static TemplateEngine devEngine(Path dir) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(dir), Files.createTempDirectory("bi4j-jte-"), ContentType.Html, loader);
            java.util.LinkedHashSet<String> classpath = new java.util.LinkedHashSet<>(java.util.Arrays.asList(System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)));
            for (ClassLoader l = loader; l != null; l = l.getParent()) {
                if (l instanceof java.net.URLClassLoader u) {
                    for (java.net.URL url : u.getURLs()) {
                        if ("file".equals(url.getProtocol())) classpath.add(Path.of(url.toURI()).toString());
                    }
                }
            }
            classpath.remove("");
            engine.setClassPath(new java.util.ArrayList<>(classpath));
            return engine;
        } catch (java.io.IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("cannot set up the template compiler for " + dir + ": " + e.getMessage(), e);
        }
    }

    @Bean
    ConversationStore conversationStore(Bi4jServerProperties props) {
        if (!props.history()) return new MemoryConversationStore();
        Path dir = props.dataDir().isBlank() ? Path.of(System.getProperty("user.home"), ".bi4j") : Path.of(props.dataDir());
        return new FileConversationStore(dir);
    }

    @Bean(destroyMethod = "close")
    ChatTurns chatTurns(TurnService turns, ConversationStore store, ModelHost host, UiProperties ui) {
        return new ChatTurns(turns, store, host, Clock.systemUTC(), ui.orphanTimeout(), 20);
    }
}
