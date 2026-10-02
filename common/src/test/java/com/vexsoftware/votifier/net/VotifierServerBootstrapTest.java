package com.vexsoftware.votifier.net;

import com.vexsoftware.votifier.platform.LoggingAdapter;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VotifierServerBootstrapTest {
    @Test
    void shutdownWaitsForTerminationNotificationsAndRetainsInterruption() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        VotifierPlugin plugin = mock(VotifierPlugin.class);
        when(plugin.getPluginLogger()).thenReturn(mock(LoggingAdapter.class));
        VotifierServerBootstrap bootstrap = new VotifierServerBootstrap("127.0.0.1", port, plugin, true);
        CountDownLatch notificationEntered = new CountDownLatch(1);
        CountDownLatch releaseNotifications = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptionRetained = new AtomicBoolean();
        Thread shutdown = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                bootstrap.shutdown();
                interruptionRetained.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        }, "Votifier shutdown regression");
        try {
            start(bootstrap);
            GlobalEventExecutor.INSTANCE.submit(() -> {
                notificationEntered.countDown();
                releaseNotifications.await();
                return null;
            });
            assertTrue(notificationEntered.await(5, TimeUnit.SECONDS));
            shutdown.start();
            for (String fieldName : new String[]{"bossLoopGroup", "eventLoopGroup"}) {
                var field = VotifierServerBootstrap.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                EventLoopGroup group = (EventLoopGroup) field.get(bootstrap);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!group.isTerminated() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(group.isTerminated(), "Network threads did not terminate");
            }
            assertFalse(finished.await(200, TimeUnit.MILLISECONDS),
                    "Shutdown returned while termination notifications were still queued");
        } finally {
            releaseNotifications.countDown();
            shutdown.join(5000);
            bootstrap.shutdown();
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(interruptionRetained.get(), "Shutdown lost its caller's interrupt flag");
    }

    @Test
    void reloadClosesPromptlyAndRebindsTheVoteListener() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        VotifierPlugin plugin = mock(VotifierPlugin.class);
        when(plugin.getPluginLogger()).thenReturn(mock(LoggingAdapter.class));

        VotifierServerBootstrap first = new VotifierServerBootstrap("127.0.0.1", port, plugin, true);
        long elapsedMillis;
        try {
            startAndCheckGreeting(first, port);
        } finally {
            long started = System.nanoTime();
            first.shutdown();
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }
        assertTrue(elapsedMillis < 1500, "Reload shutdown took " + elapsedMillis + " ms");

        VotifierServerBootstrap second = new VotifierServerBootstrap("127.0.0.1", port, plugin, true);
        try {
            startAndCheckGreeting(second, port);
        } finally {
            second.shutdown();
        }
    }

    private static void startAndCheckGreeting(VotifierServerBootstrap bootstrap, int port) throws Exception {
        start(bootstrap);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            String greeting = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
            assertNotNull(greeting, "Vote listener closed without a greeting");
            assertTrue(greeting.startsWith("VOTIFIER 2 "), "Unexpected vote greeting: " + greeting);
        }
    }

    private static void start(VotifierServerBootstrap bootstrap) throws Exception {
        CountDownLatch bound = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        bootstrap.start(error -> {
            failure.set(error);
            bound.countDown();
        });
        assertTrue(bound.await(5, TimeUnit.SECONDS), "Vote listener did not bind");
        assertNull(failure.get(), "Vote listener failed to bind");
    }
}
