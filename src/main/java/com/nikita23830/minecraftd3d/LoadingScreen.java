package com.nikita23830.minecraftd3d;

import com.nikita23830.minecraftd3d.engine.GLContext;
import com.nikita23830.minecraftd3d.engine.GLLock;
import com.nikita23830.minecraftd3d.engine.RS;

public final class LoadingScreen {
    private LoadingScreen() {}

    private static volatile Thread thread;
    private static volatile boolean kill;
    private static GLContext fresh;
    private static volatile long frames, skipped;

    private static final float[] intervals = new float[1 << 16];
    private static int nIntervals;
    private static long lastPresent, holdNanos;
    private static String summary = "Ladebildschirm: Engine-Thread wurde nicht gestartet\n";

    static void captureFresh() {
        fresh = GLContext.fresh();
    }

    public static boolean available() {
        return PD3D.engine && fresh != null;
    }

    static boolean isLoaderThread() {
        Thread t = thread;
        return t != null && Thread.currentThread() == t;
    }

    public static synchronized boolean start(final Runnable frame, final int fps) {
        if (!available() || thread != null) return false;
        kill = false;
        GLLock.shared = true;
        final GLContext[] ctx = { fresh };
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(frame, Math.max(10, fps), ctx);
            }
        }, "MinecraftD3D loading screen");
        t.setDaemon(true);
        thread = t;
        t.start();
        PD3D.LOG.info("MinecraftD3D: Ladebildschirm wird in eigenem Thread gezeichnet ({} FPS), GL des Hauptthreads ist gesperrt", fps);
        return true;
    }

    private static long secStart;
    private static int secFrames;
    private static float secWorst, secWait, secFrame, secPresent;

    private static void timeline(long now, float iv, float waitMs, float frameMs, float presentMs) {
        if (secStart == 0) secStart = now;
        secFrames++;
        if (iv > secWorst) { secWorst = iv; secWait = waitMs; secFrame = frameMs; secPresent = presentMs; }
        if (now - secStart >= 1_000_000_000L) {
            PD3D.LOG.info(String.format(java.util.Locale.ROOT,
                "MinecraftD3D: Laden – %d Frames/s, schlechtestes Intervall %.0f ms (Sperre %.1f, BLS-Frame %.1f, Present %.1f ms; GPU-Frame %.1f ms)",
                secFrames, secWorst, secWait, secFrame, secPresent, PD3D.statGpuMs));
            secStart = now; secFrames = 0; secWorst = 0;
        }
    }

    private static void loop(Runnable frame, int fps, GLContext[] ctx) {
        long period = 1_000_000_000L / fps, next = System.nanoTime();
        boolean warned = false;
        while (!kill) {
            long w0 = System.nanoTime();
            if (GLLock.tryLock(250)) {
                holdNanos += System.nanoTime() - w0;
                try {
                    GLContext main = GLContext.capture();
                    ctx[0].apply();
                    try {
                        RS.glViewport(0, 0, RS.fullW, RS.fullH);
                        long f0 = System.nanoTime();
                        frame.run();
                        long p0 = System.nanoTime();
                        PD3D.present();
                        frames++;
                        long now = System.nanoTime();
                        float iv = lastPresent == 0 ? 0 : (now - lastPresent) / 1e6f;
                        if (lastPresent != 0 && nIntervals < intervals.length) intervals[nIntervals++] = iv;
                        lastPresent = now;
                        timeline(now, iv, (f0 - w0) / 1e6f, (p0 - f0) / 1e6f, (now - p0) / 1e6f);
                    } catch (Throwable e) {
                        if (!warned) PD3D.LOG.warn("MinecraftD3D: Frame des Ladebildschirms", e);
                        warned = true;
                    } finally {
                        ctx[0] = GLContext.capture();
                        main.apply();
                    }
                } finally {
                    GLLock.unlock();
                }
            } else {
                skipped++;
            }
            next += period;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    public static synchronized void stop() {
        Thread t = thread;
        if (t == null) return;
        kill = true;
        try {
            t.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        GLLock.shared = false;
        summary = makeSummary();
        PD3D.LOG.info("MinecraftD3D: Ladebildschirm-Thread beendet. {}", summary.trim());
    }

    public static String summary() {
        return summary;
    }

    private static String makeSummary() {
        int n = nIntervals;
        if (n == 0) return "Ladebildschirm (Engine-Thread): Frames " + frames + "\n";
        float[] a = java.util.Arrays.copyOf(intervals, n);
        java.util.Arrays.sort(a);
        double sum = 0;
        int g34 = 0, g100 = 0;
        for (float v : a) {
            sum += v;
            if (v > 33.4f) g34++;
            if (v > 100f) g100++;
        }
        return String.format(java.util.Locale.ROOT,
            "Ladebildschirm (Engine-Thread): %d Frames in %.1f s, Intervall ms: Mittel %.1f Median %.1f p95 %.1f p99 %.1f max %.0f; >33 ms: %d, >100 ms: %d;"
                + " Warten auf Sperre (Hauptthread in GL) gesamt %.0f ms, übersprungene Frames %d%n",
            frames, sum / 1000, sum / n, a[n / 2], a[(int) (n * 0.95)], a[(int) (n * 0.99)], a[n - 1], g34, g100, holdNanos / 1e6, skipped);
    }
}
