package com.nikita23830.minecraftd3d.engine;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import com.nikita23830.minecraftd3d.PD3D;

public final class RS {
    private RS() {}

    private static boolean on() {
        return PD3D.engine;
    }

    static final int GL_MODELVIEW = 0x1700, GL_PROJECTION = 0x1701, GL_TEXTURE = 0x1702;
    static final int GL_BLEND = 0x0BE2, GL_ALPHA_TEST = 0x0BC0, GL_DEPTH_TEST = 0x0B71, GL_TEXTURE_2D = 0x0DE1, GL_CULL_FACE = 0x0B44,
        GL_LIGHTING = 0x0B50, GL_LIGHT0 = 0x4000, GL_LIGHT1 = 0x4001, GL_SCISSOR_TEST = 0x0C11, GL_FOG = 0x0B60;
    static final int GL_FRONT = 0x0404, GL_BACK = 0x0405;
    static final int GL_ONE = 1, GL_ZERO = 0, GL_LESS = 0x201, GL_LEQUAL = 0x203;
    static final int GL_COLOR_BUFFER_BIT = 0x4000, GL_DEPTH_BUFFER_BIT = 0x100;

    static final int MODELVIEW = 0, PROJECTION = 1, TEXTURE = 2, TEXTURE_OTHER = 3;
    static final float[][][] stacks = { new float[128][16], new float[32][16], new float[32][16], new float[32][16] };
    static final int[] depth = new int[4];
    static int mode = MODELVIEW;

    static int projVersion, lightVersion;
    static boolean textureMatrixIdentity = true;

    static {
        for (float[][] s : stacks) identity(s[0]);
    }

    static float[] mv() { return stacks[MODELVIEW][depth[MODELVIEW]]; }
    static float[] proj() { return stacks[PROJECTION][depth[PROJECTION]]; }
    static float[] tex() { return stacks[TEXTURE][depth[TEXTURE]]; }
    private static int m() { return mode == TEXTURE && activeUnit != 0 ? TEXTURE_OTHER : mode; }
    private static float[] cur() { int k = m(); return stacks[k][depth[k]]; }

    public static float[] modelview() { return mv().clone(); }
    public static float[] projection() { return proj().clone(); }

    private static void changed() {
        if (mode == PROJECTION) projVersion++;
        else if (m() == TEXTURE) textureMatrixIdentity = isIdentity(tex());
    }

    static void identity(float[] m) {
        for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1 : 0;
    }

    private static boolean isIdentity(float[] m) {
        for (int i = 0; i < 16; i++) if (m[i] != ((i % 5 == 0) ? 1 : 0)) return false;
        return true;
    }

    private static final float[] tmp = new float[16], res = new float[16];

    private static void postMul(float[] b) {
        float[] a = cur();
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                res[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j];
        System.arraycopy(res, 0, a, 0, 16);
        changed();
    }

    public static void glMatrixMode(int m) {
        if (!on()) { GL11.glMatrixMode(m); return; }
        mode = m == GL_PROJECTION ? PROJECTION : m == GL_TEXTURE ? TEXTURE : MODELVIEW;
    }

    static int matrixModeGl() {
        return mode == PROJECTION ? GL_PROJECTION : mode == TEXTURE ? GL_TEXTURE : GL_MODELVIEW;
    }

    public static void glPushMatrix() {
        if (!on()) { GL11.glPushMatrix(); return; }
        int k = m();
        float[][] s = stacks[k];
        if (depth[k] + 1 >= s.length) { GLEmu.warnOnce("Matrixstapel-Überlauf " + k); return; }
        System.arraycopy(s[depth[k]], 0, s[depth[k] + 1], 0, 16);
        depth[k]++;
    }

    public static void glPopMatrix() {
        if (!on()) { GL11.glPopMatrix(); return; }
        int k = m();
        if (depth[k] > 0) depth[k]--;
        else GLEmu.warnOnce("Matrixstapel-Unterlauf " + k);
        changed();
    }

    public static void glLoadIdentity() {
        if (!on()) { GL11.glLoadIdentity(); return; }
        identity(cur());
        changed();
    }

    public static void glTranslatef(float x, float y, float z) {
        if (!on()) { GL11.glTranslatef(x, y, z); return; }
        identity(tmp); tmp[3] = x; tmp[7] = y; tmp[11] = z;
        postMul(tmp);
    }

