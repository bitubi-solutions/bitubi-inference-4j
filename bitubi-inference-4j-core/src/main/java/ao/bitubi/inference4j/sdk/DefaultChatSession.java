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

import ao.bitubi.inference4j.safetensors.prompt.PromptContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class DefaultChatSession implements ChatSession {
    private final DefaultBi4j owner;
    private final SessionOptions options;
    private final UUID id = UUID.randomUUID();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Message> history = new ArrayList<>(); // guarded by itself
    private boolean started; // guarded by busy

    DefaultChatSession(DefaultBi4j owner, SessionOptions options) {
        this.owner = owner;
        this.options = options;
    }

    @Override
    public ChatResponse send(String userText) {
        return send(Message.user(userText));
    }

    @Override
    public ChatResponse send(Message message) {
        if (message.role() != Message.Role.USER) throw new IllegalArgumentException("send needs a USER message, got " + message.role());
        owner.ensureOpen();
        if (closed.get()) throw new IllegalStateException("ChatSession is closed");
        if (!busy.compareAndSet(false, true)) throw new SessionBusyException(id);
        try {
            if (closed.get()) throw new IllegalStateException("ChatSession is closed");
            PromptMapper.checkInputs(owner.capabilities(), List.of(message));

            boolean fresh = options.temporary() || !started; // a fresh prompt carries the system message and preamble
            List<Message> turn = new ArrayList<>();
            if (fresh && options.system() != null) turn.add(Message.system(options.system()));
            turn.add(message);
            PromptContext prompt = PromptMapper.toPrompt(owner.model().promptSupport(), turn, !fresh);

            ChatResponse response = owner.engine().run(id, prompt, options.defaults(), t -> {});
            if (!options.temporary()) {
                started = true;
                synchronized (history) {
                    history.add(message);
                    history.add(Message.assistant(response.text()));
                }
            }
            return response;
        } finally {
            if (options.temporary() || closed.get()) owner.model().releaseSession(id);
            busy.set(false);
        }
    }

    @Override
    public List<Message> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        owner.untrack(this);
        // Taking the busy flag keeps new calls out; if a call is running, its own finally releases the cache.
        if (busy.compareAndSet(false, true)) owner.model().releaseSession(id);
    }
}
