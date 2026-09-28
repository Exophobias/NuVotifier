package com.vexsoftware.votifier.net;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounds unauthenticated connections independently of how often clients send data. */
final class VotifierConnectionManager {
    private static final int ADMISSIONS_PER_SECOND = 128;
    private static final int MAXIMUM_ADMISSION_BURST = 256;
    private final int maximumConnections;
    private final int maximumConnectionsPerAddress;
    private final long lifetimeMillis;
    private final int admissionsPerSecond;
    private final int maximumAdmissionBurst;
    private final LongSupplier monotonicClock;
    private final Map<InetAddress, Integer> addressCounts = new HashMap<>();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private int connectionCount;
    private boolean closed;
    private double availableAdmissions;
    private long lastRefillNanos;

    VotifierConnectionManager(int maximumConnections, int maximumConnectionsPerAddress, long lifetimeMillis) {
        this(maximumConnections, maximumConnectionsPerAddress, lifetimeMillis,
                ADMISSIONS_PER_SECOND, MAXIMUM_ADMISSION_BURST, System::nanoTime);
    }

    VotifierConnectionManager(int maximumConnections, int maximumConnectionsPerAddress, long lifetimeMillis,
                              int admissionsPerSecond, int maximumAdmissionBurst, LongSupplier monotonicClock) {
        if (maximumConnections < 1 || maximumConnectionsPerAddress < 1 || lifetimeMillis < 1) {
            throw new IllegalArgumentException("Connection limits must be positive");
        }
        if (admissionsPerSecond < 1 || maximumAdmissionBurst < 1) {
            throw new IllegalArgumentException("Admission rate limits must be positive");
        }
        this.maximumConnections = maximumConnections;
        this.maximumConnectionsPerAddress = maximumConnectionsPerAddress;
        this.lifetimeMillis = lifetimeMillis;
        this.admissionsPerSecond = admissionsPerSecond;
        this.maximumAdmissionBurst = maximumAdmissionBurst;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        availableAdmissions = maximumAdmissionBurst;
        lastRefillNanos = monotonicClock.getAsLong();
    }

    synchronized boolean register(Channel channel) {
        SocketAddress remote = channel.remoteAddress();
        if (closed || !channel.isOpen() || !(remote instanceof InetSocketAddress)) {
            channel.close();
            return false;
        }
        InetAddress address = ((InetSocketAddress) remote).getAddress();
        int addressCount = addressCounts.getOrDefault(address, 0);
        if (address == null || connectionCount >= maximumConnections
                || addressCount >= maximumConnectionsPerAddress || !takeAdmission()) {
            channel.close();
            return false;
        }

        connectionCount++;
        addressCounts.put(address, addressCount + 1);
        channel.closeFuture().addListener(future -> release(address));
        try {
            if (!channels.add(channel)) {
                // Every admitted connection must be tracked for shutdown, even on an ID collision.
                channel.close();
                return false;
            }
            ScheduledFuture<?> deadline = channel.eventLoop().schedule(
                    () -> channel.close(), lifetimeMillis, TimeUnit.MILLISECONDS);
            channel.closeFuture().addListener(future -> deadline.cancel(false));
            return true;
        } catch (RuntimeException exception) {
            // A stopped event loop must never leave an admitted connection or permit alive.
            channel.close();
            return false;
        }
    }

    private boolean takeAdmission() {
        long now = monotonicClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            availableAdmissions = Math.min(maximumAdmissionBurst,
                    availableAdmissions + elapsed / 1_000_000_000.0 * admissionsPerSecond);
            lastRefillNanos = now;
        }
        if (availableAdmissions < 1) {
            return false;
        }
        availableAdmissions--;
        return true;
    }

    private synchronized void release(InetAddress address) {
        connectionCount--;
        int remaining = addressCounts.get(address) - 1;
        if (remaining == 0) {
            addressCounts.remove(address);
        } else {
            addressCounts.put(address, remaining);
        }
    }

    synchronized ChannelGroupFuture close() {
        closed = true;
        return channels.close();
    }
}
