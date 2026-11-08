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

import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** For scripts and probes: 200 only when the model is ready, 503 with the state otherwise. */
@RestController
public class HealthController {
    private final ModelHost host;

    HealthController(ModelHost host) {
        this.host = host;
    }

    @Operation(summary = "Readiness: 200 when the model is loaded, 503 while it downloads, loads or after it failed")
    @GetMapping("/healthz")
    ResponseEntity<Map<String, Object>> health() {
        ModelHost.Status s = host.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", s.state().name().toLowerCase());
        if (s.state() == ModelHost.State.READY) {
            body.put("model", s.detail());
            return ResponseEntity.ok(body);
        }
        body.put("detail", s.detail());
        body.put("done", s.done());
        body.put("total", s.total());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
