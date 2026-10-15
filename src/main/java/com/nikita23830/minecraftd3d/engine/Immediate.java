package com.nikita23830.minecraftd3d.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Immediate {
    private Immediate() {}

    static final int VERTEX = 28, CMD = 192;

    static final int IF_BLEND = 1, IF_ALPHA = 2, IF_DEPTH = 4, IF_DEPTHWRITE = 8, IF_LIGHT = 16, IF_TEX = 32, IF_FOG = 128, IF_REVZ = 512, IF_SCISSOR = 1024, IF_NHALF = 2048,
        IF_POLYOFF = 4096,
        IF_LIGHTMAP = 64,
        IF_GLMESH = 8192;
    private static ByteBuffer verts = ByteBuffer.allocateDirect(VERTEX * 65536).order(ByteOrder.nativeOrder());
    private static ByteBuffer cmds = ByteBuffer.allocateDirect(CMD * 1024).order(ByteOrder.nativeOrder());
    private static int nVerts, nCmds;

    private static boolean inWorld;

    private static boolean inFbo, savedInWorld;
    private static int tgtTex, tgtDepth, tgtW, tgtH;

    private static int lastCmd = -1, lastTopo, lastTex, lastFlags, lastState = -1, lastProj = -1, lastLight = -1;
    public static int submitted, merged, worldDraws;

    public static ByteBuffer vertexBuffer() { return verts; }
    public static ByteBuffer commandBuffer() { return cmds; }
    public static int vertexCount() { return nVerts; }
    public static int commandCount() { return nCmds; }

    public static void reset() {
        nVerts = 0; nCmds = 0; lastCmd = -1; submitted = 0; merged = 0; worldDraws = 0; inWorld = false; savedInWorld = false;

        if (inFbo) emitTarget(tgtTex, tgtDepth, tgtW, tgtH);
    }

    public static void afterFlush() {
        nVerts = 0; nCmds = 0; lastCmd = -1;
    }

    static boolean inWorld() { return inWorld && !inFbo; }

    private static void ensure(int addVerts) {
        if ((nVerts + addVerts) * VERTEX > verts.capacity()) {
            ByteBuffer b = ByteBuffer.allocateDirect(Math.max(verts.capacity() * 2, (nVerts + addVerts) * VERTEX)).order(ByteOrder.nativeOrder());
            verts.position(0).limit(nVerts * VERTEX);
            b.put(verts);
            verts = b;
        }
        if ((nCmds + 1) * CMD > cmds.capacity()) {
            ByteBuffer b = ByteBuffer.allocateDirect(cmds.capacity() * 2).order(ByteOrder.nativeOrder());
            cmds.position(0).limit(nCmds * CMD);
            b.put(cmds);
            cmds = b;
        }
    }

    public static void world() {
        world(false);
    }

    public static void world(boolean splitTranslucent) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 4).putInt(base + 4, splitTranslucent ? 1 : 0);
        lastCmd = -1;
        inWorld = true;
    }

    public static void worldEye(boolean splitTranslucent, float[] viewProj, int colorMask) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 4).putInt(base + 4, (splitTranslucent ? 1 : 0) | 2).putInt(base + 52, colorMask);
        for (int k = 0; k < 16; k++) cmds.putFloat(base + 56 + k * 4, viewProj[k]);
        lastCmd = -1;
        inWorld = true;
    }

    public static void worldTranslucent() {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 10);
        lastCmd = -1;
    }

    static void clearDepth(boolean write, boolean sc, int x, int y, int w, int h) {
        if (!inFbo) inWorld = false;
        if (!write || (sc && (w <= 0 || h <= 0))) return;
        int base = clearCmd(2, sc, x, y, w, h);
        cmds.putInt(base + 52, 15);
    }

    static void clearColor(float r, float g, float b, float a, int mask, boolean sc, int x, int y, int w, int h) {
        if (sc && (w <= 0 || h <= 0)) return;
        int base = clearCmd(3, sc, x, y, w, h);
        cmds.putInt(base + 52, mask);
        cmds.putFloat(base + 144, r).putFloat(base + 148, g).putFloat(base + 152, b).putFloat(base + 156, a);
    }

    private static int clearCmd(int type, boolean sc, int x, int y, int w, int h) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, type).putInt(base + 20, sc ? IF_SCISSOR : 0);
        cmds.putInt(base + 56, x).putInt(base + 60, y).putInt(base + 64, w).putInt(base + 68, h);
        lastCmd = -1;
        return base;
    }

    static void target(int tex, int depthKey, int w, int h) {
        boolean fbo = tex != 0 || depthKey != 0;
        if (fbo && !inFbo) { savedInWorld = inWorld; inWorld = false; }
        else if (!fbo && inFbo) inWorld = savedInWorld;
        inFbo = fbo; tgtTex = tex; tgtDepth = depthKey; tgtW = w; tgtH = h;
        emitTarget(tex, depthKey, w, h);
    }

    private static void emitTarget(int tex, int depthKey, int w, int h) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 8).putInt(base + 8, depthKey).putInt(base + 16, tex).putInt(base + 56, w).putInt(base + 60, h);
        lastCmd = -1;
    }

    static void blit(int[] v) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 9);
        for (int k = 0; k < v.length; k++) cmds.putInt(base + 56 + k * 4, v[k]);
        lastCmd = -1;
    }

    public static int apiVertices(ByteBuffer src, int count) {
        ensure(count);
        int first = nVerts;
        ByteBuffer d = verts.duplicate();
        d.position(nVerts * VERTEX);
        ByteBuffer s = src.duplicate();
        s.limit(s.position() + count * VERTEX);
        d.put(s);
        nVerts += count;
        return first;
    }

    public static void apiCommand(int type, int first, int count, int topo, int tex, int flags, int bs, int bd, int bsa, int bda,
                                  float alphaRef, int depthFunc, int cull, float[] clip, float[] l0, float[] l1, int light, int tint) {
        ensure(0);
        int cb = nCmds * CMD;
        for (int k = 0; k < CMD; k += 4) cmds.putInt(cb + k, 0);
        cmds.putInt(cb, type).putInt(cb + 4, topo).putInt(cb + 8, first).putInt(cb + 12, count).putInt(cb + 16, tex).putInt(cb + 20, flags);
        cmds.putInt(cb + 24, bs).putInt(cb + 28, bd).putInt(cb + 32, bsa).putInt(cb + 36, bda);
        cmds.putFloat(cb + 40, alphaRef).putInt(cb + 44, depthFunc).putInt(cb + 48, cull).putInt(cb + 52, 15);
        for (int k = 0; k < 16; k++) cmds.putFloat(cb + 56 + k * 4, clip[k]);
        for (int k = 0; k < 3; k++) { cmds.putFloat(cb + 120 + k * 4, l0[k]); cmds.putFloat(cb + 132 + k * 4, l1[k]); }
        cmds.putInt(cb + 144, light).putInt(cb + 148, tint);
        nCmds++;
        lastCmd = -1;
    }

    public static void apiNative(long fn, long user) {
        ensure(0);
        int cb = nCmds++ * CMD;
        cmds.putInt(cb, 6).putLong(cb + 56, fn).putLong(cb + 64, user);
        lastCmd = -1;
    }

    static void copyTex(int tex, int level, int xo, int yo, int x, int y, int w, int h) {
        ensure(0);
        int base = nCmds++ * CMD;
        cmds.putInt(base, 7).putInt(base + 8, level).putInt(base + 16, tex);
        cmds.putInt(base + 56, xo).putInt(base + 60, yo).putInt(base + 64, x).putInt(base + 68, y).putInt(base + 72, w).putInt(base + 76, h);
        lastCmd = -1;
    }

    public static void submit(int[] raw, int n, int drawMode, boolean hasTexture, boolean hasColor, boolean hasNormals, boolean hasBrightness) {
        if (n <= 0) return;
        if (DL.rec != null) {
            int[] copy = Arrays.copyOf(raw, n * 8);
            DL.add(() -> submitNow(copy, n, drawMode, hasTexture, hasColor, hasNormals, hasBrightness));
            DL.addGeometry(copy, n, drawMode, hasTexture, hasColor, hasNormals, hasBrightness);
            return;
        }
        submitNow(raw, n, drawMode, hasTexture, hasColor, hasNormals, hasBrightness);
    }

    private static void submitNow(int[] raw, int n, int drawMode, boolean hasTexture, boolean hasColor, boolean hasNormals, boolean hasBrightness) {
        int topo;
        int outCount;
        switch (drawMode) {
            case 7: topo = 0; outCount = n - n % 4; break;
            case 4: topo = 1; outCount = n - n % 3; break;
            case 6: case 5: case 9: topo = 1; outCount = Math.max(0, n - 2) * 3; break;
            case 8: topo = 0; outCount = (n / 2 - 1) * 4; if (outCount < 0) outCount = 0; break;
            case 1: topo = 2; outCount = n - n % 2; break;
            case 3: topo = 2; outCount = Math.max(0, n - 1) * 2; break;
            case 2: topo = 2; outCount = n * 2; break;
            default: return;
        }
        if (outCount == 0) return;
        submitted++;
        ensure(outCount);

        float[] m = RS.mv();
        float[] tm = RS.textureMatrixIdentity ? null : RS.tex();
        boolean hasTexOrig = hasTexture;
        boolean texGen = RS.texGenAny;
        if (texGen) { hasTexture = true; if (tm == null) tm = IDENT; }
        int color = RS.colorPacked();
        normalMatrix(m);
        int defNormal = packNormal(RS.nx, RS.ny, RS.nz);
        int[] lm = RS.lightmapOn ? RS.lightmapColors : null;
        int defBright = ((int) RS.lmT) << 16 | ((int) RS.lmS);
        int start = nVerts;
        int base = nVerts * VERTEX;

        for (int k = 0; k < outCount; k++) {
            int r = sourceIndex(drawMode, k, n) * 8;
            float x = Float.intBitsToFloat(raw[r]), y = Float.intBitsToFloat(raw[r + 1]), z = Float.intBitsToFloat(raw[r + 2]);
            verts.putFloat(base, m[0] * x + m[1] * y + m[2] * z + m[3]);
            verts.putFloat(base + 4, m[4] * x + m[5] * y + m[6] * z + m[7]);
            verts.putFloat(base + 8, m[8] * x + m[9] * y + m[10] * z + m[11]);
            float u = 0, v = 0;
            if (texGen) {
                float ex = m[0] * x + m[1] * y + m[2] * z + m[3], ey = m[4] * x + m[5] * y + m[6] * z + m[7];
                float ez = m[8] * x + m[9] * y + m[10] * z + m[11], ew = m[12] * x + m[13] * y + m[14] * z + m[15];
                float tu = hasRawUv(raw, r, hasTexOrig) ? Float.intBitsToFloat(raw[r + 3]) : 0f, tv = hasRawUv(raw, r, hasTexOrig) ? Float.intBitsToFloat(raw[r + 4]) : 0f;
                for (int c = 0; c < 4; c++) {
                    float g;
                    if (RS.texGenOn[c]) {
                        float[] pl = RS.texGenMode[c] == 1 ? RS.texGenEye[c] : RS.texGenObj[c];
                        g = RS.texGenMode[c] == 1 ? pl[0] * ex + pl[1] * ey + pl[2] * ez + pl[3] * ew : pl[0] * x + pl[1] * y + pl[2] * z + pl[3];
                    } else g = c == 0 ? tu : c == 1 ? tv : c == 2 ? 0f : 1f;
                    tg[c] = g;
                }
                float s4 = tm[0] * tg[0] + tm[1] * tg[1] + tm[2] * tg[2] + tm[3] * tg[3];
                float t4 = tm[4] * tg[0] + tm[5] * tg[1] + tm[6] * tg[2] + tm[7] * tg[3];
                float q4 = tm[12] * tg[0] + tm[13] * tg[1] + tm[14] * tg[2] + tm[15] * tg[3];
                if (Math.abs(q4) < 1e-6f) q4 = 1e-6f;
                u = s4 / q4; v = t4 / q4;
            } else if (hasTexture) {
                u = Float.intBitsToFloat(raw[r + 3]);
                v = Float.intBitsToFloat(raw[r + 4]);
                if (tm != null) {
                    float tu = tm[0] * u + tm[1] * v + tm[3], tv = tm[4] * u + tm[5] * v + tm[7];
                    u = tu; v = tv;
                }
            }
            verts.putFloat(base + 12, u);
            verts.putFloat(base + 16, v);
            int col = hasColor ? raw[r + 5] : color;
            if (lm != null) col = lightmapped(col, lm, hasBrightness ? raw[r + 7] : defBright);
            verts.putInt(base + 20, col);
            int nrm;
            if (hasNormals) {
                int pn = raw[r + 6];
                nrm = packNormal((byte) pn / 127f, (byte) (pn >> 8) / 127f, (byte) (pn >> 16) / 127f);
            } else nrm = defNormal;
            verts.putInt(base + 24, nrm);
            base += VERTEX;
        }
        nVerts += outCount;
        boolean wide = topo == 2 && RS.lineWidth > 1.01f;
        if (wide) { outCount = wideLines(start, outCount); topo = 0; lastCmd = -1; }

        int tex = RS.texture2D && hasTexture ? RS.bound() : 0;
        int flags = (RS.blend ? IF_BLEND : 0) | (RS.alphaTest ? IF_ALPHA : 0) | (RS.depthTest ? IF_DEPTH : 0) | (RS.depthMask ? IF_DEPTHWRITE : 0)
            | (RS.lighting ? IF_LIGHT : 0) | (tex != 0 ? IF_TEX : 0) | (inWorld ? IF_REVZ : 0) | (RS.fog ? IF_FOG : 0) | IF_NHALF
            | (RS.polyOffset && (RS.polyFactor != 0 || RS.polyUnits != 0) ? IF_POLYOFF : 0);
        boolean partial = RS.partialViewport();
        int sx = 0, sy = 0, sw = 0, sh = 0;
        if (RS.scissor || partial) {
            sx = partial ? RS.vpX : 0; sy = partial ? RS.vpY : 0; sw = partial ? RS.vpW : RS.targetW(); sh = partial ? RS.vpH : RS.targetH();
            if (RS.scissor) {
                int x1 = Math.max(sx, RS.scX), y1 = Math.max(sy, RS.scY);
                int x2 = Math.min(sx + sw, RS.scX + RS.scW), y2 = Math.min(sy + sh, RS.scY + RS.scH);
                sx = x1; sy = y1; sw = Math.max(0, x2 - x1); sh = Math.max(0, y2 - y1);
            }
            flags |= IF_SCISSOR;
        }
        if (inWorld) worldDraws++;

        if (lastCmd >= 0 && topo == lastTopo && tex == lastTex && flags == lastFlags && RS.stateVersion == lastState
            && RS.projVersion == lastProj && (!RS.lighting || RS.lightVersion == lastLight)) {
            int cb = lastCmd * CMD;
            if (cmds.getInt(cb + 8) + cmds.getInt(cb + 12) == start) {
                cmds.putInt(cb + 12, cmds.getInt(cb + 12) + outCount);
                merged++;
                return;
            }
        }
        int cb = nCmds * CMD;
        cmds.putInt(cb, 1).putInt(cb + 4, topo).putInt(cb + 8, start).putInt(cb + 12, outCount).putInt(cb + 16, tex).putInt(cb + 20, flags);
        cmds.putInt(cb + 24, RS.blendSrc).putInt(cb + 28, RS.blendDst).putInt(cb + 32, RS.blendSrcA).putInt(cb + 36, RS.blendDstA);
        cmds.putFloat(cb + 40, RS.alphaRef).putInt(cb + 44, RS.depthFunc);
        cmds.putInt(cb + 48, !RS.cullFace || wide ? 0 : RS.cullMode == RS.GL_FRONT ? 2 : 1).putInt(cb + 52, RS.colorMask);
        float[] p = RS.proj();
        if (partial) p = viewportProj(p);
        for (int k = 0; k < 16; k++) cmds.putFloat(cb + 56 + k * 4, p[k]);
        cmds.putFloat(cb + 120, RS.light0 ? RS.light0Dir[0] : 0).putFloat(cb + 124, RS.light0 ? RS.light0Dir[1] : 0).putFloat(cb + 128, RS.light0 ? RS.light0Dir[2] : 0);
        cmds.putFloat(cb + 132, RS.light1 ? RS.light1Dir[0] : 0).putFloat(cb + 136, RS.light1 ? RS.light1Dir[1] : 0).putFloat(cb + 140, RS.light1 ? RS.light1Dir[2] : 0);

        cmds.putInt(cb + 144, 0).putInt(cb + 148, 0);
        cmds.putInt(cb + 152, (sx & 0xFFFF) | sy << 16).putInt(cb + 156, (sw & 0xFFFF) | sh << 16);
        cmds.putFloat(cb + 160, RS.fogColor[0]).putFloat(cb + 164, RS.fogColor[1]).putFloat(cb + 168, RS.fogColor[2]);
        cmds.putFloat(cb + 172, RS.fogStart).putFloat(cb + 176, RS.fogEnd).putFloat(cb + 180, RS.fogDensity).putInt(cb + 184, RS.fogMode);
        cmds.putInt(cb + 188, (flags & IF_POLYOFF) != 0 ? (fixed16(RS.polyFactor) & 0xFFFF) | fixed16(RS.polyUnits) << 16 : 0);
        lastCmd = nCmds++;
        lastTopo = topo; lastTex = tex; lastFlags = flags; lastState = RS.stateVersion; lastProj = RS.projVersion; lastLight = RS.lightVersion;
        if (wide) lastCmd = -1;
    }

    static int sourceIndex(int drawMode, int k, int n) {
        switch (drawMode) {
            case 6: case 9: { int t = k / 3, c = k % 3; return c == 0 ? 0 : t + c; }
            case 5: { int t = k / 3, c = k % 3; return (t & 1) == 0 ? t + c : t + (c == 0 ? 1 : c == 1 ? 0 : 2); }
            case 8: { int q = k / 4, c = k % 4; return q * 2 + (c == 0 ? 0 : c == 1 ? 1 : c == 2 ? 3 : 2); }
            case 3: return k / 2 + k % 2;
            case 2: return (k / 2 + k % 2) % n;
            default: return k;
        }
    }

    static int packNormalUnit(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 0) { x /= len; y /= len; z /= len; }
        return (Math.round(x * 127) & 255) | (Math.round(y * 127) & 255) << 8 | (Math.round(z * 127) & 255) << 16;
    }

    private static final float[] mvp = new float[16], inv3 = new float[9];

    static void submitMesh(int mesh, int count, int topo, boolean hasTexture, boolean hasColor) {
        ensure(0);
        float[] m = RS.mv();
        float[] p = RS.proj();
        boolean partial = RS.partialViewport();
        if (partial) p = viewportProj(p);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                mvp[i * 4 + j] = p[i * 4] * m[j] + p[i * 4 + 1] * m[4 + j] + p[i * 4 + 2] * m[8 + j] + p[i * 4 + 3] * m[12 + j];
        int tex = RS.texture2D && hasTexture ? RS.bound() : 0;
        int flags = (RS.blend ? IF_BLEND : 0) | (RS.alphaTest ? IF_ALPHA : 0) | (RS.depthTest ? IF_DEPTH : 0) | (RS.depthMask ? IF_DEPTHWRITE : 0)
            | (RS.lighting ? IF_LIGHT : 0) | (tex != 0 ? IF_TEX : 0) | (inWorld ? IF_REVZ : 0) | (RS.fog ? IF_FOG : 0)
            | (RS.lightmapOn ? IF_LIGHTMAP : 0) | IF_GLMESH
            | (RS.polyOffset && (RS.polyFactor != 0 || RS.polyUnits != 0) ? IF_POLYOFF : 0);
        int sx = 0, sy = 0, sw = 0, sh = 0;
        if (RS.scissor || partial) {
            sx = partial ? RS.vpX : 0; sy = partial ? RS.vpY : 0; sw = partial ? RS.vpW : RS.targetW(); sh = partial ? RS.vpH : RS.targetH();
            if (RS.scissor) {
                int x1 = Math.max(sx, RS.scX), y1 = Math.max(sy, RS.scY);
                int x2 = Math.min(sx + sw, RS.scX + RS.scW), y2 = Math.min(sy + sh, RS.scY + RS.scH);
                sx = x1; sy = y1; sw = Math.max(0, x2 - x1); sh = Math.max(0, y2 - y1);
            }
            flags |= IF_SCISSOR;
        }
        if (inWorld) worldDraws++;
        float k = invert3(m);
        float lk = RS.normalize ? k : 1f;
        int cb = nCmds * CMD;
        cmds.putInt(cb, 5).putInt(cb + 4, topo).putInt(cb + 8, mesh).putInt(cb + 12, count).putInt(cb + 16, tex).putInt(cb + 20, flags);
        cmds.putInt(cb + 24, RS.blendSrc).putInt(cb + 28, RS.blendDst).putInt(cb + 32, RS.blendSrcA).putInt(cb + 36, RS.blendDstA);
        cmds.putFloat(cb + 40, RS.alphaRef).putInt(cb + 44, RS.depthFunc);
        cmds.putInt(cb + 48, !RS.cullFace ? 0 : RS.cullMode == RS.GL_FRONT ? 2 : 1).putInt(cb + 52, RS.colorMask);
        for (int i = 0; i < 16; i++) cmds.putFloat(cb + 56 + i * 4, mvp[i]);
        putLight(cb + 120, RS.light0, RS.light0Dir, lk);
        putLight(cb + 132, RS.light1, RS.light1Dir, lk);
        cmds.putInt(cb + 144, ((int) RS.lmT) << 16 | ((int) RS.lmS));
        int tint = 0;
        if (!hasColor) {
            tint = RS.clamp255(RS.ca) << 24 | RS.clamp255(RS.cr) << 16 | RS.clamp255(RS.cg) << 8 | RS.clamp255(RS.cb);
            if (tint == 0) tint = 1;
        }
        cmds.putInt(cb + 148, tint);
        cmds.putInt(cb + 152, (sx & 0xFFFF) | sy << 16).putInt(cb + 156, (sw & 0xFFFF) | sh << 16);
        cmds.putFloat(cb + 160, RS.fogColor[0]).putFloat(cb + 164, RS.fogColor[1]).putFloat(cb + 168, RS.fogColor[2]);
        cmds.putFloat(cb + 172, RS.fogStart).putFloat(cb + 176, RS.fogEnd).putFloat(cb + 180, RS.fogDensity).putInt(cb + 184, RS.fogMode);
        cmds.putInt(cb + 188, (flags & IF_POLYOFF) != 0 ? (fixed16(RS.polyFactor) & 0xFFFF) | fixed16(RS.polyUnits) << 16 : 0);
        nCmds++;
        lastCmd = -1;
    }

    private static void putLight(int at, boolean on, float[] dir, float k) {
        if (!on) { cmds.putFloat(at, 0).putFloat(at + 4, 0).putFloat(at + 8, 0); return; }
        float x = dir[0], y = dir[1], z = dir[2];
        cmds.putFloat(at, (inv3[0] * x + inv3[1] * y + inv3[2] * z) * k);
        cmds.putFloat(at + 4, (inv3[3] * x + inv3[4] * y + inv3[5] * z) * k);
        cmds.putFloat(at + 8, (inv3[6] * x + inv3[7] * y + inv3[8] * z) * k);
    }

    private static float invert3(float[] m) {
        float a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) { inv3[0] = 1; inv3[1] = 0; inv3[2] = 0; inv3[3] = 0; inv3[4] = 1; inv3[5] = 0; inv3[6] = 0; inv3[7] = 0; inv3[8] = 1; return 1f; }
        float r = 1f / det;
        inv3[0] = A * r; inv3[1] = -(b * i - c * h) * r; inv3[2] = (b * f - c * e) * r;
        inv3[3] = B * r; inv3[4] = (a * i - c * g) * r; inv3[5] = -(a * f - c * d) * r;
        inv3[6] = C * r; inv3[7] = -(a * h - b * g) * r; inv3[8] = (a * e - b * d) * r;
        return (float) Math.cbrt(Math.abs(det));
    }

    private static int fixed16(float v) {
        return Math.max(-32768, Math.min(32767, Math.round(v * 16f)));
    }

    private static int wideLines(int start, int count) {
        int segs = count / 2;
        ensure(segs * 4 - count);
        float[] p = RS.proj();
        float hw = RS.lineWidth * 0.5f;
        float vw = RS.vpW > 0 ? RS.vpW : RS.targetW(), vh = RS.vpH > 0 ? RS.vpH : RS.targetH();

        float det = p[0] * p[5] - p[1] * p[4];
        if (Math.abs(det) < 1e-12f) return count;
        int[] tmpv = new int[segs * 4 * 7];
        for (int sgi = 0; sgi < segs; sgi++) {
            int a = (start + sgi * 2) * VERTEX, b = a + VERTEX;
            float ax = verts.getFloat(a), ay = verts.getFloat(a + 4), az = verts.getFloat(a + 8);
            float bx = verts.getFloat(b), by = verts.getFloat(b + 4), bz = verts.getFloat(b + 8);
            float cax = p[0] * ax + p[1] * ay + p[2] * az + p[3], cay = p[4] * ax + p[5] * ay + p[6] * az + p[7], caw = p[12] * ax + p[13] * ay + p[14] * az + p[15];
            float cbx = p[0] * bx + p[1] * by + p[2] * bz + p[3], cby = p[4] * bx + p[5] * by + p[6] * bz + p[7], cbw = p[12] * bx + p[13] * by + p[14] * bz + p[15];
            if (Math.abs(caw) < 1e-6f) caw = 1e-6f;
            if (Math.abs(cbw) < 1e-6f) cbw = 1e-6f;
            float dx = (cbx / cbw - cax / caw) * vw, dy = (cby / cbw - cay / caw) * vh;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float nx = len > 1e-6f ? -dy / len : 0, ny = len > 1e-6f ? dx / len : 1;
            for (int e = 0; e < 4; e++) {
                boolean atB = e == 1 || e == 2;
                float sgn = (e == 0 || e == 1) ? 1f : -1f;
                float w = atB ? cbw : caw;

                float ox = sgn * nx * hw * 2f / vw * w, oy = sgn * ny * hw * 2f / vh * w;
                float ex = (p[5] * ox - p[1] * oy) / det, ey = (p[0] * oy - p[4] * ox) / det;
                int src = atB ? b : a, d = (sgi * 4 + e) * 7;
                tmpv[d] = Float.floatToRawIntBits(verts.getFloat(src) + ex);
                tmpv[d + 1] = Float.floatToRawIntBits(verts.getFloat(src + 4) + ey);
                for (int k = 2; k < 7; k++) tmpv[d + k] = verts.getInt(src + k * 4);
            }
        }
        int base = start * VERTEX;
        for (int i = 0; i < tmpv.length; i++) verts.putInt(base + i * 4, tmpv[i]);
        nVerts = start + segs * 4;
        return segs * 4;
    }

    private static final float[] vpm = new float[16];
    private static final float[] tg = new float[4];
    private static final float[] IDENT = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

    private static boolean hasRawUv(int[] raw, int r, boolean hasTex) {
        return hasTex;
    }

    private static float[] viewportProj(float[] p) {
        float fw = RS.targetW(), fh = RS.targetH();
        float ax = RS.vpW / fw, bx = (2f * RS.vpX + RS.vpW) / fw - 1f;
        float ay = RS.vpH / fh, by = (2f * RS.vpY + RS.vpH) / fh - 1f;
        for (int k = 0; k < 4; k++) {
            vpm[k] = ax * p[k] + bx * p[12 + k];
            vpm[4 + k] = ay * p[4 + k] + by * p[12 + k];
            vpm[8 + k] = p[8 + k];
            vpm[12 + k] = p[12 + k];
        }
        return vpm;
    }

    private static int lightmapped(int col, int[] lm, int bright) {
        int block = (bright & 0xFFFF) >> 4, sky = (bright >>> 16) >> 4;
        if (block > 15) block = 15;
        if (sky > 15) sky = 15;
        int l = lm[sky * 16 + block];
        int r = ((col & 255) * ((l >> 16) & 255)) / 255;
        int g = (((col >> 8) & 255) * ((l >> 8) & 255)) / 255;
        int b = (((col >> 16) & 255) * (l & 255)) / 255;
        return (col & 0xFF000000) | b << 16 | g << 8 | r;
    }

    private static final float[] nm = new float[9];
    private static boolean nmNormalize;

    private static void normalMatrix(float[] m) {
        float a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) { nm[0] = a; nm[1] = b; nm[2] = c; nm[3] = d; nm[4] = e; nm[5] = f; nm[6] = g; nm[7] = h; nm[8] = i; }
        else {
            float r = 1f / det;

            nm[0] = A * r; nm[1] = B * r; nm[2] = C * r;
            nm[3] = -(b * i - c * h) * r; nm[4] = (a * i - c * g) * r; nm[5] = -(a * h - b * g) * r;
            nm[6] = (b * f - c * e) * r; nm[7] = -(a * f - c * d) * r; nm[8] = (a * e - b * d) * r;
        }
        nmNormalize = RS.normalize;
    }

    private static int packNormal(float x, float y, float z) {
        float ex = nm[0] * x + nm[1] * y + nm[2] * z, ey = nm[3] * x + nm[4] * y + nm[5] * z, ez = nm[6] * x + nm[7] * y + nm[8] * z;
        float len = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (nmNormalize ? len > 0 : len > 2) { float k = (nmNormalize ? 1f : 2f) / len; ex *= k; ey *= k; ez *= k; }
        return (Math.round(ex * 63.5f) & 255) | (Math.round(ey * 63.5f) & 255) << 8 | (Math.round(ez * 63.5f) & 255) << 16;
    }

    public static final class Snapshot {
        final ByteBuffer v, c;
        final int nv, nc;

        Snapshot(ByteBuffer v, int nv, ByteBuffer c, int nc) {
            this.v = v; this.nv = nv; this.c = c; this.nc = nc;
        }
    }

    public static Snapshot capture(int cmd0, int vert0) {
        int nv = nVerts - vert0, nc = nCmds - cmd0;
        ByteBuffer v = ByteBuffer.allocateDirect(Math.max(1, nv * VERTEX)).order(ByteOrder.nativeOrder());
        ByteBuffer d = verts.duplicate();
        d.position(vert0 * VERTEX).limit(nVerts * VERTEX);
        v.put(d).flip();
        ByteBuffer c = ByteBuffer.allocateDirect(Math.max(1, nc * CMD)).order(ByteOrder.nativeOrder());
        d = cmds.duplicate();
        d.position(cmd0 * CMD).limit(nCmds * CMD);
        c.put(d).flip();
        for (int k = 0; k < nc; k++) {
            int cb = k * CMD;
            if (c.getInt(cb) == 1) c.putInt(cb + 8, c.getInt(cb + 8) - vert0);
        }
        return new Snapshot(v, nv, c, nc);
    }

    public static void replay(Snapshot s) {
        ensure(s.nv);
        while ((nCmds + s.nc) * CMD > cmds.capacity()) {
            ByteBuffer b = ByteBuffer.allocateDirect(cmds.capacity() * 2).order(ByteOrder.nativeOrder());
            cmds.position(0).limit(nCmds * CMD);
            b.put(cmds);
            cmds = b;
        }
        int v0 = nVerts;
        ByteBuffer d = verts.duplicate();
        d.position(nVerts * VERTEX);
        d.put(s.v.duplicate());
        nVerts += s.nv;
        d = cmds.duplicate();
        int c0 = nCmds;
        d.position(nCmds * CMD);
        d.put(s.c.duplicate());
        nCmds += s.nc;
        for (int k = c0; k < nCmds; k++) {
            int cb = k * CMD;
            if (cmds.getInt(cb) == 1) cmds.putInt(cb + 8, cmds.getInt(cb + 8) + v0);
        }
        lastCmd = -1;
    }

    public static int vertexMark() { return nVerts; }
    public static int commandMark() { return nCmds; }
}
