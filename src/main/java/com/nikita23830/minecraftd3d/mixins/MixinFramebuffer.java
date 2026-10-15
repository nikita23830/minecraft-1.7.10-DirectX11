package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(Framebuffer.class)
public abstract class MixinFramebuffer {
    @Inject(method = "framebufferRender", at = @At("HEAD"), cancellable = true)
    private void pd3d$skipMainRender(int w, int h, CallbackInfo ci) {
        if (!PD3D.isMainFramebuffer(this)) return;
        ci.cancel();
    }
}
