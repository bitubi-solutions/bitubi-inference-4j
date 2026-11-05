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

import java.util.List;
import org.junit.After;
import org.junit.Test;

public class StopMatcherTest {

    @After
    public void clearInterrupt() {
        Thread.interrupted(); // the matcher interrupts the current thread when a stop matches
    }

    @Test
    public void aStopSplitAcrossTwoPiecesStillStops() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of("\n\n"));
        assertEquals("Hello", m.accept("Hello\n")); // "\n" is held back: it may start "\n\n"
        assertFalse(m.stopped());
        assertEquals("", m.accept("\nWorld")); // completes the stop; nothing after it is emitted
        assertTrue(m.stopped());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals("", m.accept("more"));
        assertEquals("", m.flush());
    }

    @Test
    public void textBeforeAStopInTheSamePieceIsKept() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of("END"));
        assertEquals("abc", m.accept("abcENDdef"));
        assertTrue(m.stopped());
    }

    @Test
    public void aStopSplitAcrossThreePieces() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of("<|end|>"));
        assertEquals("x", m.accept("x<|"));
        assertEquals("", m.accept("en"));
        assertEquals("", m.accept("d|>"));
        assertTrue(m.stopped());
    }

    @Test
    public void heldBackTextComesOutWhenItTurnsOutNotToBeAStop() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of("\n\n"));
        assertEquals("a", m.accept("a\n"));
        assertEquals("\nb", m.accept("b")); // "\n" + "b": no stop, everything is released
        assertFalse(m.stopped());
        assertEquals("c", m.accept("c\n"));
        assertEquals("\n", m.flush()); // generation ended: the held text is part of the answer
    }

    @Test
    public void withoutStopsEverythingPassesThrough() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of());
        assertEquals("anything\n\n", m.accept("anything\n\n"));
        assertFalse(m.stopped());
        assertEquals("", m.flush());
    }

    @Test
    public void theEarliestOfSeveralStopsWins() {
        Engine.StopMatcher m = new Engine.StopMatcher(List.of("B", "A"));
        assertEquals("x", m.accept("xAyB"));
        assertTrue(m.stopped());
    }
}
