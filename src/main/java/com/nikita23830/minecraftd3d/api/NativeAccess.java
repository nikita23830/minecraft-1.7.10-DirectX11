package com.nikita23830.minecraftd3d.api;

import com.nikita23830.minecraftd3d.api.impl.ApiBackend;

public final class NativeAccess {
    private NativeAccess() {}

    public static long d3d11Device() {
        return MinecraftD3DRender.backend() == Backend.D3D11 ? ApiBackend.Natives.device() : 0L;
    }

    public static long d3d11Context() {
        return MinecraftD3DRender.backend() == Backend.D3D11 ? ApiBackend.Natives.context() : 0L;
    }
}
