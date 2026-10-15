package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.gui.GuiMainMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nikita23830.minecraftd3d.PD3D;

@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu {
    @Shadow
    private String field_92025_p;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pd3d$noGlWarning(CallbackInfo ci) {
        if (PD3D.engine) this.field_92025_p = "";
    }
}
