package com.nikita23830.minecraftd3d.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;

import com.nikita23830.minecraftd3d.api.impl.ApiBackend;

public final class MinecraftD3DRender {
    private MinecraftD3DRender() {}

    private static ApiBackend impl;
    @SuppressWarnings("unchecked")
    private static final List<RenderCallback>[] passes = new List[RenderPass.values().length];
    private static final Map<Class<? extends TileEntity>, TileRenderer<?>> tiles = new LinkedHashMap<Class<? extends TileEntity>, TileRenderer<?>>();

    public static double tileRenderDistance = 64.0;

    static {
        for (int i = 0; i < passes.length; i++) passes[i] = new ArrayList<RenderCallback>();
    }

    public static Backend backend() {
        return impl == null ? Backend.NONE : impl.kind();
    }

    public static RenderDevice device() {
        if (impl == null) throw new IllegalStateException("MinecraftD3DRender: Backend nicht aktiv (backend() == NONE)");
        return impl.device();
    }

    public static void registerPass(RenderPass pass, RenderCallback callback) {
        passes[pass.ordinal()].add(callback);
    }

    public static <T extends TileEntity> void registerTileRenderer(Class<T> type, TileRenderer<? super T> renderer) {
        tiles.put(type, renderer);
    }

    public static void install(ApiBackend backend) {
        impl = backend;
    }

    public static List<RenderCallback> callbacks(RenderPass pass) {
        return passes[pass.ordinal()];
    }

    public static Map<Class<? extends TileEntity>, TileRenderer<?>> tileRenderers() {
        return tiles;
    }
}
