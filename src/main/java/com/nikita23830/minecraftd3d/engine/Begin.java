package com.nikita23830.minecraftd3d.engine;

import java.util.Arrays;

final class Begin {
    private Begin() {}

    private static boolean active;
    private static int mode, n;
    private static int[] raw = new int[8 * 256];
    private static float u, v;
    private static int color, normal;
    private static boolean hasTex, hasColor, hasNormal;

    static float u() { return u; }
    static float v() { return v; }

    static void begin(int m) {
        active = true;
        mode = m;
        n = 0;
        hasTex = hasColor = hasNormal = false;
        color = RS.colorPacked();
        normal = pack(RS.nx, RS.ny, RS.nz);
    }

    static void color(float r, float g, float b, float a) {
        if (!active) return;
        hasColor = true;
        color = RS.clamp255(a) << 24 | RS.clamp255(b) << 16 | RS.clamp255(g) << 8 | RS.clamp255(r);
    }

    static void normal(float x, float y, float z) {
        if (!active) return;
        hasNormal = true;
        normal = pack(x, y, z);
    }

    static void texCoord(float uu, float vv) {
        u = uu; v = vv;
        hasTex = true;
    }

    static void vertex(float x, float y, float z) {
        if (!active) { GLEmu.warnOnce("glVertex außerhalb von glBegin"); return; }
        if ((n + 1) * 8 > raw.length) raw = Arrays.copyOf(raw, raw.length * 2);
        int b = n * 8;
        raw[b] = Float.floatToRawIntBits(x);
        raw[b + 1] = Float.floatToRawIntBits(y);
        raw[b + 2] = Float.floatToRawIntBits(z);
        raw[b + 3] = Float.floatToRawIntBits(u);
        raw[b + 4] = Float.floatToRawIntBits(v);
        raw[b + 5] = color;
        raw[b + 6] = normal;
        raw[b + 7] = 0;
        n++;
    }

    static void end() {
        if (!active) return;
        active = false;
        if (n > 0) Immediate.submit(Arrays.copyOf(raw, n * 8), n, mode, hasTex, hasColor, hasNormal, false);
    }

    private static int pack(float x, float y, float z) {
        return ((byte) (x * 127)) & 255 | (((byte) (y * 127)) & 255) << 8 | (((byte) (z * 127)) & 255) << 16;
    }
}
