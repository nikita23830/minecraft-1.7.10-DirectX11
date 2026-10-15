package com.nikita23830.minecraftd3d;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import com.nikita23830.minecraftd3d.api.impl.ApiDispatch;
import com.nikita23830.minecraftd3d.api.impl.D3dApiBackend;
import com.nikita23830.minecraftd3d.engine.GLLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.ForgeHooksClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFWNativeWin32;

import com.nikita23830.minecraftd3d.engine.GLEmu;
import com.nikita23830.minecraftd3d.engine.Immediate;
import com.nikita23830.minecraftd3d.engine.RS;
import com.nikita23830.minecraftd3d.mixins.AccessorEntityRenderer;
import com.nikita23830.minecraftd3d.rfb.MinecraftD3DRfbPlugin;

@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
public final class PD3D {
    public static final Logger LOG = LogManager.getLogger("MinecraftD3D");
    public static final boolean engine = MinecraftD3DRfbPlugin.ENABLED;

    public static boolean active;
    private static boolean nativeReady;
    private static Thread mainThread;

    private static Minecraft mc;
    public static SectionManager sections;

    private static final ByteBuffer desc = ByteBuffer.allocateDirect(4 * 64 + 1024 + 64).order(ByteOrder.nativeOrder());
    private static final ByteBuffer statsBuf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static boolean frameHasWorld;

    public static float statCpuMs, statGpuMs, statPresentMs;
    public static int statDraws, statVisible, statTotal, statQuads, statImmDraws, statImmVerts;
    public static long statMem;

    static final boolean NO_WORLD_FOG = Boolean.getBoolean("minecraftd3d.noWorldFog");

    public static float[] lastWorldFog;
    public static long lastWorldNanos, lastEntityNanos, lastFrameCallNanos, lastApiNanos;
    public static int lastWorldDraws, lastTileEntities;

    private PD3D() {}

