package com.nikita23830.minecraftd3d.api;

public final class Material {
    public enum Blend {
        OPAQUE,

        CUTOUT,

        ALPHA,

        ADDITIVE
    }

    public final Texture texture;
    public final Blend blend;
    public final boolean depthTest, depthWrite, cull, lit, fog;

    private Material(Builder b) {
        texture = b.texture; blend = b.blend; depthTest = b.depthTest; cull = b.cull; lit = b.lit; fog = b.fog;
        depthWrite = b.depthWrite != null ? b.depthWrite : (blend == Blend.OPAQUE || blend == Blend.CUTOUT);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Texture texture;
        private Blend blend = Blend.OPAQUE;
        private boolean depthTest = true, cull = true, lit = true, fog = true;
        private Boolean depthWrite;

        public Builder texture(Texture t) { texture = t; return this; }
        public Builder blend(Blend b) { blend = b; return this; }
        public Builder depthTest(boolean v) { depthTest = v; return this; }

        public Builder depthWrite(boolean v) { depthWrite = v; return this; }
        public Builder cull(boolean v) { cull = v; return this; }

        public Builder lit(boolean v) { lit = v; return this; }
        public Builder fog(boolean v) { fog = v; return this; }
        public Material build() { return new Material(this); }
    }
}
