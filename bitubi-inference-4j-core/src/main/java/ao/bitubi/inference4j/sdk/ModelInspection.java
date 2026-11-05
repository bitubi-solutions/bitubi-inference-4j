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

import ao.bitubi.inference4j.gguf.GgufConfigFactory;
import ao.bitubi.inference4j.gguf.GgufFile;
import ao.bitubi.inference4j.gguf.GgufTensorInfo;
import ao.bitubi.inference4j.model.ModelSupport;
import ao.bitubi.inference4j.safetensors.Config;
import ao.bitubi.inference4j.safetensors.DType;
import ao.bitubi.inference4j.safetensors.SafeTensorSupport;
import ao.bitubi.inference4j.safetensors.TensorInfo;
import ao.bitubi.inference4j.util.JsonSupport;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** What a model folder or GGUF file holds, read from the headers only: nothing is loaded. */
record ModelInspection(Path root, DType weights, long weightBytes, boolean gguf, ModelSupport.ModelType type, int layers, int kvLength,
    int maxContext) {

    /** @throws IllegalArgumentException if the path is not a model the engine can read */
    static ModelInspection of(Path modelRoot) {
        File f = modelRoot.toFile();
        if (!f.exists()) throw new IllegalArgumentException("Model location does not exist: " + modelRoot);
        try {
            return f.isFile() && f.getName().toLowerCase().endsWith(".gguf") ? ofGguf(modelRoot) : ofSafetensors(modelRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ModelInspection ofGguf(Path file) throws IOException {
        GgufFile gguf = new GgufFile(file);
        Config c = GgufConfigFactory.create(gguf.getMetadata());
        Map<DType, Long> bytes = new EnumMap<>(DType.class);
        long total = 0;
        for (GgufTensorInfo t : gguf.getTensors()) {
            DType d = switch (t.getType()) {
                case F32 -> DType.F32;
                case F16 -> DType.F16;
                case BF16 -> DType.BF16;
                case Q4_0 -> DType.Q4;
                case Q8_0 -> DType.I8;
            };
            bytes.merge(d, t.getByteSize(), Long::sum);
            total += t.getByteSize();
        }
        return new ModelInspection(
            file,
            dominant(bytes),
            total,
            true,
            ModelSupport.ModelType.QWEN2,
            c.numberOfLayers,
            c.kvLength,
            c.contextLength
        );
    }

    private static ModelInspection ofSafetensors(Path dir) throws IOException {
        File config = dir.resolve("config.json").toFile();
        if (!config.isFile()) throw new IllegalArgumentException("config.json not found in " + dir);
        ModelSupport.ModelType type = SafeTensorSupport.detectModel(config);
        Config c = JsonSupport.om.readValue(config, type.configClass);

        File[] shards = dir.toFile().listFiles((d, name) -> name.endsWith(".safetensors"));
        if (shards == null || shards.length == 0) throw new IllegalArgumentException("No .safetensors file in " + dir);
        Map<DType, Long> bytes = new EnumMap<>(DType.class);
        long total = 0;
        for (File shard : shards) {
            try (RandomAccessFile raf = new RandomAccessFile(shard, "r")) {
                ByteBuffer length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                raf.getChannel().read(length, 0);
                ByteBuffer header = ByteBuffer.allocate((int) (8 + length.getLong(0)));
                raf.getChannel().read(header, 0);
                for (TensorInfo t : SafeTensorSupport.readTensorInfoMap(header.flip(), Optional.empty()).values()) {
                    bytes.merge(t.dType, t.dataOffsets[1] - t.dataOffsets[0], Long::sum);
                }
                total += shard.length();
            }
        }
        return new ModelInspection(dir, dominant(bytes), total, false, type, c.numberOfLayers, c.kvLength, c.contextLength);
    }

    /** The type holding the most bytes: it decides which kernels run. */
    private static DType dominant(Map<DType, Long> bytes) {
        return bytes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }
}
