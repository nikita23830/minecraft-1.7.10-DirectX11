package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.FrameStats;

@Mixin(Minecraft.class)
public abstract class MixinFrameStats {
    @Inject(method = "startGame", at = @At("RETURN"))
    private void pd3d$statsStart(CallbackInfo ci) {
        FrameStats.start();
    }

    @Inject(method = "func_147120_f", at = @At("HEAD"), remap = false)
    private void pd3d$beforeSwap(CallbackInfo ci) {
        FrameStats.beforeSwap();
    }

    @Inject(method = "func_147120_f", at = @At("RETURN"), remap = false)
    private void pd3d$afterSwap(CallbackInfo ci) {
        FrameStats.afterSwap();
    }
}
