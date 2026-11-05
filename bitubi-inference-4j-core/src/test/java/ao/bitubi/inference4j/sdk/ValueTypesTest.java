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
package ao.bitubi.inference4j.sdk;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ValueTypesTest {

    @Test
    public void settingsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(0, 0.7f, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(10, -0.1f, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(10, Float.NaN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(10, 0.7f, List.of("")));
        assertThrows(NullPointerException.class, () -> new GenerationSettings(10, 0.7f, Arrays.asList((String) null)));
    }

    @Test
    public void defaultsMatchTheSpec() {
        assertEquals(new GenerationSettings(512, 0.7f, List.of()), GenerationSettings.defaults());
    }

    @Test
    public void settingsCanBeChangedOneFieldAtATime() {
        GenerationSettings s = GenerationSettings.defaults().withMaxNewTokens(8).withTemperature(0f).withStop(List.of("x"));
        assertEquals(new GenerationSettings(8, 0f, List.of("x")), s);
    }

    @Test
    public void requestNeedsAMessageAndIsImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(List.of(), GenerationSettings.defaults()));
        List<Message> m = new ArrayList<>(List.of(Message.user("hi")));
        ChatRequest r = new ChatRequest(m, GenerationSettings.defaults());
        m.add(Message.user("later"));
        assertEquals(1, r.messages().size());
    }

    @Test
    public void sessionOptionsBuilderCollectsEverything() {
        SessionOptions o = new SessionOptions.Builder().system("Be brief.")
            .temporary()
            .maxNewTokens(5)
            .temperature(0f)
            .stop("x", "y")
            .build();
        assertEquals("Be brief.", o.system());
        assertTrue(o.temporary());
        assertEquals(new GenerationSettings(5, 0f, List.of("x", "y")), o.defaults());
        assertNull(new SessionOptions.Builder().build().system());
    }

    @Test
    public void everyErrorIsABi4jException() {
        Bi4jException e = new SessionBusyException(UUID.randomUUID());
        // Exhaustive without a default branch: this only compiles while the hierarchy is sealed and complete
        String kind = switch (e) {
            case ModelNotFoundException x -> "model";
            case UnsupportedModelException x -> "format";
            case InsufficientMemoryException x -> "memory";
            case EngineSetupException x -> "setup";
            case SessionBusyException x -> "busy";
            case ContextLengthExceededException x -> "context";
            case UnsupportedInputException x -> "input";
        };
        assertEquals("busy", kind);
    }
}
