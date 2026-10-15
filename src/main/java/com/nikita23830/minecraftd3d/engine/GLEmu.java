package com.nikita23830.minecraftd3d.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import org.lwjglx.opengl.ContextCapabilities;

import com.nikita23830.minecraftd3d.PD3D;

public final class GLEmu {
    private GLEmu() {}

    public static final HashMap<String, int[]> unportedCalls = new HashMap<String, int[]>();
    public static final TreeMap<String, Integer> unportedTotal = new TreeMap<String, Integer>();
    private static final HashSet<String> warned = new HashSet<String>();
    public static boolean offThreadWarned;

    public static void unported(String what) {
        if (!PD3D.isMainThread()) { offThreadWarned = true; return; }
        int[] c = unportedCalls.get(what);
        if (c == null) {
            unportedCalls.put(what, c = new int[1]);
            if (!unportedTotal.containsKey(what)) PD3D.LOG.warn("MinecraftD3D: nicht portierter GL-Aufruf {}", what);
        }
        c[0]++;
    }

    public static void resetCounters() {
        for (Map.Entry<String, int[]> e : unportedCalls.entrySet()) unportedTotal.merge(e.getKey(), e.getValue()[0], Integer::sum);
        unportedCalls.clear();
    }

    static void warnOnce(String s) {
        if (warned.add(s)) PD3D.LOG.warn("MinecraftD3D GL: {}", s);
    }

    private static ContextCapabilities caps;

    public static ContextCapabilities getCapabilities() {
        if (caps == null) {
            try {
                java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                uf.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
                ContextCapabilities c = (ContextCapabilities) u.allocateInstance(ContextCapabilities.class);
                java.util.List<String> on = new java.util.ArrayList<String>(java.util.Arrays.asList("OpenGL11", "OpenGL12", "OpenGL13", "OpenGL14", "GL_ARB_multitexture", "GL_EXT_blend_func_separate"));
                if (FB.ENABLED) on.addAll(java.util.Arrays.asList("GL_ARB_framebuffer_object", "GL_EXT_framebuffer_object", "GL_EXT_framebuffer_blit"));
                if (FB.GL30) on.add("OpenGL30");
                for (String f : on) {
                    try { ContextCapabilities.class.getField(f).setBoolean(c, true); } catch (NoSuchFieldException ignored) {}
                }
                caps = c;
            } catch (Exception e) {
                throw new RuntimeException("MinecraftD3D: ContextCapabilities konnten nicht erstellt werden", e);
            }
        }
        return caps;
    }

    public static void checkGLError() {}

    public static int glGetError() { return RS.glGetError(); }

    public static String glGetString(int name) {
        switch (name) {
            case 0x1F00: return "MinecraftD3D";
            case 0x1F01: return "Direct3D 11 (MinecraftD3D, ohne OpenGL)";
            case 0x1F02: return "1.4 MinecraftD3D";
            default: return "";
        }
    }

    public static void glMatrixMode(int m) { if (DL.rec != null) { DL.add(() -> RS.glMatrixMode(m)); return; } RS.glMatrixMode(m); }
    public static void glPushMatrix() { if (DL.rec != null) { DL.add(RS::glPushMatrix); return; } RS.glPushMatrix(); }
    public static void glPopMatrix() { if (DL.rec != null) { DL.add(RS::glPopMatrix); return; } RS.glPopMatrix(); }
    public static void glLoadIdentity() { if (DL.rec != null) { DL.add(RS::glLoadIdentity); return; } RS.glLoadIdentity(); }
    public static void glTranslatef(float x, float y, float z) { if (DL.rec != null) { DL.add(() -> RS.glTranslatef(x, y, z)); return; } RS.glTranslatef(x, y, z); }
    public static void glTranslated(double x, double y, double z) { glTranslatef((float) x, (float) y, (float) z); }
    public static void glScalef(float x, float y, float z) { if (DL.rec != null) { DL.add(() -> RS.glScalef(x, y, z)); return; } RS.glScalef(x, y, z); }
    public static void glScaled(double x, double y, double z) { glScalef((float) x, (float) y, (float) z); }
    public static void glRotatef(float a, float x, float y, float z) { if (DL.rec != null) { DL.add(() -> RS.glRotatef(a, x, y, z)); return; } RS.glRotatef(a, x, y, z); }
    public static void glRotated(double a, double x, double y, double z) { glRotatef((float) a, (float) x, (float) y, (float) z); }
    public static void glOrtho(double l, double r, double b, double t, double n, double f) { if (DL.rec != null) { DL.add(() -> RS.glOrtho(l, r, b, t, n, f)); return; } RS.glOrtho(l, r, b, t, n, f); }
    public static void glFrustum(double l, double r, double b, double t, double n, double f) { if (DL.rec != null) { DL.add(() -> RS.glFrustum(l, r, b, t, n, f)); return; } RS.glFrustum(l, r, b, t, n, f); }
    public static void gluPerspective(float fovy, float aspect, float n, float f) { if (DL.rec != null) { DL.add(() -> RS.gluPerspective(fovy, aspect, n, f)); return; } RS.gluPerspective(fovy, aspect, n, f); }
    public static void gluOrtho2D(float l, float r, float b, float t) { glOrtho(l, r, b, t, -1, 1); }
    public static void gluLookAt(float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        if (DL.rec != null) { DL.add(() -> RS.gluLookAt(ex, ey, ez, cx, cy, cz, ux, uy, uz)); return; }
        RS.gluLookAt(ex, ey, ez, cx, cy, cz, ux, uy, uz);
    }
    public static void glMultMatrix(FloatBuffer m) {
        if (DL.rec != null) { FloatBuffer c = copy(m); DL.add(() -> RS.glMultMatrix(c)); return; }
        RS.glMultMatrix(m);
    }
    public static void glMultMatrix(DoubleBuffer m) { RS.glMultMatrix(m); }
    public static void glLoadMatrix(FloatBuffer m) {
        if (DL.rec != null) { FloatBuffer c = copy(m); DL.add(() -> RS.glLoadMatrix(c)); return; }
        RS.glLoadMatrix(m);
    }

