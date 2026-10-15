package com.nikita23830.minecraftd3d.mixins;

import net.minecraft.client.gui.FontRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.ResourceLocation;

import com.nikita23830.minecraftd3d.FontBatch;

@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {
    private int pd3d$charIdx = -2;

    @Shadow
    protected byte[] glyphWidth;

    @Shadow
    private boolean unicodeFlag;

    @Shadow protected int[] charWidth;
    @Shadow protected float posX;
    @Shadow protected float posY;
    @Shadow @org.spongepowered.asm.mixin.Final protected ResourceLocation locationFontTexture;
    @Shadow private boolean strikethroughStyle;
    @Shadow private boolean underlineStyle;

    @Shadow
    private ResourceLocation getUnicodePageLocation(int page) { return null; }

    @Shadow(remap = false)
    protected void bindTexture(ResourceLocation location) {}

    @Inject(method = "renderStringAtPos", at = @At("HEAD"), require = 0)
    private void pd3d$batchBegin(String s, boolean shadow, CallbackInfo ci) {
        FontBatch b = FontBatch.get();
        b.end();
        b.depth = 1;
        b.shadow = shadow;
    }

    @Inject(method = "renderStringAtPos", at = @At("RETURN"), require = 0)
    private void pd3d$batchEnd(String s, boolean shadow, CallbackInfo ci) {
        FontBatch b = FontBatch.get();
        b.depth = 0;
        b.end();
    }

    @Inject(method = "setColor", at = @At("HEAD"), remap = false, require = 0)
    private void pd3d$batchColor(float r, float g, float b, float a, CallbackInfo ci) {
        FontBatch.get().color(r, g, b, a);
    }

    @Inject(method = "doDraw", at = @At("HEAD"), remap = false, require = 0)
    private void pd3d$batchLines(float f, CallbackInfo ci) {
        if (!this.strikethroughStyle && !this.underlineStyle) return;
        FontBatch fb = FontBatch.get();
        if (fb.depth > 0) fb.flush();
    }

    @Inject(method = "renderDefaultChar", at = @At("HEAD"), cancellable = true, require = 0)
    private void pd3d$batchDefault(int ch, boolean italic, CallbackInfoReturnable<Float> cir) {
        FontBatch b = FontBatch.get();
        if (b.depth == 0) return;
        float f = ch % 16 * 8, f1 = ch / 16 * 8, f2 = italic ? 1.0F : 0.0F;
        if (b.switchTo(this.locationFontTexture)) bindTexture(this.locationFontTexture);
        float f3 = this.charWidth[ch] - 0.01F;
        b.quad(this.posX + f2, this.posX - f2, this.posX + f3 - 1.0F + f2, this.posX + f3 - 1.0F - f2, this.posY, this.posY + 7.99F,
            f / 128.0F, f1 / 128.0F, (f + f3 - 1.0F) / 128.0F, (f1 + 7.99F) / 128.0F);
        cir.setReturnValue((float) this.charWidth[ch]);
    }

    @Inject(method = "renderUnicodeChar", at = @At("HEAD"), cancellable = true, require = 0)
    private void pd3d$batchUnicode(char c, boolean italic, CallbackInfoReturnable<Float> cir) {
        FontBatch b = FontBatch.get();
        if (b.depth == 0) return;
        if (this.glyphWidth[c] == 0) {
            cir.setReturnValue(0.0F);
            return;
        }
        ResourceLocation page = getUnicodePageLocation(c / 256);
        if (b.switchTo(page)) bindTexture(page);
        int j = this.glyphWidth[c] >>> 4;
        int k = this.glyphWidth[c] & 15;
        float f = j, f1 = k + 1;
        float f2 = c % 16 * 16 + f, f3 = (c & 255) / 16 * 16;
        float f4 = f1 - f - 0.02F, f5 = italic ? 1.0F : 0.0F;
        b.quad(this.posX + f5, this.posX - f5, this.posX + f4 / 2.0F + f5, this.posX + f4 / 2.0F - f5, this.posY, this.posY + 7.99F,
            f2 / 256.0F, f3 / 256.0F, (f2 + f4) / 256.0F, (f3 + 15.98F) / 256.0F);
        cir.setReturnValue((f1 - f) / 2.0F + 1.0F);
    }

    @Inject(method = "getCharWidth", at = @At("HEAD"))
    private void pd3d$widthBegin(char c, CallbackInfoReturnable<Integer> cir) {
        this.pd3d$charIdx = -2;
    }

    @Redirect(method = "getCharWidth", at = @At(value = "INVOKE", target = "Ljava/lang/String;indexOf(I)I"), require = 0)
    private int pd3d$widthIndex(String charmap, int c) {
        int i = charmap.indexOf(c);
        this.pd3d$charIdx = i;
        return i;
    }

    @Inject(method = "getCharWidth", at = @At("RETURN"), cancellable = true)
    private void pd3d$unicodeWidth(char c, CallbackInfoReturnable<Integer> cir) {
        int idx = this.pd3d$charIdx;
        if (idx == -2) return;
        if (c > 0 && idx != -1 && !this.unicodeFlag) return;
        if (this.glyphWidth[c] == 0) return;
        int j = this.glyphWidth[c] >>> 4;
        int k = this.glyphWidth[c] & 15;
        cir.setReturnValue((int) ((k + 1 - j) / 2.0F + 1.0F));
    }
}
