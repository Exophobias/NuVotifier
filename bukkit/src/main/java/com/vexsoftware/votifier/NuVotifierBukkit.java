/*
 * Copyright (C) 2012 Vex Software LLC
 * This file is part of Votifier.
 *
 * Votifier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Votifier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Votifier.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vexsoftware.votifier;

import com.vexsoftware.votifier.cmd.NVReloadCmd;
import com.vexsoftware.votifier.cmd.TestVoteCmd;
import com.vexsoftware.votifier.config.BukkitConfigLoader;
import com.vexsoftware.votifier.config.BukkitConfigLoader.ConfigException;
import com.vexsoftware.votifier.config.BukkitConfigLoader.Prepared;
import com.vexsoftware.votifier.config.BukkitConfigLoader.Settings;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import com.vexsoftware.votifier.net.VotifierServerBootstrap;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.platform.JavaUtilLogger;
import com.vexsoftware.votifier.platform.LoggingAdapter;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import com.vexsoftware.votifier.platform.scheduler.VotifierScheduler;
import com.vexsoftware.votifier.support.forwarding.ForwardedVoteListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated vote receiver with strict config adoption and last-known-good reloads. */
public class NuVotifierBukkit extends JavaPlugin implements VoteHandler, VotifierPlugin, ForwardedVoteListener {
    private static final long BIND_TIMEOUT_SECONDS = 5;
    private volatile RuntimeState active;
    private VotifierScheduler scheduler;
    private LoggingAdapter pluginLogger;
    private VoteErrorReporter errorReporter;

    private record Generation(Settings settings) {}

    private record RuntimeState(Prepared config, RuntimeView view, VotifierServerBootstrap bootstrap) {}

    /** Every network connection reads a complete settings generation, never a mutable token map. */
    private final class RuntimeView implements VotifierPlugin {
        private volatile Generation generation;

        private RuntimeView(Generation generation) {
            this.generation = generation;
        }

        @Override
        public Map<String, Key> getTokens() {
            return generation.settings().tokens();
        }

        @Override
        public KeyPair getProtocolV1Key() {
            return null; // Authenticated protocol v2 has no legacy RSA key material.
        }

        @Override
        public boolean isDebug() {
            return generation.settings().debug();
        }

        @Override
        public LoggingAdapter getPluginLogger() {
            return NuVotifierBukkit.this.getPluginLogger();
        }

        @Override
        public VotifierScheduler getScheduler() {
            return NuVotifierBukkit.this.getScheduler();
        }

        @Override
        public void onVoteReceived(Vote vote, VotifierSession.ProtocolVersion protocol, String address) {
            RuntimeState published = active;
            if (published == null || published.view() != this) {
                // A newly bound endpoint can authenticate while its final source recheck is
                // pending. Throw so the wire handler replies with an error, allowing a retry;
                // never acknowledge or dispatch a vote from an unpublished or retired view.
                throw new IllegalStateException("The vote receiver generation is not active");
            }
            NuVotifierBukkit.this.onVoteReceived(vote, protocol, address);
        }

        @Override
        public void onError(Throwable failure, boolean completed, String address) {
            NuVotifierBukkit.this.onError(failure, completed, address);
        }
    }

    private Prepared prepareConfig() throws ConfigException, IOException {
        Path directory = getDataFolder().toPath();
        Files.createDirectories(directory);
        String host = getServer().getIp();
        if (host == null || host.isEmpty()) {
            host = "0.0.0.0";
        }
        try (InputStream resource = getResource("bukkitConfig.yml")) {
            if (resource == null) {
                throw new IOException("the bundled config template is unavailable");
            }
            String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            return new BukkitConfigLoader().prepare(directory.resolve("config.yml"), template, host);
        }
    }

    private VotifierServerBootstrap bind(Settings settings, RuntimeView view) throws IOException {
        if (settings.port() == -1) {
            return null;
        }
        VotifierServerBootstrap candidate = new VotifierServerBootstrap(settings.host(), settings.port(), view, settings.disableV1());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        boolean bound = false;
        try {
            candidate.start(failure -> {
                error.set(failure);
                completed.countDown();
            });
            if (!completed.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || error.get() != null) {
                throw new IOException("the vote listener could not bind within its startup deadline");
            }
            bound = true;
            return candidate;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("vote listener startup was interrupted");
        } finally {
            if (!bound) {
                candidate.shutdown();
            }
        }
    }

