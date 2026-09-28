package com.vexsoftware.votifier.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BukkitConfigLoaderTest {
    private static final String SECRET = "existing-provider-token-kept-exactly";

    @TempDir
    Path directory;

    static String template() throws IOException {
        try (InputStream resource = BukkitConfigLoaderTest.class.getClassLoader().getResourceAsStream("bukkitConfig.yml")) {
            assertNotNull(resource);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String legacy() {
        return """
                # Old operator comments are replaced by the maintained template.
                host: 0.0.0.0
                port: 31053
                disable-v1-protocol: true
                tokens:
                  default: %s
                forwarding:
                  method: none
                  extension:
                    enabled: true
                extension:
                  values: [7, yes, stable]
                """.formatted(SECRET);
    }

    private Path write(String contents) throws IOException {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, contents);
        return config;
    }

    private BukkitConfigLoader.Prepared prepare(Path config) throws Exception {
        return new BukkitConfigLoader().prepare(config, template(), "0.0.0.0");
    }

    @Test
    void freshConfigUsesAuthenticatedV2PrivateCredentialsAndNoBackups() throws Exception {
        Path config = directory.resolve("config.yml");
        BukkitConfigLoader.Prepared prepared = prepare(config);
        assertEquals("created", prepared.state());
        assertEquals(1, prepared.sourceVersion());
        assertTrue(prepared.settings().disableV1());
        assertFalse(prepared.settings().debug());
        assertEquals(8192, prepared.settings().port());
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(
                new String(prepared.settings().tokens().get("default").getEncoded(), StandardCharsets.UTF_8)).length);
        assertThrows(UnsupportedOperationException.class, () -> prepared.settings().tokens().clear());
        try (var entries = Files.list(directory)) {
            assertEquals(List.of("config.yml"), entries.map(path -> path.getFileName().toString()).toList());
        }
        if (Files.getFileAttributeView(config, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(config));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(config, AclFileAttributeView.class);
            assertNotNull(acl);
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner(acl))));
        }
    }

    private static java.nio.file.attribute.UserPrincipal owner(AclFileAttributeView acl) {
        try {
            return acl.getOwner();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void schemaZeroAdoptsCanonicalOrderCommentsAndPreservesOperatorValuesAndExtensions() throws Exception {
        Path config = write(legacy());
        BukkitConfigLoader.Prepared prepared = prepare(config);
        assertEquals("migrated", prepared.state());
        assertEquals(0, prepared.sourceVersion());
        assertEquals(31053, prepared.settings().port());
        assertEquals(SECRET, new String(prepared.settings().tokens().get("default").getEncoded(), StandardCharsets.UTF_8));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(config));
        assertEquals(List.of("config-version", "host", "port", "disable-v1-protocol", "debug", "tokens", "forwarding", "extension"),
                yaml.getKeys(false).stream().toList());
        assertEquals(List.of("method", "pluginMessaging", "extension"), yaml.getConfigurationSection("forwarding").getKeys(false).stream().toList());
        assertTrue(yaml.getBoolean("forwarding.extension.enabled"));
        assertEquals(List.of(7, true, "stable"), yaml.getList("extension.values"));
        String promoted = Files.readString(config);
        assertTrue(promoted.contains("Authenticated protocol v2 is required"));
        assertFalse(promoted.contains("Old operator comments"));
        assertTrue(promoted.indexOf("debug:") < promoted.indexOf("tokens:"));
        assertTrue(promoted.indexOf("pluginMessaging:") < promoted.indexOf("extension:"));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void explicitVersionZeroFollowsTheSameAdoptionEdge() throws Exception {
        Path config = write("config-version: 0\n" + legacy());
        assertEquals("migrated", prepare(config).state());
        assertTrue(Files.readString(config).contains("config-version: 1"));
    }

    @Test
    void installedSchemaDiagnosticsReturnOnlyBoundedMarkerStates() throws Exception {
        Path config = directory.resolve("config.yml");
        assertEquals("missing", BukkitConfigLoader.installedVersion(config));
        write(legacy());
        assertEquals("0", BukkitConfigLoader.installedVersion(config));
        write("config-version: 999\n" + legacy());
        assertEquals("999", BukkitConfigLoader.installedVersion(config));
        write("config-version: '" + SECRET + "'\n" + legacy());
        assertEquals("invalid", BukkitConfigLoader.installedVersion(config));
    }

    @Test
    void currentConfigurationIsByteIdempotentAndNeverRewritten() throws Exception {
        Path config = write(legacy());
        prepare(config);
        byte[] current = Files.readAllBytes(config);
        BukkitConfigLoader loader = new BukkitConfigLoader((path, candidate, expected) -> fail("Current schemas must not be written"));
        BukkitConfigLoader.Prepared second = loader.prepare(config, template(), "127.0.0.1");
        assertEquals("current", second.state());
        assertArrayEquals(current, second.loadedBytes());
        assertArrayEquals(current, Files.readAllBytes(config));
    }

    @Test
    void legacyQuietChoiceRetainsItsMeaning() throws Exception {
        BukkitConfigLoader.Prepared prepared = prepare(write(legacy() + "quiet: false\n"));
        assertTrue(prepared.settings().debug());
    }

    @Test
    void siteOnlyTokensDoNotAcquireAnUnrequestedDefaultCredential() throws Exception {
        Path config = write(legacy().replace("default:", "specific-site:"));
        BukkitConfigLoader.Prepared prepared = prepare(config);
        assertEquals(Set.of("specific-site"), prepared.settings().tokens().keySet());
        assertFalse(Files.readString(config).contains("default:"));
    }

    @Test
    void legacyQuietContinuesToTakePriorityOverAnExplicitDebugValue() throws Exception {
        assertFalse(prepare(write(legacy() + "debug: true\nquiet: true\n")).settings().debug());
        assertTrue(prepare(write(legacy() + "debug: false\nquiet: false\n")).settings().debug());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "192.168.1.42", "::", "::1", "2001:db8::1"})
    void literalIpv4AndIpv6ListeningAddressesAreAccepted(String host) throws Exception {
        Path config = write(legacy().replace("host: 0.0.0.0", "host: '" + host + "'"));
        assertEquals(host, prepare(config).settings().host());
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "votes.example.com", "999.1.2.3", "127.0.0", "::gg", "1.2.3.4:8192", " 127.0.0.1 "})
    void dnsAndMalformedListeningAddressesAreRefusedUnchanged(String host) throws Exception {
        assertRejectedUnchanged(legacy().replace("host: 0.0.0.0", "host: '" + host + "'"));
    }

    @Test
    void freshIpv6TemplateDefaultsDoNotBecomeAmbiguousYaml() throws Exception {
        Path config = directory.resolve("config.yml");
        assertEquals("::", new BukkitConfigLoader().prepare(config, template(), "::").settings().host());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "-1", "1.0", "\"1\"", "'1'", "true", "2", "2147483648", "01", "0x1", "1_0"})
    void invalidOrFutureMarkersAreRejectedWithoutWrites(String marker) throws Exception {
        assertRejectedUnchanged("config-version: " + marker + "\n" + legacy());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "config-version: 1\nconfig-version: 1\n",
            "'config-version': 1\n",
            "config-version: !!int 1\n",
            "extension: null\n",
            "extension: [null]\n",
            "extension:\n  same: one\n  same: two\n",
            "extension:\n  42: value\n",
            "extension:\n  dotted.key: value\n",
            "extension:\n  ? [one, two]\n  : value\n",
            "extension: &value {child: preserved}\n",
            "extension: {<<: {child: value}}\n",
            "extension: [unclosed\n",
            "---\nfirst: document\n---\n",
            "extension: !!binary c2VjcmV0\n"
    })
    void lossyOrAmbiguousYamlIsRejectedWithoutWrites(String invalid) throws Exception {
        assertRejectedUnchanged(invalid + legacy().replace("extension:", "legacy-extension:"));
    }

    @Test
    void unauthenticatedLegacyV1ChoiceIsRejectedWithoutRewritingTheOwnerValue() throws Exception {
        assertRejectedUnchanged(legacy().replace("disable-v1-protocol: true", "disable-v1-protocol: false"));
    }

    @Test
    void clientCarriedPluginMessagesCannotBeEnabledForRewardedVotes() throws Exception {
        assertRejectedUnchanged(legacy().replace("method: none", "method: pluginMessaging"));
    }

    @Test
    void unknownForwardingMethodAndInvalidTokensFailClosed() throws Exception {
        assertRejectedUnchanged(legacy().replace("method: none", "method: misspelled"));
        assertRejectedUnchanged(legacy().replace(SECRET, "weak"));
        assertRejectedUnchanged(legacy().replace(SECRET, "12345678901234567890"));
        assertRejectedUnchanged(legacy().replace("tokens:\n  default: " + SECRET, "tokens: {}"));
    }

    @Test
    void malformedUtf8IsRejectedWithoutRepairingTheFile() throws Exception {
        Path config = directory.resolve("config.yml");
        byte[] bytes = {(byte) 0xc3, (byte) 0x28};
        Files.write(config, bytes);
        assertThrows(BukkitConfigLoader.ConfigException.class, () -> prepare(config));
        assertArrayEquals(bytes, Files.readAllBytes(config));
    }

    @Test
    void atomicReplacementFailureLeavesTheOriginalBytes() throws Exception {
        Path config = write(legacy());
        byte[] before = Files.readAllBytes(config);
        BukkitConfigLoader loader = new BukkitConfigLoader((path, candidate, expected) -> {
            throw new AtomicMoveNotSupportedException("source", "target", "fixture");
        });
        assertThrows(BukkitConfigLoader.ConfigException.class, () -> loader.prepare(config, template(), "0.0.0.0"));
        assertArrayEquals(before, Files.readAllBytes(config));
    }

    @Test
    void sourceEditBetweenValidationAndReplacementWinsWithoutBeingOverwritten() throws Exception {
        Path config = write(legacy());
        byte[] edited = legacy().replace("31053", "31054").getBytes(StandardCharsets.UTF_8);
        BukkitConfigLoader loader = new BukkitConfigLoader((path, candidate, expected) -> {
            Files.write(path, edited);
            BukkitConfigLoader.writeAtomic(path, candidate, expected);
        });
        assertThrows(BukkitConfigLoader.ConfigException.class, () -> loader.prepare(config, template(), "0.0.0.0"));
        assertArrayEquals(edited, Files.readAllBytes(config));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void promotedFileMustMatchTheValidatedCandidateBeforePublication() throws Exception {
        Path config = write(legacy());
        BukkitConfigLoader loader = new BukkitConfigLoader((path, candidate, expected) -> {
            BukkitConfigLoader.writeAtomic(path, candidate, expected);
            Files.writeString(path, "raced: different-generation\n");
        });
        assertThrows(BukkitConfigLoader.ConfigException.class, () -> loader.prepare(config, template(), "0.0.0.0"));
        assertEquals("raced: different-generation\n", Files.readString(config));
    }

    @Test
    void preparedSourceBytesAreDefensiveAndFinalRecheckRejectsAConcurrentEdit() throws Exception {
        Path config = write(legacy());
        BukkitConfigLoader.Prepared prepared = prepare(config);
        prepared.loadedBytes()[0] = 0;
        prepared.recheck();
        Files.writeString(config, "changed: after-preparation\n");
        assertThrows(BukkitConfigLoader.ConfigException.class, prepared::recheck);
    }

    private void assertRejectedUnchanged(String content) throws Exception {
        Path config = write(content);
        byte[] before = Files.readAllBytes(config);
        BukkitConfigLoader.ConfigException failure = assertThrows(BukkitConfigLoader.ConfigException.class, () -> prepare(config));
        assertFalse(failure.getMessage().contains(SECRET));
        assertNull(failure.getCause());
        assertArrayEquals(before, Files.readAllBytes(config));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }
}
