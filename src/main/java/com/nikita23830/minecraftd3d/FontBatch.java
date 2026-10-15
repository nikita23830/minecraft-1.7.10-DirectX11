package com.nikita23830.minecraftd3d;

import java.util.ArrayList;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import com.nikita23830.minecraftd3d.engine.Tess;

public final class FontBatch {
    private static final FontBatch MAIN = new FontBatch();
    private static final ThreadLocal<FontBatch> OTHER = new ThreadLocal<FontBatch>() {
        @Override
        protected FontBatch initialValue() {
            return new FontBatch();
        }
    };

    public static FontBatch get() {
        return PD3D.isMainThread() && !PD3D.isLoadingThread() ? MAIN : OTHER.get();
    }

    private float[] v = new float[5 * 4 * 512];
    private int n;
    private ResourceLocation tex;
    private float r = 1, g = 1, b = 1, a = 1;
    private int rgba = -1;

    public static final class Segment {
        public final ResourceLocation tex;
        public final float[] verts;
        public final int count;
        public final boolean shadow;

        Segment(ResourceLocation tex, float[] verts, int count, boolean shadow) {
            this.tex = tex;
            this.verts = verts;
            this.count = count;
            this.shadow = shadow;
        }
    }

    public ArrayList<Segment> record;

    public boolean recordOnly;

    public void color(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        rgba = clamp(a) << 24 | clamp(b) << 16 | clamp(g) << 8 | clamp(r);
    }

    private static int clamp(float f) {
        return f <= 0 ? 0 : f >= 1 ? 255 : (int) (f * 255f + 0.5f);
    }

    public boolean shadow;

    public int depth;

    public boolean switchTo(ResourceLocation t) {
        if (t == tex) return false;
        flush();
        tex = t;
        return true;
    }

    public void quad(float x0t, float x0b, float x1t, float x1b, float y0, float y1, float u0, float v0, float u1, float v1) {
        if ((n + 4) * 5 > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
        int p = n * 5;
        float c = Float.intBitsToFloat(rgba);
        p = put(p, x0t, y0, u0, v0, c);
        p = put(p, x0b, y1, u0, v1, c);
        p = put(p, x1b, y1, u1, v1, c);
        put(p, x1t, y0, u1, v0, c);
        n += 4;
    }

    private int put(int p, float x, float y, float u, float w, float c) {
        v[p] = x; v[p + 1] = y; v[p + 2] = u; v[p + 3] = w; v[p + 4] = c;
        return p + 5;
    }

    public void flush() {
        if (n > 0) {
            if (record != null) record.add(new Segment(tex, java.util.Arrays.copyOf(v, n * 5), n, shadow));
            if (record == null || !recordOnly) emit(v, n);
            n = 0;
        }
    }

    public void end() {
        flush();
        tex = null;
    }

    static void emit(float[] v, int count) {
        Tessellator t = PD3D.engine ? Tess.instance() : Tessellator.instance;
        t.startDrawingQuads();
        for (int i = 0, p = 0; i < count; i++, p += 5) {
            int c = Float.floatToRawIntBits(v[p + 4]);
            t.setColorRGBA(c & 255, c >>> 8 & 255, c >>> 16 & 255, c >>> 24);
            t.addVertexWithUV(v[p], v[p + 1], 0, v[p + 2], v[p + 3]);
        }
        t.draw();
    }
}
