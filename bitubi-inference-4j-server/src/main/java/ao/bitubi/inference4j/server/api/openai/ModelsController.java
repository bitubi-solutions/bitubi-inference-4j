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
package ao.bitubi.inference4j.server.api.openai;

import ao.bitubi.inference4j.server.ModelHost;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "bi4j.api.enabled", havingValue = "true", matchIfMissing = true)
public class ModelsController {
    private final ModelHost host;

    ModelsController(ModelHost host) {
        this.host = host;
    }

    @Operation(summary = "Lists the loaded model; the list is empty while the model is loading")
    @GetMapping("/v1/models")
    OpenAi.ModelList models() {
        return new OpenAi.ModelList(
            "list",
            host.bi4j().map(b -> List.of(new OpenAi.ModelEntry(b.info().name(), "model", System.currentTimeMillis() / 1000, "bitubi"))).orElse(List.of())
        );
    }
}
