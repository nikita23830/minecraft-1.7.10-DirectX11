package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;
import com.nikita23830.minecraftd3d.api.RenderPass;
import com.nikita23830.minecraftd3d.api.impl.ApiDispatch;

@Mixin(EntityRenderer.class)
public abstract class MixinApiHooks {
    @Shadow
    private Minecraft mc;

    @Shadow
    private float farPlaneDistance;

    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(FZII)V", shift = At.Shift.AFTER))
    private void pd3d$guiPass(float pt, CallbackInfo ci) {
        ApiDispatch.guiPass(pt);
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/client/renderer/culling/ICamera;F)V", ordinal = 0, shift = At.Shift.AFTER))
    private void pd3d$opaque(float pt, long nanos, CallbackInfo ci) {
        if (!PD3D.engine) pass(RenderPass.WORLD_OPAQUE, pt);
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/ForgeHooksClient;renderFirstPersonHand(Lnet/minecraft/client/renderer/RenderGlobal;FI)Z", remap = false))
    private void pd3d$lateWorld(float pt, long nanos, CallbackInfo ci) {
        if (PD3D.engine) return;
        pass(RenderPass.WORLD_TRANSLUCENT, pt);
        pass(RenderPass.WORLD_OVERLAY, pt);
    }

    private void pass(RenderPass p, float pt) {
        EntityLivingBase e = this.mc.renderViewEntity;
        if (e == null) return;
        AccessorEntityRenderer a = (AccessorEntityRenderer) this;
        double cx = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
        double cy = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
        double cz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        ApiDispatch.worldPass(p, pt, cx, cy, cz, new float[] { a.pd3d$fogR(), a.pd3d$fogG(), a.pd3d$fogB(), this.farPlaneDistance * 0.75f, this.farPlaneDistance });
    }
}
