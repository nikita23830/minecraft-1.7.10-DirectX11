package com.nikita23830.minecraftd3d.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MeshBuilder {
    public enum Mode { QUADS, TRIANGLES }

    public static final int VERTEX_BYTES = 28;
    private ByteBuffer buf = ByteBuffer.allocateDirect(VERTEX_BYTES * 256).order(ByteOrder.nativeOrder());
    private Mode mode = Mode.QUADS;
    private int count;
    private float u, v, nx, ny = 1, nz;
    private int color = 0xFFFFFFFF;

    public MeshBuilder begin(Mode m) {
        mode = m;
        count = 0;
        buf.clear();
        return this;
    }

    public MeshBuilder uv(float u, float v) { this.u = u; this.v = v; return this; }
    public MeshBuilder color(int argb) { color = argb; return this; }
    public MeshBuilder normal(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }

    public MeshBuilder vertex(float x, float y, float z) {
        if (buf.remaining() < VERTEX_BYTES) {
            ByteBuffer b = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
            buf.flip();
            b.put(buf);
            buf = b;
        }
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        buf.putInt(packColor(color));
        buf.putInt(packNormal(nx, ny, nz));
        count++;
        return this;
    }

    public MeshBuilder quad(float[] p, float u0, float v0, float u1, float v1) {
        uv(u0, v0).vertex(p[0], p[1], p[2]);
        uv(u0, v1).vertex(p[3], p[4], p[5]);
        uv(u1, v1).vertex(p[6], p[7], p[8]);
        uv(u1, v0).vertex(p[9], p[10], p[11]);
        return this;
    }

    public static int packColor(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return a << 24 | b << 16 | g << 8 | r;
    }

    public static int packNormal(float x, float y, float z) {
        return (Math.round(x * 127) & 255) | (Math.round(y * 127) & 255) << 8 | (Math.round(z * 127) & 255) << 16;
    }

    public Mode mode() { return mode; }
    public int vertexCount() { return count; }

    public ByteBuffer data() {
        ByteBuffer d = buf.duplicate().order(ByteOrder.nativeOrder());
        d.flip();
        return d;
    }
}
