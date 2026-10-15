package com.nikita23830.minecraftd3d.mixins;

import com.nikita23830.minecraftd3d.PD3D;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
@Mixin(value = org.lwjglx.opengl.Display.class, remap = false)
public abstract class MixinDisplay {
    @Inject(method = "create(Lorg/lwjglx/opengl/PixelFormat;Lorg/lwjglx/opengl/ContextAttribs;J)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void pd3d$noClientApi(CallbackInfo ci) {
        PD3D.markMainThread();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_ANY_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_NO_ERROR, GLFW.GLFW_FALSE);
        PD3D.LOG.info("MinecraftD3D: GLFW-Fenster wird ohne OpenGL erstellt (GLFW_NO_API)");
    }

    @Redirect(method = "create(Lorg/lwjglx/opengl/PixelFormat;Lorg/lwjglx/opengl/ContextAttribs;J)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private static void pd3d$noMakeCurrent(long window) {}

    @Redirect(method = "create(Lorg/lwjglx/opengl/PixelFormat;Lorg/lwjglx/opengl/ContextAttribs;J)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private static org.lwjgl.opengl.GLCapabilities pd3d$noCaps() {
        return null;
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V"))
    private static void pd3d$noSwapInterval(int interval) {}

    @Inject(method = "swapBuffers()V", at = @At("HEAD"), cancellable = true)
    private static void pd3d$noSwap(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = { "makeCurrent()V", "releaseContext()V" }, at = @At("HEAD"), cancellable = true)
    private static void pd3d$noContext(CallbackInfo ci) {
        ci.cancel();
    }
}
