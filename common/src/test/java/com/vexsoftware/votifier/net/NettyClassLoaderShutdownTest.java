package com.vexsoftware.votifier.net;

import com.google.gson.Gson;
import com.vexsoftware.votifier.VoteHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyClassLoaderShutdownTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(20)
    void finalDisableFinishesBeforeItsPluginJarIsClosed() throws Exception {
        Path fixture = directory.resolve("isolated-votifier.jar");
        packageRuntimeClasses(fixture);
        List<Thread> ownedThreads = new ArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        JarPluginClassLoader loader = new JarPluginClassLoader(fixture);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> pluginType = loader.loadClass("com.vexsoftware.votifier.platform.VotifierPlugin");
            Class<?> loggerType = loader.loadClass("com.vexsoftware.votifier.platform.LoggingAdapter");
            Object logger = Proxy.newProxyInstance(loader, new Class<?>[]{loggerType}, (proxy, method, args) -> {
                if (method.getName().equals("error")) {
                    errors.add(String.valueOf(args[0]));
                }
                return null;
            });
            Object plugin = Proxy.newProxyInstance(loader, new Class<?>[]{pluginType}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "getPluginLogger" -> logger;
                        case "getTokens" -> Map.of();
                        case "isDebug" -> false;
                        default -> null;
                    });
            Class<?> bootstrapType = loader.loadClass(VotifierServerBootstrap.class.getName());
            Object bootstrap = bootstrapType.getConstructor(String.class, int.class, pluginType, boolean.class)
                    .newInstance("127.0.0.1", 0, plugin, true);
            assertSame(loader, bootstrapType.getClassLoader());
            CountDownLatch bound = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Consumer<Throwable> callback = error -> {
                failure.set(error);
                bound.countDown();
            };
            try {
                bootstrapType.getMethod("start", Consumer.class).invoke(bootstrap, callback);
                assertTrue(bound.await(5, TimeUnit.SECONDS), "Isolated vote listener did not bind");
                assertNull(failure.get(), "Isolated vote listener failed to bind");
                for (String fieldName : List.of("bossLoopGroup", "eventLoopGroup")) {
                    Field field = bootstrapType.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    for (Object executor : (Iterable<?>) field.get(bootstrap)) {
                        ownedThreads.add(captureThread((ExecutorService) executor, errors));
                    }
                }
                Class<?> globalType = loader.loadClass(GlobalEventExecutor.class.getName());
                assertSame(loader, globalType.getClassLoader());
                ownedThreads.add(captureThread((ExecutorService) globalType.getField("INSTANCE").get(null), errors));
            } finally {
                bootstrapType.getMethod("shutdown").invoke(bootstrap);
                loader.loadClass(NettyShutdown.class.getName()).getMethod("awaitGlobalExecutor").invoke(null);
                // Match Paper's ordering: close the plugin's JAR immediately after final disable.
                loader.close();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            loader.close();
        }
        for (Thread thread : ownedThreads) {
            assertFalse(thread.isAlive(), "Plugin-owned thread survived JAR closure: " + thread.getName());
        }
        assertTrue(errors.isEmpty(), "Isolated Netty cleanup errors: " + errors);
        assertTrue(loader.lateLookups.isEmpty(), "Class lookups after JAR closure: " + loader.lateLookups);
    }

    private static Thread captureThread(ExecutorService executor, List<String> errors) throws Exception {
        return executor.submit(() -> {
            Thread thread = Thread.currentThread();
            thread.setUncaughtExceptionHandler((failed, error) -> errors.add(failed.getName() + ": " + error));
            return thread;
        }).get(5, TimeUnit.SECONDS);
    }

    private static void packageRuntimeClasses(Path fixture) throws Exception {
        Set<Path> origins = new HashSet<>();
        for (Class<?> type : List.of(VotifierServerBootstrap.class, VoteHandler.class, Gson.class,
                GlobalEventExecutor.class, Channel.class, ByteBuf.class, AddressResolver.class,
                ByteToMessageDecoder.class, ReadTimeoutHandler.class, Epoll.class, DomainSocketAddress.class)) {
            origins.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        }
        Set<String> written = new HashSet<>();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(fixture))) {
            for (Path origin : origins) {
                if (Files.isDirectory(origin)) {
                    try (var files = Files.walk(origin)) {
                        for (Path file : files.filter(Files::isRegularFile).toList()) {
                            writeClass(output, written, origin.relativize(file).toString().replace('\\', '/'),
                                    Files.readAllBytes(file));
                        }
                    }
                } else {
                    try (JarFile jar = new JarFile(origin.toFile())) {
                        for (JarEntry entry : jar.stream().filter(item -> !item.isDirectory()).toList()) {
                            if (entry.getName().endsWith(".class")) {
                                try (var input = jar.getInputStream(entry)) {
                                    writeClass(output, written, entry.getName(), input.readAllBytes());
                                }
                            }
                        }
                    }
                }
            }
        }
        // No native binaries are copied: this portable classloader test exercises the NIO lifecycle.
    }

    private static void writeClass(JarOutputStream output, Set<String> written, String name, byte[] bytes)
            throws IOException {
        if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")
                || !written.add(name)) {
            return;
        }
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    /** Like Bukkit, resolution reads an open JarFile and fails if cleanup outlives its closure. */
    private static final class JarPluginClassLoader extends ClassLoader implements AutoCloseable {
        private final JarFile jar;
        private final List<String> lateLookups = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        private JarPluginClassLoader(Path fixture) throws IOException {
            super(ClassLoader.getPlatformClassLoader());
            jar = new JarFile(fixture.toFile());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (closed && (name.startsWith("io.netty.") || name.startsWith("com.vexsoftware.votifier."))) {
                lateLookups.add(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                JarEntry entry = jar.getJarEntry(name.replace('.', '/') + ".class");
                if (entry == null) {
                    throw new ClassNotFoundException(name);
                }
                try (var input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                jar.close();
            }
        }
    }
}
