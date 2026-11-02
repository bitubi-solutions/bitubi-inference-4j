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
package ao.bitubi.inference4j.gguf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GGUF v3 little-endian reader. Metadata is read through a channel; each tensor is mapped separately. */
public final class GgufFile {
    private static final int GGUF_MAGIC = 0x46554747;
    private static final int VERSION = 3;
    private final Path path;
    private final long fileSize;
    private final long dataOffset;
    private final int alignment;
    private final Map<String, Object> metadata;
    private final List<GgufTensorInfo> tensors;
    private final Map<String, GgufTensorInfo> tensorsByName;

    public GgufFile(Path path) throws IOException {
        this.path = path;
        try (FileChannel channel = FileChannel.open(path)) {
            this.fileSize = channel.size();
            Reader reader = new Reader(channel);
            int magic = reader.readInt();
            if (magic != GGUF_MAGIC) throw new IOException("Invalid GGUF magic: " + Integer.toHexString(magic));
            int version = reader.readInt();
            if (version != VERSION) throw new IOException("Unsupported GGUF version: " + version + "; expected: " + VERSION);
            long tensorCount = checkedCount(reader.readLong(), "tensors");
            long metadataCount = checkedCount(reader.readLong(), "metadata");
            Map<String, Object> parsedMetadata = new HashMap<>();
            for (long i = 0; i < metadataCount; i++) {
                String key = reader.readString();
                Object value = reader.readValue(reader.readInt(), 0);
                if (parsedMetadata.putIfAbsent(key, value) != null) throw new IOException("Duplicate GGUF key: " + key);
            }
            Object alignmentValue = parsedMetadata.get("general.alignment");
            this.alignment = alignmentValue == null ? 32 : asInt(alignmentValue, "general.alignment");
            if (alignment < 8 || alignment % 8 != 0) throw new IOException("Invalid GGUF alignment: " + alignment);
            this.metadata = Collections.unmodifiableMap(parsedMetadata);
            if (tensorCount > (fileSize - reader.position()) / 24) throw new IOException("Inconsistent GGUF tensor count: " + tensorCount);
            List<RawTensor> rawTensors = new ArrayList<>((int) tensorCount);
            for (int i = 0; i < tensorCount; i++) {
                String name = reader.readString();
                int dimensionCount = reader.readInt();
                if (dimensionCount < 1 || dimensionCount > 4) throw new IOException("Invalid GGUF dimension count in " + name + ": " + dimensionCount);
                long[] dims = new long[dimensionCount];
                for (int d = 0; d < dimensionCount; d++) dims[d] = reader.readLong();
                int typeId = reader.readInt();
                long relativeOffset = reader.readLong();
                if (relativeOffset < 0 || relativeOffset % alignment != 0) throw new IOException("Invalid offset/alignment in " + name + ": " + relativeOffset);
                try { rawTensors.add(new RawTensor(name, GgufType.fromId(typeId), dims, relativeOffset)); }
                catch (IllegalArgumentException e) { throw new IOException("Tensor " + name + ": " + e.getMessage(), e); }
            }
            this.dataOffset = alignUp(reader.position(), alignment);
            if (dataOffset > fileSize) throw new IOException("GGUF header truncated before the tensor data");
            List<GgufTensorInfo> parsedTensors = new ArrayList<>((int) tensorCount);
            Map<String, GgufTensorInfo> byName = new HashMap<>();
            for (RawTensor raw : rawTensors) {
                GgufTensorInfo info;
                try { info = new GgufTensorInfo(raw.name, raw.type, raw.dims, raw.offset, dataOffset); }
                catch (ArithmeticException | IllegalArgumentException e) { throw new IOException("Invalid GGUF tensor descriptor: " + raw.name, e); }
                if (info.getOffset() > fileSize || info.getByteSize() > fileSize - info.getOffset()) {
                    throw new IOException("Truncated data for GGUF tensor " + raw.name);
                }
                if (byName.putIfAbsent(raw.name, info) != null) throw new IOException("Duplicate GGUF tensor name: " + raw.name);
                parsedTensors.add(info);
            }
            this.tensors = Collections.unmodifiableList(parsedTensors);
            this.tensorsByName = Collections.unmodifiableMap(byName);
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IOException("GGUF header truncated or malformed", e);
        } catch (ArithmeticException e) {
            throw new IOException("GGUF size/offset exceeds the supported limits", e);
        }
    }

