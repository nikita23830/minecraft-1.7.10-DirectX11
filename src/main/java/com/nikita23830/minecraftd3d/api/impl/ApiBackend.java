package com.nikita23830.minecraftd3d.api.impl;

import com.nikita23830.minecraftd3d.PD3D;
import com.nikita23830.minecraftd3d.api.Backend;
import com.nikita23830.minecraftd3d.api.RenderDevice;
import com.nikita23830.minecraftd3d.api.RenderPass;

public abstract class ApiBackend {
    public abstract Backend kind();

    public abstract RenderDevice device();

    public abstract BaseContext worldContext(RenderPass pass, float pt, double cx, double cy, double cz, float[] fog);

    public abstract BaseContext guiContext(float pt);

    public static final class Natives {
        private Natives() {}

        public static long device() { return PD3D.apiDevicePtr(); }

        public static long context() { return PD3D.apiContextPtr(); }
    }
}
