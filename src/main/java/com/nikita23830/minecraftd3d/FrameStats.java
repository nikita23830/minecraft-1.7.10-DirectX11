package com.nikita23830.minecraftd3d;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;

import com.nikita23830.minecraftd3d.engine.Immediate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public final class FrameStats {
    private static final long WINDOW = 1_000_000_000L;
    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    private static final boolean ENABLED = !"false".equals(System.getProperty("minecraftd3d.frameStats"));
    private static boolean started;
    private static long lastSwap, winStart, winCpu0, swapStart;
    private static final float[] frames = new float[1 << 15];
    private static final float[] sorted = new float[1 << 15];
    private static int nFrames;
    private static double presentSum, gpuSum;
    private static int gpuN;
    private static long drawSum, immSum;
    private static int lastChunkUpdates, chunkUpdates;

    private static String backend = "";

    private static String describeBackend() {
        try {
            if (PD3D.engine) return D3DNative.adapterInfo();

            return "OpenGL " + GLX.glString(org.lwjgl.opengl.GL11.GL_VERSION) + ", " + GLX.glString(org.lwjgl.opengl.GL11.GL_VENDOR)
                + " — " + GLX.glString(org.lwjgl.opengl.GL11.GL_RENDERER);
        } catch (Throwable t) {
            return PD3D.engine ? "Direct3D 11" : "OpenGL";
        }
    }

    private static volatile String[] lines = { "MinecraftD3D: Frame-Statistik wird gesammelt…" };

    private FrameStats() {}

    public static void start() {
        started = ENABLED;
        backend = describeBackend();
        PD3D.LOG.info("MinecraftD3D: Backend – {}", backend);
    }

    public static void beforeSwap() {
        if (!started) return;
        if (!PD3D.engine) glEndQuery();
        swapStart = System.nanoTime();
    }

    public static void afterSwap() {
        if (!started) return;
        long now = System.nanoTime();
        if (lastSwap == 0) {
            lastSwap = winStart = now;
            winCpu0 = cpu();
            if (!PD3D.engine) glBeginQuery();
            return;
        }
        if (nFrames < frames.length) frames[nFrames++] = (now - lastSwap) / 1e6f;
        lastSwap = now;
        if (PD3D.engine) {
            presentSum += PD3D.statPresentMs;
            gpuSum += PD3D.statGpuMs;
            gpuN++;
            drawSum += PD3D.statDraws;
            immSum += PD3D.statImmDraws;
        } else {
            presentSum += (now - swapStart) / 1e6;
            glBeginQuery();
        }
        int cu = WorldRenderer.chunksUpdated;
        chunkUpdates += cu >= lastChunkUpdates ? cu - lastChunkUpdates : cu;
        lastChunkUpdates = cu;
        if (now - winStart >= WINDOW) summarize(now);
    }

    private static long cpu() {
        try {
            return MX.isCurrentThreadCpuTimeSupported() ? MX.getCurrentThreadCpuTime() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void summarize(long now) {
        int n = Math.max(1, nFrames);
        double sum = 0;
        for (int i = 0; i < nFrames; i++) sum += frames[i];
        System.arraycopy(frames, 0, sorted, 0, nFrames);
        Arrays.sort(sorted, 0, nFrames);
        double winMs = (now - winStart) / 1e6;
        double fps = nFrames * 1000.0 / winMs;
        double avg = sum / n;
        float p99 = nFrames > 0 ? sorted[Math.min(nFrames - 1, (int) (nFrames * 0.99))] : 0;
        long c1 = cpu();
        double cpuMs = winCpu0 >= 0 && c1 >= 0 ? (c1 - winCpu0) / 1e6 / n : -1;
        double present = presentSum / n;
        double gpu = gpuN > 0 ? gpuSum / gpuN : -1;

        String limit;
        if (present > avg * 0.3) limit = "Present/vsync";
        else if (gpu >= 0 && gpu >= (avg - present) * 0.85) limit = "GPU";
        else limit = "CPU";

        Minecraft mc = Minecraft.getMinecraft();
        String l1 = String.format(Locale.ROOT, "§e[%s]§r %.0f fps · Frame %.2f ms, 1%% low %.0f fps (p99 %.2f ms)",
            PD3D.engine ? "D3D11" : "OpenGL", fps, avg, p99 > 0 ? 1000.0 / p99 : 0, p99);
        String l2 = String.format(Locale.ROOT, "CPU Hauptthread %s · GPU %s · Present %.2f ms · Limit: %s",
            cpuMs >= 0 ? String.format(Locale.ROOT, "%.2f ms", cpuMs) : "k. A.",
            gpu >= 0 ? String.format(Locale.ROOT, "%.2f ms (ausgelastet %.0f%%)", gpu, Math.min(100, gpu / avg * 100)) : "k. A.",
            present, limit);
        String l3;
        if (PD3D.engine) {
            int queue = PD3D.sections != null ? PD3D.sections.pending() : 0;
            l3 = String.format(Locale.ROOT, "Draws: Welt %d + Immediate/GUI %d · Sektionen in Warteschlange %d · Sektionen/s %d",
                drawSum / n, immSum / n, queue, chunkUpdates);
        } else {
            Object rg = mc.renderGlobal;
            int lists = rg == null ? -1 : intField(rg, "renderersBeingRendered", "field_72749_I");
            Object q = rg == null ? null : field(rg, "worldRenderersToUpdate", "field_72767_j");
            int queue = q instanceof java.util.Collection ? ((java.util.Collection<?>) q).size() : -1;
            l3 = "Chunks im Frame " + (lists >= 0 ? lists : "k. A.") + " (Display Lists) · Chunk-Warteschlange " + (queue >= 0 ? queue : "k. A.")
                + " · Chunks/s " + chunkUpdates;
        }
        int sep = backend.indexOf(" — ");
        lines = PD3D.engine && sep > 0
            ? new String[] { backend.substring(0, sep), backend.substring(sep + 3), l1, l2, l3 }
            : new String[] { backend, l1, l2, l3 };

        nFrames = 0;
        presentSum = gpuSum = 0;
        gpuN = 0;
        drawSum = immSum = 0;
        chunkUpdates = 0;
        winStart = now;
        winCpu0 = c1;
    }

    private static final java.util.HashMap<String, java.lang.reflect.Field> fields = new java.util.HashMap<String, java.lang.reflect.Field>();

    private static Object field(Object o, String... names) {
        String key = o.getClass().getName() + "#" + names[0];
        java.lang.reflect.Field f = fields.get(key);
        if (f == null && !fields.containsKey(key)) {
            for (Class<?> c = o.getClass(); c != null && f == null; c = c.getSuperclass())
                for (String n : names) {
                    try {
                        f = c.getDeclaredField(n);
                        f.setAccessible(true);
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            fields.put(key, f);
        }
        try {
            return f == null ? null : f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String... names) {
        Object v = field(o, names);
        return v instanceof Integer ? (Integer) v : -1;
    }

    private static final int RING = 4;
    private static final int[] queries = new int[RING];
    private static int qHead, qTail, qPending;
    private static boolean qOpen, qInit, qOk;

    private static void glBeginQuery() {
        if (!qInit) {
            qInit = true;
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                qOk = caps.OpenGL33 || caps.GL_ARB_timer_query;
                if (qOk) for (int i = 0; i < RING; i++) queries[i] = GL15.glGenQueries();
            } catch (Throwable t) {
                qOk = false;
            }
        }
        if (!qOk || qOpen || qPending >= RING) return;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[qHead]);
        qHead = (qHead + 1) % RING;
        qOpen = true;
    }

    private static void glEndQuery() {
        if (!qOk) return;
        if (qOpen) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            qOpen = false;
            qPending++;
        }
        while (qPending > 0) {
            int id = queries[qTail];
            if (GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break;
            gpuSum += GL33.glGetQueryObjectui64(id, GL15.GL_QUERY_RESULT) / 1e6;
            gpuN++;
            qTail = (qTail + 1) % RING;
            qPending--;
        }
    }

    public static final class Overlay {
        private static final long REBUILD = 200_000_000L;
        private static final boolean CACHE = !"false".equals(System.getProperty("minecraftd3d.f3cache"));
        private static long tCall, tBuild, nCall, nBuild, tLog;
        private static int draws;

        public static String summary() {
            long frames = nCall + nBuild;
            return String.format(java.util.Locale.ROOT,
                "F3 (Java-Teil, eigene Messung): im Mittel %.1f µs/Frame; Frame aus Cache %.1f µs (%d Frames), Neuaufbau %.1f µs (%d-mal), Draws im Cache %d",
                frames > 0 ? (tCall + tBuild) / 1e3 / frames : 0, nCall > 0 ? tCall / 1e3 / nCall : 0, nCall, nBuild > 0 ? tBuild / 1e3 / nBuild : 0, nBuild, draws);
        }
        private long lastBuild;
        private int list, cacheW, cacheH;
        private Immediate.Snapshot snap;
        private boolean cached;

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onPre(RenderGameOverlayEvent.Pre ev) {
            if (ev.type != RenderGameOverlayEvent.ElementType.DEBUG) return;
            cached = CACHE && (list != 0 || snap != null) && System.nanoTime() - lastBuild < REBUILD && cacheW == ev.resolution.getScaledWidth()
                && cacheH == ev.resolution.getScaledHeight();
            if (cached) ev.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onText(RenderGameOverlayEvent.Text ev) {
            Minecraft mc = Minecraft.getMinecraft();
            if (!mc.gameSettings.showDebugInfo) return;
            ev.setCanceled(true);
            long t0 = System.nanoTime();
            if (cached) {
                if (snap != null) Immediate.replay(snap);
                else GLX.callList(list);
                tCall += System.nanoTime() - t0;
                nCall++;
                return;
            }
            draws = 0;
            String[] l = lines;
            int at = Math.min(1, ev.left.size());
            for (int i = 0; i < l.length; i++) ev.left.add(at + i, l[i]);
            FontBatch b = FontBatch.get();
            b.record = new java.util.ArrayList<FontBatch.Segment>();
            b.recordOnly = true;
            net.minecraft.client.gui.FontRenderer fr = mc.fontRenderer;
            int width = ev.resolution.getScaledWidth();
            java.util.ArrayList<FontBatch.Segment> segs;
            try {
                for (int x = 0; x < ev.left.size(); x++) {
                    String msg = ev.left.get(x);
                    if (msg != null) fr.drawStringWithShadow(msg, 2, 2 + x * 10, 0xFFFFFF);
                }
                for (int x = 0; x < ev.right.size(); x++) {
                    String msg = ev.right.get(x);
                    if (msg != null) fr.drawStringWithShadow(msg, width - fr.getStringWidth(msg) - 10, 2 + x * 10, 0xFFFFFF);
                }
            } finally {
                segs = b.record;
                b.record = null;
                b.recordOnly = false;
            }
            if (segs.isEmpty()) return;

            java.util.ArrayList<Object[]> groups = new java.util.ArrayList<Object[]>();
            for (int pass = 0; pass < 2; pass++) {
                java.util.LinkedHashMap<net.minecraft.util.ResourceLocation, float[]> byTex = new java.util.LinkedHashMap<net.minecraft.util.ResourceLocation, float[]>();
                java.util.HashMap<net.minecraft.util.ResourceLocation, Integer> counts = new java.util.HashMap<net.minecraft.util.ResourceLocation, Integer>();
                for (FontBatch.Segment seg : segs) {
                    if (seg.shadow != (pass == 0)) continue;
                    float[] acc = byTex.get(seg.tex);
                    int c = acc == null ? 0 : counts.get(seg.tex);
                    if (acc == null) acc = new float[seg.count * 5 * 4];
                    if ((c + seg.count) * 5 > acc.length) acc = java.util.Arrays.copyOf(acc, Math.max(acc.length * 2, (c + seg.count) * 5));
                    System.arraycopy(seg.verts, 0, acc, c * 5, seg.count * 5);
                    byTex.put(seg.tex, acc);
                    counts.put(seg.tex, c + seg.count);
                }
                for (java.util.Map.Entry<net.minecraft.util.ResourceLocation, float[]> e : byTex.entrySet())
                    groups.add(new Object[] { e.getKey(), e.getValue(), counts.get(e.getKey()) });
            }
            if (PD3D.engine) {
                int c0 = Immediate.commandMark(), v0 = Immediate.vertexMark();
                emitGroups(mc, groups);
                snap = Immediate.capture(c0, v0);
            } else {
                if (list == 0) list = GLX.genLists(1);
                GLX.newList(list);
                emitGroups(mc, groups);
                GLX.endList();
                GLX.callList(list);
            }
            lastBuild = System.nanoTime();
            tBuild += lastBuild - t0;
            nBuild++;
            cacheW = width;
            cacheH = ev.resolution.getScaledHeight();
        }

        private static void emitGroups(Minecraft mc, java.util.ArrayList<Object[]> groups) {
            for (Object[] g : groups) {
                mc.getTextureManager().bindTexture((net.minecraft.util.ResourceLocation) g[0]);
                FontBatch.emit((float[]) g[1], (Integer) g[2]);
                draws++;
            }
        }
    }
}
