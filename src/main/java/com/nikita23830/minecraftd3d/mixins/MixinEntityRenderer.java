package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {
    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
    private void pd3d$renderWorld(float pt, long finishNanos, CallbackInfo ci) {
        if (!PD3D.active) return;
        PD3D.renderWorld((EntityRenderer) (Object) this, pt, finishNanos);
        ci.cancel();
    }
}
