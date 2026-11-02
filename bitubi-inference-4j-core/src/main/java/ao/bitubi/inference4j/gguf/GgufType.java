/*
 * Copyright 2026 Bitubi Solutions
 *
 * This file is part of the bitubi-inference-4j project.
 * Licensed under the Apache License 2.0.
 */
package ao.bitubi.inference4j.gguf;

/** GGML types supported by the first GGUF slice. */
public enum GgufType {
    F32(0, 1, 4),
    F16(1, 1, 2),
    Q4_0(2, 32, 18),
    Q8_0(8, 32, 34),
    BF16(30, 1, 2);

    private final int id;
    private final int elementsPerBlock;
    private final int bytesPerBlock;

    GgufType(int id, int elementsPerBlock, int bytesPerBlock) {
        this.id = id;
        this.elementsPerBlock = elementsPerBlock;
        this.bytesPerBlock = bytesPerBlock;
    }

    public int getId() { return id; }
    public int getElementsPerBlock() { return elementsPerBlock; }
    public int getBytesPerBlock() { return bytesPerBlock; }

    public static GgufType fromId(int id) {
        for (GgufType type : values()) if (type.id == id) return type;
        throw new IllegalArgumentException("Unsupported GGUF type in this slice: " + id);
    }
}
