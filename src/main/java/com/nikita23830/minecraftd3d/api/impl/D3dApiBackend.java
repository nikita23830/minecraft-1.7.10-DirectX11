package com.nikita23830.minecraftd3d.api.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import com.nikita23830.minecraftd3d.PD3D;
import com.nikita23830.minecraftd3d.api.Backend;
import com.nikita23830.minecraftd3d.api.Material;
import com.nikita23830.minecraftd3d.api.Matrix4;
import com.nikita23830.minecraftd3d.api.Mesh;
import com.nikita23830.minecraftd3d.api.MeshBuilder;
import com.nikita23830.minecraftd3d.api.RenderDevice;
import com.nikita23830.minecraftd3d.api.RenderPass;
import com.nikita23830.minecraftd3d.api.Texture;
import com.nikita23830.minecraftd3d.engine.Immediate;
import com.nikita23830.minecraftd3d.engine.RS;

public final class D3dApiBackend extends ApiBackend {
    static final int F_BLEND = 1, F_ALPHA = 2, F_DEPTH = 4, F_DEPTHWRITE = 8, F_LIGHT = 16, F_TEX = 32, F_LIGHTMAP = 64, F_FOG = 128, F_RAWPROJ = 256;
    static final int GL_SRC_ALPHA = 0x302, GL_ONE_MINUS_SRC_ALPHA = 0x303, GL_ONE = 1, GL_LEQUAL = 0x203, GL_GEQUAL = 0x206;

    private final Device device = new Device();
    private static int nextMesh = 1;

    public Backend kind() { return Backend.D3D11; }

    public RenderDevice device() { return device; }

    public BaseContext worldContext(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog) {
        return new Ctx(pass, pt, cx, cy, cz, fog, PD3D.lastViewProj.clone());
    }

    public BaseContext guiContext(float pt) {
        return new Ctx(RenderPass.GUI, pt, 0, 0, 0, null, null);
    }

    static final class Tex implements Texture {
        final int id, w, h;
        final boolean owned;

        Tex(int id, int w, int h, boolean owned) { this.id = id; this.w = w; this.h = h; this.owned = owned; }

        public int width() { return w; }
        public int height() { return h; }

        public void upload(int level, int x, int y, int w, int h, int[] argb) {
            IntBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            b.put(argb, 0, w * h).flip();
            PD3D.texUpload(id, level, x, y, w, h, b);
        }

        public void close() { if (owned) RS.glDeleteTextures(id); }
    }

    static final class D3dMesh implements Mesh {
        final int id, count, topo;
        D3dMesh(int id, int count, int topo) { this.id = id; this.count = count; this.topo = topo; }
        public int vertexCount() { return count; }
        public void close() { PD3D.apiMeshDelete(id); }
    }

    static final class Device implements RenderDevice {
        public Texture createTexture(int w, int h, int mips, boolean blur, boolean clamp) {
            int id = RS.glGenTextures();
            PD3D.texCreate(id, w, h, Math.max(1, mips));
            PD3D.texParams(id, (mips > 1 ? 1 : 0) | (clamp ? 2 : 0) | (blur ? 4 : 0));
            return new Tex(id, w, h, true);
        }

        public Texture minecraftTexture(ResourceLocation rl) {
            TextureManager tm = Minecraft.getMinecraft().getTextureManager();
            ITextureObject t = tm.getTexture(rl);
            if (t == null) { tm.bindTexture(rl); t = tm.getTexture(rl); }
            return new Tex(t.getGlTextureId(), 0, 0, false);
        }

        public Texture blockAtlas() {
            return new Tex(Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId(), 0, 0, false);
        }

        public Mesh createMesh(MeshBuilder b) {
            int id = nextMesh++;
            int topo = b.mode() == MeshBuilder.Mode.QUADS ? 0 : 1;
            PD3D.apiMeshCreate(id, b.data(), b.vertexCount(), topo);
            return new D3dMesh(id, b.vertexCount(), topo);
        }
    }

    static final class Ctx extends BaseContext {
        private final float[] viewProj;
        private final float[] l0 = new float[3], l1 = new float[3];

        Ctx(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog, float[] viewProj) {
            super(pass, pt, cx, cy, cz, fog);
            this.viewProj = viewProj;
        }

        private Matrix4 base() {
            Matrix4 m = new Matrix4();
            if (world()) System.arraycopy(viewProj, 0, m.m, 0, 16);
            else { System.arraycopy(RS.projection(), 0, m.m, 0, 16); m.mul(RS.modelview()); }
            return m;
        }

        private int flags(Material mat, boolean lightmap) {
            int f = 0;
            if (mat.texture != null) f |= F_TEX;
            if (mat.blend == Material.Blend.CUTOUT) f |= F_ALPHA;
            if (mat.blend == Material.Blend.ALPHA || mat.blend == Material.Blend.ADDITIVE) f |= F_BLEND;
            if (mat.depthTest) f |= F_DEPTH;
            if (mat.depthWrite) f |= F_DEPTHWRITE;
            if (mat.lit) f |= F_LIGHT | (lightmap && world() ? F_LIGHTMAP : 0);
            if (mat.fog && world()) f |= F_FOG;
            if (world()) f |= F_RAWPROJ;
            return f;
        }

        private void emit(int type, int first, int count, int topo, Material mat, int flags, float[] clip, int light, int tint) {
            int src = GL_ONE, dst = 0, srcA = GL_ONE, dstA = 0;
            if (mat.blend == Material.Blend.ALPHA) { src = GL_SRC_ALPHA; dst = GL_ONE_MINUS_SRC_ALPHA; dstA = GL_ONE_MINUS_SRC_ALPHA; }
            else if (mat.blend == Material.Blend.ADDITIVE) { src = GL_SRC_ALPHA; dst = GL_ONE; dstA = GL_ONE; }
            Immediate.apiCommand(type, first, count, topo, mat.texture != null ? ((Tex) mat.texture).id : 0, flags,
                src, dst, srcA, dstA, 0.1f, world() ? GL_GEQUAL : GL_LEQUAL, mat.cull ? 1 : 0, clip, l0, l1, light, tint);
            draws++;
        }

        public void draw(Mesh mesh, Material mat, double x, double y, double z, Matrix4 local, int light, int tint) {
            D3dMesh m = (D3dMesh) mesh;
            Matrix4 clip = base();
            if (world()) clip.translate((float) (x - cx), (float) (y - cy), (float) (z - cz));
            else clip.translate((float) x, (float) y, (float) z);
            if (local != null) clip.mul(local);
            lightsInModel(local != null ? local : new Matrix4(), l0, l1);
            emit(5, m.id, m.count, m.topo, mat, flags(mat, true), clip.m, light, tint);
        }

        protected void submitBatch(BatchBuffer b) {
            int n = b.count();
            int first = Immediate.apiVertices(b.data(), n);
            lightsInModel(new Matrix4(), l0, l1);
            emit(1, first, n, 0, b.material, flags(b.material, false), base().m, 0x00F000F0, 0);
        }

        public void nativeCallback(long fn, long userData) {
            Immediate.apiNative(fn, userData);
        }
    }
}
