package com.vexsoftware.votifier.net;

import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NettyShutdownTest {
    @Test
    void finalCleanupDrainsNotificationsAndJoinsExecutorDespiteInterruption() throws Exception {
        CountDownLatch notificationEntered = new CountDownLatch(1);
        CountDownLatch releaseNotifications = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptionRetained = new AtomicBoolean();
        GlobalEventExecutor.INSTANCE.submit(() -> {
            notificationEntered.countDown();
            releaseNotifications.await();
            return null;
        });
        assertTrue(notificationEntered.await(5, TimeUnit.SECONDS));
        Thread cleanup = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                NettyShutdown.awaitGlobalExecutor();
                interruptionRetained.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        }, "Votifier final cleanup regression");
        try {
            cleanup.start();
            assertFalse(finished.await(200, TimeUnit.MILLISECONDS), "Cleanup skipped queued work");
            releaseNotifications.countDown();
            // Exercise interruption while joining the idle worker as well.
            cleanup.interrupt();
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Cleanup did not finish");
            assertNull(failure.get());
            assertTrue(interruptionRetained.get(), "Cleanup lost its caller's interrupt flag");
            assertTrue(GlobalEventExecutor.INSTANCE.awaitInactivity(0, TimeUnit.NANOSECONDS),
                    "The notification executor can still access the plugin classloader");
        } finally {
            releaseNotifications.countDown();
            cleanup.join(5000);
        }
    }
}