    public static boolean isLoadingThread() {
        return LoadingScreen.isLoaderThread();
    }

    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread || LoadingScreen.isLoaderThread();
    }

    public static void markMainThread() {
        mainThread = Thread.currentThread();
    }

    static File findDll(File gameDir) {
        String prop = System.getProperty("minecraftd3d.dll");
        if (prop != null && !prop.isEmpty()) {
            File f = new File(prop);
            if (!f.isFile()) throw new RuntimeException("MinecraftD3D: -Dminecraftd3d.dll=" + prop + " – Datei existiert nicht");
            LOG.info("MinecraftD3D: minecraftd3d.dll aus -Dminecraftd3d.dll: {}", f.getAbsolutePath());
            return f;
        }
        File local = new File(gameDir, "minecraftd3d.dll");
        if (local.isFile()) {
            LOG.info("MinecraftD3D: minecraftd3d.dll aus dem Spielordner: {}", local.getAbsolutePath());
            return local;
        }
        try (java.io.InputStream in = PD3D.class.getResourceAsStream("/natives/minecraftd3d.dll")) {
            if (in == null) throw new RuntimeException("MinecraftD3D: minecraftd3d.dll nicht gefunden (-Dminecraftd3d.dll, " + local + ", natives/ im Jar)");
            byte[] data = readAll(in);
            String sha1 = sha1(data);
            File dir = new File(System.getProperty("java.io.tmpdir"), "minecraftd3d-" + sha1.substring(0, 12));
            File out = new File(dir, "minecraftd3d.dll");
            if (!out.isFile() || !sha1(java.nio.file.Files.readAllBytes(out.toPath())).equals(sha1)) {
                dir.mkdirs();
                File tmp = new File(dir, "minecraftd3d.dll." + System.nanoTime() + ".tmp");
                java.nio.file.Files.write(tmp.toPath(), data);
                try {
                    java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException e) {
                    tmp.delete();
                    if (!out.isFile()) throw e;
                }
            }
            LOG.info("MinecraftD3D: minecraftd3d.dll aus dem Jar (natives/minecraftd3d.dll, sha1 {}) -> {}", sha1, out.getAbsolutePath());
            return out;
        } catch (java.io.IOException e) {
            throw new RuntimeException("MinecraftD3D: minecraftd3d.dll konnte nicht aus dem Jar entpackt werden", e);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static String sha1(byte[] d) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-1").digest(d)) sb.append(String.format("%02x", x & 255));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static void preInit(Minecraft minecraft) {
        mc = minecraft;
        mainThread = Thread.currentThread();
        if (!engine) return;
        File dll = findDll(minecraft.mcDataDir);
        try {
            System.load(dll.getAbsolutePath());
        } catch (Throwable t) {
            throw new RuntimeException("MinecraftD3D: konnte nicht geladen werden: " + dll, t);
        }
        new File("logs").mkdirs();
        long window = org.lwjglx.opengl.Display.getWindow();
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        int r = D3DNative.init(hwnd, Integer.getInteger("minecraftd3d.flags", 0));
        if (r != 0) throw new RuntimeException("MinecraftD3D: D3DNative.init = " + r + " (siehe logs/minecraftd3d-native.log)");
        nativeReady = true;
        LoadingScreen.captureFresh();
        RS.fullW = Math.max(1, mc.displayWidth);
        RS.fullH = Math.max(1, mc.displayHeight);
        LOG.info("MinecraftD3D: D3D11 auf GLFW-Fenster {} (HWND 0x{}), kein OpenGL-Kontext", window, Long.toHexString(hwnd));
    }

    public static void init(Minecraft minecraft) {
        if (!engine) return;
        int th = Integer.getInteger("minecraftd3d.threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        sections = new SectionManager(th);
        active = true;
        ApiDispatch.install(new D3dApiBackend());
        LOG.info("MinecraftD3D-Engine: Meshing-Threads {}; GLEmu: nicht portierte GL-Aufrufe beim Start – {} Stellen", th, GLEmu.unportedCalls.size());
    }

    public static void onWorldChange(WorldClient world) {
        if (!active) return;
        sections.reset(world);
    }

    public static void onRenderersReload() {
        if (!active || mc.theWorld == null) return;
        sections.reset(mc.theWorld);
        sections.markAllLoaded(mc.theWorld);
    }

    public static void renderWorld(EntityRenderer er, float pt, long finishNanos) {
        long t0 = System.nanoTime();
        AccessorEntityRenderer a = (AccessorEntityRenderer) er;
        if (a.pd3d$lightmapUpdateNeeded()) a.pd3d$updateLightmap(pt);
        RS.lightmapColors = a.pd3d$lightmapColors();
        RS.glEnable(0x0B44);
        RS.glEnable(0x0B71);
        RS.glEnable(0x0BC0);
        RS.glAlphaFunc(0x204, 0.5f);
        if (mc.renderViewEntity == null) mc.renderViewEntity = mc.thePlayer;
        er.getMouseOver(pt);
        EntityLivingBase e = mc.renderViewEntity;
        double cx = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
        double cy = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
        double cz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        RenderGlobal rg = mc.renderGlobal;

        RS.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        a.pd3d$updateFogColor(pt);
        RS.glClear(0x4000 | 0x100);
        RS.glEnable(0x0B44);

        a.pd3d$setupCameraTransform(pt, 0);
        ActiveRenderInfo.updateRenderInfo(mc.thePlayer, mc.gameSettings.thirdPersonView == 2);
        ClippingHelperImpl.getInstance();
        float[] vp = Matrices.mul(Matrices.reverseZ(RS.projection()), RS.modelview());
        System.arraycopy(vp, 0, lastViewProj, 0, 16);

        int rd = mc.gameSettings.renderDistanceChunks;

        if (rd >= 4) {
            a.pd3d$setupFog(-1, pt);
            safe("Himmel", () -> rg.renderSky(pt));
        }
        RS.glEnable(0x0B60);
        a.pd3d$setupFog(1, pt);

        a.pd3d$setupFog(0, pt);
        float[] fog = RS.fogState();
        if (NO_WORLD_FOG) fog = null;
        lastWorldFog = fog;

        Frustrum frustrum = new Frustrum();
        frustrum.setPosition(cx, cy, cz);
        sections.update(mc.theWorld, cx, cy, cz, rd);
        float far = rd * 16f;
        float fr = a.pd3d$fogR(), fg = a.pd3d$fogG(), fb = a.pd3d$fogB();
        int secX = floorDiv16(cx), secY = floorDiv16(cy), secZ = floorDiv16(cz);
        desc.clear();
        desc.putInt(1);
        desc.putInt(secX).putInt(secY).putInt(secZ);
        desc.putFloat((float) (cx - secX * 16.0)).putFloat((float) (cy - secY * 16.0)).putFloat((float) (cz - secZ * 16.0));
        for (int i = 0; i < 16; i++) desc.putFloat(vp[i]);
        if (fog != null) desc.putFloat(fog[0]).putFloat(fog[1]).putFloat(fog[2]).putFloat(fog[3]).putFloat(fog[4]);
        else desc.putFloat(fr).putFloat(fg).putFloat(fb).putFloat(far * 0.75f).putFloat(far);
        desc.putFloat(fr).putFloat(fg).putFloat(fb);
        desc.putInt(rd);
        int[] lm = RS.lightmapColors;
        for (int i = 0; i < 256; i++) desc.putInt(lm[i]);
        desc.putInt(mc.getTextureMapBlocks().getGlTextureId());
        desc.putInt(fog != null ? (int) fog[5] : 0).putFloat(fog != null ? fog[6] : 0f);
        desc.flip();
        Immediate.world(true);
        frameHasWorld = true;

        long t1 = System.nanoTime();
        if (e.posY < 128.0D) safe("Wolken", () -> a.pd3d$renderCloudsCheck(rg, pt));
        a.pd3d$setupFog(0, pt);
        RS.glEnable(0x0B60);
        RenderHelper.disableStandardItemLighting();
        safe("Entities/TESR", () -> renderEntities(er, e, pt, frustrum, 0));
        RenderHelper.disableStandardItemLighting();
        er.disableLightmap(pt);

        boolean hand = a.pd3d$cameraZoom() == 1.0D;
        if (e instanceof EntityPlayer && !mc.gameSettings.hideGUI && mc.objectMouseOver != null && hand) {
            EntityPlayer p = (EntityPlayer) e;
            RS.glDisable(0x0BC0);
            safe("Auswahlrahmen", () -> {
                if (!ForgeHooksClient.onDrawBlockHighlight(rg, p, mc.objectMouseOver, 0, p.inventory.getCurrentItem(), pt))
                    rg.drawSelectionBox(p, mc.objectMouseOver, 0, pt);
            });
            RS.glEnable(0x0BC0);
        }
        RS.glEnable(0x0BE2);
        RS.glBlendFuncSeparate(770, 1, 1, 0);
        safe("Abbaurisse", () -> rg.drawBlockDamageTexture(Tessellator.instance, e, pt));
        RS.glDisable(0x0BE2);

        safe("Partikel", () -> {
            er.enableLightmap(pt);
            mc.effectRenderer.renderLitParticles(e, pt);
            RenderHelper.disableStandardItemLighting();
            a.pd3d$setupFog(0, pt);
            mc.effectRenderer.renderParticles(e, pt);
            er.disableLightmap(pt);
        });

        RS.glDepthMask(false);
        RS.glEnable(0x0B44);
        safe("Wetter", () -> a.pd3d$renderRainSnow(pt));
        RS.glDepthMask(true);
        RS.glDisable(0x0BE2);
        RS.glEnable(0x0B44);
        RS.glBlendFuncSeparate(770, 771, 1, 0);
        RS.glAlphaFunc(0x204, 0.1f);
        a.pd3d$setupFog(0, pt);
        Immediate.worldTranslucent();

        safe("Entities/TESR (Pass 1)", () -> renderEntities(er, e, pt, frustrum, 1));
        RS.glDepthMask(true);
        RS.glEnable(0x0B44);
        RS.glDisable(0x0BE2);

        final float farF = far;
        long tApi = System.nanoTime();
        safe("Render-API: Welt-Passes", () -> ApiDispatch.worldPasses(pt, cx, cy, cz, new float[] { fr, fg, fb, farF * 0.75f, farF }));
        lastApiNanos = System.nanoTime() - tApi;
        RS.glDisable(0x0B60);
        if (e.posY >= 128.0D) safe("Wolken", () -> a.pd3d$renderCloudsCheck(rg, pt));
        safe("RenderWorldLastEvent", () -> ForgeHooksClient.dispatchRenderLast(rg, pt));
        lastWorldDraws = Immediate.worldDraws;
        long t2 = System.nanoTime();
        lastEntityNanos = t2 - t1;

        if (hand && !ForgeHooksClient.renderFirstPersonHand(rg, pt, 0)) {
            RS.glClear(0x100);
            if (RS.TRACE_TEX && !RS.glIsEnabled(0x0DE1) && RS.lastTexOff != null && traced++ < 3) LOG.warn("MinecraftD3D: Textur vor der Hand deaktiviert", RS.lastTexOff);
            safe("Hand", () -> a.pd3d$renderHand(pt, 0));
        }
        lastWorldNanos = System.nanoTime() - t0;
    }

    private static int entityErrors, traced;

    private static void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            GLEmu.unported("Ausnahme (" + what + "): " + t + " @ PD3D.renderWorld");
            if (entityErrors++ < 5) LOG.error("MinecraftD3D: Fehler in „{}“", what, t);
        }
    }

    public static final java.util.HashMap<Class<?>, long[]> tesrNanos = new java.util.HashMap<Class<?>, long[]>();
    public static boolean profileTesr;
    private static final java.util.ArrayList<TileEntity> tiles = new java.util.ArrayList<TileEntity>();

    private static void renderEntities(EntityRenderer er, EntityLivingBase view, float pt, Frustrum frustrum, int pass) {
        TileEntityRendererDispatcher ted = TileEntityRendererDispatcher.instance;
        if (pass == 0) {
            mc.renderGlobal.tileEntities.clear();
            tiles.clear();
            for (Object o : mc.theWorld.loadedTileEntityList) {
                TileEntity te = (TileEntity) o;
                if (ted.hasSpecialRenderer(te)) tiles.add(te);
            }
            lastTileEntities = tiles.size();
        }
        RenderHelper.enableStandardItemLighting();
        ForgeHooksClient.setRenderPass(pass);
        mc.renderGlobal.renderEntities(view, frustrum, pt);
        er.enableLightmap(pt);
        RenderHelper.enableStandardItemLighting();
        for (int i = 0, n = tiles.size(); i < n; i++) {
            TileEntity te = tiles.get(i);
            if (!te.shouldRenderInPass(pass) || !frustrum.isBoundingBoxInFrustum(te.getRenderBoundingBox())) continue;
            if (!profileTesr) {
                ted.renderTileEntity(te, pt);
                continue;
            }
            long s = System.nanoTime();
            ted.renderTileEntity(te, pt);
            long d = System.nanoTime() - s;
            Class<?> c = ted.getSpecialRenderer(te).getClass();
            long[] acc = tesrNanos.get(c);
            if (acc == null) tesrNanos.put(c, acc = new long[2]);
            acc[0] += d;
            acc[1]++;
        }
        er.disableLightmap(pt);
        ForgeHooksClient.setRenderPass(-1);
        RenderHelper.disableStandardItemLighting();
    }

    public static final float[] lastViewProj = new float[16];

    public static void apiMeshCreate(int id, ByteBuffer data, int count, int topo) { if (nativeReady) D3DNative.apiMeshCreate(id, data, count, topo); }

    public static void apiMeshDelete(int id) { if (nativeReady) D3DNative.apiMeshDelete(id); }

    public static long apiDevicePtr() { return nativeReady ? D3DNative.apiDevice() : 0L; }

    public static long apiContextPtr() { return nativeReady ? D3DNative.apiContext() : 0L; }

    private static int floorDiv16(double v) {
        return (int) Math.floor(v / 16.0);
    }

    public static void present() {
        GLLock.enter();
        try {
            presentLocked();
        } finally {
            GLLock.exit();
        }
    }

    private static void emptyDescIfNoWorld() {
        if (frameHasWorld) return;
        desc.clear();
        for (int i = 0; i < 7 + 16 + 5 + 3 + 1 + 256 + 1 + 2; i++) desc.putInt(0);
        desc.flip();
    }

    private static void presentLocked() {
        emptyDescIfNoWorld();
        long t0 = System.nanoTime();
        D3DNative.frameEngine(desc, Immediate.commandBuffer(), Immediate.commandCount(), Immediate.vertexBuffer(), Immediate.vertexCount());
        Immediate.reset();
        lastFrameCallNanos = System.nanoTime() - t0;
        frameHasWorld = false;
        if (Thread.currentThread() == mainThread) org.lwjglx.opengl.Display.processMessages();
        RS.fullW = Math.max(1, mc.displayWidth);
        RS.fullH = Math.max(1, mc.displayHeight);
        D3DNative.stats(statsBuf);
        statsBuf.clear();
        statCpuMs = statsBuf.getFloat(0);
        statGpuMs = statsBuf.getFloat(4);
        statPresentMs = statsBuf.getFloat(8);
        statDraws = statsBuf.getInt(12);
        statVisible = statsBuf.getInt(16);
        statTotal = statsBuf.getInt(20);
        statQuads = statsBuf.getInt(24);
        statMem = statsBuf.getLong(32);
        statImmDraws = statsBuf.getInt(48);
        statImmVerts = statsBuf.getInt(52);
    }

    public static void flushFrame() {
        if (!nativeReady || Immediate.commandCount() == 0) return;
        emptyDescIfNoWorld();
        D3DNative.flush(desc, Immediate.commandBuffer(), Immediate.commandCount(), Immediate.vertexBuffer(), Immediate.vertexCount());
        Immediate.afterFlush();
    }

    public static int fbRead(int tex, int depthKey, int kind, int x, int y, int w, int h, ByteBuffer out) {
        return nativeReady ? D3DNative.fbRead(tex, depthKey, kind, x, y, w, h, out) : -1;
    }

    public static int texRead(int id, int level, ByteBuffer out) { return nativeReady ? D3DNative.texRead(id, level, out) : -1; }

    public static void fbDepthDelete(int key) { if (nativeReady) D3DNative.fbDepthDelete(key); }

    public static int mainFramebufferObject() {
        net.minecraft.client.shader.Framebuffer f = mc != null ? mc.getFramebuffer() : null;
        return f != null ? f.framebufferObject : Integer.MIN_VALUE;
    }

    public static int mainFramebufferTexture() {
        net.minecraft.client.shader.Framebuffer f = mc != null ? mc.getFramebuffer() : null;
        return f != null ? f.framebufferTexture : Integer.MIN_VALUE;
    }

    public static boolean isMainFramebuffer(Object fb) { return mc != null && fb == mc.getFramebuffer(); }

    public static void texCreate(int id, int w, int h, int mips) { if (nativeReady) D3DNative.texCreate(id, w, h, mips); }

    private static ByteBuffer direct = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

    public static void texUpload(int id, int level, int x, int y, int w, int h, Buffer data) {
        if (!nativeReady) return;

        Buffer b = data;
        if (!data.isDirect()) {
            int bytes = data instanceof IntBuffer ? data.remaining() * 4 : data.remaining();
            if (direct.capacity() < bytes) direct = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            direct.clear();
            if (data instanceof IntBuffer) direct.asIntBuffer().put(((IntBuffer) data).duplicate());
            else direct.put(((ByteBuffer) data).duplicate());
            direct.position(0).limit(bytes);
            b = direct;
        } else if (data.position() != 0) {
            if (data instanceof IntBuffer) b = ((IntBuffer) data).slice();
            else if (data instanceof ByteBuffer) b = ((ByteBuffer) data).slice();
        }
        D3DNative.texUpload(id, level, x, y, w, h, b);
    }

    public static void texParams(int id, int params) { if (nativeReady) D3DNative.texParams(id, params); }

    public static void texDelete(int id) { if (nativeReady) D3DNative.texDelete(id); }

    static void sectionSet(int x, int y, int z, ByteBuffer buf, int opaque, int trans) { D3DNative.sectionSet(x, y, z, buf, opaque, trans); }

    static void clearSections() { D3DNative.clearSections(); }

    public static void shutdown() {
        if (!active) return;
        active = false;
        sections.shutdown();
        if (nativeReady) D3DNative.shutdown();
        nativeReady = false;
    }
}
