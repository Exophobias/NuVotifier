package com.vexsoftware.votifier.net;

import com.vexsoftware.votifier.net.protocol.TestVotifierPlugin;
import com.vexsoftware.votifier.net.protocol.VotifierProtocolDifferentiator;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VotifierConnectionManagerTest {
    @Test
    void boundsRapidReconnectsAndRecoversWithMonotonicTime() {
        AtomicLong clock = new AtomicLong();
        VotifierConnectionManager manager = new VotifierConnectionManager(1, 1, 10_000,
                2, 2, clock::get);
        EmbeddedChannel first = channel("127.0.0.1");
        EmbeddedChannel second = channel("127.0.0.1");
        EmbeddedChannel rejected = channel("127.0.0.2");
        EmbeddedChannel tooEarly = channel("127.0.0.3");
        EmbeddedChannel recovered = channel("127.0.0.4");
        try {
            assertTrue(manager.register(first));
            first.close();
            assertTrue(manager.register(second));
            second.close();
            assertFalse(manager.register(rejected));
            assertFalse(rejected.isOpen());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(499));
            assertFalse(manager.register(tooEarly));
            assertFalse(tooEarly.isOpen());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(manager.register(recovered));
        } finally {
            manager.close().awaitUninterruptibly();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            rejected.finishAndReleaseAll();
            tooEarly.finishAndReleaseAll();
            recovered.finishAndReleaseAll();
        }
    }

    @Test
    void capsConnectionsPerAddressAndGloballyAndReleasesClosedPermits() {
        VotifierConnectionManager manager = new VotifierConnectionManager(3, 2, 10_000);
        EmbeddedChannel first = channel("127.0.0.1");
        EmbeddedChannel second = channel("127.0.0.1");
        EmbeddedChannel excessForAddress = channel("127.0.0.1");
        EmbeddedChannel third = channel("127.0.0.2");
        EmbeddedChannel excessGlobally = channel("127.0.0.3");
        EmbeddedChannel replacement = channel("127.0.0.1");
        try {
            assertTrue(manager.register(first));
            assertTrue(manager.register(second));
            assertFalse(manager.register(excessForAddress));
            assertFalse(excessForAddress.isOpen());
            assertTrue(manager.register(third));
            assertFalse(manager.register(excessGlobally));
            assertFalse(excessGlobally.isOpen());
            first.close();
            assertTrue(manager.register(replacement));
        } finally {
            manager.close().awaitUninterruptibly();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            excessForAddress.finishAndReleaseAll();
            third.finishAndReleaseAll();
            excessGlobally.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void partialTrafficCannotExtendTheAbsoluteConnectionDeadline() {
        VotifierConnectionManager manager = new VotifierConnectionManager(1, 1, 10_000);
        EmbeddedChannel partial = channel("127.0.0.1");
        EmbeddedChannel replacement = channel("127.0.0.1");
        partial.attr(VotifierSession.KEY).set(new VotifierSession());
        partial.attr(VotifierPlugin.KEY).set(TestVotifierPlugin.getI());
        partial.pipeline().addLast("protocolDifferentiator", new VotifierProtocolDifferentiator(false, true));
        partial.freezeTime();
        try {
            assertTrue(manager.register(partial));
            assertFalse(partial.writeInbound(Unpooled.wrappedBuffer(new byte[]{0})));
            partial.advanceTimeBy(6, TimeUnit.SECONDS);
            partial.runScheduledPendingTasks();
            assertFalse(partial.writeInbound(Unpooled.wrappedBuffer(new byte[]{0})));
            partial.advanceTimeBy(3, TimeUnit.SECONDS);
            partial.runScheduledPendingTasks();
            assertTrue(partial.isOpen());
            partial.advanceTimeBy(2, TimeUnit.SECONDS);
            partial.runScheduledPendingTasks();
            assertFalse(partial.isOpen());
            assertTrue(manager.register(replacement));
        } finally {
            manager.close().awaitUninterruptibly();
            partial.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void closesSilentClientsAndRejectsConnectionsAfterShutdown() {
        VotifierConnectionManager manager = new VotifierConnectionManager(1, 1, 10_000);
        EmbeddedChannel silent = channel("127.0.0.1");
        EmbeddedChannel afterShutdown = channel("127.0.0.2");
        silent.freezeTime();
        try {
            assertTrue(manager.register(silent));
            silent.advanceTimeBy(11, TimeUnit.SECONDS);
            silent.runScheduledPendingTasks();
            assertFalse(silent.isOpen());
            manager.close().awaitUninterruptibly();
            assertFalse(manager.register(afterShutdown));
            assertFalse(afterShutdown.isOpen());
        } finally {
            manager.close().awaitUninterruptibly();
            silent.finishAndReleaseAll();
            afterShutdown.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsConnectionsWithoutAResolvedInternetAddress() {
        VotifierConnectionManager manager = new VotifierConnectionManager(1, 1, 10_000);
        EmbeddedChannel unknown = new EmbeddedChannel();
        try {
            assertFalse(manager.register(unknown));
            assertFalse(unknown.isOpen());
        } finally {
            manager.close().awaitUninterruptibly();
            unknown.finishAndReleaseAll();
        }
    }

    @Test
    void shutdownClosesEveryAdmittedConnection() {
        VotifierConnectionManager manager = new VotifierConnectionManager(2, 2, 10_000);
        EmbeddedChannel first = channel("127.0.0.1");
        EmbeddedChannel second = channel("127.0.0.2");
        try {
            assertTrue(manager.register(first));
            assertTrue(manager.register(second));
            manager.close().awaitUninterruptibly();
            assertFalse(first.isOpen());
            assertFalse(second.isOpen());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void duplicateChannelIdsFailClosedWithoutLosingShutdownTracking() {
        VotifierConnectionManager manager = new VotifierConnectionManager(2, 2, 10_000);
        ChannelId id = DefaultChannelId.newInstance();
        EmbeddedChannel first = channel("127.0.0.1", id);
        EmbeddedChannel duplicate = channel("127.0.0.2", id);
        EmbeddedChannel replacement = channel("127.0.0.2");
        try {
            assertTrue(manager.register(first));
            assertFalse(manager.register(duplicate));
            assertFalse(duplicate.isOpen());
            assertTrue(manager.register(replacement));
            manager.close().awaitUninterruptibly();
            assertFalse(first.isOpen());
            assertFalse(replacement.isOpen());
        } finally {
            manager.close().awaitUninterruptibly();
            first.finishAndReleaseAll();
            duplicate.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(String address) {
        return channel(address, DefaultChannelId.newInstance());
    }

    private static EmbeddedChannel channel(String address, ChannelId id) {
        return new EmbeddedChannel(id) {
            @Override
            protected SocketAddress remoteAddress0() {
                return new InetSocketAddress(address, 12345);
            }
        };
    }
}
