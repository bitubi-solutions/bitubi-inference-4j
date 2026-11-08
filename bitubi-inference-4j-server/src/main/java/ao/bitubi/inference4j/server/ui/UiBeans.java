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
import gg.jte.TemplateEngine;
import java.nio.file.Path;
import java.time.Clock;
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

    @Bean
    Templates templates(TemplateEngine engine) {
        return new Templates(engine);
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
