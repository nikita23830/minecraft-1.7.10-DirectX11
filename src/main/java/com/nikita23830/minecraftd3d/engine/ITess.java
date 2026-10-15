package com.nikita23830.minecraftd3d.engine;

public interface ITess {
    int[] pd3d$raw();

    int pd3d$vertexCount();

    boolean pd3d$hasColor();

    boolean pd3d$hasBrightness();

    boolean pd3d$isDrawing();

    int pd3d$drawMode();

    void pd3d$finish();

    void pd3d$truncate(int n);
}
