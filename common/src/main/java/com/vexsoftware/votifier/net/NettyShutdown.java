package com.vexsoftware.votifier.net;

import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.TimeUnit;

/** Final cleanup before a platform closes the plugin classloader. */
public final class NettyShutdown {
    private static final long TIMEOUT_SECONDS = 15;

    private NettyShutdown() {
    }

    public static void awaitGlobalExecutor() {
        // All channel/event-loop producers must already be stopped. This barrier
        // drains their queued notifications and starts the executor if it has
        // never run, making awaitInactivity valid even after a failed enable.
        GlobalEventExecutor executor = GlobalEventExecutor.INSTANCE;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        if (!executor.submit(() -> { }).awaitUninterruptibly(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Netty notification executor did not drain before plugin disable");
        }
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Netty notification executor did not stop before plugin disable");
                }
                try {
                    if (!executor.awaitInactivity(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("Netty notification executor did not stop before plugin disable");
                    }
                    return;
                } catch (InterruptedException e) {
                    // Disable must finish cleanup even when its caller is already
                    // interrupted; restore the flag only after the thread exits.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
