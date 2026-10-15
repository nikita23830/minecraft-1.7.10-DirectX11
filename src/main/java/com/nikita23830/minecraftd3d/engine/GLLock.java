package com.nikita23830.minecraftd3d.engine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class GLLock {
    private GLLock() {}

    public static volatile boolean shared;

    static final ReentrantLock LOCK = new ReentrantLock(true);

    public static void enter() {
        if (shared) LOCK.lock();
    }

    public static void exit() {
        if (shared && LOCK.isHeldByCurrentThread()) LOCK.unlock();
    }

    public static boolean tryLock(long timeoutMs) {
        try {
            return LOCK.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void unlock() {
        LOCK.unlock();
    }
}
