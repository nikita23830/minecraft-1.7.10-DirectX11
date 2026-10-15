package com.nikita23830.minecraftd3d.engine;

import java.util.IdentityHashMap;

public final class MeshThread extends Thread {
    final IdentityHashMap<Object, double[]> bounds = new IdentityHashMap<Object, double[]>();

    int anvilSide;

    public MeshThread(Runnable r, String name) {
        super(r, name);
    }
}
