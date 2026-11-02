/*
 * Copyright 2026 Bitubi Solutions
 * Licensed under the Apache License 2.0.
 */
package ao.bitubi.inference4j.gguf;

import java.util.Arrays;

/** Tensor descriptor; dimensions keep the GGML order and offset is absolute in the file. */
public final class GgufTensorInfo {
    private final String name;
    private final GgufType type;
    private final long[] dims;
    private final long relativeOffset;
    private final long offset;
    private final long byteSize;

    public GgufTensorInfo(String name, GgufType type, long[] dims, long relativeOffset, long dataOffset) {
        if (name == null || type == null || dims == null || dims.length == 0 || dims.length > 4) {
            throw new IllegalArgumentException("Invalid GGUF tensor descriptor");
        }
        this.name = name;
        this.type = type;
        this.dims = dims.clone();
        this.relativeOffset = relativeOffset;
        this.offset = Math.addExact(dataOffset, relativeOffset);
        long elements = 1;
        for (long dim : dims) {
            if (dim <= 0) throw new IllegalArgumentException("Invalid GGUF dimension in " + name + ": " + dim);
            elements = Math.multiplyExact(elements, dim);
        }
        if (elements % type.getElementsPerBlock() != 0) {
            throw new IllegalArgumentException("Element count is not a multiple of the block size of " + type + " in " + name);
        }
        this.byteSize = Math.multiplyExact(elements / type.getElementsPerBlock(), type.getBytesPerBlock());
    }

    public String getName() { return name; }
    public GgufType getType() { return type; }
    public long[] getDims() { return dims.clone(); }
    public int getDimensions() { return dims.length; }
    public long getRelativeOffset() { return relativeOffset; }
    public long getOffset() { return offset; }
    public long getByteSize() { return byteSize; }

    @Override
    public String toString() {
        return "GgufTensorInfo{name='" + name + "', type=" + type + ", dims=" + Arrays.toString(dims) + ", offset=" + offset + '}';
    }
}
