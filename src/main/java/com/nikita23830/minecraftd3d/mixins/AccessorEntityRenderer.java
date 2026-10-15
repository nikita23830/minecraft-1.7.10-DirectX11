package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface AccessorEntityRenderer {
    @Accessor("lightmapColors")
    int[] pd3d$lightmapColors();

    @Accessor("lightmapTexture")
    net.minecraft.client.renderer.texture.DynamicTexture pd3d$lightmapTexture();

    @Accessor("lightmapUpdateNeeded")
    boolean pd3d$lightmapUpdateNeeded();

    @Accessor("fogColorRed")
    float pd3d$fogR();

    @Accessor("fogColorGreen")
    float pd3d$fogG();

    @Accessor("fogColorBlue")
    float pd3d$fogB();

    @Invoker("updateLightmap")
    void pd3d$updateLightmap(float pt);

    @Invoker("updateFogColor")
    void pd3d$updateFogColor(float pt);

    @Invoker("setupCameraTransform")
    void pd3d$setupCameraTransform(float pt, int pass);

    @Invoker("setupFog")
    void pd3d$setupFog(int mode, float pt);

    @Invoker("renderHand")
    void pd3d$renderHand(float pt, int pass);

    @Invoker("renderRainSnow")
    void pd3d$renderRainSnow(float pt);

    @Invoker("renderCloudsCheck")
    void pd3d$renderCloudsCheck(net.minecraft.client.renderer.RenderGlobal rg, float pt);

    @Accessor("cameraZoom")
    double pd3d$cameraZoom();
}
