package com.nikita23830.minecraftd3d.rfb;

import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;

public final class MinecraftD3DRfbPlugin implements RfbPlugin {
    public static final boolean ENABLED = "engine".equals(System.getProperty("minecraftd3d"));

    @Override
    public void onConstruction(PluginContext ctx) {
        if (!ENABLED) return;
        try {
            narrowLwjglxExclusion();
        } catch (Throwable t) {
            System.err.println("[MinecraftD3D] Ausschluss org.lwjglx. konnte nicht eingeschränkt werden: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void narrowLwjglxExclusion() throws Exception {
        LaunchClassLoader lcl = Launch.classLoader;
        Field f = LaunchClassLoader.class.getDeclaredField("transformerExceptions");
        f.setAccessible(true);
        Set<String> ex = (Set<String>) f.get(lcl);
        URL url = lcl.getResource("org/lwjglx/opengl/Display.class");
        if (url == null) url = ClassLoader.getSystemClassLoader().getResource("org/lwjglx/opengl/Display.class");
        if (url == null || !(url.openConnection() instanceof JarURLConnection)) {
            System.err.println("[MinecraftD3D] org/lwjglx/opengl/Display.class nicht im Jar gefunden: " + url);
            return;
        }
        int added = 0;
        try (JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (!n.startsWith("org/lwjglx/") || !n.endsWith(".class")) continue;
                String cls = n.substring(0, n.length() - 6).replace('/', '.');
                if (cls.equals("org.lwjglx.opengl.Display") || cls.startsWith("org.lwjglx.opengl.Display$")) continue;
                ex.add(cls);
                added++;
            }
        }
        ex.remove("org.lwjglx.");
        System.out.println("[MinecraftD3D] Ausschluss org.lwjglx. eingeschränkt: " + added + " Klassen, Display für Mixin freigegeben");
    }

    @Override
    public RfbClassTransformer[] makeTransformers() {
        if (!ENABLED) return new RfbClassTransformer[] { new ModJarFilterTransformer() };
        return new RfbClassTransformer[] { new ModJarFilterTransformer(), new GLRedirectTransformer() };
    }
}
