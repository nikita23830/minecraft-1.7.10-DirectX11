package com.nikita23830.minecraftd3d.engine;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.nikita23830.minecraftd3d.PD3D;

public final class GLContext {
    private static final Class<?>[] CLASSES = { RS.class, DL.class, Begin.class, VA.class, GLEmu.class, FB.class };

    private static final HashSet<String> SHARED = new HashSet<String>(Arrays.asList(
        "RS.nextTex", "RS.lightmapColors", "RS.fullW", "RS.fullH", "RS.lastTexOff", "RS.projVersion", "RS.lightVersion",
        "RS.stateVersion", "RS.tmp", "RS.res", "DL.next", "DL.calls", "DL.ops", "VA.nextBuffer", "GLEmu.offThreadWarned",
        "GLEmu.caps", "FB.nextFbo", "FB.nextRb", "DL.nextMesh", "DL.meshCalls", "DL.meshGroups"));
    private static final Field[] FIELDS = collect();

    private final Object[] values = new Object[FIELDS.length];

    private GLContext() {}

    private static Field[] collect() {
        List<Field> out = new ArrayList<Field>();
        StringBuilder names = new StringBuilder();
        for (Class<?> c : CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isStatic(m) || f.isSynthetic()) continue;
                if (SHARED.contains(c.getSimpleName() + "." + f.getName())) continue;
                Class<?> t = f.getType();
                if (Modifier.isFinal(m) && !t.isArray()) continue;
                if (Buffer.class.isAssignableFrom(t)) continue;
                if (Modifier.isFinal(m) && !t.getComponentType().isPrimitive() && !t.getComponentType().isArray()) {
                    throw new IllegalStateException("MinecraftD3D: final-Objektarray " + c.getSimpleName() + "." + f.getName() + " lässt sich nicht umschalten");
                }
                f.setAccessible(true);
                out.add(f);
                names.append(c.getSimpleName()).append('.').append(f.getName()).append(' ');
            }
        }
        PD3D.LOG.info("MinecraftD3D: GL-Emulationskontext – {} Felder: {}", out.size(), names);
        return out.toArray(new Field[0]);
    }

    public static GLContext fresh() {
        GLContext c = capture();
        for (int i = 0; i < FIELDS.length; i++) {
            Object v = c.values[i];
            if (v == null || FIELDS[i].getType().isPrimitive()) continue;
            if (v.getClass().isArray() && !v.getClass().getComponentType().isPrimitive() && !v.getClass().getComponentType().isArray()) {
                c.values[i] = Array.newInstance(v.getClass().getComponentType(), Array.getLength(v));
            } else if (v instanceof VA.Ptr) {
                c.values[i] = new VA.Ptr();
            } else if (v instanceof ArrayList) {
                c.values[i] = null;
            }
        }
        return c;
    }

    public static GLContext capture() {
        GLContext c = new GLContext();
        try {
            for (int i = 0; i < FIELDS.length; i++) c.values[i] = copy(FIELDS[i].get(null));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    public void apply() {
        try {
            for (int i = 0; i < FIELDS.length; i++) {
                Field f = FIELDS[i];
                Object v = values[i];
                if (Modifier.isFinal(f.getModifiers())) copyInto(v, f.get(null));
                else f.set(null, Modifier.isFinal(f.getModifiers()) ? v : copyIfPrimitiveArray(v));
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        RS.projVersion++;
        RS.lightVersion++;
        RS.stateVersion++;
        FB.rebind();
    }

    private static Object copy(Object v) {
        if (v == null || !v.getClass().isArray()) return v;
        Class<?> ct = v.getClass().getComponentType();
        if (ct.isPrimitive()) {
            int n = Array.getLength(v);
            Object r = Array.newInstance(ct, n);
            System.arraycopy(v, 0, r, 0, n);
            return r;
        }
        if (ct.isArray()) {
            int n = Array.getLength(v);
            Object r = Array.newInstance(ct, n);
            for (int i = 0; i < n; i++) Array.set(r, i, copy(Array.get(v, i)));
            return r;
        }
        return v;
    }

    private static Object copyIfPrimitiveArray(Object v) {
        return v != null && v.getClass().isArray() && (v.getClass().getComponentType().isPrimitive() || v.getClass().getComponentType().isArray()) ? copy(v) : v;
    }

    private static void copyInto(Object src, Object dst) {
        Class<?> ct = dst.getClass().getComponentType();
        int n = Array.getLength(dst);
        if (ct.isPrimitive()) System.arraycopy(src, 0, dst, 0, n);
        else for (int i = 0; i < n; i++) copyInto(Array.get(src, i), Array.get(dst, i));
    }
}
