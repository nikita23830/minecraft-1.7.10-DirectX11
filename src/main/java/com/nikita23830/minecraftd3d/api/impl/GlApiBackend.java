package com.nikita23830.minecraftd3d.api.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.nikita23830.minecraftd3d.mixins.AccessorEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import com.nikita23830.minecraftd3d.PD3D;
import com.nikita23830.minecraftd3d.api.Backend;
import com.nikita23830.minecraftd3d.api.Material;
import com.nikita23830.minecraftd3d.api.Matrix4;
import com.nikita23830.minecraftd3d.api.Mesh;
import com.nikita23830.minecraftd3d.api.MeshBuilder;
import com.nikita23830.minecraftd3d.api.RenderDevice;
import com.nikita23830.minecraftd3d.api.RenderPass;
import com.nikita23830.minecraftd3d.api.Texture;

public final class GlApiBackend extends ApiBackend {
    private final Device device = new Device();
    private int program = -1;
    private int uTint, uLight, uL0, uL1, uFogColor, uFogRange, uUseTex, uUseLight, uUseLightmap, uUseFog, uAlphaRef;

    public Backend kind() { return Backend.OPENGL; }

    public RenderDevice device() { return device; }

    public BaseContext worldContext(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog) {
        return new Ctx(pass, pt, cx, cy, cz, fog);
    }

    public BaseContext guiContext(float pt) {
        return new Ctx(RenderPass.GUI, pt, 0, 0, 0, null);
    }

    private static final String VS = "#version 120\n"
        + "attribute vec3 aPos; attribute vec2 aUv; attribute vec4 aCol; attribute vec4 aNrm;\n"
        + "uniform vec4 uTint;\n"
        + "varying vec2 vUv; varying vec4 vCol; varying vec3 vN; varying float vDist;\n"
        + "void main() {\n"
        + "  vec4 eye = gl_ModelViewMatrix * vec4(aPos, 1.0);\n"
        + "  gl_Position = gl_ProjectionMatrix * eye;\n"
        + "  vUv = aUv; vCol = aCol * uTint; vN = aNrm.xyz; vDist = length(eye.xyz);\n"
        + "}\n";
    private static final String FS = "#version 120\n"
        + "uniform sampler2D uTex; uniform sampler2D uLm;\n"
        + "uniform vec2 uLight; uniform vec3 uL0; uniform vec3 uL1; uniform vec4 uFogColor; uniform vec2 uFogRange;\n"
        + "uniform float uUseTex, uUseLight, uUseLightmap, uUseFog, uAlphaRef;\n"
        + "varying vec2 vUv; varying vec4 vCol; varying vec3 vN; varying float vDist;\n"
        + "void main() {\n"
        + "  vec4 c = vCol;\n"
        + "  if (uUseTex > 0.5) c *= texture2D(uTex, vUv);\n"
        + "  if (uUseLight > 0.5) {\n"
        + "    vec3 n = normalize(vN);\n"
        + "    c.rgb *= clamp(0.4 + 0.6 * (max(dot(n, uL0), 0.0) + max(dot(n, uL1), 0.0)), 0.0, 1.0);\n"
        + "  }\n"
        + "  if (uUseLightmap > 0.5) c.rgb *= texture2D(uLm, (uLight + 8.0) / 256.0).rgb;\n"
        + "  if (uUseFog > 0.5) c.rgb = mix(c.rgb, uFogColor.rgb, clamp((vDist - uFogRange.x) / max(uFogRange.y - uFogRange.x, 0.001), 0.0, 1.0));\n"
        + "  if (uAlphaRef >= 0.0 && !(c.a > uAlphaRef)) discard;\n"
        + "  gl_FragColor = c;\n"
        + "}\n";

