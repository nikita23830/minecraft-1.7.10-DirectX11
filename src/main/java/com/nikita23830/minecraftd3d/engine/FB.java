package com.nikita23830.minecraftd3d.engine;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;

import com.nikita23830.minecraftd3d.PD3D;

public final class FB {
    private FB() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("minecraftd3d.fbo"));

    public static final boolean GL30 = Boolean.getBoolean("minecraftd3d.gl30");

    static final int GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9;
    static final int GL_COLOR_ATTACHMENT0 = 0x8CE0, GL_DEPTH_ATTACHMENT = 0x8D00, GL_STENCIL_ATTACHMENT = 0x8D20, GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5, GL_INCOMPLETE_MISSING_ATTACHMENT = 0x8CD7;

    static final int DEPTH_TEX_KEY = 0x40000000;

    private static final class Fbo { int colorTex, colorRb, depthRb, depthTex; }
    private static final class Rb { int w, h, format, tex; }

    private static final HashMap<Integer, Fbo> fbos = new HashMap<Integer, Fbo>();
    private static final HashMap<Integer, Rb> rbs = new HashMap<Integer, Rb>();
    private static int nextFbo = 1, nextRb = 1;

    static int drawBinding, readBinding, rbBinding;

    static int curTex, curDepth, curW, curH;

    public static int genFramebuffer() { int id = nextFbo++; fbos.put(id, new Fbo()); return id; }

    public static void deleteFramebuffer(int id) {
        if (id <= 0 || fbos.remove(id) == null) return;
        if (drawBinding == id) bind(GL_DRAW_FRAMEBUFFER, 0);
        if (readBinding == id) readBinding = 0;
    }

    public static boolean isFramebuffer(int id) { return fbos.containsKey(id); }

    public static int genRenderbuffer() { int id = nextRb++; rbs.put(id, new Rb()); return id; }

    public static void deleteRenderbuffer(int id) {
        Rb rb = rbs.remove(id);
        if (rb == null) return;
        if (rb.tex != 0) RS.glDeleteTextures(rb.tex);
        PD3D.fbDepthDelete(id);
        if (rbBinding == id) rbBinding = 0;
    }

    public static boolean isRenderbuffer(int id) { return rbs.containsKey(id); }

    public static void bindRenderbuffer(int target, int id) { rbBinding = id; }

    public static void renderbufferStorage(int target, int format, int w, int h) {
        Rb rb = rbs.get(rbBinding);
        if (rb == null) return;
        rb.w = w; rb.h = h; rb.format = format;
        if (isDepthFormat(format)) {
            if (rb.tex != 0) { RS.glDeleteTextures(rb.tex); rb.tex = 0; }
        } else {
            if (rb.tex == 0) rb.tex = RS.glGenTextures();
            RS.texCreateInternal(rb.tex, w, h);
        }
        syncTarget();
    }

    public static int getRenderbufferParameter(int target, int pname) {
        Rb rb = rbs.get(rbBinding);
        if (rb == null) return 0;
        switch (pname) {
            case 0x8D42: return rb.w;
            case 0x8D43: return rb.h;
            case 0x8D44: return rb.format;
            case 0x8D50: case 0x8D51: case 0x8D52: case 0x8D53: return isDepthFormat(rb.format) ? 0 : 8;
            case 0x8D54: return isDepthFormat(rb.format) ? 24 : 0;
            case 0x8D55: return rb.format == 0x88F0 ? 8 : 0;
            default: return 0;
        }
    }

    static boolean isDepthFormat(int f) {
        switch (f) {
            case 0x1902: case 0x81A5: case 0x81A6: case 0x81A7: case 0x8CAC:
            case 0x84F9: case 0x88F0: case 0x8CAD:
            case 0x8D48:
                return true;
            default: return false;
        }
    }

    private static boolean isMain(int id) { return id > 0 && id == PD3D.mainFramebufferObject(); }

    private static Fbo fbo(int id) { return id == 0 || isMain(id) ? null : fbos.get(id); }

    public static void bind(int target, int id) {
        if (target == GL_READ_FRAMEBUFFER) { readBinding = id; return; }
        if (target != GL_DRAW_FRAMEBUFFER) readBinding = id;
        drawBinding = id;
        syncTarget();
    }

    private static Fbo targetFbo(int target) { return fbo(target == GL_READ_FRAMEBUFFER ? readBinding : drawBinding); }

    public static void framebufferTexture2D(int target, int attachment, int textarget, int tex, int level) {
        Fbo f = targetFbo(target);
        if (f == null) return;
        if (attachment == GL_COLOR_ATTACHMENT0) { f.colorTex = tex; f.colorRb = 0; }
        else if (attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
            if (f.depthTex != 0 && f.depthTex != tex) PD3D.fbDepthDelete(DEPTH_TEX_KEY | f.depthTex);
            f.depthTex = tex; f.depthRb = 0;
        } else if (attachment != GL_STENCIL_ATTACHMENT) GLEmu.warnOnce("FBO: Anhang 0x" + Integer.toHexString(attachment) + " nicht unterstützt (nur COLOR_ATTACHMENT0)");
        syncTarget();
    }

    public static void framebufferRenderbuffer(int target, int attachment, int rbTarget, int rb) {
        Fbo f = targetFbo(target);
        if (f == null) return;
        if (attachment == GL_COLOR_ATTACHMENT0) { f.colorRb = rb; f.colorTex = 0; }
        else if (attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT) { f.depthRb = rb; f.depthTex = 0; }
        else if (attachment != GL_STENCIL_ATTACHMENT) GLEmu.warnOnce("FBO: Anhang 0x" + Integer.toHexString(attachment) + " nicht unterstützt (nur COLOR_ATTACHMENT0)");
        syncTarget();
    }

    public static int checkStatus(int target) {
        Fbo f = targetFbo(target);
        if (f == null) return GL_FRAMEBUFFER_COMPLETE;
        return colorTex(f) != 0 || depthKey(f) != 0 ? GL_FRAMEBUFFER_COMPLETE : GL_INCOMPLETE_MISSING_ATTACHMENT;
    }

    public static int getAttachmentParameter(int target, int attachment, int pname) {
        Fbo f = targetFbo(target);
        if (f == null) return pname == 0x8CD0 ? 0x1907  : 0;
        boolean color = attachment == GL_COLOR_ATTACHMENT0;
        int tex = color ? f.colorTex : f.depthTex, rb = color ? f.colorRb : f.depthRb;
        if (pname == 0x8CD0) return tex != 0 ? 0x0DE1 : rb != 0 ? 0x8D41 : 0;
        if (pname == 0x8CD1) return tex != 0 ? tex : rb;
        return 0;
    }

    private static int colorTex(Fbo f) {
        if (f.colorTex != 0) return f.colorTex;
        Rb rb = f.colorRb != 0 ? rbs.get(f.colorRb) : null;
        return rb != null ? rb.tex : 0;
    }

    private static int depthKey(Fbo f) {
        if (f.depthRb != 0) { Rb rb = rbs.get(f.depthRb); return rb != null && isDepthFormat(rb.format) ? f.depthRb : 0; }
        return f.depthTex != 0 ? DEPTH_TEX_KEY | f.depthTex : 0;
    }

    private static int width(Fbo f) {
        int t = colorTex(f);
        if (t != 0) return RS.texWidth(t);
        if (f.depthRb != 0) { Rb rb = rbs.get(f.depthRb); return rb != null ? rb.w : 0; }
        return f.depthTex != 0 ? RS.texWidth(f.depthTex) : 0;
    }

    private static int height(Fbo f) {
        int t = colorTex(f);
        if (t != 0) return RS.texHeight(t);
        if (f.depthRb != 0) { Rb rb = rbs.get(f.depthRb); return rb != null ? rb.h : 0; }
        return f.depthTex != 0 ? RS.texHeight(f.depthTex) : 0;
    }

    static void syncTarget() {
        Fbo f = fbo(drawBinding);
        int tex = 0, depth = 0, w = 0, h = 0;
        if (f != null) {
            tex = colorTex(f); depth = depthKey(f); w = width(f); h = height(f);
            if (tex == 0 && depth == 0) tex = -1;
        }
        if (tex == curTex && depth == curDepth && w == curW && h == curH) return;
        curTex = tex; curDepth = depth; curW = w; curH = h;
        Immediate.target(tex, depth, w, h);
    }

    public static void rebind() {
        curTex = curDepth = curW = curH = Integer.MIN_VALUE;
        syncTarget();
    }

    static boolean inFbo() { return curTex != 0 || curDepth != 0; }

    static void onTexImage(int id) {
        if (id != 0 && (id == curTex || (DEPTH_TEX_KEY | id) == curDepth)) { curTex = curDepth = curW = curH = 0; syncTarget(); }
    }

    static void onTexDelete(int id) {
        PD3D.fbDepthDelete(DEPTH_TEX_KEY | id);
    }

    public static void blit(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        Fbo s = fbo(readBinding), d = fbo(drawBinding);
        int st = s != null ? colorTex(s) : 0, sd = s != null ? depthKey(s) : 0;
        int dt = d != null ? colorTex(d) : 0, dd = d != null ? depthKey(d) : 0;
        if ((s != null && st == 0 && sd == 0) || (d != null && dt == 0 && dd == 0)) return;
        Immediate.blit(new int[] { sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter, st, sd, dt, dd });
    }

    static int getInteger(int pname) {
        switch (pname) {
            case 0x8CA6: return drawBinding;
            case 0x8CAA: return readBinding;
            case 0x8CA7: return rbBinding;
            case 0x84E8: return 16384;
            case 0x8CDF: return 1;
            case 0x0D02: return packRowLength; case 0x0D03: return packSkipRows; case 0x0D04: return packSkipPixels; case 0x0D05: return packAlignment;
            default: return -1;
        }
    }

    static int packRowLength, packSkipRows, packSkipPixels, packAlignment = 4;

    static boolean pixelStore(int p, int v) {
        switch (p) {
            case 0x0D02: packRowLength = v; return true;
            case 0x0D03: packSkipRows = v; return true;
            case 0x0D04: packSkipPixels = v; return true;
            case 0x0D05: packAlignment = v; return true;
            default: return false;
        }
    }

    private static ByteBuffer pixels = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

    private static ByteBuffer pixels(int bytes) {
        if (pixels.capacity() < bytes) pixels = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        pixels.clear();
        return pixels;
    }

    public static void readPixels(int x, int y, int w, int h, int format, int type, Buffer out) {
        if (out == null || w <= 0 || h <= 0 || !PD3D.isMainThread()) return;
        Fbo f = fbo(readBinding);
        int tex = 0, depth = 0;
        if (f != null) { tex = colorTex(f); depth = depthKey(f); if (tex == 0 && depth == 0) return; }
        boolean isDepth = format == 0x1902, isStencil = format == 0x1901 || format == 0x84F9;
        ByteBuffer src = pixels(w * h * 4);
        if (!isStencil) {
            PD3D.flushFrame();
            PD3D.fbRead(tex, depth, isDepth ? 1 : 0, x, y, w, h, src);

            if (isDepth && f == null && Immediate.inWorld()) for (int i = 0; i < w * h; i++) src.putFloat(i * 4, 1f - src.getFloat(i * 4));
        } else {
            for (int i = 0; i < w * h; i++) src.putInt(i * 4, 0);
        }
        pack(src, w, h, format, type, out, isDepth || isStencil);
    }

    public static void getTexImage(int target, int level, int format, int type, Buffer out) {
        if (out == null || !PD3D.isMainThread()) return;
        int id = RS.boundTexture();
        if (id != 0 && id == PD3D.mainFramebufferTexture()) {
            int rb = readBinding; readBinding = 0;
            readPixels(0, 0, RS.fullW, RS.fullH, format, type, out);
            readBinding = rb;
            return;
        }
        int w = Math.max(1, RS.texWidth(id) >> level), h = Math.max(1, RS.texHeight(id) >> level);
        if (RS.texWidth(id) <= 0) return;
        ByteBuffer src = pixels(w * h * 4);
        PD3D.flushFrame();
        if (PD3D.texRead(id, level, src) != 0) for (int i = 0; i < w * h; i++) src.putInt(i * 4, 0);
        pack(src, w, h, format, type, out, false);
    }

    private static int[] components(int format, boolean depth) {
        if (depth) return new int[] { 5 };
        switch (format) {
            case 0x80E1: return new int[] { 0, 1, 2, 3 };
            case 0x1908: return new int[] { 2, 1, 0, 3 };
            case 0x1907: return new int[] { 2, 1, 0 };
            case 0x80E0: return new int[] { 0, 1, 2 };
            case 0x1903: return new int[] { 2 };
            case 0x1904: return new int[] { 1 };
            case 0x1905: return new int[] { 0 };
            case 0x1906: return new int[] { 3 };
            case 0x1909: return new int[] { 4 };
            case 0x190A: return new int[] { 4, 3 };
            default:
                GLEmu.warnOnce("Pixel lesen: Format 0x" + Integer.toHexString(format) + " – als GL_RGBA");
                return new int[] { 2, 1, 0, 3 };
        }
    }

    private static void pack(ByteBuffer src, int w, int h, int format, int type, Buffer out, boolean depth) {
        int[] comp = components(format, depth);
        int n = comp.length;
        int packed;
        int compBytes;
        switch (type) {
            case 0x1401: case 0x1400: compBytes = 1; packed = 0; break;
            case 0x1403: case 0x1402: compBytes = 2; packed = 0; break;
            case 0x1405: case 0x1404: case 0x1406: compBytes = 4; packed = 0; break;
            case 0x8367: case 0x8035: compBytes = 1; packed = 4; break;
            default:
                GLEmu.warnOnce("Pixel lesen: Typ 0x" + Integer.toHexString(type) + " nicht unterstützt");
                return;
        }
        int bpp = packed != 0 ? packed : n * compBytes;
        int rowLen = packRowLength > 0 ? packRowLength : w;
        int align = Math.max(1, packAlignment);
        int stride = (rowLen * bpp + align - 1) / align * align;
        int first = packSkipRows * stride + packSkipPixels * bpp;
        int need = first + (h - 1) * stride + w * bpp;

        int elem = out instanceof ByteBuffer ? 1 : out instanceof ShortBuffer ? 2 : out instanceof DoubleBuffer ? 8 : 4;
        int avail = out.remaining() * elem;
        if (need > avail) { GLEmu.warnOnce("Pixel lesen: Puffer zu klein (" + avail + " von " + need + " Bytes)"); need = avail - avail % Math.max(1, bpp); if (need <= 0) return; }
        ByteBuffer t = tmp(need + 8);
        readOut(out, t, (need + elem - 1) / elem);

        boolean fast = !depth && packed == 4 && type == 0x8367 && format == 0x80E1 || (!depth && format == 0x80E1 && type == 0x1401);
        for (int y = 0; y < h; y++) {
            int row = first + y * stride;
            if (row + w * bpp > need) break;
            int s = y * w * 4;
            if (fast) {
                for (int i = 0; i < w * 4; i++) t.put(row + i, src.get(s + i));
                continue;
            }
            for (int x = 0; x < w; x++) {
                int sp = s + x * 4, dp = row + x * bpp;
                if (packed == 4) {
                    for (int c = 0; c < 4; c++) {
                        int v = c < n ? byteOf(src, sp, comp[c]) : 255;
                        t.put(dp + (type == 0x8367 ? c : 3 - c), (byte) v);
                    }
                    continue;
                }
                for (int c = 0; c < n; c++) {
                    float f = depth ? src.getFloat(sp) : byteOf(src, sp, comp[c]) / 255f;
                    int o = dp + c * compBytes;
                    switch (type) {
                        case 0x1401: t.put(o, (byte) Math.round(clamp(f) * 255f)); break;
                        case 0x1400: t.put(o, (byte) Math.round(clamp(f) * 127f)); break;
                        case 0x1403: t.putShort(o, (short) Math.round(clamp(f) * 65535f)); break;
                        case 0x1402: t.putShort(o, (short) Math.round(clamp(f) * 32767f)); break;
                        case 0x1405: t.putInt(o, (int) (long) (clamp(f) * 4294967295.0)); break;
                        case 0x1404: t.putInt(o, (int) (clamp(f) * 2147483647.0)); break;
                        default: t.putFloat(o, f);
                    }
                }
            }
        }
        writeOut(t, out, (need + elem - 1) / elem);
    }

    private static float clamp(float f) { return f < 0 ? 0 : f > 1 ? 1 : f; }

    private static int byteOf(ByteBuffer src, int sp, int c) {
        if (c < 4) return src.get(sp + c) & 255;
        int r = src.get(sp + 2) & 255, g = src.get(sp + 1) & 255, b = src.get(sp) & 255;
        return Math.min(255, r + g + b);
    }

    private static ByteBuffer tmp = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

    private static ByteBuffer tmp(int bytes) {
        if (tmp.capacity() < bytes) tmp = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        tmp.clear();
        return tmp;
    }

    private static void readOut(Buffer out, ByteBuffer t, int elems) {
        int p = out.position();
        if (out instanceof ByteBuffer) { ByteBuffer b = ((ByteBuffer) out).duplicate(); b.position(p).limit(p + elems); t.duplicate().order(ByteOrder.nativeOrder()).put(b); }
        else if (out instanceof IntBuffer) { IntBuffer b = ((IntBuffer) out).duplicate(); b.position(p).limit(p + elems); t.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().put(b); }
        else if (out instanceof FloatBuffer) { FloatBuffer b = ((FloatBuffer) out).duplicate(); b.position(p).limit(p + elems); t.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().put(b); }
        else if (out instanceof ShortBuffer) { ShortBuffer b = ((ShortBuffer) out).duplicate(); b.position(p).limit(p + elems); t.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().put(b); }
        else if (out instanceof DoubleBuffer) { DoubleBuffer b = ((DoubleBuffer) out).duplicate(); b.position(p).limit(p + elems); t.duplicate().order(ByteOrder.nativeOrder()).asDoubleBuffer().put(b); }
    }

    private static void writeOut(ByteBuffer t, Buffer out, int elems) {
        int p = out.position();
        ByteBuffer s = t.duplicate().order(ByteOrder.nativeOrder());
        s.position(0).limit(elems * (out instanceof ByteBuffer ? 1 : out instanceof ShortBuffer ? 2 : out instanceof DoubleBuffer ? 8 : 4));
        if (out instanceof ByteBuffer) { ByteBuffer b = ((ByteBuffer) out).duplicate(); b.position(p); b.put(s); }
        else if (out instanceof IntBuffer) { IntBuffer b = ((IntBuffer) out).duplicate(); b.position(p); b.put(s.asIntBuffer()); }
        else if (out instanceof FloatBuffer) { FloatBuffer b = ((FloatBuffer) out).duplicate(); b.position(p); b.put(s.asFloatBuffer()); }
        else if (out instanceof ShortBuffer) { ShortBuffer b = ((ShortBuffer) out).duplicate(); b.position(p); b.put(s.asShortBuffer()); }
        else if (out instanceof DoubleBuffer) { DoubleBuffer b = ((DoubleBuffer) out).duplicate(); b.position(p); b.put(s.asDoubleBuffer()); }
    }
}
