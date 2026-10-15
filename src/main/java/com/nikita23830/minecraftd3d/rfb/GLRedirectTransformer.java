package com.nikita23830.minecraftd3d.rfb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;

public final class GLRedirectTransformer implements RfbClassTransformer {
    static final String EMU = "com/nikita23830/minecraftd3d/engine/GLEmu";
    static final String LOCK = "com/nikita23830/minecraftd3d/engine/GLLock";
    static final String TESS = "net/minecraft/client/renderer/Tessellator";
    static final String TESS_HOOK = "com/nikita23830/minecraftd3d/engine/Tess";

    private static final byte[][] SCAN = { bytes("org/lwjgl/opengl/"), bytes("org/lwjgl/util/glu/"), bytes(TESS),
        bytes("field_149759_B"), bytes("field_149760_C"), bytes("field_149754_D"), bytes("field_149755_E"), bytes("field_149756_F"), bytes("field_149757_G"),
        bytes("field_149833_b") };

    static final String BOUNDS = "com/nikita23830/minecraftd3d/engine/BlockBounds";
    private static final String[] BOUNDS_FIELDS = { "field_149759_B", "field_149760_C", "field_149754_D", "field_149755_E", "field_149756_F", "field_149757_G" };

    private static int boundsIndex(String name) {
        for (int i = 0; i < 6; i++) if (BOUNDS_FIELDS[i].equals(name)) return i;
        return -1;
    }
    private static final String[] SKIP_PREFIX = { "com.nikita23830.minecraftd3d.", "org.lwjgl.", "java.", "javax.", "sun.", "com.sun.", "jdk.",
        "org.objectweb.", "org.spongepowered.", "com.google.", "org.apache.", "io.netty.", "scala.", "kotlin.",
        "com.gtnewhorizons.retrofuturabootstrap.", "me.eigenraven.lwjgl3ify.", "it.unimi.", "gnu.trove.", "com.llamalad7." };
    private static final Set<String> KEEP_OWNERS = new HashSet<String>();
    static {
        for (String s : new String[] { "Display", "DisplayMode", "PixelFormat", "ContextAttribs" })
            KEEP_OWNERS.add("org/lwjgl/opengl/" + s);
    }

    private final Set<String> emu = new HashSet<String>();
    private PrintWriter log;
    private int classes, redirects, stubs, tess;