    private void ensureProgram() {
        if (program >= 0) return;
        int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(vs, VS); GL20.glCompileShader(vs);
        GL20.glShaderSource(fs, FS); GL20.glCompileShader(fs);
        if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == 0) PD3D.LOG.error("API GL VS: {}", GL20.glGetShaderInfoLog(vs, 4096));
        if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == 0) PD3D.LOG.error("API GL FS: {}", GL20.glGetShaderInfoLog(fs, 4096));
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs); GL20.glAttachShader(p, fs);
        GL20.glBindAttribLocation(p, 0, "aPos"); GL20.glBindAttribLocation(p, 1, "aUv");
        GL20.glBindAttribLocation(p, 2, "aCol"); GL20.glBindAttribLocation(p, 3, "aNrm");
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) PD3D.LOG.error("API GL link: {}", GL20.glGetProgramInfoLog(p, 4096));
        program = p;
        uTint = GL20.glGetUniformLocation(p, "uTint"); uLight = GL20.glGetUniformLocation(p, "uLight");
        uL0 = GL20.glGetUniformLocation(p, "uL0"); uL1 = GL20.glGetUniformLocation(p, "uL1");
        uFogColor = GL20.glGetUniformLocation(p, "uFogColor"); uFogRange = GL20.glGetUniformLocation(p, "uFogRange");
        uUseTex = GL20.glGetUniformLocation(p, "uUseTex"); uUseLight = GL20.glGetUniformLocation(p, "uUseLight");
        uUseLightmap = GL20.glGetUniformLocation(p, "uUseLightmap"); uUseFog = GL20.glGetUniformLocation(p, "uUseFog");
        uAlphaRef = GL20.glGetUniformLocation(p, "uAlphaRef");
        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "uTex"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "uLm"), 1);
        GL20.glUseProgram(0);
    }

    static final class Tex implements Texture {
        final int id, w, h;
        final boolean owned;

        Tex(int id, int w, int h, boolean owned) { this.id = id; this.w = w; this.h = h; this.owned = owned; }

        public int width() { return w; }
        public int height() { return h; }

        public void upload(int level, int x, int y, int w, int h, int[] argb) {
            IntBuffer b = BufferUtils.createIntBuffer(w * h);
            b.put(argb, 0, w * h).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, level, x, y, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, b);
        }

        public void close() { if (owned) TextureUtil.deleteTexture(id); }
    }

    static final class GlMesh implements Mesh {
        final int vbo, count, mode;
        GlMesh(int vbo, int count, int mode) { this.vbo = vbo; this.count = count; this.mode = mode; }
        public int vertexCount() { return count; }
        public void close() { GL15.glDeleteBuffers(vbo); }
    }

    static final class Device implements RenderDevice {
        public Texture createTexture(int w, int h, int mips, boolean blur, boolean clamp) {
            int id = TextureUtil.glGenTextures();
            TextureUtil.allocateTextureImpl(id, Math.max(1, mips) - 1, w, h, 1f);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                blur ? (mips > 1 ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR) : (mips > 1 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, blur ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            int wrap = clamp ? GL12.GL_CLAMP_TO_EDGE : GL11.GL_REPEAT;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
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
            int vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, b.data(), GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            return new GlMesh(vbo, b.vertexCount(), b.mode() == MeshBuilder.Mode.QUADS ? GL11.GL_QUADS : GL11.GL_TRIANGLES);
        }
    }

    final class Ctx extends BaseContext {
        private final float[] l0 = new float[3], l1 = new float[3], cm = new float[16];
        private final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
        private boolean begun;

        Ctx(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog) {
            super(pass, pt, cx, cy, cz, fog);
        }

        private void begin() {
            if (begun) return;
            begun = true;
            ensureProgram();

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_POLYGON_BIT);
        }

        private void endDraw() {
            for (int i = 0; i < 4; i++) GL20.glDisableVertexAttribArray(i);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(0);
        }

        public void finish() {
            if (!begun) return;
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glPopAttrib();
        }

        private void apply(Material m, boolean lightmap, int light, int tint) {
            begin();
            GL20.glUseProgram(program);
            for (int i = 0; i < 4; i++) GL20.glEnableVertexAttribArray(i);
            if (fog != null) {
                GL20.glUniform4f(uFogColor, fog[0], fog[1], fog[2], 1f);
                GL20.glUniform2f(uFogRange, fog[3], fog[4]);
            }
            if (m.texture != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((Tex) m.texture).id);
            }
            boolean lm = m.lit && lightmap && world();
            if (lm) {
                OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((AccessorEntityRenderer) Minecraft.getMinecraft().entityRenderer).pd3d$lightmapTexture().getGlTextureId());
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            }
            GL20.glUniform1f(uUseTex, m.texture != null ? 1 : 0);
            GL20.glUniform1f(uUseLight, m.lit ? 1 : 0);
            GL20.glUniform1f(uUseLightmap, lm ? 1 : 0);
            GL20.glUniform1f(uUseFog, m.fog && world() && fog != null ? 1 : 0);
            GL20.glUniform1f(uAlphaRef, m.blend == Material.Blend.CUTOUT ? 0.1f : -1f);
            GL20.glUniform2f(uLight, light & 0xFFFF, (light >>> 16) & 0xFFFF);
            GL20.glUniform3f(uL0, l0[0], l0[1], l0[2]);
            GL20.glUniform3f(uL1, l1[0], l1[1], l1[2]);
            if (tint == 0) tint = -1;
            GL20.glUniform4f(uTint, ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f, (tint >>> 24) / 255f);
            if (m.blend == Material.Blend.ALPHA || m.blend == Material.Blend.ADDITIVE) {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, m.blend == Material.Blend.ADDITIVE ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            if (m.depthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL); } else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(m.depthWrite);
            if (m.cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
        }

        private void pointers(long base) {
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, base);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 28, base + 12);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 28, base + 20);
            GL20.glVertexAttribPointer(3, 4, GL11.GL_BYTE, true, 28, base + 24);
        }

        public void draw(Mesh mesh, Material m, double x, double y, double z, Matrix4 local, int light, int tint) {
            GlMesh gm = (GlMesh) mesh;
            lightsInModel(local != null ? local : new Matrix4(), l0, l1);
            apply(m, true, light, tint);
            GL11.glPushMatrix();
            if (world()) GL11.glTranslated(x - cx, y - cy, z - cz);
            else GL11.glTranslated(x, y, z);
            if (local != null) {
                local.toColumnMajor(cm);
                mat.clear(); mat.put(cm).flip();
                GL11.glMultMatrix(mat);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gm.vbo);
            pointers(0);
            GL11.glDrawArrays(gm.mode, 0, gm.count);
            endDraw();
            GL11.glPopMatrix();
            draws++;
        }

        protected void submitBatch(BatchBuffer b) {
            lightsInModel(new Matrix4(), l0, l1);
            apply(b.material, false, 0x00F000F0, 0);
            ByteBuffer d = b.data();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            ByteBuffer p = d.duplicate().order(ByteOrder.nativeOrder());
            p.position(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, p.slice());
            p.position(12); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 28, p.slice());
            p.position(20); GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 28, p.slice());
            p.position(24); GL20.glVertexAttribPointer(3, 4, GL11.GL_BYTE, true, 28, p.slice());
            GL11.glDrawArrays(GL11.GL_QUADS, 0, b.count());
            endDraw();
            draws++;
        }

        public void nativeCallback(long fn, long userData) {
        }
    }
}
