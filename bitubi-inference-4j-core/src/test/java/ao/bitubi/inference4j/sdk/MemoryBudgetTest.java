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

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class MemoryBudgetTest {

    @Test
    public void directMemoryProblemNamesTheFlagToRaise() {
        MemoryEstimate e = new MemoryEstimate(4L << 30, 8L << 30, 1L << 30);
        List<String> p = MemoryBudget.problems(e, new MemoryBudget.Available(16L << 30, 4L << 30, 100L << 30));
        assertEquals(p.toString(), 1, p.size());
        assertTrue(p.get(0), p.get(0).contains("-XX:MaxDirectMemorySize=8g"));
    }

    @Test
    public void everythingThatFitsHasNoProblem() {
        MemoryEstimate e = new MemoryEstimate(4L << 30, 8L << 30, 1L << 30);
        assertTrue(MemoryBudget.problems(e, new MemoryBudget.Available(64L << 30, 16L << 30, 100L << 30)).isEmpty());
    }

    @Test
    public void physicalMemoryAndDiskAreEachChecked() {
        MemoryEstimate e = new MemoryEstimate(4L << 30, 0, 6L << 30);
        List<String> p = MemoryBudget.problems(e, new MemoryBudget.Available(8L << 30, 8L << 30, 2L << 30));
        assertEquals(p.toString(), 2, p.size());
        assertTrue(p.get(0), p.get(0).contains("contextLength"));
        assertTrue(p.get(1), p.get(1).contains("free disk"));
    }

    @Test
    public void measureReadsRealValues() {
        MemoryBudget.Available a = MemoryBudget.measure(Path.of("."));
        assertTrue(a.physicalBytes() > 0);
        assertTrue(a.maxDirectBytes() > 0);
        assertTrue(a.diskBytes() > 0);
    }
}