    private static long checkedCount(long value, String what) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid GGUF " + what + " count: " + value);
        return value;
    }

    private static int asInt(Object value, String key) throws IOException {
        if (!(value instanceof Number number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) {
            throw new IOException("Invalid value for " + key + ": " + value);
        }
        return number.intValue();
    }

    private static long alignUp(long value, int alignment) {
        return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment);
    }

    public int getAlignment() { return alignment; }
    public long getDataOffset() { return dataOffset; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<GgufTensorInfo> getTensors() { return tensors; }
    public GgufTensorInfo getTensor(String name) { return tensorsByName.get(name); }

    /** Maps only this tensor's range; the caller must finish consuming the buffer quickly. */
    public MappedByteBuffer mapTensor(GgufTensorInfo tensor) throws IOException {
        Objects.requireNonNull(tensor, "tensor");
        if (tensorsByName.get(tensor.getName()) != tensor) throw new IllegalArgumentException("Tensor does not belong to this file: " + tensor.getName());
        long size = tensor.getByteSize();
        if (size > Integer.MAX_VALUE) throw new IOException("Tensor exceeds the limit of a Java mapping: " + tensor.getName() + " (" + size + " bytes)");
        try (FileChannel channel = FileChannel.open(path)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, tensor.getOffset(), size);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return mapped;
        }
    }

    private record RawTensor(String name, GgufType type, long[] dims, long offset) {}

    private static final class Reader {
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        private long position;
        Reader(FileChannel channel) { this.channel = channel; buffer.limit(0); }
        long position() { return position; }
        private void require(int count) throws IOException {
            while (buffer.remaining() < count) {
                buffer.compact();
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Premature end of GGUF file at position " + position);
                if (read == 0) continue;
                buffer.flip();
            }
        }
        int readInt() throws IOException { require(4); position += 4; return buffer.getInt(); }
        long readLong() throws IOException { require(8); position += 8; return buffer.getLong(); }
        short readShort() throws IOException { require(2); position += 2; return buffer.getShort(); }
        int readByte() throws IOException { require(1); position++; return buffer.get() & 0xff; }
        int readSignedByte() throws IOException { require(1); position++; return buffer.get(); }
        byte[] readBytes(int length) throws IOException {
            if (length < 0) throw new IOException("Negative GGUF length: " + length);
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                if (!buffer.hasRemaining()) { buffer.clear(); int read = channel.read(buffer); if (read < 0) throw new IOException("Premature end of GGUF string"); buffer.flip(); }
                int count = Math.min(buffer.remaining(), length - offset);
                buffer.get(bytes, offset, count); offset += count; position += count;
            }
            return bytes;
        }
        String readString() throws IOException {
            long length = readLong();
            if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("GGUF string too long: " + length);
            return new String(readBytes((int) length), StandardCharsets.UTF_8);
        }
        Object readValue(int type, int depth) throws IOException {
            if (depth > 8) throw new IOException("GGUF arrays nested too deeply");
            return switch (type) {
                case 0 -> readByte();
                case 1 -> readSignedByte();
                case 2 -> Short.toUnsignedInt(readShort());
                case 3 -> readShort();
                case 4 -> Integer.toUnsignedLong(readInt());
                case 5 -> readInt();
                case 6 -> Float.intBitsToFloat(readInt());
                case 7 -> { int v = readByte(); if (v > 1) throw new IOException("Invalid GGUF boolean: " + v); yield v != 0; }
                case 8 -> readString();
                case 9 -> {
                    int elementType = readInt(); long count = readLong();
                    if (count < 0 || count > Integer.MAX_VALUE) throw new IOException("GGUF array too large: " + count);
                    List<Object> values = new ArrayList<>((int) count);
                    for (int i = 0; i < count; i++) values.add(readValue(elementType, depth + 1));
                    yield Collections.unmodifiableList(values);
                }
                case 10, 11 -> readLong();
                case 12 -> Double.longBitsToDouble(readLong());
                default -> throw new IOException("Unknown GGUF metadata type: " + type);
            };
        }
    }
}
