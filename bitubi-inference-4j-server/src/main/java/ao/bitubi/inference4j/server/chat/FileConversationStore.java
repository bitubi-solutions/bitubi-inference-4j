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
package ao.bitubi.inference4j.server.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One plain-text JSON file per conversation, {@code <dataDir>/conversations/{uuid}.json}. File names are UUIDs only (no
 * path is ever built from request input); writes go to a temp file and move into place atomically.
 */
public final class FileConversationStore implements ConversationStore {
    private static final Logger log = LoggerFactory.getLogger(FileConversationStore.class);

    /** Moves a file into place; replaceable in tests to simulate the Windows sharing violations. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private final Path dir;
    private final Mover mover;
    private final JsonMapper mapper = JsonMapper.builder()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    private final AtomicReference<String> error = new AtomicReference<>();

    public FileConversationStore(Path dataDir) {
        this(dataDir, FileConversationStore::atomicMove);
    }

    FileConversationStore(Path dataDir, Mover mover) {
        this.dir = dataDir.resolve("conversations");
        this.mover = mover;
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path file(UUID id) {
        return dir.resolve(id + ".json");
    }

    @Override
    public List<Conversation> list() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Conversation> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                UUID id = parseId(name.substring(0, name.length() - 5));
                if (id == null) {
                    log.warn("ignoring {}: the name is not a conversation id", f);
                    continue;
                }
                read(id, f).ifPresent(out::add);
            }
        } catch (IOException e) {
            log.warn("cannot list {}: {}", dir, e.toString());
        }
        out.sort(Comparator.comparing(Conversation::updatedAt).reversed());
        return out;
    }

    private static UUID parseId(String s) {
        try {
            UUID id = UUID.fromString(s);
            return id.toString().equals(s) ? id : null; // only the canonical lower-case form
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public Optional<Conversation> load(UUID id) {
        Path f = file(id);
        return Files.isRegularFile(f) ? read(id, f) : Optional.empty();
    }

    private Optional<Conversation> read(UUID id, Path f) {
        if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Conversation c = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Conversation.class);
            if (c.version() != Conversation.VERSION) {
                log.warn("ignoring {}: version {} is not supported", f, c.version());
                return Optional.empty();
            }
            if (!id.equals(c.id())) {
                log.warn("ignoring {}: the file holds another conversation id", f);
                return Optional.empty();
            }
            return Optional.of(c);
        } catch (RuntimeException | IOException e) {
            log.warn("ignoring {}: cannot be read ({})", f, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(Conversation c) {
        Path target = file(c.id());
        Path tmp = dir.resolve(c.id() + ".json.tmp");
        try {
            ensureDirectory(dir);
            Files.writeString(tmp, mapper.writeValueAsString(c), StandardCharsets.UTF_8);
            moveWithRetry(tmp, target);
            error.set(null);
        } catch (RuntimeException | IOException e) {
            error.set(e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("cannot save conversation {}: {}", c.id(), e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }

    /** Creates the conversations directory (and its parent); new directories are private to the user where the OS allows it. */
    private static void ensureDirectory(Path d) throws IOException {
        if (Files.isDirectory(d)) return;
        Path parent = d.getParent();
        boolean parentNew = parent != null && !Files.isDirectory(parent);
        Files.createDirectories(d);
        restrict(d);
        if (parentNew) restrict(parent);
    }

    private static void restrict(Path d) {
        try {
            Files.setPosixFilePermissions(d, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows): the profile directory is already private
        }
    }

    private void moveWithRetry(Path from, Path to) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                mover.move(from, to);
                return;
            } catch (AccessDeniedException e) { // Windows: the target is briefly open elsewhere
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    public synchronized void delete(UUID id) {
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            error.set(e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("cannot delete conversation {}: {}", id, e.toString());
        }
    }

    @Override
    public boolean writable() {
        return error.get() == null;
    }

    @Override
    public Optional<String> lastError() {
        return Optional.ofNullable(error.get());
    }
}
