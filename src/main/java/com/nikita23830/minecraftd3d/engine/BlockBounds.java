package com.nikita23830.minecraftd3d.engine;

import java.lang.reflect.Field;

import net.minecraft.block.Block;

import com.nikita23830.minecraftd3d.PD3D;

public final class BlockBounds {
    private BlockBounds() {}

    public static final boolean ON = !"false".equals(System.getProperty("minecraftd3d.safeBounds"));
    private static final sun.misc.Unsafe U;
    private static final long O0, O1, O2, O3, O4, O5;

    private static volatile java.util.IdentityHashMap<Object, double[]> ref;

    static {
        sun.misc.Unsafe u = null;
        long[] o = new long[6];
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            u = (sun.misc.Unsafe) uf.get(null);
            String[] srg = { "field_149759_B", "field_149760_C", "field_149754_D", "field_149755_E", "field_149756_F", "field_149757_G" };
            String[] mcp = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
            for (int i = 0; i < 6; i++) o[i] = u.objectFieldOffset(field(srg[i], mcp[i]));
        } catch (Exception e) {
            throw new RuntimeException("MinecraftD3D: Block-Grenzfelder nicht gefunden", e);
        }
        U = u;
        O0 = o[0]; O1 = o[1]; O2 = o[2]; O3 = o[3]; O4 = o[4]; O5 = o[5];
        PD3D.LOG.info("MinecraftD3D: Blockgrenzen in Meshing-Threads – {}", ON ? "eigene pro Thread" : "gemeinsam (wie Vanilla)");
    }

    private static Field field(String srg, String mcp) throws NoSuchFieldException {
        try {
            return Block.class.getDeclaredField(srg);
        } catch (NoSuchFieldException e) {
            return Block.class.getDeclaredField(mcp);
        }
    }

    private static long off(int i) {
        switch (i) {
            case 0: return O0;
            case 1: return O1;
            case 2: return O2;
            case 3: return O3;
            case 4: return O4;
            default: return O5;
        }
    }

    private static double[] fields(Object b) {
        return new double[] { U.getDouble(b, O0), U.getDouble(b, O1), U.getDouble(b, O2), U.getDouble(b, O3), U.getDouble(b, O4), U.getDouble(b, O5) };
    }

    public static void snapshot() {
        java.util.IdentityHashMap<Object, double[]> m = new java.util.IdentityHashMap<Object, double[]>();
        for (Object b : Block.blockRegistry) m.put(b, fields(b));
        ref = m;
        PD3D.LOG.info("MinecraftD3D: Referenzgrenzen für {} Blöcke", m.size());
    }

    private static double[] local(MeshThread t, Object b) {
        double[] a = t.bounds.get(b);
        if (a == null) {
            java.util.IdentityHashMap<Object, double[]> r = ref;
            double[] e = r != null ? r.get(b) : null;
            a = e != null ? e.clone() : fields(b);
            t.bounds.put(b, a);
        }
        return a;
    }

    private static double get(Object b, int i) {
        if (ON) {
            Thread t = Thread.currentThread();
            if (t instanceof MeshThread) return local((MeshThread) t, b)[i];
        }
        return U.getDouble(b, off(i));
    }

    private static void set(Object b, int i, double v) {
        if (ON) {
            Thread t = Thread.currentThread();
            if (t instanceof MeshThread) {
                local((MeshThread) t, b)[i] = v;
                return;
            }
        }
        U.putDouble(b, off(i), v);
    }

    private static final class Anvil {
        static final long OFF;
        static {
            try {
                Class<?> c = Class.forName("net.minecraft.block.BlockAnvil");
                Field f;
                try {
                    f = c.getDeclaredField("field_149833_b");
                } catch (NoSuchFieldException e) {
                    f = c.getDeclaredField("anvilRenderSide");
                }
                OFF = U.objectFieldOffset(f);
            } catch (Exception e) {
                throw new RuntimeException("MinecraftD3D: BlockAnvil.field_149833_b nicht gefunden", e);
            }
        }
    }

    public static int anvilGet(Object b) {
        if (ON) {
            Thread t = Thread.currentThread();
            if (t instanceof MeshThread) return ((MeshThread) t).anvilSide;
        }
        return U.getInt(b, Anvil.OFF);
    }

    public static void anvilSet(Object b, int v) {
        if (ON) {
            Thread t = Thread.currentThread();
            if (t instanceof MeshThread) { ((MeshThread) t).anvilSide = v; return; }
        }
        U.putInt(b, Anvil.OFF, v);
    }

    public static double get0(Block b) { return get(b, 0); }
    public static double get1(Block b) { return get(b, 1); }
    public static double get2(Block b) { return get(b, 2); }
    public static double get3(Block b) { return get(b, 3); }
    public static double get4(Block b) { return get(b, 4); }
    public static double get5(Block b) { return get(b, 5); }
    public static void set0(Block b, double v) { set(b, 0, v); }
    public static void set1(Block b, double v) { set(b, 1, v); }
    public static void set2(Block b, double v) { set(b, 2, v); }
    public static void set3(Block b, double v) { set(b, 3, v); }
    public static void set4(Block b, double v) { set(b, 4, v); }
    public static void set5(Block b, double v) { set(b, 5, v); }
}
