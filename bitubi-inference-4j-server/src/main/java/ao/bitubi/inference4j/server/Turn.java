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

import ao.bitubi.inference4j.sdk.ChatResponse;
import ao.bitubi.inference4j.sdk.Generation;
import ao.bitubi.inference4j.sdk.Token;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** One running generation. Close it to free the session reservation; closing is idempotent. */
public final class Turn implements AutoCloseable {
    private final UUID id;
    private final Generation generation;
    private final Stream<Token> stream;
    private final Iterator<Token> iterator;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    Turn(UUID id, Generation generation, Runnable release) {
        this.id = id;
        this.generation = generation;
        this.release = release;
        this.stream = generation.tokens();
        this.iterator = stream.iterator();
    }

    public UUID id() {
        return id;
    }

    public Iterator<Token> tokens() {
        return iterator;
    }

    /** Reads up to the first token, so context and engine errors surface here and not in the middle of a stream. */
    public void prime() {
        iterator.hasNext();
    }

    public void cancel() {
        generation.cancel();
    }

    public ChatResponse result() {
        return generation.result().orElseThrow(() -> new IllegalStateException("generation has no result yet"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            stream.close();
            generation.close();
        } finally {
            release.run();
        }
    }
}
