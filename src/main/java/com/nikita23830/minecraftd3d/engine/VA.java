package com.nikita23830.minecraftd3d.engine;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;

final class VA {
    private VA() {}

    static final int GL_FLOAT = 0x1406, GL_DOUBLE = 0x140A, GL_BYTE = 0x1400, GL_UNSIGNED_BYTE = 0x1401, GL_SHORT = 0x1402,
        GL_UNSIGNED_SHORT = 0x1403, GL_INT = 0x1404, GL_UNSIGNED_INT = 0x1405;
    static final int ARRAY_BUFFER = 0x8892, ELEMENT_ARRAY_BUFFER = 0x8893;

    static final class Ptr {
        int size, type, stride;
        Buffer buf; int base;
        int vbo = -1; long offset;
        boolean on;

        int compBytes() {
            switch (type) {
                case GL_FLOAT: case GL_INT: case GL_UNSIGNED_INT: return 4;
                case GL_DOUBLE: return 8;
                case GL_SHORT: case GL_UNSIGNED_SHORT: return 2;
                default: return 1;
            }
        }

        int strideBytes() { return stride != 0 ? stride : size * compBytes(); }

        float get(int i, int c) {
            int off = i * strideBytes() + c * compBytes();
            if (vbo >= 0) {
                ByteBuffer b = buffers.get(vbo);
                if (b == null) return 0;
                return read(b, (int) offset + off);
            }
            if (buf instanceof ByteBuffer) return read((ByteBuffer) buf, base + off);
            if (buf instanceof FloatBuffer) return ((FloatBuffer) buf).get(base + off / 4);
            if (buf instanceof DoubleBuffer) return (float) ((DoubleBuffer) buf).get(base + off / 8);
            if (buf instanceof IntBuffer) return ((IntBuffer) buf).get(base + off / 4);
            if (buf instanceof ShortBuffer) return ((ShortBuffer) buf).get(base + off / 2);
            return 0;
        }

        private float read(ByteBuffer b, int at) {
            if (at < 0 || at + compBytes() > b.capacity()) return 0;
            switch (type) {
                case GL_FLOAT: return b.getFloat(at);
                case GL_DOUBLE: return (float) b.getDouble(at);
                case GL_INT: case GL_UNSIGNED_INT: return b.getInt(at);
                case GL_SHORT: return b.getShort(at);
                case GL_UNSIGNED_SHORT: return b.getShort(at) & 0xFFFF;
                case GL_UNSIGNED_BYTE: return b.get(at) & 255;
                default: return b.get(at);
            }
        }

        void set(int size, int type, int stride, Buffer data) {
            this.size = size; this.type = type; this.stride = stride; this.vbo = -1;
            if (data instanceof ByteBuffer) {
                ByteBuffer d = ((ByteBuffer) data).duplicate().order(ByteOrder.nativeOrder());
                this.buf = d; this.base = d.position();
            } else { this.buf = data; this.base = data == null ? 0 : data.position(); }
        }

        void setVbo(int size, int type, int stride, long offset) {
            this.size = size; this.type = type; this.stride = stride; this.buf = null;
            this.vbo = boundArray; this.offset = offset;
        }
    }

    static Ptr vertex = new Ptr(), color = new Ptr(), normal = new Ptr(), tex0 = new Ptr(), tex1 = new Ptr();
    static int clientUnit;

    static Ptr texPtr() { return clientUnit == 1 ? tex1 : tex0; }

    static void enable(int cap, boolean on) {
        switch (cap) {
            case 0x8074: vertex.on = on; break;
            case 0x8075: normal.on = on; break;
            case 0x8076: color.on = on; break;
            case 0x8078: texPtr().on = on; break;
            default: break;
        }
    }

    static final HashMap<Integer, ByteBuffer> buffers = new HashMap<Integer, ByteBuffer>();
    static int nextBuffer = 1, boundArray, boundElement;

