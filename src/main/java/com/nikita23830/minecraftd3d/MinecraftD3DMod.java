package com.nikita23830.minecraftd3d;

import com.nikita23830.minecraftd3d.api.impl.ApiDispatch;
import com.nikita23830.minecraftd3d.api.impl.GlApiBackend;
import com.nikita23830.minecraftd3d.engine.BlockBounds;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = "minecraftd3d", name = "MinecraftD3D", version = "0.1", acceptableRemoteVersions = "*")
public class MinecraftD3DMod {
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent ev) {
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent ev) {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) BlockBounds.snapshot();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent ev) {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT && !PD3D.engine) {
            ApiDispatch.install(new GlApiBackend());
        }
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new FrameStats.Overlay());
        }
    }
}
