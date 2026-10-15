package com.nikita23830.minecraftd3d.api;

public interface Batch {
    Batch color(int argb);

    Batch uv(float u, float v);

    Batch normal(float x, float y, float z);

    Batch vertex(double x, double y, double z);

    void end();
}
