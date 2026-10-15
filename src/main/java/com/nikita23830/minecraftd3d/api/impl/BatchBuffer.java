package com.nikita23830.minecraftd3d.api.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.nikita23830.minecraftd3d.api.Batch;
import com.nikita23830.minecraftd3d.api.Material;
import com.nikita23830.minecraftd3d.api.MeshBuilder;

public final class BatchBuffer implements Batch {
    private static ByteBuffer shared = ByteBuffer.allocateDirect(MeshBuilder.VERTEX_BYTES * 4096).order(ByteOrder.nativeOrder());

    final BaseContext ctx;
    public final Material material;
    private ByteBuffer buf;
    private int count;
    private float u, v, nx, ny = 1, nz;
    private int color = -1;

    BatchBuffer(BaseContext ctx, Material material) {
        this.ctx = ctx;
        this.material = material;
        buf = shared;
        buf.clear();
    }

    public Batch color(int argb) { color = argb; return this; }
    public Batch uv(float u, float v) { this.u = u; this.v = v; return this; }
    public Batch normal(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }

    public Batch vertex(double x, double y, double z) {
        if (buf.remaining() < MeshBuilder.VERTEX_BYTES) {
            ByteBuffer b = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
            buf.flip();
            b.put(buf);
            buf = b;
            shared = b;
        }
        boolean world = ctx.world();
        buf.putFloat((float) (world ? x - ctx.cx : x)).putFloat((float) (world ? y - ctx.cy : y)).putFloat((float) (world ? z - ctx.cz : z));
        buf.putFloat(u).putFloat(v);
        buf.putInt(MeshBuilder.packColor(color));
        buf.putInt(MeshBuilder.packNormal(nx, ny, nz));
        count++;
        return this;
    }

    public void end() {
        if (count >= 4) ctx.submitBatch(this);
        count = 0;
        buf.clear();
    }

    public int count() { return count - count % 4; }

    public ByteBuffer data() {
        ByteBuffer d = buf.duplicate().order(ByteOrder.nativeOrder());
        d.flip();
        return d;
    }
}
