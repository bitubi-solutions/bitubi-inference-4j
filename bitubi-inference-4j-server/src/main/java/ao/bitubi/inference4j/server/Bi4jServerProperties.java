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
package ao.bitubi.inference4j.server;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code bi4j.server.*}: {@code allowed-hosts} are Host names accepted besides loopback; {@code history} and {@code data-dir} say where conversations live (default {@code ~/.bi4j}). */
@ConfigurationProperties("bi4j.server")
public record Bi4jServerProperties(@DefaultValue List<String> allowedHosts, @DefaultValue("true") boolean history, @DefaultValue("") String dataDir) {}
