package com.nikita23830.minecraftd3d;

import org.lwjgl.opengl.GL11;

import com.nikita23830.minecraftd3d.engine.GLEmu;

public final class GLX {
    private GLX() {}

    public static void color(float r, float g, float b, float a) {
        if (PD3D.engine) GLEmu.glColor4f(r, g, b, a);
        else GL11.glColor4f(r, g, b, a);
    }

    public static int genLists(int n) {
        return PD3D.engine ? GLEmu.glGenLists(n) : GL11.glGenLists(n);
    }

    public static void newList(int list) {
        if (PD3D.engine) GLEmu.glNewList(list, GL11.GL_COMPILE);
        else GL11.glNewList(list, GL11.GL_COMPILE);
    }

    public static void endList() {
        if (PD3D.engine) GLEmu.glEndList();
        else GL11.glEndList();
    }

    public static void callList(int list) {
        if (PD3D.engine) GLEmu.glCallList(list);
        else GL11.glCallList(list);
    }

    public static String glString(int name) {
        return PD3D.engine ? null : GL11.glGetString(name);
    }
}
