package com.nikita23830.minecraftd3d.api.impl;

import net.minecraft.client.Minecraft;

import com.nikita23830.minecraftd3d.api.Batch;
import com.nikita23830.minecraftd3d.api.Material;
import com.nikita23830.minecraftd3d.api.Matrix4;
import com.nikita23830.minecraftd3d.api.RenderContext;
import com.nikita23830.minecraftd3d.api.RenderPass;

public abstract class BaseContext implements RenderContext {
    static final float[] LIGHT0 = norm(0.2f, 1.0f, -0.7f), LIGHT1 = norm(-0.2f, 1.0f, 0.7f);

    protected final RenderPass pass;
    protected final float pt;
    protected final double cx, cy, cz;
    protected final float[] fog;
    public int draws;

    protected BaseContext(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog) {
        this.pass = pass; this.pt = pt; this.cx = cx; this.cy = cy; this.cz = cz; this.fog = fog;
    }

    public RenderPass pass() { return pass; }
    public float partialTicks() { return pt; }
    public double cameraX() { return cx; }
    public double cameraY() { return cy; }
    public double cameraZ() { return cz; }

    protected boolean world() {
        return pass != RenderPass.GUI;
    }

    public int lightAt(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!world() || mc.theWorld == null) return 0x00F000F0;
        return mc.theWorld.getLightBrightnessForSkyBlocks(x, y, z, 0);
    }

    public Batch batch(Material material) {
        return new BatchBuffer(this, material);
    }

    protected abstract void submitBatch(BatchBuffer batch);

    public void finish() {}

    static void lightsInModel(Matrix4 local, float[] out0, float[] out1) {
        mulT(local.m, LIGHT0, out0);
        mulT(local.m, LIGHT1, out1);
    }

    private static void mulT(float[] m, float[] v, float[] out) {
        float x = m[0] * v[0] + m[4] * v[1] + m[8] * v[2];
        float y = m[1] * v[0] + m[5] * v[1] + m[9] * v[2];
        float z = m[2] * v[0] + m[6] * v[1] + m[10] * v[2];
        float[] n = norm(x, y, z);
        out[0] = n[0]; out[1] = n[1]; out[2] = n[2];
    }

    static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return l == 0 ? new float[] { 0, 1, 0 } : new float[] { x / l, y / l, z / l };
    }
}
