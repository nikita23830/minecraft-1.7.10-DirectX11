package com.nikita23830.minecraftd3d.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

import com.nikita23830.minecraftd3d.PD3D;

public final class DL {
    private DL() {}

    static final boolean MESHES = !"false".equals(System.getProperty("minecraftd3d.meshLists"));

    private static final int MESH_BASE = 0x40000000;
    private static int nextMesh = MESH_BASE;

    private static final class Geo {
        final int[] raw; final int n, mode; final boolean tex, col, nrm, bright;
        Geo(int[] raw, int n, int mode, boolean tex, boolean col, boolean nrm, boolean bright) {
            this.raw = raw; this.n = n; this.mode = mode; this.tex = tex; this.col = col; this.nrm = nrm; this.bright = bright;
        }
    }

    static final class Group {
        final int mesh, count, topo; final boolean tex, col, nrm;
        Group(int mesh, int count, int topo, boolean tex, boolean col, boolean nrm) {
            this.mesh = mesh; this.count = count; this.topo = topo; this.tex = tex; this.col = col; this.nrm = nrm;
        }
    }

    private static final class List {
        final Runnable[] ops;
        final Group[] groups;
        List(Runnable[] ops, Group[] groups) { this.ops = ops; this.groups = groups; }
    }

    private static final HashMap<Integer, List> lists = new HashMap<Integer, List>();
    private static int next = 1;

    static ArrayList<Runnable> rec;
    private static ArrayList<Geo> recGeo;
    private static int recId;
    private static boolean recExecute;
    private static int callDepth;
    public static int calls, ops, meshCalls, meshGroups;

    static int gen(int range) {
        int base = next;
        next += Math.max(1, range);
        return base;
    }

    static int recordingId() {
        return rec != null ? recId : 0;
    }

    static int recordingMode() {
        return rec == null ? 0 : recExecute ? 0x1301 : 0x1300;
    }

    static void newList(int id, int mode) {
        if (rec != null) {
            RS.setError(0x0502);
            GLEmu.warnOnce("verschachteltes glNewList (ignoriert, GL_INVALID_OPERATION)");
            return;
        }
        rec = new ArrayList<Runnable>();
        recGeo = new ArrayList<Geo>();
        recId = id;
        recExecute = mode == 0x1301;
    }

    static void endList() {
        if (rec == null) { RS.setError(0x0502); return; }
        Runnable[] r = rec.toArray(new Runnable[0]);
        Group[] g = MESHES && recGeo.size() == r.length && !recGeo.isEmpty() ? upload(recGeo) : null;
        List old = lists.put(recId, new List(r, g));
        if (old != null) release(old);
        rec = null;
        recGeo = null;
    }

    static void add(Runnable r) {
        rec.add(r);
        if (recExecute) {
            ArrayList<Runnable> saved = rec;
            rec = null;
            try { r.run(); } finally { rec = saved; }
        }
    }

    static void addGeometry(int[] raw, int n, int mode, boolean tex, boolean col, boolean nrm, boolean bright) {
        if (recGeo != null) recGeo.add(new Geo(raw, n, mode, tex, col, nrm, bright));
    }

    static void call(int id) {
        List l = lists.get(id);
        if (l == null) return;
        if (callDepth > 64) { GLEmu.warnOnce("glCallList-Tiefe > 64"); return; }
        callDepth++;
        calls++;
        try {
            if (l.groups != null && meshesUsable(l.groups)) {
                meshCalls++;
                for (Group g : l.groups) {
                    Immediate.submitMesh(g.mesh, g.count, g.topo, g.tex, g.col);
                    meshGroups++;
                }
                return;
            }
            DL.ops += l.ops.length;
            for (Runnable r : l.ops) r.run();
        } finally {
            callDepth--;
        }
    }

    private static boolean meshesUsable(Group[] groups) {
        if (rec != null || RS.texGenAny) return false;
        for (Group g : groups) {
            if (g.tex && RS.texture2D && !RS.textureMatrixIdentity) return false;
            if (!g.nrm && RS.lighting) return false;
        }
        return true;
    }

    static void delete(int id, int range) {
        for (int i = 0; i < range; i++) {
            List l = lists.remove(id + i);
            if (l != null) release(l);
        }
    }

    private static void release(List l) {
        if (l.groups == null) return;
        for (Group g : l.groups) PD3D.apiMeshDelete(g.mesh);
    }

    static boolean exists(int id) {
        return lists.containsKey(id);
    }

    public static int count() {
        return lists.size();
    }

    private static Group[] upload(ArrayList<Geo> geo) {
        ArrayList<Group> out = new ArrayList<Group>();
        int i = 0;
        while (i < geo.size()) {
            Geo first = geo.get(i);
            int topo = topology(first.mode);
            if (topo < 0 || first.bright) return null;
            int j = i, verts = 0;
            while (j < geo.size()) {
                Geo g = geo.get(j);
                if (topology(g.mode) != topo || g.tex != first.tex || g.col != first.col || g.nrm != first.nrm || g.bright) break;
                verts += outCount(g.mode, g.n);
                j++;
            }
            if (verts > 0) {
                ByteBuffer b = ByteBuffer.allocateDirect(verts * 28).order(ByteOrder.nativeOrder());
                for (int k = i; k < j; k++) {
                    Geo g = geo.get(k);
                    int oc = outCount(g.mode, g.n);
                    for (int v = 0; v < oc; v++) {
                        int r = Immediate.sourceIndex(g.mode, v, g.n) * 8;
                        b.putFloat(Float.intBitsToFloat(g.raw[r])).putFloat(Float.intBitsToFloat(g.raw[r + 1])).putFloat(Float.intBitsToFloat(g.raw[r + 2]));
                        b.putFloat(g.tex ? Float.intBitsToFloat(g.raw[r + 3]) : 0f).putFloat(g.tex ? Float.intBitsToFloat(g.raw[r + 4]) : 0f);
                        b.putInt(g.col ? g.raw[r + 5] : -1);
                        int pn = g.raw[r + 6];
                        b.putInt(g.nrm ? Immediate.packNormalUnit((byte) pn / 127f, (byte) (pn >> 8) / 127f, (byte) (pn >> 16) / 127f) : 0);
                    }
                }
                b.flip();
                int id = nextMesh++;
                PD3D.apiMeshCreate(id, b, verts, topo);
                out.add(new Group(id, verts, topo, first.tex, first.col, first.nrm));
            }
            i = j;
        }
        return out.toArray(new Group[0]);
    }

    private static int topology(int mode) {
        switch (mode) {
            case 7: case 8: return 0;
            case 4: case 5: case 6: case 9: return 1;
            default: return -1;
        }
    }

    private static int outCount(int mode, int n) {
        switch (mode) {
            case 7: return n - n % 4;
            case 8: return Math.max(0, (n / 2 - 1) * 4);
            case 4: return n - n % 3;
            default: return Math.max(0, n - 2) * 3;
        }
    }
}
