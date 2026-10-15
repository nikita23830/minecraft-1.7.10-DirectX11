package com.nikita23830.minecraftd3d.core;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(-100)
@IFMLLoadingPlugin.TransformerExclusions("com.nikita23830.minecraftd3d.core.")
public final class MinecraftD3DCore implements IFMLLoadingPlugin {
    static final boolean ENABLED = "engine".equals(System.getProperty("minecraftd3d"));

    public MinecraftD3DCore() {
        System.out.println("[MinecraftD3D] Coremod geladen, Engine: " + ENABLED);
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "com.nikita23830.minecraftd3d.core.TessellatorBufferTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
