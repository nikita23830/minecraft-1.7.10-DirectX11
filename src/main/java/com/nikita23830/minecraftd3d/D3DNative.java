package com.nikita23830.minecraftd3d;

import java.nio.ByteBuffer;

public final class D3DNative {
    private D3DNative() {}

    static native int init(long hwnd, int flags);

    static native String adapterInfo();

    static native void sectionSet(int x, int y, int z, ByteBuffer buf, int opaqueVerts, int transVerts);

    static native void clearSections();

    static native void frameEngine(ByteBuffer desc, ByteBuffer cmds, int ncmd, ByteBuffer verts, int nverts);

    static native void flush(ByteBuffer desc, ByteBuffer cmds, int ncmd, ByteBuffer verts, int nverts);

    static native int fbRead(int tex, int depthKey, int kind, int x, int y, int w, int h, ByteBuffer out);

    static native int texRead(int id, int level, ByteBuffer out);

    static native void fbDepthDelete(int key);

    static native void stats(ByteBuffer out);

    static native void texCreate(int id, int w, int h, int mips);

    static native void texUpload(int id, int level, int x, int y, int w, int h, java.nio.Buffer data);

    static native void texParams(int id, int params);

    static native void texDelete(int id);

    static native void shutdown();

    static native void apiMeshCreate(int id, ByteBuffer data, int count, int topo);

    static native void apiMeshDelete(int id);

    static native long apiDevice();

    static native long apiContext();
}
