package com.nikita23830.minecraftd3d.mixins;

import com.nikita23830.minecraftd3d.engine.GLLock;
import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nikita23830.minecraftd3d.PD3D;
import com.nikita23830.minecraftd3d.engine.ITess;
import com.nikita23830.minecraftd3d.engine.Immediate;
import com.nikita23830.minecraftd3d.engine.Tess;

@Mixin(Tessellator.class)
public abstract class MixinTessellator implements ITess {
    @Shadow private int[] rawBuffer;
    @Shadow private int vertexCount;
    @Shadow private boolean hasTexture;
    @Shadow private boolean hasBrightness;
    @Shadow private boolean hasColor;
    @Shadow private boolean hasNormals;
    @Shadow private int rawBufferIndex;
    @Shadow private int drawMode;
    @Shadow private boolean isDrawing;
    @Shadow private int addedVertices;

    @Shadow
    protected abstract void reset();

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void pd3d$draw(CallbackInfoReturnable<Integer> cir) {
        if (!PD3D.engine) return;
        if (!this.isDrawing) {
            cir.setReturnValue(0);
            return;
        }
        this.isDrawing = false;
        int bytes = this.rawBufferIndex * 4;
        Tessellator self = (Tessellator) (Object) this;
        if (Tess.isWorker(self)) {
            Tess.workerFlush(self);
        } else if (PD3D.isMainThread()) {
            GLLock.enter();
            try {
                Immediate.submit(this.rawBuffer, this.vertexCount, this.drawMode, this.hasTexture, this.hasColor, this.hasNormals, this.hasBrightness);
            } finally {
                GLLock.exit();
            }
        } else {
            Tess.offThreadDraws++;
        }
        this.reset();
        cir.setReturnValue(bytes);
    }

    @Override public int[] pd3d$raw() { return this.rawBuffer; }
    @Override public int pd3d$vertexCount() { return this.vertexCount; }
    @Override public boolean pd3d$hasColor() { return this.hasColor; }
    @Override public boolean pd3d$hasBrightness() { return this.hasBrightness; }
    @Override public boolean pd3d$isDrawing() { return this.isDrawing; }
    @Override public int pd3d$drawMode() { return this.drawMode; }

    @Override
    public void pd3d$truncate(int n) {
        this.vertexCount = n;
        this.rawBufferIndex = n * 8;
        this.addedVertices = n;
    }

    @Override
    public void pd3d$finish() {
        this.isDrawing = false;
        this.reset();
    }
}
