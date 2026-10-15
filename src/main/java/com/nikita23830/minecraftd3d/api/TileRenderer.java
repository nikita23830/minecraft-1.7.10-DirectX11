package com.nikita23830.minecraftd3d.api;

import net.minecraft.tileentity.TileEntity;

public interface TileRenderer<T extends TileEntity> {
    void render(T tile, RenderContext ctx);
}