    static int gen() {
        int id = nextBuffer++;
        buffers.put(id, ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()));
        return id;
    }

    static void delete(int id) {
        buffers.remove(id);
        if (boundArray == id) boundArray = 0;
        if (boundElement == id) boundElement = 0;
    }

    static void bind(int target, int id) {
        if (target == ARRAY_BUFFER) boundArray = id;
        else if (target == ELEMENT_ARRAY_BUFFER) boundElement = id;
    }

    private static int bound(int target) {
        return target == ELEMENT_ARRAY_BUFFER ? boundElement : boundArray;
    }

    static void data(int target, Buffer src, long size) {
        int id = bound(target);
        if (id == 0) return;
        int bytes = src != null ? byteSize(src) : (int) size;
        ByteBuffer b = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        if (src != null) copyInto(b, 0, src);
        buffers.put(id, b);
    }

    static void subData(int target, long offset, Buffer src) {
        ByteBuffer b = buffers.get(bound(target));
        if (b == null || src == null) return;
        copyInto(b, (int) offset, src);
    }

    static ByteBuffer map(int target, long offset, long length) {
        ByteBuffer b = buffers.get(bound(target));
        if (b == null) return null;
        ByteBuffer d = b.duplicate().order(ByteOrder.nativeOrder());
        int from = (int) offset, len = length < 0 ? b.capacity() - from : (int) length;
        d.position(from).limit(from + len);
        return d.slice().order(ByteOrder.nativeOrder());
    }

    private static int byteSize(Buffer src) {
        int n = src.remaining();
        if (src instanceof ByteBuffer) return n;
        if (src instanceof ShortBuffer) return n * 2;
        if (src instanceof DoubleBuffer) return n * 8;
        return n * 4;
    }

    private static void copyInto(ByteBuffer dst, int at, Buffer src) {
        ByteBuffer d = dst.duplicate().order(ByteOrder.nativeOrder());
        d.position(at);
        if (src instanceof ByteBuffer) d.put(((ByteBuffer) src).duplicate());
        else if (src instanceof FloatBuffer) d.asFloatBuffer().put(((FloatBuffer) src).duplicate());
        else if (src instanceof IntBuffer) d.asIntBuffer().put(((IntBuffer) src).duplicate());
        else if (src instanceof ShortBuffer) d.asShortBuffer().put(((ShortBuffer) src).duplicate());
        else if (src instanceof DoubleBuffer) d.asDoubleBuffer().put(((DoubleBuffer) src).duplicate());
    }

    private static int[] raw = new int[8 * 1024];

    static void drawArrays(int mode, int first, int count) {
        if (!vertex.on || count <= 0) return;
        ensure(count);
        for (int k = 0; k < count; k++) fill(k, first + k);
        Immediate.submit(raw, count, mode, tex0.on, color.on, normal.on, tex1.on);
    }

    static void drawElements(int mode, int count, int type, Buffer indices, long offset) {
        if (!vertex.on || count <= 0) return;
        ensure(count);
        ByteBuffer ib = null;
        int ibBase = 0;
        if (indices == null) { ib = buffers.get(boundElement); ibBase = (int) offset; if (ib == null) return; }
        for (int k = 0; k < count; k++) {
            int idx;
            if (indices instanceof IntBuffer) idx = ((IntBuffer) indices).get(indices.position() + k);
            else if (indices instanceof ShortBuffer) idx = ((ShortBuffer) indices).get(indices.position() + k) & 0xFFFF;
            else if (indices instanceof ByteBuffer) {
                ByteBuffer b = ((ByteBuffer) indices).duplicate().order(ByteOrder.nativeOrder());
                int p = b.position();
                idx = type == GL_UNSIGNED_INT ? b.getInt(p + k * 4) : type == GL_UNSIGNED_SHORT ? b.getShort(p + k * 2) & 0xFFFF : b.get(p + k) & 255;
            } else {
                idx = type == GL_UNSIGNED_INT ? ib.getInt(ibBase + k * 4) : type == GL_UNSIGNED_SHORT ? ib.getShort(ibBase + k * 2) & 0xFFFF : ib.get(ibBase + k) & 255;
            }
            fill(k, idx);
        }
        Immediate.submit(raw, count, mode, tex0.on, color.on, normal.on, tex1.on);
    }

    private static void ensure(int n) {
        if (raw.length < n * 8) raw = new int[Math.max(n * 8, raw.length * 2)];
    }

    private static void fill(int k, int i) {
        int r = k * 8;
        float x = vertex.get(i, 0), y = vertex.size > 1 ? vertex.get(i, 1) : 0, z = vertex.size > 2 ? vertex.get(i, 2) : 0;
        if (vertex.size > 3) { float w = vertex.get(i, 3); if (w != 0 && w != 1) { x /= w; y /= w; z /= w; } }
        raw[r] = Float.floatToRawIntBits(x);
        raw[r + 1] = Float.floatToRawIntBits(y);
        raw[r + 2] = Float.floatToRawIntBits(z);
        if (tex0.on) {
            raw[r + 3] = Float.floatToRawIntBits(tex0.get(i, 0));
            raw[r + 4] = Float.floatToRawIntBits(tex0.size > 1 ? tex0.get(i, 1) : 0);
        }
        if (color.on) raw[r + 5] = colorAt(i);
        if (normal.on) {
            float nx = normal.get(i, 0), ny = normal.get(i, 1), nz = normal.get(i, 2);
            if (normal.type == GL_BYTE) { nx /= 127f; ny /= 127f; nz /= 127f; }
            else if (normal.type == GL_SHORT) { nx /= 32767f; ny /= 32767f; nz /= 32767f; }
            raw[r + 6] = ((byte) (nx * 127)) & 255 | (((byte) (ny * 127)) & 255) << 8 | (((byte) (nz * 127)) & 255) << 16;
        }
        if (tex1.on) raw[r + 7] = ((int) tex1.get(i, 1)) << 16 | ((int) tex1.get(i, 0) & 0xFFFF);
    }

    private static int colorAt(int i) {
        float cr, cg, cb, ca = 1;
        if (color.type == GL_UNSIGNED_BYTE) {
            cr = color.get(i, 0) / 255f; cg = color.get(i, 1) / 255f; cb = color.get(i, 2) / 255f;
            if (color.size > 3) ca = color.get(i, 3) / 255f;
        } else if (color.type == GL_BYTE) {
            cr = color.get(i, 0) / 127f; cg = color.get(i, 1) / 127f; cb = color.get(i, 2) / 127f;
            if (color.size > 3) ca = color.get(i, 3) / 127f;
        } else {
            cr = color.get(i, 0); cg = color.get(i, 1); cb = color.get(i, 2);
            if (color.size > 3) ca = color.get(i, 3);
        }
        return RS.clamp255(ca) << 24 | RS.clamp255(cb) << 16 | RS.clamp255(cg) << 8 | RS.clamp255(cr);
    }
}