    private boolean prepareAndActivate() {
        RuntimeState previous = active;
        VotifierServerBootstrap candidate = null;
        boolean previousClosed = false;
        try {
            Prepared prepared = prepareConfig();
            Settings settings = prepared.settings();
            Generation generation = new Generation(settings);
            prepared.recheck();
            if (previous != null && settings.sameListener(previous.config().settings())) {
                // Token removal revokes authentication immediately without opening a second socket
                // or interrupting current schedules; the volatile generation is the commit point.
                previous.view().generation = generation;
                active = new RuntimeState(prepared, previous.view(), previous.bootstrap());
            } else {
                RuntimeView view = new RuntimeView(generation);
                // Changing settings on the same port requires releasing that socket first. Other
                // endpoints are bound while the previous listener remains available.
                if (previous != null && previous.bootstrap() != null && settings.port() == previous.config().settings().port()) {
                    previous.bootstrap().shutdown();
                    previousClosed = true;
                }
                candidate = bind(settings, view);
                prepared.recheck();
                active = new RuntimeState(prepared, view, candidate);
                candidate = null;
                if (previous != null && previous.bootstrap() != null && !previousClosed) {
                    previous.bootstrap().shutdown();
                }
            }
            getLogger().info("NuVotifier version=" + getDescription().getVersion()
                    + " config supported=1 installed=1 source=" + prepared.sourceVersion() + " state=" + prepared.state());
            if (settings.port() == -1) {
                getLogger().warning("The vote TCP listener is disabled; no votes can be received by this Bukkit receiver.");
            }
            return true;
        } catch (ConfigException failure) {
            getLogger().severe("NuVotifier version=" + getDescription().getVersion()
                    + " config supported=1 installed=" + BukkitConfigLoader.installedVersion(getDataFolder().toPath().resolve("config.yml"))
                    + " state=blocked: " + failure.getMessage());
        } catch (Exception failure) {
            getLogger().severe("NuVotifier version=" + getDescription().getVersion() + " config supported=1 installed="
                    + BukkitConfigLoader.installedVersion(getDataFolder().toPath().resolve("config.yml"))
                    + " state=blocked: configuration or listener activation failed safely");
        } finally {
            if (candidate != null) {
                candidate.shutdown();
            }
        }
        if (previousClosed && previous != null) {
            try {
                VotifierServerBootstrap restored = bind(previous.config().settings(), previous.view());
                active = new RuntimeState(previous.config(), previous.view(), restored);
                getLogger().warning("The previous known-good vote listener was restored after the rejected reload.");
            } catch (Exception failure) {
                getLogger().severe("The previous vote listener could not be restored; NuVotifier is disabled.");
                setEnabled(false);
            }
        } else if (previous != null) {
            getLogger().warning("The previous known-good vote listener and settings remain active.");
        }
        return false;
    }

    @Override
    public void onEnable() {
        scheduler = new BukkitScheduler(this);
        pluginLogger = new JavaUtilLogger(getLogger());
        errorReporter = new VoteErrorReporter(getLogger());
        PluginCommand reloadCommand = getCommand("nvreload");
        PluginCommand testVoteCommand = getCommand("testvote");
        if (reloadCommand == null || testVoteCommand == null) {
            getLogger().severe("NuVotifier failed closed: required commands are absent from the plugin descriptor.");
            getServer().getScheduler().cancelTasks(this);
            setEnabled(false);
            return;
        }
        reloadCommand.setExecutor(new NVReloadCmd(this));
        testVoteCommand.setExecutor(new TestVoteCmd(this));
        if (!prepareAndActivate()) {
            getLogger().severe("NuVotifier failed closed before receiving votes.");
            getServer().getScheduler().cancelTasks(this);
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        RuntimeState previous = active;
        active = null;
        if (previous != null && previous.bootstrap() != null) {
            previous.bootstrap().shutdown();
        }
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("NuVotifier disabled.");
    }

    public boolean reload() {
        return prepareAndActivate();
    }

    @Override
    public LoggingAdapter getPluginLogger() {
        return pluginLogger;
    }

    @Override
    public VotifierScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public boolean isDebug() {
        RuntimeState snapshot = active;
        return snapshot != null && snapshot.view().isDebug();
    }

    @Override
    public Map<String, Key> getTokens() {
        RuntimeState snapshot = active;
        return snapshot == null ? Map.of() : snapshot.view().getTokens();
    }

    @Override
    public KeyPair getProtocolV1Key() {
        RuntimeState snapshot = active;
        return snapshot == null ? null : snapshot.view().getProtocolV1Key();
    }

    @Override
    public void onVoteReceived(final Vote vote, VotifierSession.ProtocolVersion protocolVersion, String remoteAddress) {
        if (isDebug()) {
            getLogger().info("Got a " + protocolVersion.humanReadable + " vote record from " + remoteAddress + " -> " + vote);
        }
        Bukkit.getScheduler().runTask(this, () -> fireVotifierEvent(vote));
    }

    @Override
    public void onError(Throwable throwable, boolean alreadyHandledVote, String remoteAddress) {
        // Do not log untrusted payload exceptions: they can contain credentials or user data.
        if ((!alreadyHandledVote || isDebug()) && errorReporter != null) {
            errorReporter.report(alreadyHandledVote);
        }
    }

    @Override
    public void onForward(final Vote vote) {
        if (isDebug()) {
            getLogger().info("Got a forwarded vote -> " + vote);
        }
        Bukkit.getScheduler().runTask(this, () -> fireVotifierEvent(vote));
    }

    private void fireVotifierEvent(Vote vote) {
        if (VotifierEvent.getHandlerList().getRegisteredListeners().length == 0) {
            getLogger().severe("A vote was received, but no vote event listeners are available.");
        }
        Bukkit.getPluginManager().callEvent(new VotifierEvent(vote));
    }
}
