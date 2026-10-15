package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.init.Blocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {
    @Shadow
    private Minecraft mc;

    @Inject(method = "loadRenderers", at = @At("HEAD"), cancellable = true)
    private void pd3d$loadRenderers(CallbackInfo ci) {
        if (!PD3D.active) return;

        Blocks.leaves.setGraphicsLevel(this.mc.gameSettings.fancyGraphics);
        Blocks.leaves2.setGraphicsLevel(this.mc.gameSettings.fancyGraphics);
        PD3D.onRenderersReload();
        ci.cancel();
    }

    @Inject(method = "markBlocksForUpdate", at = @At("HEAD"), cancellable = true)
    private void pd3d$markDirty(int x1, int y1, int z1, int x2, int y2, int z2, CallbackInfo ci) {
        if (!PD3D.active) return;
        PD3D.sections.markDirty(x1, y1, z1, x2, y2, z2);
        ci.cancel();
    }

    @Inject(method = "getDebugInfoRenders", at = @At("HEAD"), cancellable = true)
    private void pd3d$debug(CallbackInfoReturnable<String> cir) {
        if (!PD3D.active) return;
        cir.setReturnValue("C: " + PD3D.statVisible + "/" + PD3D.statTotal + " (D3D11). draw: " + PD3D.statDraws + "+" + PD3D.statImmDraws
            + ", Quads: " + PD3D.statQuads + ", TESR: " + PD3D.lastTileEntities);
    }
}