    private static FloatBuffer copy(FloatBuffer m) {
        FloatBuffer c = FloatBuffer.allocate(16);
        for (int i = 0; i < 16; i++) c.put(i, m.get(m.position() + i));
        return c;
    }

    public static void glEnable(int cap) { if (DL.rec != null) { DL.add(() -> RS.glEnable(cap)); return; } RS.glEnable(cap); }
    public static void glDisable(int cap) { if (DL.rec != null) { DL.add(() -> RS.glDisable(cap)); return; } RS.glDisable(cap); }
    public static boolean glIsEnabled(int cap) { return RS.glIsEnabled(cap); }

    public static void glColor4f(float r, float g, float b, float a) {
        Begin.color(r, g, b, a);
        if (DL.rec != null) { DL.add(() -> RS.glColor4f(r, g, b, a)); return; }
        RS.glColor4f(r, g, b, a);
    }
    public static void glColor3f(float r, float g, float b) { glColor4f(r, g, b, 1f); }
    public static void glColor4d(double r, double g, double b, double a) { glColor4f((float) r, (float) g, (float) b, (float) a); }
    public static void glColor3d(double r, double g, double b) { glColor4f((float) r, (float) g, (float) b, 1f); }
    public static void glColor4ub(byte r, byte g, byte b, byte a) { glColor4f((r & 255) / 255f, (g & 255) / 255f, (b & 255) / 255f, (a & 255) / 255f); }
    public static void glColor3ub(byte r, byte g, byte b) { glColor4f((r & 255) / 255f, (g & 255) / 255f, (b & 255) / 255f, 1f); }
    public static void glColor4b(byte r, byte g, byte b, byte a) { glColor4f(r / 127f, g / 127f, b / 127f, a / 127f); }
    public static void glColor3b(byte r, byte g, byte b) { glColor4f(r / 127f, g / 127f, b / 127f, 1f); }

    public static void glNormal3f(float x, float y, float z) {
        Begin.normal(x, y, z);
        if (DL.rec != null) { DL.add(() -> RS.glNormal3f(x, y, z)); return; }
        RS.glNormal3f(x, y, z);
    }
    public static void glNormal3d(double x, double y, double z) { glNormal3f((float) x, (float) y, (float) z); }
    public static void glNormal3b(byte x, byte y, byte z) { glNormal3f(x / 127f, y / 127f, z / 127f); }
    public static void glNormal3i(int x, int y, int z) { glNormal3f(x, y, z); }