    public static void glScalef(float x, float y, float z) {
        if (!on()) { GL11.glScalef(x, y, z); return; }
        identity(tmp); tmp[0] = x; tmp[5] = y; tmp[10] = z;
        postMul(tmp);
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        if (!on()) { GL11.glRotatef(angle, x, y, z); return; }
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0) return;
        x /= len; y /= len; z /= len;
        double a = Math.toRadians(angle);
        float c = (float) Math.cos(a), s = (float) Math.sin(a), t = 1 - c;
        identity(tmp);
        tmp[0] = t * x * x + c;     tmp[1] = t * x * y - s * z; tmp[2] = t * x * z + s * y;
        tmp[4] = t * x * y + s * z; tmp[5] = t * y * y + c;     tmp[6] = t * y * z - s * x;
        tmp[8] = t * x * z - s * y; tmp[9] = t * y * z + s * x; tmp[10] = t * z * z + c;
        postMul(tmp);
    }

    public static void glOrtho(double l, double r, double b, double t, double n, double f) {
        if (!on()) { GL11.glOrtho(l, r, b, t, n, f); return; }
        identity(tmp);
        tmp[0] = (float) (2 / (r - l)); tmp[3] = (float) (-(r + l) / (r - l));
        tmp[5] = (float) (2 / (t - b)); tmp[7] = (float) (-(t + b) / (t - b));
        tmp[10] = (float) (-2 / (f - n)); tmp[11] = (float) (-(f + n) / (f - n));
        postMul(tmp);
    }

    public static void glFrustum(double l, double r, double b, double t, double n, double f) {
        if (!on()) { GL11.glFrustum(l, r, b, t, n, f); return; }
        identity(tmp);
        tmp[0] = (float) (2 * n / (r - l)); tmp[2] = (float) ((r + l) / (r - l));
        tmp[5] = (float) (2 * n / (t - b)); tmp[6] = (float) ((t + b) / (t - b));
        tmp[10] = (float) (-(f + n) / (f - n)); tmp[11] = (float) (-2 * f * n / (f - n));
        tmp[14] = -1; tmp[15] = 0;
        postMul(tmp);
    }

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        if (!on()) { Project.gluPerspective(fovy, aspect, zNear, zFar); return; }
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovy) / 2));
        identity(tmp);
        tmp[0] = f / aspect; tmp[5] = f;
        tmp[10] = (zFar + zNear) / (zNear - zFar); tmp[11] = 2 * zFar * zNear / (zNear - zFar);
        tmp[14] = -1; tmp[15] = 0;
        postMul(tmp);
    }

    public static void gluLookAt(float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        if (!on()) { org.lwjgl.util.glu.GLU.gluLookAt(ex, ey, ez, cx, cy, cz, ux, uy, uz); return; }
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl;
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        identity(tmp);
        tmp[0] = sx; tmp[1] = sy; tmp[2] = sz;
        tmp[4] = vx; tmp[5] = vy; tmp[6] = vz;
        tmp[8] = -fx; tmp[9] = -fy; tmp[10] = -fz;
        postMul(tmp);
        glTranslatef(-ex, -ey, -ez);
    }

    public static void glMultMatrix(FloatBuffer m) {
        if (!on()) { GL11.glMultMatrix(m); return; }
        int p = m.position();
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) tmp[i * 4 + j] = m.get(p + j * 4 + i);
        postMul(tmp);
    }

    public static void glMultMatrix(DoubleBuffer m) {
        if (!on()) { GL11.glMultMatrix(m); return; }
        int p = m.position();
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) tmp[i * 4 + j] = (float) m.get(p + j * 4 + i);
        postMul(tmp);
    }

    public static void glLoadMatrix(FloatBuffer m) {
        if (!on()) { GL11.glLoadMatrix(m); return; }
        int p = m.position();
        float[] a = cur();
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) a[i * 4 + j] = m.get(p + j * 4 + i);
        changed();
    }

    static void putColumnMajor(float[] a, FloatBuffer out) {
        int p = out.position();
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out.put(p + j * 4 + i, a[i * 4 + j]);
    }

    static float cr = 1, cg = 1, cb = 1, ca = 1;
    static float nx = 0, ny = 0, nz = 1;
    static boolean blend, alphaTest, depthTest, texture2D = true, cullFace, lighting, light0, light1, scissor;

    static boolean lightmapOn;
    static float lmS = 240, lmT = 240;

    public static int[] lightmapColors;
    static int blendSrc = GL_ONE, blendDst = GL_ZERO, blendSrcA = GL_ONE, blendDstA = GL_ZERO;
    static float alphaRef;
    static int depthFunc = GL_LESS, cullMode = GL_BACK;
    static boolean depthMask = true;
    static int colorMask = 15;
    static int activeUnit;
    static final int[] boundTex = new int[8];
    static final boolean[] unitTex2D = new boolean[8];
    static final float[] light0Dir = new float[3], light1Dir = new float[3];
    static float clearR, clearG, clearB, clearA;
    static int vpX, vpY, vpW = 1, vpH = 1;

    public static int fullW = 1, fullH = 1;

    static boolean fog;

    static boolean normalize;

    public static final boolean TRACE_TEX = Boolean.getBoolean("minecraftd3d.traceTex");
    public static Throwable lastTexOff;

    static final boolean[] texGenOn = new boolean[4];
    static final int[] texGenMode = new int[4];
    static final float[][] texGenObj = { { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 0, 0 }, { 0, 0, 0, 0 } };
    static final float[][] texGenEye = { { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 0, 0 }, { 0, 0, 0, 0 } };
    static boolean texGenAny;
    static int fogMode;
    static float fogStart, fogEnd = 1, fogDensity = 1;
    static final float[] fogColor = new float[4];
    static int scX, scY, scW, scH;

    static float lineWidth = 1, polyFactor, polyUnits;
    static boolean polyOffset;

    static int stateVersion;

    public static void glEnable(int cap) { if (!on()) GL11.glEnable(cap); else set(cap, true); }

    public static void glDisable(int cap) { if (!on()) GL11.glDisable(cap); else set(cap, false); }

    private static void set(int cap, boolean v) {
        boolean old;
        switch (cap) {
            case GL_BLEND: old = blend; blend = v; break;
            case GL_ALPHA_TEST: old = alphaTest; alphaTest = v; break;
            case GL_DEPTH_TEST: old = depthTest; depthTest = v; break;
            case GL_TEXTURE_2D:
                unitTex2D[activeUnit] = v;
                if (activeUnit == 1) { old = lightmapOn; lightmapOn = v; break; }
                if (activeUnit != 0) return;
                old = texture2D; texture2D = v;
                if (TRACE_TEX && !v) lastTexOff = new Throwable("glDisable(GL_TEXTURE_2D)");
                break;
            case GL_CULL_FACE: old = cullFace; cullFace = v; break;
            case GL_LIGHTING: old = lighting; lighting = v; break;
            case GL_LIGHT0: old = light0; light0 = v; break;
            case GL_LIGHT1: old = light1; light1 = v; break;
            case GL_SCISSOR_TEST: old = scissor; scissor = v; break;
            case GL_FOG: old = fog; fog = v; break;
            case 0x8037: old = polyOffset; polyOffset = v; break;
            case 0x0BA1: case 0x803A: old = normalize; normalize = v; break;
            case 0x0C60: case 0x0C61: case 0x0C62: case 0x0C63:
                if (activeUnit != 0) return;
                old = texGenOn[cap - 0x0C60]; texGenOn[cap - 0x0C60] = v;
                texGenAny = texGenOn[0] || texGenOn[1] || texGenOn[2] || texGenOn[3];
                break;
            default: return;
        }
        if (old != v) stateVersion++;
    }

    public static boolean glIsEnabled(int cap) {
        if (!on()) return GL11.glIsEnabled(cap);
        switch (cap) {
            case GL_BLEND: return blend;
            case GL_ALPHA_TEST: return alphaTest;
            case GL_DEPTH_TEST: return depthTest;
            case GL_TEXTURE_2D: return unitTex2D[activeUnit] || (activeUnit == 0 && texture2D);
            case GL_CULL_FACE: return cullFace;
            case GL_LIGHTING: return lighting;
            case GL_LIGHT0: return light0;
            case GL_LIGHT1: return light1;
            case GL_SCISSOR_TEST: return scissor;
            case GL_FOG: return fog;
            case 0x8037: return polyOffset;
            default: return false;
        }
    }

    public static void glColor4f(float r, float g, float b, float a) {
        if (!on()) { GL11.glColor4f(r, g, b, a); return; }
        cr = r; cg = g; cb = b; ca = a;
    }

    static int colorPacked() {
        return clamp255(ca) << 24 | clamp255(cb) << 16 | clamp255(cg) << 8 | clamp255(cr);
    }

    static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    public static void glNormal3f(float x, float y, float z) {
        if (!on()) { GL11.glNormal3f(x, y, z); return; }
        nx = x; ny = y; nz = z;
    }

    public static void glBlendFuncSeparate(int src, int dst, int srcA, int dstA) {
        if (blendSrc == src && blendDst == dst && blendSrcA == srcA && blendDstA == dstA) return;
        blendSrc = src; blendDst = dst; blendSrcA = srcA; blendDstA = dstA;
        stateVersion++;
    }

    static int alphaFunc = 0x0207;
    static int shadeModel = 0x1D01;

    static int glError;

    static void setError(int e) {
        if (glError == 0) glError = e;
    }

    public static void glAlphaFunc(int func, float ref) {
        if (!on()) { GL11.glAlphaFunc(func, ref); return; }
        alphaFunc = func;
        if (alphaRef == ref) return;
        alphaRef = ref;
        stateVersion++;
    }

    public static void glDepthMask(boolean m) {
        if (!on()) { GL11.glDepthMask(m); return; }
        if (depthMask == m) return;
        depthMask = m;
        stateVersion++;
    }

    public static void glDepthFunc(int f) {
        if (!on()) { GL11.glDepthFunc(f); return; }
        if (depthFunc == f) return;
        depthFunc = f;
        stateVersion++;
    }

    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        if (!on()) { GL11.glColorMask(r, g, b, a); return; }
        int cm = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        if (colorMask == cm) return;
        colorMask = cm;
        stateVersion++;
    }

    public static void glCullFace(int m) {
        if (!on()) { GL11.glCullFace(m); return; }
        if (cullMode == m) return;
        cullMode = m;
        stateVersion++;
    }

    public static void glScissor(int x, int y, int w, int h) {
        if (!on()) { GL11.glScissor(x, y, w, h); return; }
        if (scX == x && scY == y && scW == w && scH == h) return;
        scX = x; scY = y; scW = w; scH = h;
        stateVersion++;
    }

    public static void glLight(int light, int pname, FloatBuffer params) {
        if (!on()) { GL11.glLight(light, pname, params); return; }
        if (pname != 0x1203 ) return;
        int p = params.position();
        float x = params.get(p), y = params.get(p + 1), z = params.get(p + 2);
        float[] m = mv();
        float ex = m[0] * x + m[1] * y + m[2] * z, ey = m[4] * x + m[5] * y + m[6] * z, ez = m[8] * x + m[9] * y + m[10] * z;
        float len = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len > 0) { ex /= len; ey /= len; ez /= len; }
        float[] d = light == GL_LIGHT1 ? light1Dir : light0Dir;
        d[0] = ex; d[1] = ey; d[2] = ez;
        lightVersion++;
    }

    public static void glClearColor(float r, float g, float b, float a) {
        if (!on()) { GL11.glClearColor(r, g, b, a); return; }
        clearR = r; clearG = g; clearB = b; clearA = a;
    }

    public static void glClear(int mask) {
        if (!on()) { GL11.glClear(mask); return; }
        boolean sc = false;
        int x = 0, y = 0, w = targetW(), h = targetH();
        if (scissor) {
            int x1 = Math.max(0, scX), y1 = Math.max(0, scY), x2 = Math.min(w, scX + scW), y2 = Math.min(h, scY + scH);
            sc = x1 > 0 || y1 > 0 || x2 < w || y2 < h;
            x = x1; y = y1; w = Math.max(0, x2 - x1); h = Math.max(0, y2 - y1);
        }
        if ((mask & GL_COLOR_BUFFER_BIT) != 0 && colorMask != 0) Immediate.clearColor(clearR, clearG, clearB, clearA, colorMask, sc, x, y, w, h);
        if ((mask & GL_DEPTH_BUFFER_BIT) != 0) Immediate.clearDepth(depthMask, sc, x, y, w, h);
    }

    public static void glViewport(int x, int y, int w, int h) {
        if (!on()) { GL11.glViewport(x, y, w, h); return; }
        if (vpX == x && vpY == y && vpW == w && vpH == h) return;
        vpX = x; vpY = y; vpW = w; vpH = h;
        projVersion++;
        stateVersion++;
    }

    static boolean partialViewport() {
        return vpX != 0 || vpY != 0 || vpW != targetW() || vpH != targetH();
    }

    static int targetW() { return FB.inFbo() ? Math.max(1, FB.curW) : fullW; }

    static int targetH() { return FB.inFbo() ? Math.max(1, FB.curH) : fullH; }

    public static void fogi(int p, int v) {
        if (p == 0x0B65) {
            int m = v == 0x0800 ? 1 : v == 0x0801 ? 2 : 0;
            if (m != fogMode) { fogMode = m; stateVersion++; }
        }
    }

    public static void fogf(int p, float v) {
        switch (p) {
            case 0x0B63: if (fogStart != v) { fogStart = v; stateVersion++; } break;
            case 0x0B64: if (fogEnd != v) { fogEnd = v; stateVersion++; } break;
            case 0x0B62: if (fogDensity != v) { fogDensity = v; stateVersion++; } break;
            case 0x0B65: fogi(p, (int) v); break;
            default: break;
        }
    }

    public static void fogv(int p, FloatBuffer v) {
        if (p == 0x0B66) {
            int i = v.position();
            fogColor[0] = v.get(i); fogColor[1] = v.get(i + 1); fogColor[2] = v.get(i + 2); fogColor[3] = v.get(i + 3);
            stateVersion++;
        } else fogf(p, v.get(v.position()));
    }

    public static void activeTexture(int unit) {
        activeUnit = Math.max(0, Math.min(7, unit - 0x84C0 ));
    }

    public static void multiTexCoord(int target, float s, float t) {
        if (target - 0x84C0 == 1) { lmS = s; lmT = t; }
    }

    static final int CURRENT_BIT = 0x1, POLYGON_BIT = 0x8, LIGHTING_BIT = 0x40, FOG_BIT = 0x80, DEPTH_BUFFER_BIT = 0x100,
        VIEWPORT_BIT = 0x800, TRANSFORM_BIT = 0x1000, ENABLE_BIT = 0x2000, COLOR_BUFFER_BIT = 0x4000, TEXTURE_BIT = 0x40000,
        SCISSOR_BIT = 0x80000;

    private static final class Attr {
        int mask;
        float cr, cg, cb, ca, nx, ny, nz;
        boolean blend, alphaTest, depthTest, texture2D, cullFace, lighting, light0, light1, scissor, lightmapOn, fog, normalize, polyOffset;
        float lineWidth, polyFactor, polyUnits;
        boolean[] texGen = new boolean[4];
        int blendSrc, blendDst, blendSrcA, blendDstA, depthFunc, cullMode, colorMask, matrixMode;
        float alphaRef; boolean depthMask;
        int fogMode; float fogStart, fogEnd, fogDensity; float[] fogColor = new float[4];
        int vpX, vpY, vpW, vpH, scX, scY, scW, scH;
        int activeUnit; int[] bound = new int[2];
    }
    private static Attr[] attrStack = new Attr[32];
    private static int attrDepth;

    public static void glPushAttrib(int mask) {
        if (!on()) { GL11.glPushAttrib(mask); return; }
        if (attrDepth >= attrStack.length) { GLEmu.warnOnce("glPushAttrib-Stapelüberlauf"); return; }
        Attr a = attrStack[attrDepth];
        if (a == null) a = attrStack[attrDepth] = new Attr();
        attrDepth++;
        a.mask = mask;
        a.cr = cr; a.cg = cg; a.cb = cb; a.ca = ca; a.nx = nx; a.ny = ny; a.nz = nz;
        a.blend = blend; a.alphaTest = alphaTest; a.depthTest = depthTest; a.texture2D = texture2D; a.cullFace = cullFace;
        a.lighting = lighting; a.light0 = light0; a.light1 = light1; a.scissor = scissor; a.lightmapOn = lightmapOn; a.fog = fog;
        a.normalize = normalize; System.arraycopy(texGenOn, 0, a.texGen, 0, 4);
        a.polyOffset = polyOffset; a.lineWidth = lineWidth; a.polyFactor = polyFactor; a.polyUnits = polyUnits;
        a.blendSrc = blendSrc; a.blendDst = blendDst; a.blendSrcA = blendSrcA; a.blendDstA = blendDstA; a.depthFunc = depthFunc;
        a.cullMode = cullMode; a.colorMask = colorMask; a.alphaRef = alphaRef; a.depthMask = depthMask; a.matrixMode = mode;
        a.fogMode = fogMode; a.fogStart = fogStart; a.fogEnd = fogEnd; a.fogDensity = fogDensity; System.arraycopy(fogColor, 0, a.fogColor, 0, 4);
        a.vpX = vpX; a.vpY = vpY; a.vpW = vpW; a.vpH = vpH; a.scX = scX; a.scY = scY; a.scW = scW; a.scH = scH;
        a.activeUnit = activeUnit; a.bound[0] = boundTex[0]; a.bound[1] = boundTex[1];
    }

    public static void glPopAttrib() {
        if (!on()) { GL11.glPopAttrib(); return; }
        if (attrDepth == 0) { GLEmu.warnOnce("glPushAttrib-Stapelunterlauf"); return; }
        Attr a = attrStack[--attrDepth];
        int m = a.mask;
        if ((m & CURRENT_BIT) != 0) { cr = a.cr; cg = a.cg; cb = a.cb; ca = a.ca; nx = a.nx; ny = a.ny; nz = a.nz; }
        if ((m & ENABLE_BIT) != 0) {
            blend = a.blend; alphaTest = a.alphaTest; depthTest = a.depthTest; texture2D = a.texture2D; cullFace = a.cullFace;
            lighting = a.lighting; light0 = a.light0; light1 = a.light1; scissor = a.scissor; lightmapOn = a.lightmapOn; fog = a.fog;
            normalize = a.normalize; System.arraycopy(a.texGen, 0, texGenOn, 0, 4);
        }
        if ((m & LIGHTING_BIT) != 0) { lighting = a.lighting; light0 = a.light0; light1 = a.light1; }
        if ((m & POLYGON_BIT) != 0) { cullFace = a.cullFace; cullMode = a.cullMode; polyOffset = a.polyOffset; polyFactor = a.polyFactor; polyUnits = a.polyUnits; }
        if ((m & 0x4) != 0) lineWidth = a.lineWidth;
        if ((m & ENABLE_BIT) != 0) polyOffset = a.polyOffset;
        if ((m & DEPTH_BUFFER_BIT) != 0) { depthTest = a.depthTest; depthFunc = a.depthFunc; depthMask = a.depthMask; }
        if ((m & COLOR_BUFFER_BIT) != 0) {
            alphaTest = a.alphaTest; alphaRef = a.alphaRef; blend = a.blend; blendSrc = a.blendSrc; blendDst = a.blendDst;
            blendSrcA = a.blendSrcA; blendDstA = a.blendDstA; colorMask = a.colorMask;
        }
        if ((m & FOG_BIT) != 0) { fog = a.fog; fogMode = a.fogMode; fogStart = a.fogStart; fogEnd = a.fogEnd; fogDensity = a.fogDensity; System.arraycopy(a.fogColor, 0, fogColor, 0, 4); }
        if ((m & TRANSFORM_BIT) != 0) { mode = a.matrixMode; normalize = a.normalize; }
        if ((m & VIEWPORT_BIT) != 0) { vpX = a.vpX; vpY = a.vpY; vpW = a.vpW; vpH = a.vpH; projVersion++; }
        if ((m & SCISSOR_BIT) != 0) { scissor = a.scissor; scX = a.scX; scY = a.scY; scW = a.scW; scH = a.scH; }
        if ((m & TEXTURE_BIT) != 0) {
            activeUnit = a.activeUnit; boundTex[0] = a.bound[0]; boundTex[1] = a.bound[1];
            texture2D = a.texture2D; lightmapOn = a.lightmapOn; System.arraycopy(a.texGen, 0, texGenOn, 0, 4);
        }
        texGenAny = texGenOn[0] || texGenOn[1] || texGenOn[2] || texGenOn[3];
        unitTex2D[0] = texture2D; unitTex2D[1] = lightmapOn;
        stateVersion++;
    }

    public static void getFloat(int pname, FloatBuffer out) {
        switch (pname) {
            case 0x0BA6: putColumnMajor(mv(), out); return;
            case 0x0BA7: putColumnMajor(proj(), out); return;
            case 0x0BA8: putColumnMajor(tex(), out); return;
            case 0x0B00: { int p = out.position(); out.put(p, cr).put(p + 1, cg).put(p + 2, cb).put(p + 3, ca); return; }
            case 0x0BA2: { int p = out.position(); out.put(p, vpX).put(p + 1, vpY).put(p + 2, vpW).put(p + 3, vpH); return; }
            case 0x0B02: { int p = out.position(); out.put(p, nx).put(p + 1, ny).put(p + 2, nz); return; }
            case 0x0BC2: out.put(out.position(), alphaRef); return;
            default: out.put(out.position(), getInteger(pname));
        }
    }

    public static int getInteger(int p) {
        switch (p) {
            case 0x0D33: return 16384;
            case 0x8069: return boundTex[activeUnit];
            case 0x0BA0: return matrixModeGl();
            case 0x84E0: return 0x84C0 + activeUnit;
            case 0x0BC1: return alphaFunc;
            case 0x0B54: return shadeModel;
            case 0x0B33: return DL.recordingId();
            case 0x0B30: return DL.recordingMode();
            case 0x0BA3: return depth[MODELVIEW] + 1;
            case 0x0BA4: return depth[PROJECTION] + 1;
            case 0x0BA5: return depth[TEXTURE] + 1;
            case 0x0BB0: return attrDepth;
            case 0x0B74: return depthFunc;
            case 0x0BE1: case 0x80C9: return blendSrc;
            case 0x0BE0: case 0x80C8: return blendDst;
            case 0x0D32: return 8;
            case 0x0D36: return 64;
            case 0x0D57: return 8;
            case 0x8872: case 0x84E2: return 8;
            default: { int v = FB.getInteger(p); return v == -1 ? 0 : v; }
        }
    }

    public static void getInteger(int pname, IntBuffer out) {
        int p = out.position();
        if (pname == 0x0BA2) { out.put(p, vpX).put(p + 1, vpY).put(p + 2, vpW).put(p + 3, vpH); return; }
        if (pname == 0x0C10) { out.put(p, scX).put(p + 1, scY).put(p + 2, scW).put(p + 3, scH); return; }
        out.put(p, getInteger(pname));
    }

    private static int nextTex = 1;

    public static void firstTextureId(int id) {
        nextTex = Math.max(nextTex, id);
    }

    private static final class TexInfo { int maxLevel, params, w, h; }
    private static final HashMap<Integer, TexInfo> texInfo = new HashMap<Integer, TexInfo>();

    private static TexInfo info(int id) {
        TexInfo t = texInfo.get(id);
        if (t == null) { t = new TexInfo(); texInfo.put(id, t); }
        return t;
    }

    public static int glGenTextures() {
        if (!on()) return GL11.glGenTextures();
        return nextTex++;
    }

    public static void glDeleteTextures(int id) {
        if (!on()) { GL11.glDeleteTextures(id); return; }
        if (texInfo.remove(id) != null) { PD3D.texDelete(id); FB.onTexDelete(id); }
    }

    public static void glBindTexture(int target, int id) {
        if (!on()) { GL11.glBindTexture(target, id); return; }
        boundTex[activeUnit] = id;
    }

    static int bound() {
        return boundTex[0];
    }

    static int boundTexture() {
        return boundTex[activeUnit];
    }

    static int texWidth(int id) { TexInfo t = texInfo.get(id); return t != null ? t.w : 0; }

    static int texHeight(int id) { TexInfo t = texInfo.get(id); return t != null ? t.h : 0; }

    static void texCreateInternal(int id, int w, int h) {
        TexInfo t = info(id);
        t.w = w; t.h = h;
        PD3D.texCreate(id, w, h, 1);
    }

    public static void glTexParameteri(int target, int pname, int v) {
        if (!on()) { GL11.glTexParameteri(target, pname, v); return; }
        TexInfo t = info(boundTex[activeUnit]);
        int p = t.params;
        switch (pname) {
            case 0x813D: t.maxLevel = v; return;
            case 0x2801:
                p = (v == 0x2601 || v == 0x2703 || v == 0x2701) ? p | 4 : p & ~4;
                p = (v == 0x2703 || v == 0x2702 || v == 0x2701 || v == 0x2700) ? p | 1 : p & ~1;
                break;
            case 0x2802:
                p = (v == 0x2900 || v == 0x812F) ? p | 2 : p & ~2;
                break;
            default: return;
        }
        if (p != t.params) { t.params = p; PD3D.texParams(boundTex[activeUnit], p); }
    }

    public static void glTexParameterf(int target, int pname, float v) {
        if (!on()) { GL11.glTexParameterf(target, pname, v); return; }
        if (pname == 0x813D) info(boundTex[activeUnit]).maxLevel = (int) v;
        else if (pname == 0x2801 || pname == 0x2802) glTexParameteri(target, pname, (int) v);
    }

    public static int glGetTexParameteri(int target, int pname) {
        if (!on()) return GL11.glGetTexParameteri(target, pname);
        int p = info(boundTex[activeUnit]).params;
        if (pname == 0x2801) return (p & 4) != 0 ? ((p & 1) != 0 ? 0x2703 : 0x2601) : ((p & 1) != 0 ? 0x2702 : 0x2600);
        if (pname == 0x2800) return (p & 4) != 0 ? 0x2601 : 0x2600;
        if (pname == 0x813D) return info(boundTex[activeUnit]).maxLevel;
        return 0;
    }

    public static void texImage2D(int target, int level, int w, int h, java.nio.Buffer data) {
        if (target == 0x8064) return;
        int id = boundTex[activeUnit];
        if (level == 0) {
            TexInfo t = info(id);
            t.w = w; t.h = h;
            PD3D.texCreate(id, w, h, t.maxLevel + 1);
            PD3D.texParams(id, t.params);
            FB.onTexImage(id);
        }
        if (data != null) PD3D.texUpload(id, level, 0, 0, w, h, data);
    }

    public static void texGeni(int coord, int pname, int mode) {
        int c = coord - 0x2000;
        if (c < 0 || c > 3 || pname != 0x2500) return;
        if (mode == 0x2401) texGenMode[c] = 0;
        else if (mode == 0x2400) texGenMode[c] = 1;
        else GLEmu.warnOnce("glTexGen-Modus 0x" + Integer.toHexString(mode) + " nicht unterstützt");
    }

    public static void texGenv(int coord, int pname, float p0, float p1, float p2, float p3) {
        int c = coord - 0x2000;
        if (c < 0 || c > 3) return;
        if (pname == 0x2501) { float[] o = texGenObj[c]; o[0] = p0; o[1] = p1; o[2] = p2; o[3] = p3; }
        else if (pname == 0x2502) {
            float[] inv = invert(mv());
            float[] e = texGenEye[c];
            for (int j = 0; j < 4; j++) e[j] = p0 * inv[j] + p1 * inv[4 + j] + p2 * inv[8 + j] + p3 * inv[12 + j];
        } else if (pname == 0x2500) texGeni(coord, pname, (int) p0);
    }

    static float[] invert(float[] m) {
        float[] inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0) return inv;
        det = 1f / det;
        for (int i = 0; i < 16; i++) inv[i] *= det;
        return inv;
    }

    public static void copyTexSubImage2D(int level, int xo, int yo, int x, int y, int w, int h) {
        Immediate.copyTex(boundTex[activeUnit], level, xo, yo, x, y, w, h);
    }

    public static void texSubImage2D(int level, int x, int y, int w, int h, java.nio.Buffer data) {
        if (data != null) PD3D.texUpload(boundTex[activeUnit], level, x, y, w, h, data);
    }

    static ByteBuffer asBytes(java.nio.Buffer b) {
        return b instanceof ByteBuffer ? (ByteBuffer) b : null;
    }

    public static int colorMaskBits() {
        return colorMask;
    }

    public static float[] fogState() {
        if (!fog) return null;
        return new float[] { fogColor[0], fogColor[1], fogColor[2], fogStart, fogEnd, fogMode, fogDensity };
    }

    public static void glTexGeni(int coord, int pname, int mode) { if (!on()) GL11.glTexGeni(coord, pname, mode); else texGeni(coord, pname, mode); }

    public static void glTexGen(int coord, int pname, FloatBuffer p) {
        if (!on()) { GL11.glTexGen(coord, pname, p); return; }
        int i = p.position();
        texGenv(coord, pname, p.get(i), p.get(i + 1), p.get(i + 2), p.get(i + 3));
    }

    public static void glPixelStorei(int p, int v) { if (!on()) GL11.glPixelStorei(p, v); else FB.pixelStore(p, v); }

    public static void glReadPixels(int x, int y, int w, int h, int format, int type, IntBuffer out) {
        if (!on()) { GL11.glReadPixels(x, y, w, h, format, type, out); return; }
        FB.readPixels(x, y, w, h, format, type, out);
    }

    public static void glReadPixels(int x, int y, int w, int h, int format, int type, ByteBuffer out) {
        if (!on()) { GL11.glReadPixels(x, y, w, h, format, type, out); return; }
        FB.readPixels(x, y, w, h, format, type, out);
    }

    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer out) {
        if (!on()) { GL11.glGetTexImage(target, level, format, type, out); return; }
        FB.getTexImage(target, level, format, type, out);
    }

    public static int glGenLists(int range) { return on() ? DL.gen(range) : GL11.glGenLists(range); }
    public static void glNewList(int list, int mode) { if (!on()) GL11.glNewList(list, mode); else DL.newList(list, mode); }
    public static void glEndList() { if (!on()) GL11.glEndList(); else DL.endList(); }
    public static void glCallList(int list) { if (!on()) GL11.glCallList(list); else DL.call(list); }
    public static void glDeleteLists(int list, int range) { if (!on()) GL11.glDeleteLists(list, range); else DL.delete(list, range); }
    public static boolean glIsList(int list) { return on() ? DL.exists(list) : GL11.glIsList(list); }

    public static void glTranslated(double x, double y, double z) {
        if (!on()) { GL11.glTranslated(x, y, z); return; }
        glTranslatef((float) x, (float) y, (float) z);
    }

    public static void glScaled(double x, double y, double z) {
        if (!on()) { GL11.glScaled(x, y, z); return; }
        glScalef((float) x, (float) y, (float) z);
    }

    public static void glColor3f(float r, float g, float b) {
        if (!on()) { GL11.glColor3f(r, g, b); return; }
        glColor4f(r, g, b, 1);
    }

    public static void glColor4ub(byte r, byte g, byte b, byte a) {
        if (!on()) { GL11.glColor4ub(r, g, b, a); return; }
        glColor4f((r & 255) / 255f, (g & 255) / 255f, (b & 255) / 255f, (a & 255) / 255f);
    }

    public static void glBlendFunc(int src, int dst) {
        if (!on()) { GL11.glBlendFunc(src, dst); return; }
        glBlendFuncSeparate(src, dst, src, dst);
    }

    public static void glFogi(int p, int v) { if (!on()) GL11.glFogi(p, v); else fogi(p, v); }

    public static void glFogf(int p, float v) { if (!on()) GL11.glFogf(p, v); else fogf(p, v); }

    public static void glFog(int p, FloatBuffer v) { if (!on()) GL11.glFog(p, v); else fogv(p, v); }

    public static void glShadeModel(int m) { if (!on()) GL11.glShadeModel(m); else shadeModel = m; }
    public static void glFlush() { if (!on()) GL11.glFlush(); }
    public static void glLineWidth(float w) {
        if (!on()) { GL11.glLineWidth(w); return; }
        if (lineWidth == w) return;
        lineWidth = w;
        stateVersion++;
    }

    public static void glPolygonOffset(float f, float u) {
        if (!on()) { GL11.glPolygonOffset(f, u); return; }
        if (polyFactor == f && polyUnits == u) return;
        polyFactor = f; polyUnits = u;
        stateVersion++;
    }

    public static void glGetInteger(int p, IntBuffer out) {
        if (!on()) { GL11.glGetInteger(p, out); return; }
        getInteger(p, out);
    }
    public static void glColorMaterial(int f, int m) { if (!on()) GL11.glColorMaterial(f, m); }
    public static void glLightModel(int p, FloatBuffer v) { if (!on()) GL11.glLightModel(p, v); }
    public static void glClearDepth(double d) { if (!on()) GL11.glClearDepth(d); }
    public static void glLogicOp(int op) { if (!on()) GL11.glLogicOp(op); }
    public static void glHint(int t, int m) { if (!on()) GL11.glHint(t, m); }
    public static void glClearStencil(int s) { if (!on()) GL11.glClearStencil(s); }

    public static int glGetError() {
        if (!on()) return GL11.glGetError();
        int e = glError;
        glError = 0;
        return e;
    }

    public static float getFloat1(int p) {
        return p == 0x0BC2 ? alphaRef : getInteger(p);
    }

    public static String glGetString(int name) {
        if (!on()) return GL11.glGetString(name);
        return name == GL11.GL_VERSION ? "2.1 MinecraftD3D (Direct3D 11)" : "MinecraftD3D";
    }

    public static int glGetInteger(int p) {
        if (!on()) return GL11.glGetInteger(p);
        return getInteger(p);
    }

    public static void glGetFloat(int p, FloatBuffer out) {
        if (!on()) { GL11.glGetFloat(p, out); return; }
        getFloat(p, out);
    }

    public static float glGetTexParameterf(int target, int pname) {
        if (!on()) return GL11.glGetTexParameterf(target, pname);
        return 1.0f;
    }

    public static void glTexImage2D(int target, int level, int internal, int w, int h, int border, int format, int type, IntBuffer data) {
        if (!on()) { GL11.glTexImage2D(target, level, internal, w, h, border, format, type, data); return; }
        texImage2D(target, level, w, h, data);
    }

    public static void glTexImage2D(int target, int level, int internal, int w, int h, int border, int format, int type, ByteBuffer data) {
        if (!on()) { GL11.glTexImage2D(target, level, internal, w, h, border, format, type, data); return; }
        texImage2D(target, level, w, h, data);
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (!on()) return GL11.glGetTexLevelParameteri(target, level, pname);
        int id = boundTex[activeUnit];
        if (pname == GL11.GL_TEXTURE_WIDTH) return Math.max(1, texWidth(id) >> level);
        if (pname == GL11.GL_TEXTURE_HEIGHT) return Math.max(1, texHeight(id) >> level);
        return 0;
    }

    public static void glCopyTexSubImage2D(int target, int level, int xo, int yo, int x, int y, int w, int h) {
        if (!on()) { GL11.glCopyTexSubImage2D(target, level, xo, yo, x, y, w, h); return; }
        copyTexSubImage2D(level, xo, yo, x, y, w, h);
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, IntBuffer data) {
        if (!on()) { GL11.glTexSubImage2D(target, level, x, y, w, h, format, type, data); return; }
        texSubImage2D(level, x, y, w, h, data);
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        if (!on()) { GL11.glTexSubImage2D(target, level, x, y, w, h, format, type, data); return; }
        texSubImage2D(level, x, y, w, h, data);
    }
}
