package com.nikita23830.minecraftd3d.api;

public interface Texture extends AutoCloseable {
    int width();

    int height();

    void upload(int level, int x, int y, int w, int h, int[] argb);

    void close();
}
