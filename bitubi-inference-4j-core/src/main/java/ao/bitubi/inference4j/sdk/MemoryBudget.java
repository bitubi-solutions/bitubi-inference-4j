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

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compares a {@link MemoryEstimate} with what the machine and the JVM offer. */
final class MemoryBudget {

    private MemoryBudget() {}

    /** Resources in bytes: total RAM, the off-heap limit of the JVM, and free disk where the KV pages go. */
    record Available(long physicalBytes, long maxDirectBytes, long diskBytes) {
    }

    static Available measure(Path workDir) {
        long physical = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        long direct = Long.parseLong(
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("MaxDirectMemorySize").getValue()
        );
        if (direct == 0) direct = Runtime.getRuntime().maxMemory(); // 0 means "same as the heap limit"
        try {
            return new Available(physical, direct, Files.getFileStore(workDir).getUsableSpace());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One sentence per resource that is too small, each with both numbers and what to change. */
    static List<String> problems(MemoryEstimate e, Available a) {
        List<String> problems = new ArrayList<>();
        long resident = e.weightBytes() + e.directBytes() + e.kvBytes();
        if (resident > a.physicalBytes()) {
            problems.add(
                "the model needs about "
                    + gib(resident)
                    + " of memory (weights "
                    + gib(e.weightBytes())
                    + ", KV cache "
                    + gib(e.kvBytes())
                    + ", widened weights "
                    + gib(e.directBytes())
                    + ") but the machine has "
                    + gib(a.physicalBytes())
                    + ": lower contextLength, or use a more quantized model"
            );
        }
        if (e.directBytes() > a.maxDirectBytes()) {
            long gigabytes = (e.directBytes() + (1L << 30) - 1) >> 30;
            problems.add(
                "the model needs "
                    + gib(e.directBytes())
                    + " of off-heap memory but the JVM allows "
                    + gib(a.maxDirectBytes())
                    + ": start the JVM with -XX:MaxDirectMemorySize="
                    + gigabytes
                    + "g"
            );
        }
        if (e.kvBytes() > a.diskBytes()) {
            problems.add(
                "the KV cache needs "
                    + gib(e.kvBytes())
                    + " of disk in the working directory but only "
                    + gib(a.diskBytes())
                    + " of free disk space is left: lower contextLength, or free some space"
            );
        }
        return problems;
    }

    private static String gib(long bytes) {
        return String.format("%.1f GiB", bytes / (double) (1L << 30));
    }
}
