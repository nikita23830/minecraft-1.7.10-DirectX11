package com.nikita23830.minecraftd3d.api;

import net.minecraft.util.ResourceLocation;

public interface RenderDevice {
    Texture createTexture(int width, int height, int mipLevels, boolean blur, boolean clamp);

    Texture minecraftTexture(ResourceLocation location);

    Texture blockAtlas();

    Mesh createMesh(MeshBuilder builder);
}
