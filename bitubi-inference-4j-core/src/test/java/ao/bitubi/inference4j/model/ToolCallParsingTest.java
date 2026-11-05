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
package ao.bitubi.inference4j.model;

import static org.junit.Assert.*;

import ao.bitubi.inference4j.safetensors.prompt.Function;
import ao.bitubi.inference4j.safetensors.prompt.Tool;
import ao.bitubi.inference4j.safetensors.prompt.ToolCall;
import java.util.List;
import org.junit.Test;

public class ToolCallParsingTest {
    private static final List<Tool> TOOLS = List.of(
        Tool.from(Function.builder().name("get_weather").description("weather").addParameter("city", "string", "a city", true).build())
    );

    @Test
    public void proseThatNamesAToolIsNotACall() {
        // tjake/Jlama#206: this threw a NullPointerException while sorting calls without a name
        assertTrue(AbstractModel.parseToolCalls("I can use get_weather. Example config: {\"units\": \"metric\"}", TOOLS).isEmpty());
        assertTrue(AbstractModel.parseToolCalls("get_weather needs a city: {\"city\": ", TOOLS).isEmpty());
    }

    @Test
    public void onlyCallsToKnownToolsAreKept() {
        List<ToolCall> calls = AbstractModel.parseToolCalls(
            "[{\"name\": \"get_weather\", \"parameters\": {\"city\": \"Luanda\"}}, {\"name\": \"rm_rf\", \"parameters\": {}}]",
            TOOLS
        );
        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).getName());
        assertEquals("000000000", calls.get(0).getId());
    }
}
