package com.nikita23830.minecraftd3d.mixins;

import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nikita23830.minecraftd3d.engine.Tess;

@Mixin(value = { ForgeHooksClient.class, MinecraftForgeClient.class }, remap = false)
public abstract class MixinForgeRenderPass {
    @Inject(method = { "getWorldRenderPass", "getRenderPass" }, at = @At("HEAD"), cancellable = true, require = 1)
    private static void pd3d$pass(CallbackInfoReturnable<Integer> cir) {
        int p = Tess.meshPass();
        if (p >= 0) cir.setReturnValue(p);
    }
}
