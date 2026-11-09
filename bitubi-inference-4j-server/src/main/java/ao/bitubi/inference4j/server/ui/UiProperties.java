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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code bi4j.ui.*}. {@code orphanTimeout}: a running answer nobody watches is cancelled after this; {@code heartbeat}: SSE keep-alive; {@code templateDir}: read templates from this folder and reload them on change (development). */
@ConfigurationProperties("bi4j.ui")
public record UiProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30s") Duration orphanTimeout,
    @DefaultValue("15s") Duration heartbeat,
    java.nio.file.Path templateDir,
    @DefaultValue Defaults defaults
) {
    /** {@code bi4j.ui.defaults.*}: what a new conversation starts with (the settings panel can change it per conversation). */
    public record Defaults(@DefaultValue("0.7") float temperature, @DefaultValue("512") int maxNewTokens, @DefaultValue("") String system) {}
}