    public GLRedirectTransformer() {
        try (InputStream in = GLRedirectTransformer.class.getClassLoader().getResourceAsStream(EMU + ".class")) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_PUBLIC) != 0) emu.add(name + desc);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        } catch (Exception e) {
            throw new RuntimeException("MinecraftD3D: nicht lesbar: " + EMU, e);
        }
        try {
            new File("logs").mkdirs();
            log = new PrintWriter(new OutputStreamWriter(new FileOutputStream("logs/minecraftd3d-transform.log"), StandardCharsets.UTF_8));
            log.println("# GLEmu: " + emu.size() + " Methoden. Zeilen: Klasse | umgeleitet | Stubs | Tessellator.instance | Stubs");
            log.flush();
        } catch (Exception e) {
            log = null;
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String id() {
        return "glemu";
    }

    @Override
    public String[] sortAfter() {
        return new String[] { "*", "mixin:mixin" };
    }

    @Override
    public String[] sortBefore() {
        return new String[] { "lwjgl3ify:redirect", "gtnhlib:redirector", "gtnhlib:tessellator" };
    }

    @Override
    public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String className,
        ClassNodeHandle node) {
        if (context == Context.SYSTEM || !node.isPresent()) return false;
        for (String p : SKIP_PREFIX) if (className.startsWith(p)) return false;

        if (className.startsWith("org.lwjglx.") && !className.startsWith("org.lwjglx.util.glu.")) return false;
        if (className.equals("net.minecraft.client.renderer.Tessellator")) return false;

        if (className.startsWith("com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager")) return false;
        byte[] b = node.getOriginalBytes();
        if (b == null) return true;
        for (byte[] p : SCAN) if (ClassHeaderMetadata.hasSubstring(b, p)) return true;
        return false;
    }

    @Override
    public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String className, ClassNodeHandle handle) {
        ClassNode cn = handle.getNode();
        if (cn == null) return;
        int r = 0, s = 0, t = 0, bnd = 0;
        TreeMap<String, Integer> stubList = null;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if ((mi.owner.startsWith("org/lwjgl/opengl/") || mi.owner.startsWith("org/lwjgl/util/glu/")) && !KEEP_OWNERS.contains(mi.owner)) {
                        String key = mi.name + mi.desc.replace("Lorg/lwjgl/", "Lorg/lwjglx/");
                        if (emu.contains(key)) {
                            mi.owner = EMU;
                            mi.itf = false;

                            m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "enter", "()V", false));
                            m.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "exit", "()V", false));
                            r++;
                        } else if (mi.owner.startsWith("org/lwjgl/util/glu/") && !gluCallsGl(mi.name)) {
                        } else {
                            String what = mi.owner.substring(mi.owner.lastIndexOf('/') + 1) + "." + mi.name + mi.desc;
                            m.instructions.insert(insn, stub(mi, what + " @ " + className));
                            m.instructions.remove(insn);
                            s++;
                            if (stubList == null) stubList = new TreeMap<String, Integer>();
                            stubList.merge(what, 1, Integer::sum);
                        }
                    }
                } else if ((insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC)
                    && TESS.equals(((FieldInsnNode) insn).owner) && isByteBuffer((FieldInsnNode) insn)) {
                    ((FieldInsnNode) insn).name = "pd3d$byteBufferShared";
                    t++;
                } else if ((insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.PUTFIELD)
                    && "D".equals(((FieldInsnNode) insn).desc) && boundsIndex(((FieldInsnNode) insn).name) >= 0) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    int bi = boundsIndex(fi.name);
                    boolean get = insn.getOpcode() == Opcodes.GETFIELD;
                    m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, BOUNDS, (get ? "get" : "set") + bi,
                        get ? "(Lnet/minecraft/block/Block;)D" : "(Lnet/minecraft/block/Block;D)V", false));
                    bnd++;
                } else if ((insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.PUTFIELD)
                    && "I".equals(((FieldInsnNode) insn).desc) && "field_149833_b".equals(((FieldInsnNode) insn).name)) {
                    boolean get = insn.getOpcode() == Opcodes.GETFIELD;
                    m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, BOUNDS, get ? "anvilGet" : "anvilSet",
                        get ? "(Ljava/lang/Object;)I" : "(Ljava/lang/Object;I)V", false));
                    bnd++;
                } else if (insn.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    if (fi.owner.equals(TESS) && (fi.name.equals("field_78398_a") || fi.name.equals("instance")) && fi.desc.equals("L" + TESS + ";")) {
                        m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, TESS_HOOK, "instance", "()L" + TESS + ";", false));
                        t++;
                    }
                }
                insn = next;
            }
        }
        if (r + s + t + bnd == 0) return;
        handle.computeMaxs();
        synchronized (this) {
            classes++;
            redirects += r;
            stubs += s;
            tess += t;
            if (log != null) {
                log.println(className + " | " + r + " | " + s + " | " + t + (bnd > 0 ? " | Blockgrenzen: " + bnd : "") + (stubList != null ? " | " + stubList : ""));
                if ((classes & 31) == 0 || s > 0) log.flush();
            }
        }
    }

    private static boolean isByteBuffer(FieldInsnNode fi) {
        return (fi.name.equals("field_78394_d") || fi.name.equals("byteBuffer")) && fi.desc.equals("Ljava/nio/ByteBuffer;");
    }

    private static boolean gluCallsGl(String name) {
        return name.equals("gluPerspective") || name.equals("gluLookAt") || name.equals("gluOrtho2D") || name.equals("gluPickMatrix")
            || name.startsWith("gluBuild") || name.equals("gluScaleImage");
    }

    private static InsnList stub(MethodInsnNode mi, String what) {
        InsnList l = new InsnList();
        Type[] args = Type.getArgumentTypes(mi.desc);
        for (int i = args.length - 1; i >= 0; i--) l.add(new InsnNode(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "enter", "()V", false));
        l.add(new LdcInsnNode(what));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EMU, "unported", "(Ljava/lang/String;)V", false));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCK, "exit", "()V", false));
        Type ret = Type.getReturnType(mi.desc);
        switch (ret.getSort()) {
            case Type.VOID: break;
            case Type.BOOLEAN: case Type.CHAR: case Type.BYTE: case Type.SHORT: case Type.INT: l.add(new InsnNode(Opcodes.ICONST_0)); break;
            case Type.FLOAT: l.add(new InsnNode(Opcodes.FCONST_0)); break;
            case Type.LONG: l.add(new InsnNode(Opcodes.LCONST_0)); break;
            case Type.DOUBLE: l.add(new InsnNode(Opcodes.DCONST_0)); break;
            default:
                if (ret.getDescriptor().equals("Ljava/lang/String;")) l.add(new LdcInsnNode(""));
                else l.add(new InsnNode(Opcodes.ACONST_NULL));
        }
        return l;
    }
}
