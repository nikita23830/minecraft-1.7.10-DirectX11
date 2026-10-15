package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Inject(method = "startGame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;initializeTextures()V"))
    private void pd3d$afterDisplay(CallbackInfo ci) {
        PD3D.preInit((Minecraft) (Object) this);
    }

    @Inject(method = "startGame", at = @At("RETURN"))
    private void pd3d$afterStart(CallbackInfo ci) {
        PD3D.init((Minecraft) (Object) this);
    }

    @Redirect(method = "func_147120_f", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false))
    private void pd3d$present() {
        PD3D.present();
    }

    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V", at = @At("RETURN"))
    private void pd3d$worldChanged(WorldClient world, String msg, CallbackInfo ci) {
        PD3D.onWorldChange(world);
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void pd3d$shutdown(CallbackInfo ci) {
        PD3D.shutdown();
    }
}
