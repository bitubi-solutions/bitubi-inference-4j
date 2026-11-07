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

import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.LoadProgress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the one {@link Bi4j} and its load state. Loading runs on a virtual thread so HTTP can listen first. */
public final class ModelHost implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ModelHost.class);

    public enum State {
        DOWNLOADING,
        LOADING,
        READY,
        FAILED
    }

    public record Status(State state, String detail, long done, long total, Throwable cause) {}

    private final Function<Consumer<LoadProgress>, Bi4j> loader;
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(State.LOADING, "waiting to start", 0, 0, null));
    private Bi4j model;
    private boolean started;
    private boolean closed;

    public ModelHost(Function<Consumer<LoadProgress>, Bi4j> loader) {
        this.loader = loader;
    }

    /** A host that is ready with an already loaded model (tests). */
    public static ModelHost ready(Bi4j bi4j) {
        ModelHost host = new ModelHost(p -> bi4j);
        host.model = bi4j;
        host.started = true;
        host.status.set(new Status(State.READY, bi4j.info().name(), 0, 0, null));
        return host;
    }

    /** Starts loading on a virtual thread and returns at once. Safe to call more than once. */
    public void start() {
        synchronized (this) {
            if (started) return;
            started = true;
        }
        Thread.ofVirtual().name("bi4j-model-load").start(() -> {
            try {
                Bi4j loaded = loader.apply(this::onProgress);
                synchronized (this) {
                    if (closed) {
                        loaded.close();
                        return;
                    }
                    model = loaded;
                    status.set(new Status(State.READY, loaded.info().name(), 0, 0, null));
                }
            } catch (Throwable t) {
                log.error("model load failed", t);
                status.set(new Status(State.FAILED, t.getMessage() == null ? t.toString() : t.getMessage(), 0, 0, t));
            }
        });
    }

    private void onProgress(LoadProgress p) {
        State state = p.phase() == LoadProgress.Phase.DOWNLOADING ? State.DOWNLOADING : State.LOADING;
        if (p.phase() == LoadProgress.Phase.READY) return; // READY is set once the loader returns
        status.set(new Status(state, p.detail(), p.done(), p.total(), null));
    }

    public Status status() {
        return status.get();
    }

    public synchronized Optional<Bi4j> bi4j() {
        return Optional.ofNullable(model);
    }

    /** The loaded model, or {@link ModelNotReadyException} while it is loading or after it failed. */
    public Bi4j require() {
        Bi4j m;
        synchronized (this) {
            m = model;
        }
        if (m == null) throw new ModelNotReadyException(status());
        return m;
    }

    @Override
    public void close() {
        Bi4j m;
        synchronized (this) {
            closed = true;
            m = model;
            model = null;
        }
        if (m != null) m.close();
    }
}
