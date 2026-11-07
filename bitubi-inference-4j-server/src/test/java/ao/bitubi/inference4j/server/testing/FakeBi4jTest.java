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
package ao.bitubi.inference4j.server.testing;

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.*;
import java.util.Iterator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class FakeBi4jTest {
    @Test
    public void aSessionStreamsTheScriptAndRemembersTheTurn() {
        FakeBi4j fake = new FakeBi4j().script("Hel", "lo");
        ChatSession s = fake.newSession(o -> {});
        Generation g = s.start("hi", GenerationSettings.defaults());
        String text = g.tokens().map(Token::text).collect(Collectors.joining());
        assertEquals("Hello", text);
        assertEquals(FinishReason.STOP, g.result().orElseThrow().finishReason());
        assertEquals(2, s.history().size());
    }

    @Test
    public void cancelEndsTheStreamWithCancelledAndFreesTheSession() {
        FakeBi4j fake = new FakeBi4j().script("a", "b", "c").delayMs(20);
        ChatSession s = fake.newSession(o -> {});
        Generation g = s.start("hi", GenerationSettings.defaults());
        Iterator<Token> it = g.tokens().iterator();
        it.next();
        g.cancel();
        while (it.hasNext()) it.next();
        assertEquals(FinishReason.CANCELLED, g.result().orElseThrow().finishReason());
        assertEquals(1, fake.cancelCount());
        s.start("again", GenerationSettings.defaults()).close(); // not busy any more
    }

    @Test
    public void aSecondGenerationOnTheSameSessionIsBusy() {
        FakeBi4j fake = new FakeBi4j().script("a", "b").delayMs(50);
        ChatSession s = fake.newSession(o -> {});
        Generation first = s.start("one", GenerationSettings.defaults());
        first.tokens().iterator().next();
        assertThrows(SessionBusyException.class, () -> s.start("two", GenerationSettings.defaults()));
        first.close();
    }

    @Test
    public void contextLimitThrowsTheSdkExceptionAtTheFirstRead() {
        FakeBi4j fake = new FakeBi4j().script("x").contextLimit(4);
        ChatSession s = fake.newSession(o -> {});
        Generation g = s.start("a very long prompt indeed", GenerationSettings.defaults());
        assertThrows(ContextLengthExceededException.class, () -> g.tokens().iterator().hasNext());
    }
}
