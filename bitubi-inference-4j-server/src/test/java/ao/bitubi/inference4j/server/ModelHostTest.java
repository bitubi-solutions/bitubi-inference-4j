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

import static org.junit.jupiter.api.Assertions.*;

import ao.bitubi.inference4j.sdk.LoadProgress;
import ao.bitubi.inference4j.sdk.ModelNotFoundException;
import ao.bitubi.inference4j.server.testing.FakeBi4j;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

public class ModelHostTest {

    public static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not met in 10 s");
            Thread.sleep(10);
        }
    }

    public static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void goesThroughLoadingToReadyAndExposesTheModel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeBi4j fake = new FakeBi4j();
        ModelHost host = new ModelHost(progress -> {
            progress.accept(new LoadProgress(LoadProgress.Phase.DOWNLOADING, "model.bin", 50, 100));
            awaitQuietly(release);
            progress.accept(new LoadProgress(LoadProgress.Phase.LOADING, "weights", 0, 0));
            return fake;
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.DOWNLOADING);
        assertEquals(50, host.status().done());
        assertThrows(ModelNotReadyException.class, host::require);
        release.countDown();
        await(() -> host.status().state() == ModelHost.State.READY);
        assertSame(fake, host.require());
    }

    @Test
    public void aLoaderFailureIsKeptWithItsMessage() throws Exception {
        ModelHost host = new ModelHost(p -> {
            throw new ModelNotFoundException("no such model: /x");
        });
        host.start();
        await(() -> host.status().state() == ModelHost.State.FAILED);
        assertTrue(host.status().detail().contains("no such model: /x"));
        ModelNotReadyException e = assertThrows(ModelNotReadyException.class, host::require);
        assertEquals(ModelHost.State.FAILED, e.status().state());
    }

    @Test
    public void closingWhileLoadingClosesTheLateModel() throws Exception {
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeBi4j fake = new FakeBi4j();
        ModelHost host = new ModelHost(p -> {
            inLoad.countDown();
            awaitQuietly(release);
            return fake;
        });
        host.start();
        assertTrue(inLoad.await(10, TimeUnit.SECONDS));
        host.close();
        release.countDown();
        await(fake::isClosed);
    }
}
