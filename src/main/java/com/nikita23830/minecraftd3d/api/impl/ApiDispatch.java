package com.nikita23830.minecraftd3d.api.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nikita23830.minecraftd3d.api.*;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;

import com.nikita23830.minecraftd3d.PD3D;

public final class ApiDispatch {
    private ApiDispatch() {}

    private static ApiBackend backend;
    private static final Map<Class<?>, TileRenderer<?>> resolved = new HashMap<Class<?>, TileRenderer<?>>();
    private static final TileRenderer<?> NONE = new TileRenderer<TileEntity>() {
        public void render(TileEntity t, RenderContext c) {}
    };

    public static int lastTiles, lastDraws;
    private static int frameTiles, frameDraws;

    public static void install(ApiBackend b) {
        backend = b;
        MinecraftD3DRender.install(b);
        PD3D.LOG.info("MinecraftD3DRender API: Backend {}", b == null ? "NONE" : b.kind());
    }

    public static void worldPasses(float pt, double cx, double cy, double cz, float[] fog) {
        worldPass(RenderPass.WORLD_OPAQUE, pt, cx, cy, cz, fog);
        worldPass(RenderPass.WORLD_TRANSLUCENT, pt, cx, cy, cz, fog);
        worldPass(RenderPass.WORLD_OVERLAY, pt, cx, cy, cz, fog);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void worldPass(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog) {
        if (backend == null) return;
        List<RenderCallback> cbs = MinecraftD3DRender.callbacks(pass);
        boolean tiles = pass == RenderPass.WORLD_OPAQUE && !MinecraftD3DRender.tileRenderers().isEmpty();
        if (cbs.isEmpty() && !tiles) return;
        BaseContext ctx = backend.worldContext(pass, pt, cx, cy, cz, fog);
        try {
            if (tiles) {
                Minecraft mc = Minecraft.getMinecraft();
                double r2 = MinecraftD3DRender.tileRenderDistance * MinecraftD3DRender.tileRenderDistance;
                List<TileEntity> list = mc.theWorld.loadedTileEntityList;
                for (int i = 0; i < list.size(); i++) {
                    TileEntity te = list.get(i);
                    TileRenderer r = find(te.getClass());
                    if (r == NONE) continue;
                    double dx = te.xCoord + 0.5 - cx, dy = te.yCoord + 0.5 - cy, dz = te.zCoord + 0.5 - cz;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    r.render(te, ctx);
                    frameTiles++;
                }
            }
            for (int i = 0; i < cbs.size(); i++) cbs.get(i).render(ctx);
        } finally {
            ctx.finish();
            frameDraws += ctx.draws;
        }
    }

    public static void guiPass(float pt) {
        if (backend != null) {
            List<RenderCallback> cbs = MinecraftD3DRender.callbacks(RenderPass.GUI);
            if (!cbs.isEmpty()) {
                BaseContext ctx = backend.guiContext(pt);
                try {
                    for (int i = 0; i < cbs.size(); i++) cbs.get(i).render(ctx);
                } finally {
                    ctx.finish();
                    frameDraws += ctx.draws;
                }
            }
        }
        lastTiles = frameTiles; lastDraws = frameDraws;
        frameTiles = 0; frameDraws = 0;
    }

    private static TileRenderer<?> find(Class<?> c) {
        TileRenderer<?> r = resolved.get(c);
        if (r != null) return r;
        Map<Class<? extends TileEntity>, TileRenderer<?>> map = MinecraftD3DRender.tileRenderers();
        for (Class<?> k = c; k != null && r == null; k = k.getSuperclass()) r = map.get(k);
        if (r == null) r = NONE;
        resolved.put(c, r);
        return r;
    }
}
