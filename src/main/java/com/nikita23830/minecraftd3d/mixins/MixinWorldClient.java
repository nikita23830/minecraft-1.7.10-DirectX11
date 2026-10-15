package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.multiplayer.WorldClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(WorldClient.class)
public abstract class MixinWorldClient {
    @Inject(method = "doPreChunk", at = @At("HEAD"))
    private void pd3d$chunk(int cx, int cz, boolean load, CallbackInfo ci) {
        if (!load && PD3D.active) PD3D.sections.onChunkUnload(cx, cz);
    }
}
