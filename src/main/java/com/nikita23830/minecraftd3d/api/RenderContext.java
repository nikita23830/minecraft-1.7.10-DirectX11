package com.nikita23830.minecraftd3d.api;

public interface RenderContext {
    RenderPass pass();

    float partialTicks();

    double cameraX();

    double cameraY();

    double cameraZ();

    void draw(Mesh mesh, Material material, double x, double y, double z, Matrix4 local, int light, int tint);

    Batch batch(Material material);

    int lightAt(int x, int y, int z);

    void nativeCallback(long fn, long userData);
}
