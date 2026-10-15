package com.nikita23830.minecraftd3d;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.nikita23830.minecraftd3d.engine.ITess;
import com.nikita23830.minecraftd3d.engine.MeshThread;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.nikita23830.minecraftd3d.engine.Tess;

public final class SectionManager {
    static final int VERTEX_BYTES = 20;
    private static final long UPLOAD_BUDGET = 32L << 20;

    private final int threads;
    private final ThreadPoolExecutor pool;
    private final List<Thread> workerThreads = new ArrayList<Thread>();

    private final HashSet<Long> dirty = new HashSet<Long>();
    private final HashSet<Long> inflight = new HashSet<Long>();
    private final HashSet<Long> redo = new HashSet<Long>();
    private final HashSet<Long> uploaded = new HashSet<Long>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<Result>();
    private WorldClient world;
    private volatile int generation;

    public final AtomicLong meshNanos = new AtomicLong();
    public final AtomicLong meshedSections = new AtomicLong();
    public final AtomicLong meshedVerts = new AtomicLong();
    public final AtomicInteger meshErrors = new AtomicInteger();
    public long uploadNanosTotal, uploadBytesTotal;
    public int dirtyInRange;

    private static final class Result {
        long key; int x, y, z, gen, opaque, trans; ByteBuffer buf; boolean failed;

        int[] tiles; int nTiles;
    }

