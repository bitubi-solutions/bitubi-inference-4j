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

import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.util.Map;

/** Renders a precompiled template to a string (fragments for htmx, and the snapshots of the stream). */
public final class Templates {
    private final TemplateEngine engine;

    public Templates(TemplateEngine engine) {
        this.engine = engine;
    }

    public String render(String template, Map<String, Object> params) {
        StringOutput out = new StringOutput();
        engine.render(template + ".jte", params, out);
        return out.toString();
    }
}
