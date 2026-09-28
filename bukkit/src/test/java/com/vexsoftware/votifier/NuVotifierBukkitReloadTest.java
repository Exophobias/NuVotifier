package com.vexsoftware.votifier;

import com.vexsoftware.votifier.net.VotifierServerBootstrap;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercise the real Bukkit reload lifecycle without opening sockets or requiring a live server. */
class NuVotifierBukkitReloadTest {
    @TempDir
    Path directory;

    private record Fixture(NuVotifierBukkit plugin, Logger logger, BukkitScheduler scheduler) {}

    private Fixture fixture() throws Exception {
        NuVotifierBukkit plugin = mock(NuVotifierBukkit.class, CALLS_REAL_METHODS);
        Logger logger = mock(Logger.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getIp()).thenReturn("0.0.0.0");
        when(server.getScheduler()).thenReturn(scheduler);
        doReturn(directory.toFile()).when(plugin).getDataFolder();
        doReturn(server).when(plugin).getServer();
        doReturn(logger).when(plugin).getLogger();
        doReturn(new PluginDescriptionFile("Votifier", "fixture", NuVotifierBukkit.class.getName())).when(plugin).getDescription();
        doReturn(mock(PluginCommand.class)).when(plugin).getCommand("nvreload");
        doReturn(mock(PluginCommand.class)).when(plugin).getCommand("testvote");
        byte[] template;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("bukkitConfig.yml")) {
            assertNotNull(stream);
            template = stream.readAllBytes();
        }
        doAnswer(call -> new ByteArrayInputStream(template)).when(plugin).getResource("bukkitConfig.yml");
        return new Fixture(plugin, logger, scheduler);
    }

    private static void succeed(VotifierServerBootstrap listener) {
        doAnswer(call -> {
            Consumer<Throwable> callback = call.getArgument(0);
            callback.accept(null);
            return null;
        }).when(listener).start(any());
    }

    @Test
    void malformedReloadKeepsTheSameLiveListenerAndAuthenticationKeys() throws Exception {
        Fixture fixture = fixture();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> succeed(listener))) {
            assertTrue(fixture.plugin().reload());
            Map<String, Key> originalTokens = fixture.plugin().getTokens();
            String token = new String(originalTokens.get("default").getEncoded(), StandardCharsets.UTF_8);
            byte[] malformed = ("config-version: 1\ntokens: [\nsecret: " + token).getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve("config.yml"), malformed);
            assertFalse(fixture.plugin().reload());
            assertSame(originalTokens, fixture.plugin().getTokens());
            assertEquals(1, listeners.constructed().size());
            verify(listeners.constructed().getFirst(), never()).shutdown();
            assertArrayEquals(malformed, Files.readAllBytes(directory.resolve("config.yml")));
            assertTrue(mockingDetails(fixture.logger()).getInvocations().stream()
                    .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                    .filter(argument -> argument instanceof String)
                    .noneMatch(argument -> ((String) argument).contains(token)));
        }
    }

    @Test
    void tokenRemovalRevokesTheOldCredentialWithoutRebinding() throws Exception {
        Fixture fixture = fixture();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> succeed(listener))) {
            assertTrue(fixture.plugin().reload());
            Path config = directory.resolve("config.yml");
            Files.writeString(config, Files.readString(config).replace("default:", "replacement-site:"));
            assertTrue(fixture.plugin().reload());
            assertEquals(java.util.Set.of("replacement-site"), fixture.plugin().getTokens().keySet());
            assertThrows(UnsupportedOperationException.class, () -> fixture.plugin().getTokens().clear());
            assertEquals(1, listeners.constructed().size());
            verify(listeners.constructed().getFirst(), never()).shutdown();
            assertNull(fixture.plugin().getProtocolV1Key());
            assertFalse(Files.exists(directory.resolve("rsa")));
        }
    }

    @Test
    void failedDifferentPortBindLeavesTheExistingSocketActive() throws Exception {
        Fixture fixture = fixture();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> {
                    if (context.getCount() == 2) {
                        doAnswer(call -> {
                            Consumer<Throwable> callback = call.getArgument(0);
                            callback.accept(new IOException("fixture: sensitive untrusted error"));
                            return null;
                        }).when(listener).start(any());
                    } else {
                        succeed(listener);
                    }
                })) {
            assertTrue(fixture.plugin().reload());
            Map<String, Key> original = fixture.plugin().getTokens();
            Path config = directory.resolve("config.yml");
            Files.writeString(config, Files.readString(config).replace("port: 8192", "port: 8193"));
            assertFalse(fixture.plugin().reload());
            assertSame(original, fixture.plugin().getTokens());
            assertEquals(2, listeners.constructed().size());
            verify(listeners.constructed().get(0), never()).shutdown();
            verify(listeners.constructed().get(1)).shutdown();
        }
    }

    @Test
    void failedSamePortReplacementRestoresTheKnownGoodListener() throws Exception {
        Fixture fixture = fixture();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> {
                    if (context.getCount() == 2) {
                        doAnswer(call -> {
                            Consumer<Throwable> callback = call.getArgument(0);
                            callback.accept(new IOException("fixture failed replacement bind"));
                            return null;
                        }).when(listener).start(any());
                    } else {
                        succeed(listener);
                    }
                })) {
            assertTrue(fixture.plugin().reload());
            Map<String, Key> original = fixture.plugin().getTokens();
            Path config = directory.resolve("config.yml");
            Files.writeString(config, Files.readString(config).replace("host: 0.0.0.0", "host: 127.0.0.1"));
            assertFalse(fixture.plugin().reload());
            assertSame(original, fixture.plugin().getTokens());
            assertEquals(3, listeners.constructed().size());
            verify(listeners.constructed().get(0)).shutdown();
            verify(listeners.constructed().get(1)).shutdown();
            verify(listeners.constructed().get(2), never()).shutdown();
            verify(fixture.logger()).warning("The previous known-good vote listener was restored after the rejected reload.");
        }
    }

    @Test
    void unpublishedCandidateCannotDispatchVotesWhenTheFinalSourceRecheckFails() throws Exception {
        Fixture fixture = fixture();
        Vote vote = new Vote("Test", "test_user", "127.0.0.1", "0");
        doNothing().when(fixture.plugin()).onVoteReceived(any(), any(), anyString());
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> {
                    if (context.getCount() == 2) {
                        VotifierPlugin candidateView = (VotifierPlugin) context.arguments().get(2);
                        doAnswer(call -> {
                            assertThrows(IllegalStateException.class, () -> candidateView.onVoteReceived(
                                    vote, VotifierSession.ProtocolVersion.TWO, "127.0.0.1"));
                            // Simulate an independent writer winning while the candidate socket binds.
                            Files.writeString(directory.resolve("config.yml"), "changed: during-bind\n");
                            Consumer<Throwable> callback = call.getArgument(0);
                            callback.accept(null);
                            return null;
                        }).when(listener).start(any());
                    } else {
                        succeed(listener);
                    }
                })) {
            assertTrue(fixture.plugin().reload());
            Map<String, Key> original = fixture.plugin().getTokens();
            Path config = directory.resolve("config.yml");
            Files.writeString(config, Files.readString(config).replace("port: 8192", "port: 8193"));
            assertFalse(fixture.plugin().reload());
            assertSame(original, fixture.plugin().getTokens());
            verify(fixture.plugin(), never()).onVoteReceived(any(), any(), anyString());
            verify(listeners.constructed().get(0), never()).shutdown();
            verify(listeners.constructed().get(1)).shutdown();
        }
    }

    @Test
    void retiredEndpointCannotDeliverAfterANewGenerationIsPublished() throws Exception {
        Fixture fixture = fixture();
        Vote vote = new Vote("Test", "test_user", "127.0.0.1", "0");
        doNothing().when(fixture.plugin()).onVoteReceived(any(), any(), anyString());
        List<VotifierPlugin> views = new ArrayList<>();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> {
                    views.add((VotifierPlugin) context.arguments().get(2));
                    succeed(listener);
                })) {
            assertTrue(fixture.plugin().reload());
            views.getFirst().onVoteReceived(vote, VotifierSession.ProtocolVersion.TWO, "127.0.0.1");
            Path config = directory.resolve("config.yml");
            Files.writeString(config, Files.readString(config).replace("port: 8192", "port: 8193"));
            assertTrue(fixture.plugin().reload());
            assertThrows(IllegalStateException.class, () -> views.getFirst().onVoteReceived(
                    vote, VotifierSession.ProtocolVersion.TWO, "127.0.0.1"));
            views.getLast().onVoteReceived(vote, VotifierSession.ProtocolVersion.TWO, "127.0.0.1");
            verify(fixture.plugin(), times(2)).onVoteReceived(vote, VotifierSession.ProtocolVersion.TWO, "127.0.0.1");
            verify(listeners.constructed().getFirst()).shutdown();
        }
    }

    @Test
    void failedInitialEnableNeverOpensTheListenerAndCancelsPendingVoteTasks() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(directory.resolve("config.yml"), "config-version: 999\n");
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class)) {
            fixture.plugin().onEnable();
            assertTrue(listeners.constructed().isEmpty());
            assertTrue(fixture.plugin().getTokens().isEmpty());
            verify(fixture.scheduler()).cancelTasks(fixture.plugin());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nvreload", "testvote"})
    void missingRequiredCommandFailsClosedBeforeConfigOrNetworkInitialization(String missing) throws Exception {
        Fixture fixture = fixture();
        doReturn(null).when(fixture.plugin()).getCommand(missing);
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class)) {
            fixture.plugin().onEnable();
            assertTrue(listeners.constructed().isEmpty());
            assertFalse(Files.exists(directory.resolve("config.yml")));
            assertTrue(fixture.plugin().getTokens().isEmpty());
            verify(fixture.scheduler()).cancelTasks(fixture.plugin());
            verify(fixture.logger()).severe("NuVotifier failed closed: required commands are absent from the plugin descriptor.");
        }
    }

    @Test
    void disableStopsTheListenerAndCancelsScheduledCallbacks() throws Exception {
        Fixture fixture = fixture();
        try (MockedConstruction<VotifierServerBootstrap> listeners = mockConstruction(VotifierServerBootstrap.class,
                (listener, context) -> succeed(listener))) {
            assertTrue(fixture.plugin().reload());
            fixture.plugin().onDisable();
            verify(listeners.constructed().getFirst()).shutdown();
            verify(fixture.scheduler()).cancelTasks(fixture.plugin());
            assertTrue(fixture.plugin().getTokens().isEmpty());
        }
    }
}
