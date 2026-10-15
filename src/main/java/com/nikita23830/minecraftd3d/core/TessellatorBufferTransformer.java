package com.nikita23830.minecraftd3d.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class TessellatorBufferTransformer implements IClassTransformer, Opcodes {
    static final String TESS = "net/minecraft/client/renderer/Tessellator";
    public static final String SHARED = "pd3d$byteBufferShared";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraft.client.renderer.Tessellator".equals(transformedName)) return bytes;
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);

        FieldNode bb = null;
        int found = 0;
        for (FieldNode f : cn.fields)
            if (f.desc.equals("Ljava/nio/ByteBuffer;") && (f.access & ACC_STATIC) != 0) { bb = f; found++; }
        if (found != 1) return bytes;
        String owner = cn.name;
        String bbName = bb.name;
        bb.access = (bb.access & ~(ACC_STATIC | ACC_PRIVATE | ACC_PROTECTED | ACC_FINAL)) | ACC_PUBLIC;
        cn.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, SHARED, "Ljava/nio/ByteBuffer;", null, null));
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    if (fi.owner.equals(owner) && fi.name.equals(bbName) && (fi.getOpcode() == GETSTATIC || fi.getOpcode() == PUTSTATIC)) fi.name = SHARED;
                }
            }
            if (m.name.equals("<init>")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>")) {
                        InsnList l = new InsnList();
                        l.add(new VarInsnNode(ALOAD, 0));
                        l.add(new FieldInsnNode(GETSTATIC, owner, SHARED, "Ljava/nio/ByteBuffer;"));
                        l.add(new FieldInsnNode(PUTFIELD, owner, bbName, "Ljava/nio/ByteBuffer;"));
                        m.instructions.insert(insn, l);
                        break;
                    }
                }
            }
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        System.out.println("[MinecraftD3D] Tessellator." + bbName + " -> Instanzfeld (Kompatibilität mit GTNHLib)");
        return cw.toByteArray();
    }
}