    public SectionManager(int threads) {
        this.threads = threads;
        this.pool = new ThreadPoolExecutor(threads, threads, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            private int n;
            public synchronized Thread newThread(Runnable r) {
                Thread t = new MeshThread(r, "MinecraftD3D-mesher-" + (n++));
                t.setDaemon(true);
                synchronized (workerThreads) { workerThreads.add(t); }
                return t;
            }
        });
        pool.prestartAllCoreThreads();
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 34) | ((long) (z & 0x3FFFFFF) << 8) | (y & 0xFF);
    }

    static int kx(long k) { return ((int) ((k >>> 34) & 0x3FFFFFF) << 6) >> 6; }
    static int kz(long k) { return ((int) ((k >>> 8) & 0x3FFFFFF) << 6) >> 6; }
    static int ky(long k) { return (int) (k & 0xFF); }

    public void markDirty(int x1, int y1, int z1, int x2, int y2, int z2) {
        int sy1 = Math.max(0, y1 >> 4), sy2 = Math.min(15, y2 >> 4);
        for (int sx = x1 >> 4; sx <= x2 >> 4; sx++)
            for (int sz = z1 >> 4; sz <= z2 >> 4; sz++)
                for (int sy = sy1; sy <= sy2; sy++) {
                    long k = key(sx, sy, sz);
                    if (inflight.contains(k)) redo.add(k);
                    else dirty.add(k);
                }
    }

    public void markAllLoaded(WorldClient w) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        int cx = (int) Math.floor(mc.thePlayer.posX) >> 4, cz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        int rd = mc.gameSettings.renderDistanceChunks + 1;
        for (int x = cx - rd; x <= cx + rd; x++)
            for (int z = cz - rd; z <= cz + rd; z++)
                if (!(w.getChunkFromChunkCoords(x, z) instanceof EmptyChunk)) markDirty(x * 16, 0, z * 16, x * 16 + 15, 255, z * 16 + 15);
    }

    public void onChunkUnload(int cx, int cz) {
        for (int sy = 0; sy < 16; sy++) {
            long k = key(cx, sy, cz);
            dirty.remove(k);
            redo.remove(k);
            if (uploaded.remove(k)) PD3D.sectionSet(cx, sy, cz, null, 0, 0);
        }
    }

    public void reset(WorldClient w) {
        generation++;
        world = w;
        dirty.clear(); redo.clear(); uploaded.clear(); inflight.clear();
        Result r;
        while ((r = results.poll()) != null) BufferPool.release(r.buf);
        PD3D.clearSections();
    }

    public String debugState() {
        return "dirty=" + dirty.size() + " imRadius=" + dirtyInRange + " inflight=" + inflight.size() + " redo=" + redo.size()
            + " results=" + results.size() + " uploaded=" + uploaded.size() + " Meshes=" + meshedSections.get() + " Fehler=" + meshErrors.get();
    }

    public int pending() {
        return inflight.size() + results.size() + dirtyInRange;
    }

    public long workerCpuNanos() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long sum = 0;
        synchronized (workerThreads) {
            for (Thread t : workerThreads) {
                long v = mx.getThreadCpuTime(t.getId());
                if (v > 0) sum += v;
            }
        }
        return sum;
    }

    public void update(WorldClient w, double cx, double cy, double cz, int rd) {
        if (w != world) reset(w);
        long t0 = System.nanoTime();
        long budget = UPLOAD_BUDGET;
        Result r;
        while (budget > 0 && (r = results.poll()) != null) {
            inflight.remove(r.key);
            if (r.gen == generation) {
                if (r.failed) {
                    dirty.add(r.key);
                } else if (world.getChunkFromChunkCoords(r.x, r.z) instanceof EmptyChunk) {
                } else if (r.opaque + r.trans == 0) {
                    if (uploaded.remove(r.key)) PD3D.sectionSet(r.x, r.y, r.z, null, 0, 0);
                } else {
                    PD3D.sectionSet(r.x, r.y, r.z, r.buf, r.opaque, r.trans);
                    net.minecraft.client.renderer.WorldRenderer.chunksUpdated++;
                    uploaded.add(r.key);
                    long bytes = (long) (r.opaque + r.trans) * VERTEX_BYTES;
                    budget -= bytes;
                    uploadBytesTotal += bytes;
                }

                boolean created = false;
                for (int i = 0; i < r.nTiles; i++) {
                    int tx = r.tiles[i * 3], ty = r.tiles[i * 3 + 1], tz = r.tiles[i * 3 + 2];
                    Chunk ch = world.getChunkFromBlockCoords(tx, tz);
                    boolean had = ch.chunkTileEntityMap.containsKey(new net.minecraft.world.ChunkPosition(tx & 15, ty, tz & 15));
                    if (world.getTileEntity(tx, ty, tz) != null && !had) created = true;
                }
                if (created) redo.add(r.key);
                if (redo.remove(r.key)) dirty.add(r.key);
            }
            BufferPool.release(r.buf);
        }
        uploadNanosTotal += System.nanoTime() - t0;

        int csx = (int) Math.floor(cx / 16.0), csy = (int) Math.floor(cy / 16.0), csz = (int) Math.floor(cz / 16.0);
        int free = threads * 3 - inflight.size();
        int inRange = 0;
        if (!dirty.isEmpty()) {
            int cap = Math.max(free, 0);
            long[] bestK = new long[cap];
            int[] bestD = new int[cap];
            int n = 0;
            for (Iterator<Long> it = dirty.iterator(); it.hasNext(); ) {
                long k = it.next();
                int dx = kx(k) - csx, dz = kz(k) - csz;
                if (dx < -rd - 1 || dx > rd + 1 || dz < -rd - 1 || dz > rd + 1) continue;
                inRange++;
                if (cap == 0) continue;
                int dy = ky(k) - csy;
                int d = dx * dx + dy * dy + dz * dz;
                if (n < cap) {
                    int i = n++;
                    while (i > 0 && bestD[i - 1] > d) { bestD[i] = bestD[i - 1]; bestK[i] = bestK[i - 1]; i--; }
                    bestD[i] = d; bestK[i] = k;
                } else if (d < bestD[n - 1]) {
                    int i = n - 1;
                    while (i > 0 && bestD[i - 1] > d) { bestD[i] = bestD[i - 1]; bestK[i] = bestK[i - 1]; i--; }
                    bestD[i] = d; bestK[i] = k;
                }
            }
            for (int i = 0; i < n; i++) {
                dirty.remove(bestK[i]);
                inRange--;
                submit(bestK[i]);
            }
        }
        dirtyInRange = inRange;
    }

    private void submit(long k) {
        final int x = kx(k), y = ky(k), z = kz(k);
        Chunk chunk = world.getChunkFromChunkCoords(x, z);
        if (chunk instanceof EmptyChunk) return;
        ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[y];
        if (ebs == null || ebs.isEmpty()) {
            if (uploaded.remove(k)) PD3D.sectionSet(x, y, z, null, 0, 0);
            return;
        }
        final ChunkCache cache = new ChunkCache(world, x * 16 - 1, y * 16 - 1, z * 16 - 1, x * 16 + 16, y * 16 + 16, z * 16 + 16, 1);
        final Result res = new Result();
        res.key = k; res.x = x; res.y = y; res.z = z; res.gen = generation;
        inflight.add(k);
        pool.execute(new Runnable() {
            public void run() {
                long t0 = System.nanoTime();
                try {
                    mesh(cache, res);
                } catch (Throwable t) {
                    res.failed = true;
                    if (res.buf != null) { BufferPool.release(res.buf); res.buf = null; }
                    if (meshErrors.incrementAndGet() <= 5) PD3D.LOG.warn("Meshing-Fehler in Sektion {} {} {}", x, y, z, t);
                } finally {
                    Tess.endPass();
                }
                meshNanos.addAndGet(System.nanoTime() - t0);
                meshedSections.incrementAndGet();
                results.add(res);
            }
        });
    }

    public static final boolean STATS = Boolean.getBoolean("minecraftd3d.blockStats");
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLongArray> BLOCK_STATS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final ThreadLocal<ByteBuffer> SCRATCH = new ThreadLocal<ByteBuffer>();

    private void mesh(ChunkCache cache, Result res) {
        Tessellator t = Tess.worker();
        RenderBlocks rb = new RenderBlocks(cache);
        int x0 = res.x * 16, y0 = res.y * 16, z0 = res.z * 16;
        ByteBuffer out = SCRATCH.get();
        if (out == null) { out = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder()); SCRATCH.set(out); }
        out.clear();
        int[] counts = new int[2];
        boolean needPass1 = false;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && !needPass1) break;
            Tess.beginPass(pass);
            t.startDrawingQuads();
            t.setTranslation(-x0, -y0, -z0);
            for (int by = y0; by < y0 + 16; by++)
                for (int bz = z0; bz < z0 + 16; bz++)
                    for (int bx = x0; bx < x0 + 16; bx++) {
                        Block block = cache.getBlock(bx, by, bz);
                        if (block.getMaterial() == Material.air) continue;
                        if (pass == 0 && block.hasTileEntity(cache.getBlockMetadata(bx, by, bz))) {
                            if (res.tiles == null) res.tiles = new int[48];
                            if (res.nTiles * 3 + 3 > res.tiles.length) res.tiles = java.util.Arrays.copyOf(res.tiles, res.tiles.length * 2);
                            res.tiles[res.nTiles * 3] = bx; res.tiles[res.nTiles * 3 + 1] = by; res.tiles[res.nTiles * 3 + 2] = bz;
                            res.nTiles++;
                        }
                        if (block.getRenderBlockPass() > pass) needPass1 = true;
                        if (!block.canRenderInPass(pass)) continue;
                        if (STATS) {
                            int before = Tess.collectedVerts() + ((ITess) t).pd3d$vertexCount();
                            boolean drawn = renderSafe(rb, t, block, bx, by, bz);
                            int after = Tess.collectedVerts() + ((ITess) t).pd3d$vertexCount();
                            java.util.concurrent.atomic.AtomicLongArray a = BLOCK_STATS.computeIfAbsent(block.getClass().getName() + " rt=" + block.getRenderType(),
                                k -> new java.util.concurrent.atomic.AtomicLongArray(3));
                            a.incrementAndGet(0);
                            if (drawn) a.incrementAndGet(1);
                            a.addAndGet(2, after - before);
                        } else renderSafe(rb, t, block, bx, by, bz);
                    }
            int[] raw = Tess.collectPass(t);
            int n = Tess.collectedVerts();
            if (out.remaining() < n * VERTEX_BYTES) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(out.capacity() * 2, out.position() + n * VERTEX_BYTES + (1 << 16))).order(ByteOrder.nativeOrder());
                out.flip();
                bigger.put(out);
                out = bigger;
                SCRATCH.set(out);
            }
            pack(raw, n, out);
            counts[pass] = n;
        }
        t.setTranslation(0, 0, 0);
        int bytes = out.position();
        res.opaque = counts[0];
        res.trans = counts[1];
        if (bytes > 0) {
            ByteBuffer dst = BufferPool.acquire(bytes);
            out.flip();
            dst.put(out);
            dst.flip();
            res.buf = dst;
        }
        meshedVerts.addAndGet(counts[0] + counts[1]);
    }

    private static final String SERIAL_DEFAULT = "";
    private static final String[] SERIAL = System.getProperty("minecraftd3d.serialRender", SERIAL_DEFAULT).isEmpty()
        ? new String[0] : System.getProperty("minecraftd3d.serialRender", SERIAL_DEFAULT).split(",");
    private static final Object SERIAL_LOCK = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> SERIAL_RT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Class<?>> SERIAL_CLASS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());
    private static final java.util.Set<Class<?>> CHECKED_CLASS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());
    public static final java.util.concurrent.atomic.AtomicInteger blockErrors = new java.util.concurrent.atomic.AtomicInteger();
    private static java.util.Map<?, ?> isbrhMap;

    private static boolean matchesSerial(String name) {
        for (String s : SERIAL) if (!s.isEmpty() && name.contains(s.trim())) return true;
        return false;
    }

    private static Object isbrh(int rt) {
        try {
            if (isbrhMap == null) {
                Class<?> c = Class.forName("cpw.mods.fml.client.registry.RenderingRegistry");
                Object reg = c.getMethod("instance").invoke(null);
                java.lang.reflect.Field f = c.getDeclaredField("blockRenderers");
                f.setAccessible(true);
                isbrhMap = (java.util.Map<?, ?>) f.get(reg);
            }
            return isbrhMap.get(rt);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean serial(Block block, int rt) {
        Class<?> bc = block.getClass();
        if (SERIAL_CLASS.contains(bc)) return true;
        if (CHECKED_CLASS.add(bc) && matchesSerial(bc.getName())) { SERIAL_CLASS.add(bc); return true; }
        Boolean f = SERIAL_RT.get(rt);
        if (f == null) {
            Object h = isbrh(rt);
            f = h != null && matchesSerial(h.getClass().getName());
            SERIAL_RT.put(rt, f);
            if (f) PD3D.LOG.info("MinecraftD3D: Renderer {} (Typ {}) – in Meshing-Threads einzeln (serialRender)", h.getClass().getName(), rt);
        }
        return f;
    }

    private static boolean renderSafe(RenderBlocks rb, Tessellator t, Block block, int x, int y, int z) {
        int rt = block.getRenderType();
        if (serial(block, rt)) {
            synchronized (SERIAL_LOCK) {
                return renderGuarded(rb, t, block, rt, x, y, z);
            }
        }
        return renderGuarded(rb, t, block, rt, x, y, z);
    }

    private static boolean renderGuarded(RenderBlocks rb, Tessellator t, Block block, int rt, int x, int y, int z) {
        ITess it = (ITess) t;
        int acc0 = Tess.collectedVerts(), v0 = it.pd3d$vertexCount();
        try {
            boolean drawn = rb.renderBlockByRenderType(block, x, y, z);
            return drawn;
        } catch (Throwable e) {
            if (!it.pd3d$isDrawing() || it.pd3d$drawMode() != 7) {
                if (it.pd3d$isDrawing()) it.pd3d$finish();
                t.startDrawingQuads();
            }
            int acc = Tess.collectedVerts();
            if (acc != acc0) {
                Tess.truncateCollected(Math.min(acc, acc0 + v0));
                it.pd3d$truncate(0);
            } else it.pd3d$truncate(Math.min(v0, it.pd3d$vertexCount()));
            Object h = isbrh(rt);
            if (SERIAL_CLASS.add(block.getClass()) || blockErrors.get() < 5)
                PD3D.LOG.warn("MinecraftD3D: Block {} (Renderer {}, Typ {}) beim Meshing bei {} {} {} abgestürzt – übersprungen, wird ab jetzt einzeln gezeichnet",
                    block.getClass().getName(), h != null ? h.getClass().getName() : "Vanilla", rt, x, y, z, e);
            blockErrors.incrementAndGet();
            return false;
        }
    }

    private static void pack(int[] raw, int n, ByteBuffer out) {
        for (int i = 0; i < n; i++) {
            int b = i * 8;
            out.putShort((short) q16((Float.intBitsToFloat(raw[b]) + 8f) * 2048f));
            out.putShort((short) q16((Float.intBitsToFloat(raw[b + 1]) + 8f) * 2048f));
            out.putShort((short) q16((Float.intBitsToFloat(raw[b + 2]) + 8f) * 2048f));
            out.putShort((short) 0);
            out.putShort((short) q16(Float.intBitsToFloat(raw[b + 3]) * 65535f));
            out.putShort((short) q16(Float.intBitsToFloat(raw[b + 4]) * 65535f));
            out.putInt(raw[b + 5]);
            int br = raw[b + 7];
            out.putShort((short) (br & 0xFFFF));
            out.putShort((short) ((br >>> 16) & 0xFFFF));
        }
    }

    private static int q16(float v) {
        int q = (int) (v + 0.5f);
        return q < 0 ? 0 : (q > 65535 ? 65535 : q);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    static final class BufferPool {
        @SuppressWarnings("unchecked")
        private static final ConcurrentLinkedQueue<ByteBuffer>[] CLASSES = new ConcurrentLinkedQueue[32];
        static {
            for (int i = 0; i < CLASSES.length; i++) CLASSES[i] = new ConcurrentLinkedQueue<ByteBuffer>();
        }

        private static int cls(int bytes) {
            return 32 - Integer.numberOfLeadingZeros(Math.max(bytes, 4096) - 1);
        }

        static ByteBuffer acquire(int bytes) {
            int c = cls(bytes);
            ByteBuffer b = CLASSES[c].poll();
            if (b == null) b = ByteBuffer.allocateDirect(1 << c).order(ByteOrder.nativeOrder());
            b.clear();
            return b;
        }

        static void release(ByteBuffer b) {
            if (b == null) return;
            CLASSES[cls(b.capacity())].add(b);
        }
    }
}
