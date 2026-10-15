package com.nikita23830.minecraftd3d.api;

public interface Mesh extends AutoCloseable {
    int vertexCount();

    void close();
}
