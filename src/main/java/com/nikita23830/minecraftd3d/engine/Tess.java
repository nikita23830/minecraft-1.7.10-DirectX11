package com.nikita23830.minecraftd3d.engine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

import net.minecraft.client.renderer.Tessellator;

import com.nikita23830.minecraftd3d.PD3D;

public final class Tess {
    private Tess() {}

    static final class Worker {
        final Tessellator t = new Tessellator();
        int[] acc = new int[8 * 8192];
        int accVerts;
        int pass = -1;
    }

    private static final ThreadLocal<Worker> LOCAL = new ThreadLocal<Worker>();
    public static volatile int offThreadDraws, nonQuadFlushes;

    private static final Tessellator MAIN = mainInstance();

    private static final MethodHandle GTNHLIB_GET = gtnhlibGet();

    private static Tessellator mainInstance() {
        for (String n : new String[] { "field_78398_a", "instance" }) {
            try {
                return (Tessellator) Tessellator.class.getField(n).get(null);
            } catch (ReflectiveOperationException ignored) {}
        }
        throw new IllegalStateException("Tessellator.instance nicht gefunden");
    }

    private static MethodHandle gtnhlibGet() {
        try {
            Class<?> c = Class.forName("com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager", false, Tess.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(c, "get", MethodType.methodType(Tessellator.class));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Tessellator loader;

    public static Tessellator instance() {
        if (PD3D.isLoadingThread()) {
            if (loader == null) loader = new Tessellator();
            return loader;
        }
        if (PD3D.isMainThread()) {
            if (GTNHLIB_GET == null) return MAIN;
            try {
                return (Tessellator) GTNHLIB_GET.invokeExact();
            } catch (Throwable t) {
                return MAIN;
            }
        }
        Worker w = LOCAL.get();
        return w != null ? w.t : MAIN;
    }

    public static boolean isMesherThread() {
        return !PD3D.isMainThread() && LOCAL.get() != null;
    }

    public static Tessellator worker() {
        Worker w = LOCAL.get();
        if (w == null) LOCAL.set(w = new Worker());
        return w.t;
    }

    public static boolean isWorker(Tessellator t) {
        if (PD3D.isMainThread()) return false;
        Worker w = LOCAL.get();
        return w != null && w.t == t;
    }

    public static int meshPass() {
        if (PD3D.isMainThread()) return -1;
        Worker w = LOCAL.get();
        return w != null ? w.pass : -1;
    }

    public static void beginPass(int pass) {
        Worker w = LOCAL.get();
        w.pass = pass;
        w.accVerts = 0;
    }

    public static void endPass() {
        LOCAL.get().pass = -1;
    }

    public static void workerFlush(Tessellator t) {
        ITess it = (ITess) t;
        Worker w = LOCAL.get();
        int n = it.pd3d$vertexCount();
        if (n == 0) return;
        if (it.pd3d$drawMode() != 7) { nonQuadFlushes++; return; }
        if ((w.accVerts + n) * 8 > w.acc.length) w.acc = Arrays.copyOf(w.acc, Math.max(w.acc.length * 2, (w.accVerts + n) * 8));
        int[] raw = it.pd3d$raw();
        System.arraycopy(raw, 0, w.acc, w.accVerts * 8, n * 8);
        boolean col = it.pd3d$hasColor(), br = it.pd3d$hasBrightness();
        if (!col || !br) {
            for (int i = w.accVerts; i < w.accVerts + n; i++) {
                if (!col) w.acc[i * 8 + 5] = -1;
                if (!br) w.acc[i * 8 + 7] = 0x00F000F0;
            }
        }
        w.accVerts += n;
    }

    static int[] collect(Tessellator t) {
        ITess it = (ITess) t;
        if (it.pd3d$isDrawing()) {
            workerFlush(t);
            it.pd3d$finish();
        }
        return LOCAL.get().acc;
    }

    public static int[] collectPass(Tessellator t) {
        return collect(t);
    }

    public static void truncateCollected(int n) {
        LOCAL.get().accVerts = n;
    }

    public static int collectedVerts() {
        return LOCAL.get().accVerts;
    }
}
