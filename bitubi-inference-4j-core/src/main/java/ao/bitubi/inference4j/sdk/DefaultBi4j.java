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

import ao.bitubi.inference4j.model.AbstractModel;
import ao.bitubi.inference4j.model.ModelSupport;
import ao.bitubi.inference4j.model.functions.Generator;
import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import ao.bitubi.inference4j.util.Bi4jProperties;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultBi4j implements Bi4j {
    private static final Logger logger = LoggerFactory.getLogger(Bi4j.class);

    private final AbstractModel model;
    private final ModelInspection inspection;
    private final Path workDir;
    private final Capabilities capabilities;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DefaultBi4j(AbstractModel model, ModelInspection inspection, Path workDir, int contextLength) {
        this.model = model;
        this.inspection = inspection;
        this.workDir = workDir;
        boolean embeddings = inspection.type() == ModelSupport.ModelType.BERT;
        this.capabilities = new Capabilities(
            Set.of(Modality.TEXT),
            model.promptSupport().isPresent() || !embeddings,
            embeddings,
            contextLength
        );
    }

    static Bi4j create(Builder b) {
        long start = System.nanoTime();
        EngineSetup.check(Bi4jProperties.getString("backend"));

        Path root = resolve(b);
        ModelInspection inspection;
        try {
            inspection = ModelInspection.of(root);
        } catch (IllegalArgumentException e) {
            throw new ModelNotFoundException("Cannot read a model at " + root + ": " + e.getMessage(), e);
        }
        if (b.contextLength != null && b.contextLength > inspection.maxContext()) {
            throw new IllegalArgumentException(
                "contextLength " + b.contextLength + " is above the model's maximum of " + inspection.maxContext()
            );
        }
        int contextLength = b.contextLength != null ? b.contextLength : Math.min(4096, inspection.maxContext());

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("bi4j-");
            Path measured = workDir;
            java.util.function.Supplier<MemoryBudget.Available> measure = b.available != null
                ? b.available
                : () -> MemoryBudget.measure(measured);
            MemoryBudget.Available available = measure.get();

            WorkingTypes types;
            if (b.manualTypes != null) {
                types = b.manualTypes;
                if (!WorkingTypes.hasKernel(types, inspection.weights())) {
                    throw new UnsupportedModelException(
                        "No kernel for working types "
                            + types
                            + " with "
                            + inspection.weights()
                            + " weights. Memory type must be F32 or BF16; "
                            + "activations must be F32, or I8 for Q4 weights. Remove workingTypes(...) to let the SDK choose."
                    );
                }
                MemoryEstimate estimate = MemoryEstimate.of(inspection, types, contextLength);
                WorkingTypes chosen = types;
                WorkingTypes.risks(chosen, inspection.weights(), estimate, available)
                    .forEach(r -> logger.warn("Working types {}: {}", chosen, r));
            } else {
                types = WorkingTypes.automatic(inspection.weights());
                MemoryEstimate estimate = MemoryEstimate.of(inspection, types, contextLength);
                var problems = MemoryBudget.problems(estimate, available);
                if (!problems.isEmpty()) {
                    throw new InsufficientMemoryException("Cannot load " + root + ":\n  - " + String.join("\n  - ", problems));
                }
            }

            AbstractModel model = load(inspection, root, workDir, types, b.threads);
            DefaultBi4j bi4j = new DefaultBi4j(model, inspection, workDir, contextLength);

            LoadEvent event = new LoadEvent();
            event.model = root.toString();
            event.weights = inspection.weights().toString();
            event.memory = types.memory().toString();
            event.activations = types.activations().toString();
            event.loadMillis = (System.nanoTime() - start) / 1_000_000;
            event.estimateBytes = MemoryEstimate.of(inspection, types, contextLength).kvBytes() + inspection.weightBytes();
            event.commit();
            logger.info("Loaded {} ({} weights, {}) in {} ms", root, inspection.weights(), types, event.loadMillis);
            return bi4j;
        } catch (IOException e) {
            deleteQuietly(workDir);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteQuietly(workDir);
            if (e instanceof Bi4jException || e instanceof IllegalArgumentException) throw e;
            throw new UnsupportedModelException("Cannot load " + root + ": " + e.getMessage(), e);
        }
    }

    private static Path resolve(Builder b) {
        if (b.modelPath != null) return b.modelPath;
        if (b.modelId == null) throw new IllegalStateException("model(...) is required");
        String[] parts = b.modelId.split("/");
        if (parts.length == 0 || parts.length > 2) {
            throw new ModelNotFoundException("Model id '" + b.modelId + "' must be in the form owner/name");
        }
        try {
            File dir = SafeTensorSupport.maybeDownloadModel(
                b.modelsDir.toString(),
                parts.length == 2 ? Optional.of(parts[0]) : Optional.empty(),
                parts[parts.length - 1],
                true,
                Optional.empty(),
                Optional.ofNullable(b.authToken),
                Optional.empty()
            );
            return dir.toPath();
        } catch (IOException e) {
            throw new ModelNotFoundException(
                "Could not get model '"
                    + b.modelId
                    + "': "
                    + e.getMessage()
                    + (b.authToken == null ? ". For a gated or private model, pass authToken(...)." : ""),
                e
            );
        }
    }

    private static AbstractModel load(ModelInspection m, Path root, Path workDir, WorkingTypes t, Integer threads) {
        File file = root.toFile();
        if (m.type() == ModelSupport.ModelType.BERT) {
            return ModelSupport.loadEmbeddingModel(file, t.memory(), t.activations());
        }
        return ModelSupport.loadModel(file, workDir.toFile(), t.memory(), t.activations(), Optional.empty(), Optional.ofNullable(threads));
    }

    AbstractModel model() {
        return model;
    }

    Path workDir() {
        return workDir;
    }

    void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Bi4j is closed");
    }

    @Override
    public Capabilities capabilities() {
        ensureOpen();
        return capabilities;
    }

    @Override
    public float[] embed(String text) {
        ensureOpen();
        if (!capabilities.embeddings()) throw new UnsupportedOperationException("this model does not produce embeddings");
        return model.embed(text, Generator.PoolingType.AVG);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            model.close();
        } finally {
            deleteQuietly(workDir);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            logger.debug("Could not delete {}", dir, e);
        }
    }
}
