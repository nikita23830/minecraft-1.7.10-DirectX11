package com.nikita23830.minecraftd3d.core;

import java.util.List;
import java.util.Set;

import com.nikita23830.minecraftd3d.rfb.ModJarFilter;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinPlugin implements IMixinConfigPlugin {
    private static final boolean ENABLED = "engine".equals(System.getProperty("minecraftd3d"));

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ModJarFilter.optifineLoaded() && (mixinClassName.endsWith(".MixinFontRenderer")
            || mixinClassName.endsWith(".MixinApiHooks") || mixinClassName.endsWith(".AccessorEntityRenderer"))) return false;

        return ENABLED || mixinClassName.endsWith(".MixinFontRenderer") || mixinClassName.endsWith(".MixinApiHooks")
            || mixinClassName.endsWith(".AccessorEntityRenderer")
            || mixinClassName.endsWith(".MixinFrameStats");
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
