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
package ao.bitubi.inference4j.server.prompts;

import ao.bitubi.inference4j.server.ui.Templates;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** The worked example of docs/server-extending.md: a "Prompts" page. */
@Controller
@ConditionalOnProperty("bi4j.test.prompts")
class PromptsController {
    private final Templates templates;
    private final List<String> prompts = List.of("Explain Java streams in simple terms", "Write a short poem about autumn");

    PromptsController(Templates templates) {
        this.templates = templates;
    }

    @GetMapping("/prompts")
    ResponseEntity<String> prompts(@RequestHeader(name = "HX-Request", required = false) String htmx) {
        String page = templates.render(htmx == null ? "prompts" : "promptsList", Map.of("prompts", prompts));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Vary", "HX-Request").body(page);
    }
}
