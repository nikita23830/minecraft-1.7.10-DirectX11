package com.nikita23830.minecraftd3d.mixins;

import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nikita23830.minecraftd3d.engine.Tess;

@Mixin(Chunk.class)
public abstract class MixinChunk {
    @Shadow
    public Map<ChunkPosition, TileEntity> chunkTileEntityMap;

    @Inject(method = "func_150806_e", at = @At("HEAD"), cancellable = true)
    private void pd3d$noCreateOffThread(int x, int y, int z, CallbackInfoReturnable<TileEntity> cir) {
        if (!Tess.isMesherThread()) return;
        TileEntity te = this.chunkTileEntityMap.get(new ChunkPosition(x, y, z));
        cir.setReturnValue(te != null && !te.isInvalid() ? te : null);
    }
}