    public static void glBlendFunc(int s, int d) { glBlendFuncSeparate(s, d, s, d); }
    public static void glBlendFuncSeparate(int s, int d, int sa, int da) {
        if (DL.rec != null) { DL.add(() -> RS.glBlendFuncSeparate(s, d, sa, da)); return; }
        RS.glBlendFuncSeparate(s, d, sa, da);
    }
    public static void glBlendFuncSeparateEXT(int s, int d, int sa, int da) { glBlendFuncSeparate(s, d, sa, da); }
    public static void glBlendEquation(int mode) { if (mode != 0x8006) warnOnce("glBlendEquation 0x" + Integer.toHexString(mode) + " nicht unterstützt"); }
    public static void glAlphaFunc(int f, float ref) { if (DL.rec != null) { DL.add(() -> RS.glAlphaFunc(f, ref)); return; } RS.glAlphaFunc(f, ref); }
    public static void glDepthMask(boolean m) { if (DL.rec != null) { DL.add(() -> RS.glDepthMask(m)); return; } RS.glDepthMask(m); }
    public static void glDepthFunc(int f) { if (DL.rec != null) { DL.add(() -> RS.glDepthFunc(f)); return; } RS.glDepthFunc(f); }
    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) { if (DL.rec != null) { DL.add(() -> RS.glColorMask(r, g, b, a)); return; } RS.glColorMask(r, g, b, a); }
    public static void glCullFace(int m) { if (DL.rec != null) { DL.add(() -> RS.glCullFace(m)); return; } RS.glCullFace(m); }
    public static void glScissor(int x, int y, int w, int h) { RS.glScissor(x, y, w, h); }
    public static void glViewport(int x, int y, int w, int h) { RS.glViewport(x, y, w, h); }
    public static void glLight(int light, int pname, FloatBuffer params) {
        if (DL.rec != null) { FloatBuffer c = FloatBuffer.allocate(4); for (int i = 0; i < 4; i++) c.put(i, params.get(params.position() + i)); DL.add(() -> RS.glLight(light, pname, c)); return; }
        RS.glLight(light, pname, params);
    }
    public static void glPushAttrib(int mask) { if (DL.rec != null) { DL.add(() -> RS.glPushAttrib(mask)); return; } RS.glPushAttrib(mask); }
    public static void glPopAttrib() { if (DL.rec != null) { DL.add(RS::glPopAttrib); return; } RS.glPopAttrib(); }

    public static void glClearColor(float r, float g, float b, float a) { RS.glClearColor(r, g, b, a); }
    public static void glClear(int mask) { RS.glClear(mask); }

    public static void glShadeModel(int m) { if (DL.rec != null) { DL.add(() -> RS.glShadeModel(m)); return; } RS.glShadeModel(m); }
    public static void glFlush() {}
    public static void glFinish() {}
    public static void glLineWidth(float w) { if (DL.rec != null) { DL.add(() -> RS.glLineWidth(w)); return; } RS.glLineWidth(w); }
    public static void glPointSize(float s) {}
    public static void glFogi(int p, int v) { if (DL.rec != null) { DL.add(() -> RS.fogi(p, v)); return; } RS.fogi(p, v); }
    public static void glFogf(int p, float v) { if (DL.rec != null) { DL.add(() -> RS.fogf(p, v)); return; } RS.fogf(p, v); }
    public static void glFog(int p, FloatBuffer v) { RS.fogv(p, v); }
    public static void glColorMaterial(int f, int m) {}
    public static void glLightModel(int p, FloatBuffer v) {}
    public static void glLightModelf(int p, float v) {}
    public static void glLightModeli(int p, int v) {}
    public static void glLightf(int l, int p, float v) {}
    public static void glLighti(int l, int p, int v) {}
    public static void glMaterial(int f, int p, FloatBuffer v) {}
    public static void glMaterialf(int f, int p, float v) {}
    public static void glClearDepth(double d) {}
    public static void glClearStencil(int s) {}
    public static void glLogicOp(int op) {}
    public static void glPolygonOffset(float f, float u) { if (DL.rec != null) { DL.add(() -> RS.glPolygonOffset(f, u)); return; } RS.glPolygonOffset(f, u); }
    public static void glHint(int t, int m) {}
    public static void glFrontFace(int m) { if (m != 0x0901) warnOnce("glFrontFace(GL_CW) nicht unterstützt"); }
    public static void glTexGeni(int coord, int pname, int mode) { if (DL.rec != null) { DL.add(() -> RS.texGeni(coord, pname, mode)); return; } RS.texGeni(coord, pname, mode); }
    public static void glTexGen(int coord, int pname, FloatBuffer v) {
        int p = v.position();
        float a = v.get(p), b = v.get(p + 1), c = v.get(p + 2), d = v.get(p + 3);
        if (DL.rec != null) { DL.add(() -> RS.texGenv(coord, pname, a, b, c, d)); return; }
        RS.texGenv(coord, pname, a, b, c, d);
    }
    public static void glTexEnvi(int t, int p, int v) {}
    public static void glTexEnvf(int t, int p, float v) {}
    public static void glTexEnv(int t, int p, FloatBuffer v) {}
    public static void glDrawBuffer(int b) {}
    public static void glReadBuffer(int b) {}
    public static void glStencilFunc(int f, int r, int m) {}
    public static void glStencilOp(int a, int b, int c) {}
    public static void glStencilMask(int m) {}
    public static void glPushClientAttrib(int m) {}
    public static void glPopClientAttrib() {}
    public static void glDepthRange(double n, double f) {}

    public static void glGetFloat(int pname, FloatBuffer out) {
        if (pname == 0x0B03) { int p = out.position(); out.put(p, Begin.u()).put(p + 1, Begin.v()).put(p + 2, 0f).put(p + 3, 1f); return; }
        RS.getFloat(pname, out);
    }
    public static float glGetFloat(int pname) { return RS.getFloat1(pname); }
    public static int glGetInteger(int pname) { return RS.getInteger(pname); }
    public static void glGetInteger(int pname, IntBuffer out) { RS.getInteger(pname, out); }

    public static void glActiveTexture(int unit) { if (DL.rec != null) { DL.add(() -> RS.activeTexture(unit)); return; } RS.activeTexture(unit); }
    public static void glActiveTextureARB(int unit) { glActiveTexture(unit); }
    public static void glMultiTexCoord2f(int target, float s, float t) { if (DL.rec != null) { DL.add(() -> RS.multiTexCoord(target, s, t)); return; } RS.multiTexCoord(target, s, t); }
    public static void glMultiTexCoord2fARB(int target, float s, float t) { glMultiTexCoord2f(target, s, t); }

    public static int glGenTextures() { return RS.glGenTextures(); }
    public static void glGenTextures(IntBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, RS.glGenTextures()); }
    public static void glDeleteTextures(int id) { RS.glDeleteTextures(id); }
    public static void glDeleteTextures(IntBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) RS.glDeleteTextures(ids.get(i)); }
    public static void glBindTexture(int target, int id) { if (DL.rec != null) { DL.add(() -> RS.glBindTexture(target, id)); return; } RS.glBindTexture(target, id); }
    public static void glTexParameteri(int target, int pname, int v) { RS.glTexParameteri(target, pname, v); }
    public static void glTexParameterf(int target, int pname, float v) { RS.glTexParameterf(target, pname, v); }
    public static void glTexParameter(int target, int pname, FloatBuffer v) {}
    public static void glTexParameter(int target, int pname, IntBuffer v) {}
    public static int glGetTexParameteri(int target, int pname) { return RS.glGetTexParameteri(target, pname); }
    public static float glGetTexParameterf(int target, int pname) { return 1.0f; }
    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        return pname == 0x1000 || pname == 0x1001 ? 16384 : 0;
    }
    public static void glCopyTexSubImage2D(int target, int level, int xo, int yo, int x, int y, int w, int h) { RS.copyTexSubImage2D(level, xo, yo, x, y, w, h); }
    public static void glGenerateMipmap(int target) {}
    public static void glGenerateMipmapEXT(int target) {}

    public static void glTexImage2D(int target, int level, int internal, int w, int h, int border, int format, int type, IntBuffer data) {
        long t0 = System.nanoTime();
        RS.texImage2D(target, level, w, h, convert(data, format, type, w, h));
        slowTex("glTexImage2D", t0, w, h, format, type);
    }
    public static void glTexImage2D(int target, int level, int internal, int w, int h, int border, int format, int type, ByteBuffer data) {
        long t0 = System.nanoTime();
        RS.texImage2D(target, level, w, h, convert(data, format, type, w, h));
        slowTex("glTexImage2D", t0, w, h, format, type);
    }
    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, IntBuffer data) {
        long t0 = System.nanoTime();
        RS.texSubImage2D(level, x, y, w, h, convert(data, format, type, w, h));
        slowTex("glTexSubImage2D", t0, w, h, format, type);
    }
    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        long t0 = System.nanoTime();
        RS.texSubImage2D(level, x, y, w, h, convert(data, format, type, w, h));
        slowTex("glTexSubImage2D", t0, w, h, format, type);
    }

    private static ByteBuffer conv = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

    private static int unpackRowLength, unpackSkipRows, unpackSkipPixels, unpackAlignment = 4;
    private static ByteBuffer tight = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

    public static void glPixelStorei(int p, int v) {
        switch (p) {
            case 0x0CF2: unpackRowLength = v; break;
            case 0x0CF3: unpackSkipRows = v; break;
            case 0x0CF4: unpackSkipPixels = v; break;
            case 0x0CF5: unpackAlignment = v; break;
            default: FB.pixelStore(p, v); break;
        }
    }

    public static void glPixelStoref(int p, float v) { glPixelStorei(p, (int) v); }

    private static int bytesPerPixel(int format) {
        switch (format) {
            case 0x1907: case 0x80E0: return 3;
            case 0x1909: case 0x1906: case 0x1903: return 1;
            case 0x190A: return 2;
            default: return 4;
        }
    }

    private static void slowTex(String what, long t0, int w, int h, int format, int type) {
        long ns = System.nanoTime() - t0;
        if (ns > 5_000_000L) PD3D.LOG.info("MinecraftD3D: langsamer Textur-Upload {} {}x{} Format 0x{}/0x{}: {} ms", what, w, h,
            Integer.toHexString(format), Integer.toHexString(type), ns / 1_000_000L);
    }

    private static int[] convInts = new int[1 << 16];
    private static byte[] convBytes = new byte[1 << 16];

    private static java.nio.Buffer convert(java.nio.Buffer data, int format, int type, int w, int h) {
        if (data == null) return null;
        int bpp = bytesPerPixel(format);
        int rowLen = unpackRowLength > 0 ? unpackRowLength : w;
        boolean plain = rowLen == w && unpackSkipRows == 0 && unpackSkipPixels == 0 && (bpp == 4 || unpackAlignment == 1 || (w * bpp) % unpackAlignment == 0);
        if (plain && format == 0x80E1  && (type == 0x8367 || type == 0x1401)) return data;

        int n = w * h;
        ByteBuffer src;
        if (data instanceof ByteBuffer) {
            ByteBuffer bb = ((ByteBuffer) data).duplicate();
            if (plain) {
                bb.limit(Math.min(bb.capacity(), bb.position() + n * bpp));
                src = bb.slice().order(ByteOrder.nativeOrder());
            } else {
                if (tight.capacity() < n * bpp) tight = ByteBuffer.allocateDirect(n * bpp).order(ByteOrder.nativeOrder());
                tight.clear();
                int stride = rowLen * bpp;
                if (unpackAlignment > 1) stride = (stride + unpackAlignment - 1) / unpackAlignment * unpackAlignment;
                int base = bb.position() + unpackSkipRows * stride + unpackSkipPixels * bpp;
                for (int y = 0; y < h; y++) {
                    int row = base + y * stride;
                    bb.limit(bb.capacity()).position(row);
                    bb.limit(Math.min(bb.capacity(), row + w * bpp));
                    tight.put(bb);
                }
                tight.flip();
                src = tight;
            }
        } else if (data instanceof IntBuffer && bpp == 4) {
            IntBuffer ib = ((IntBuffer) data).duplicate();
            if (tight.capacity() < n * 4) tight = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
            tight.clear();
            IntBuffer ti = tight.asIntBuffer();
            int base = ib.position() + unpackSkipRows * rowLen + unpackSkipPixels;
            for (int y = 0; y < h; y++) {
                int row = base + y * rowLen;
                ib.limit(ib.capacity()).position(row);
                ib.limit(Math.min(ib.capacity(), row + w));
                ti.put(ib);
            }
            tight.limit(n * 4);
            src = tight;
        } else return data;
        if (format == 0x80E1 && (type == 0x8367 || type == 0x1401)) return src;
        if (conv.capacity() < n * 4) conv = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
        conv.clear();
        IntBuffer out = conv.asIntBuffer();
        if (format == 0x1908  && (type == 0x1401 || type == 0x8367 || type == 0x8035)) {
            IntBuffer in = src.asIntBuffer();
            boolean abgr = type == 0x8035;
            for (int done = 0; done < n; ) {
                int k = Math.min(convInts.length, n - done);
                in.get(convInts, 0, k);
                for (int i = 0; i < k; i++) {
                    int v = convInts[i];
                    convInts[i] = abgr ? Integer.rotateRight(v, 8) : (v & 0xFF00FF00) | ((v >>> 16) & 0xFF) | ((v & 0xFF) << 16);
                }
                out.put(convInts, 0, k);
                done += k;
            }
        } else if ((format == 0x1907  || format == 0x80E0 ) && type == 0x1401
            || format == 0x1909  || format == 0x1906  || format == 0x1903
            || format == 0x190A ) {
            int chunk = convInts.length;
            if (convBytes.length < chunk * bpp) convBytes = new byte[chunk * bpp];
            ByteBuffer in = src.duplicate();
            for (int done = 0; done < n; ) {
                int k = Math.min(chunk, n - done);
                in.get(convBytes, 0, k * bpp);
                byte[] b = convBytes;
                for (int i = 0, q = 0; i < k; i++, q += bpp) {
                    int v;
                    switch (format) {
                        case 0x1907: v = (b[q + 2] & 255) | (b[q + 1] & 255) << 8 | (b[q] & 255) << 16 | 0xFF000000; break;
                        case 0x80E0: v = (b[q] & 255) | (b[q + 1] & 255) << 8 | (b[q + 2] & 255) << 16 | 0xFF000000; break;
                        case 0x1906: v = 0x00FFFFFF | (b[q] & 255) << 24; break;
                        case 0x190A: { int l = b[q] & 255; v = l | l << 8 | l << 16 | (b[q + 1] & 255) << 24; break; }
                        default: { int l = b[q] & 255; v = l | l << 8 | l << 16 | 0xFF000000; }
                    }
                    convInts[i] = v;
                }
                out.put(convInts, 0, k);
                done += k;
            }
        } else {
            warnOnce("Texturformat 0x" + Integer.toHexString(format) + "/0x" + Integer.toHexString(type) + " – unverändert");
            return src;
        }
        conv.limit(n * 4);
        return conv;
    }

    public static int glGenLists(int range) { return DL.gen(range); }
    public static void glNewList(int list, int mode) { DL.newList(list, mode); }
    public static void glEndList() { DL.endList(); }
    public static void glCallList(int list) { if (DL.rec != null) { DL.add(() -> DL.call(list)); return; } DL.call(list); }
    public static void glCallLists(IntBuffer lists) {
        for (int i = lists.position(); i < lists.limit(); i++) glCallList(lists.get(i));
    }
    public static void glDeleteLists(int list, int range) { DL.delete(list, range); }
    public static boolean glIsList(int list) { return DL.exists(list); }

    public static void glBegin(int mode) { Begin.begin(mode); }
    public static void glEnd() { Begin.end(); }
    public static void glVertex3f(float x, float y, float z) { Begin.vertex(x, y, z); }
    public static void glVertex2f(float x, float y) { Begin.vertex(x, y, 0); }
    public static void glVertex3d(double x, double y, double z) { Begin.vertex((float) x, (float) y, (float) z); }
    public static void glVertex2d(double x, double y) { Begin.vertex((float) x, (float) y, 0); }
    public static void glVertex3i(int x, int y, int z) { Begin.vertex(x, y, z); }
    public static void glVertex2i(int x, int y) { Begin.vertex(x, y, 0); }
    public static void glTexCoord2f(float u, float v) { Begin.texCoord(u, v); }
    public static void glTexCoord2d(double u, double v) { Begin.texCoord((float) u, (float) v); }

    public static void glEnableClientState(int cap) { VA.enable(cap, true); }
    public static void glDisableClientState(int cap) { VA.enable(cap, false); }
    public static void glClientActiveTexture(int unit) { VA.clientUnit = Math.max(0, unit - 0x84C0); }
    public static void glClientActiveTextureARB(int unit) { glClientActiveTexture(unit); }

    public static void glVertexPointer(int size, int stride, FloatBuffer p) { VA.vertex.set(size, VA.GL_FLOAT, stride, p); }
    public static void glVertexPointer(int size, int stride, DoubleBuffer p) { VA.vertex.set(size, VA.GL_DOUBLE, stride, p); }
    public static void glVertexPointer(int size, int stride, IntBuffer p) { VA.vertex.set(size, VA.GL_INT, stride, p); }
    public static void glVertexPointer(int size, int stride, ShortBuffer p) { VA.vertex.set(size, VA.GL_SHORT, stride, p); }
    public static void glVertexPointer(int size, int type, int stride, ByteBuffer p) { VA.vertex.set(size, type, stride, p); }
    public static void glVertexPointer(int size, int type, int stride, long offset) { VA.vertex.setVbo(size, type, stride, offset); }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer p) { VA.texPtr().set(size, VA.GL_FLOAT, stride, p); }
    public static void glTexCoordPointer(int size, int stride, DoubleBuffer p) { VA.texPtr().set(size, VA.GL_DOUBLE, stride, p); }
    public static void glTexCoordPointer(int size, int stride, IntBuffer p) { VA.texPtr().set(size, VA.GL_INT, stride, p); }
    public static void glTexCoordPointer(int size, int stride, ShortBuffer p) { VA.texPtr().set(size, VA.GL_SHORT, stride, p); }
    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer p) { VA.texPtr().set(size, type, stride, p); }
    public static void glTexCoordPointer(int size, int type, int stride, long offset) { VA.texPtr().setVbo(size, type, stride, offset); }

    public static void glColorPointer(int size, int stride, FloatBuffer p) { VA.color.set(size, VA.GL_FLOAT, stride, p); }
    public static void glColorPointer(int size, int stride, DoubleBuffer p) { VA.color.set(size, VA.GL_DOUBLE, stride, p); }
    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer p) { VA.color.set(size, unsigned ? VA.GL_UNSIGNED_BYTE : VA.GL_BYTE, stride, p); }
    public static void glColorPointer(int size, int type, int stride, ByteBuffer p) { VA.color.set(size, type, stride, p); }
    public static void glColorPointer(int size, int type, int stride, long offset) { VA.color.setVbo(size, type, stride, offset); }

    public static void glNormalPointer(int stride, FloatBuffer p) { VA.normal.set(3, VA.GL_FLOAT, stride, p); }
    public static void glNormalPointer(int stride, DoubleBuffer p) { VA.normal.set(3, VA.GL_DOUBLE, stride, p); }
    public static void glNormalPointer(int stride, IntBuffer p) { VA.normal.set(3, VA.GL_INT, stride, p); }
    public static void glNormalPointer(int stride, ByteBuffer p) { VA.normal.set(3, VA.GL_BYTE, stride, p); }
    public static void glNormalPointer(int type, int stride, ByteBuffer p) { VA.normal.set(3, type, stride, p); }
    public static void glNormalPointer(int type, int stride, long offset) { VA.normal.setVbo(3, type, stride, offset); }

    public static void glDrawArrays(int mode, int first, int count) { VA.drawArrays(mode, first, count); }
    public static void glDrawElements(int mode, ByteBuffer idx) { VA.drawElements(mode, idx.remaining(), VA.GL_UNSIGNED_BYTE, idx, 0); }
    public static void glDrawElements(int mode, ShortBuffer idx) { VA.drawElements(mode, idx.remaining(), VA.GL_UNSIGNED_SHORT, idx, 0); }
    public static void glDrawElements(int mode, IntBuffer idx) { VA.drawElements(mode, idx.remaining(), VA.GL_UNSIGNED_INT, idx, 0); }
    public static void glDrawElements(int mode, int count, int type, ByteBuffer idx) { VA.drawElements(mode, count, type, idx, 0); }
    public static void glDrawElements(int mode, int count, int type, long offset) { VA.drawElements(mode, count, type, null, offset); }

    public static int glGenBuffers() { return VA.gen(); }
    public static void glGenBuffers(IntBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, VA.gen()); }
    public static void glDeleteBuffers(int id) { VA.delete(id); }
    public static void glDeleteBuffers(IntBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) VA.delete(ids.get(i)); }
    public static boolean glIsBuffer(int id) { return VA.buffers.containsKey(id); }
    public static void glBindBuffer(int target, int id) { VA.bind(target, id); }
    public static void glBufferData(int target, long size, int usage) { VA.data(target, null, size); }
    public static void glBufferData(int target, ByteBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferData(int target, FloatBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferData(int target, IntBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferData(int target, ShortBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferData(int target, DoubleBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferSubData(int target, long offset, ByteBuffer data) { VA.subData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, FloatBuffer data) { VA.subData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, IntBuffer data) { VA.subData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, ShortBuffer data) { VA.subData(target, offset, data); }
    public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old) { return VA.map(target, 0, -1); }
    public static ByteBuffer glMapBuffer(int target, int access, long length, ByteBuffer old) { return VA.map(target, 0, length); }
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer old) { return VA.map(target, offset, length); }
    public static boolean glUnmapBuffer(int target) { return true; }

    public static int glGenBuffersARB() { return VA.gen(); }
    public static void glGenBuffersARB(IntBuffer out) { glGenBuffers(out); }
    public static void glDeleteBuffersARB(int id) { VA.delete(id); }
    public static void glDeleteBuffersARB(IntBuffer ids) { glDeleteBuffers(ids); }
    public static void glBindBufferARB(int target, int id) { VA.bind(target, id); }
    public static void glBufferDataARB(int target, long size, int usage) { VA.data(target, null, size); }
    public static void glBufferDataARB(int target, ByteBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferDataARB(int target, FloatBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferDataARB(int target, IntBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferDataARB(int target, ShortBuffer data, int usage) { VA.data(target, data, 0); }
    public static void glBufferSubDataARB(int target, long offset, ByteBuffer data) { VA.subData(target, offset, data); }
    public static void glBufferSubDataARB(int target, long offset, FloatBuffer data) { VA.subData(target, offset, data); }
    public static ByteBuffer glMapBufferARB(int target, int access, ByteBuffer old) { return VA.map(target, 0, -1); }
    public static boolean glUnmapBufferARB(int target) { return true; }

    public static boolean glIsTexture(int id) { return id > 0; }
    public static void glPolygonMode(int face, int mode) { if (mode != 0x1B02) warnOnce("glPolygonMode(0x" + Integer.toHexString(mode) + ") – Drahtgitter/Punkte nicht unterstützt"); }
    public static void glLineStipple(int factor, short pattern) {}
    public static boolean glGetBoolean(int pname) { return pname == 0x0B72 ? RS.depthMask : glIsEnabled(pname); }
    public static void glGetBoolean(int pname, ByteBuffer out) { out.put(out.position(), (byte) (glGetBoolean(pname) ? 1 : 0)); }
    public static void glGetLight(int light, int pname, FloatBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, 0f); }
    public static void glGetMaterial(int face, int pname, FloatBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, 0f); }
    public static void glBlendColor(float r, float g, float b, float a) { warnOnce("glBlendColor nicht unterstützt"); }

    public static int gluBuild2DMipmaps(int target, int components, int w, int h, int format, int type, ByteBuffer data) {
        glTexImage2D(target, 0, components, w, h, 0, format, type, data);
        return 0;
    }

    public static int glGenFramebuffers() { return FB.genFramebuffer(); }
    public static void glGenFramebuffers(IntBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, FB.genFramebuffer()); }
    public static void glDeleteFramebuffers(int id) { FB.deleteFramebuffer(id); }
    public static void glDeleteFramebuffers(IntBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) FB.deleteFramebuffer(ids.get(i)); }
    public static boolean glIsFramebuffer(int id) { return FB.isFramebuffer(id); }
    public static void glBindFramebuffer(int target, int id) { FB.bind(target, id); }
    public static int glCheckFramebufferStatus(int target) { return FB.checkStatus(target); }
    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int tex, int level) { FB.framebufferTexture2D(target, attachment, textarget, tex, level); }
    public static void glFramebufferRenderbuffer(int target, int attachment, int rbTarget, int rb) { FB.framebufferRenderbuffer(target, attachment, rbTarget, rb); }
    public static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) { return FB.getAttachmentParameter(target, attachment, pname); }
    public static int glGenRenderbuffers() { return FB.genRenderbuffer(); }
    public static void glGenRenderbuffers(IntBuffer out) { for (int i = out.position(); i < out.limit(); i++) out.put(i, FB.genRenderbuffer()); }
    public static void glDeleteRenderbuffers(int id) { FB.deleteRenderbuffer(id); }
    public static void glDeleteRenderbuffers(IntBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) FB.deleteRenderbuffer(ids.get(i)); }
    public static boolean glIsRenderbuffer(int id) { return FB.isRenderbuffer(id); }
    public static void glBindRenderbuffer(int target, int id) { FB.bindRenderbuffer(target, id); }
    public static void glRenderbufferStorage(int target, int format, int w, int h) { FB.renderbufferStorage(target, format, w, h); }

    public static void glRenderbufferStorageMultisample(int target, int samples, int format, int w, int h) { FB.renderbufferStorage(target, format, w, h); }
    public static int glGetRenderbufferParameteri(int target, int pname) { return FB.getRenderbufferParameter(target, pname); }
    public static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        FB.blit(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    public static int glGenFramebuffersEXT() { return glGenFramebuffers(); }
    public static void glGenFramebuffersEXT(IntBuffer out) { glGenFramebuffers(out); }
    public static void glDeleteFramebuffersEXT(int id) { glDeleteFramebuffers(id); }
    public static void glDeleteFramebuffersEXT(IntBuffer ids) { glDeleteFramebuffers(ids); }
    public static boolean glIsFramebufferEXT(int id) { return glIsFramebuffer(id); }
    public static void glBindFramebufferEXT(int target, int id) { glBindFramebuffer(target, id); }
    public static int glCheckFramebufferStatusEXT(int target) { return glCheckFramebufferStatus(target); }
    public static void glFramebufferTexture2DEXT(int target, int attachment, int textarget, int tex, int level) { glFramebufferTexture2D(target, attachment, textarget, tex, level); }
    public static void glFramebufferRenderbufferEXT(int target, int attachment, int rbTarget, int rb) { glFramebufferRenderbuffer(target, attachment, rbTarget, rb); }
    public static int glGetFramebufferAttachmentParameteriEXT(int target, int attachment, int pname) { return glGetFramebufferAttachmentParameteri(target, attachment, pname); }
    public static int glGenRenderbuffersEXT() { return glGenRenderbuffers(); }
    public static void glGenRenderbuffersEXT(IntBuffer out) { glGenRenderbuffers(out); }
    public static void glDeleteRenderbuffersEXT(int id) { glDeleteRenderbuffers(id); }
    public static void glDeleteRenderbuffersEXT(IntBuffer ids) { glDeleteRenderbuffers(ids); }
    public static boolean glIsRenderbufferEXT(int id) { return glIsRenderbuffer(id); }
    public static void glBindRenderbufferEXT(int target, int id) { glBindRenderbuffer(target, id); }
    public static void glRenderbufferStorageEXT(int target, int format, int w, int h) { glRenderbufferStorage(target, format, w, h); }
    public static void glRenderbufferStorageMultisampleEXT(int target, int samples, int format, int w, int h) { glRenderbufferStorage(target, format, w, h); }
    public static int glGetRenderbufferParameteriEXT(int target, int pname) { return glGetRenderbufferParameteri(target, pname); }
    public static void glBlitFramebufferEXT(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    public static void glReadPixels(int x, int y, int w, int h, int format, int type, ByteBuffer out) { FB.readPixels(x, y, w, h, format, type, out); }
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, IntBuffer out) { FB.readPixels(x, y, w, h, format, type, out); }
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, FloatBuffer out) { FB.readPixels(x, y, w, h, format, type, out); }
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, ShortBuffer out) { FB.readPixels(x, y, w, h, format, type, out); }
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, DoubleBuffer out) { FB.readPixels(x, y, w, h, format, type, out); }
    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer out) { FB.getTexImage(target, level, format, type, out); }
    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer out) { FB.getTexImage(target, level, format, type, out); }
    public static void glGetTexImage(int target, int level, int format, int type, FloatBuffer out) { FB.getTexImage(target, level, format, type, out); }
    public static void glGetTexImage(int target, int level, int format, int type, ShortBuffer out) { FB.getTexImage(target, level, format, type, out); }
    public static void glGetTexImage(int target, int level, int format, int type, DoubleBuffer out) { FB.getTexImage(target, level, format, type, out); }
}
