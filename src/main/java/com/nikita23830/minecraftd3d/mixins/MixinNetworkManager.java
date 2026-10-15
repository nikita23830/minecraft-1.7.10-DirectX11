package com.nikita23830.minecraftd3d.mixins;

import java.util.Queue;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {
    @Shadow
    private Channel channel;
    @Shadow
    private EnumConnectionState connectionState;
    @Shadow
    @Final
    private Queue receivedPacketsQueue;
    @Shadow
    @Final
    public static AttributeKey attrKeyConnectionState;
    @Shadow
    @Final
    private boolean isClientSide;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void pd3d$deferPlayPriority(ChannelHandlerContext ctx, Packet packet, CallbackInfo ci) {
        if (!this.channel.isOpen() || !packet.hasPriority()) return;

        if (!this.isClientSide) return;
        Object state = this.channel.attr(attrKeyConnectionState).get();
        if (state == EnumConnectionState.PLAY && this.connectionState != EnumConnectionState.PLAY) {
            this.receivedPacketsQueue.add(packet);
            ci.cancel();
        }
    }
}
