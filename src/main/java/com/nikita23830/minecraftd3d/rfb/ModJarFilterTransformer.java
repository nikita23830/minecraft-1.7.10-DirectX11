package com.nikita23830.minecraftd3d.rfb;

import java.util.jar.Manifest;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;

public final class ModJarFilterTransformer implements RfbClassTransformer {
    private static final String TARGET = "cpw.mods.fml.relauncher.FileListHelper";

    @Override
    public String id() {
        return "modjars";
    }

    @Override
    public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String className,
        ClassNodeHandle node) {
        return TARGET.equals(className) && node.isPresent();
    }

    @Override
    public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String className, ClassNodeHandle handle) {
        ClassNode cn = handle.getNode();
        if (cn == null) return;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("sortFileList") || !m.desc.equals("([Ljava/io/File;)[Ljava/io/File;")) continue;
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/nikita23830/minecraftd3d/rfb/ModJarFilter", "filter", "([Ljava/io/File;)[Ljava/io/File;", false));
            l.add(new VarInsnNode(Opcodes.ASTORE, 0));
            m.instructions.insert(l);
            handle.computeMaxs();
            System.out.println("[MinecraftD3D] Mods: Jar-Filter in FileListHelper eingebaut (" + context + ")");
        }
    }
}
