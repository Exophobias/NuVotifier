package com.vexsoftware.votifier.config;

import com.vexsoftware.votifier.util.KeyCreator;
import com.vexsoftware.votifier.util.TokenUtil;
import io.netty.util.NetUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, secret-safe configuration adoption with no automatic backups. */
public final class BukkitConfigLoader {
    public static final int CONFIG_VERSION = 1;
    private static final String VERSION_KEY = "config-version";
    private static final int MAX_CONFIG_BYTES = 1_048_576;
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final AtomicWriter writer;

    public BukkitConfigLoader() {
        this(BukkitConfigLoader::writeAtomic);
    }

    /** Marker-only diagnostic, including refused future schemas; no YAML values are returned. */
    public static String installedVersion(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "missing";
        }
        try {
            return Integer.toString(physicalVersion(decode(readConfig(path))));
        } catch (IOException | ConfigException | RuntimeException failure) {
            return "invalid";
        }
    }

    BukkitConfigLoader(AtomicWriter writer) {
        this.writer = writer;
    }

    @FunctionalInterface
    interface AtomicWriter {
        void write(Path target, byte[] candidate, byte[] expectedSource) throws IOException;
    }

    public record Settings(String host, int port, boolean disableV1, boolean debug,
                           Map<String, Key> tokens) {
        public Settings {
            tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        }

        public boolean sameListener(Settings other) {
            return host.equals(other.host) && port == other.port && disableV1 == other.disableV1;
        }
    }

    public record Prepared(Path path, byte[] loadedBytes, Settings settings,
                           int sourceVersion, String state) {
        public Prepared {
            loadedBytes = loadedBytes.clone();
        }

        @Override
        public byte[] loadedBytes() {
            return loadedBytes.clone();
        }

        /** Check the exact validated generation immediately before activation. */
        public void recheck() throws ConfigException {
            try {
                if (!Arrays.equals(loadedBytes, readConfig(path))) {
                    throw new ConfigException("config.yml changed after validation; retry the operation");
                }
            } catch (IOException failure) {
                throw new ConfigException("config.yml could not be rechecked safely");
            }
        }
    }

    /** Messages contain only fixed diagnostics, never values or parser excerpts. */
    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }
    }

    public Prepared prepare(Path path, String bundledTemplate, String defaultHost) throws ConfigException {
        try {
            String templateText = bundledTemplate.replace("%ip%", defaultHost)
                    .replace("%default_token%", TokenUtil.newToken());
            YamlConfiguration template = parse(templateText);
            if (physicalVersion(templateText) != CONFIG_VERSION) {
                throw new ConfigException("the bundled config.yml has an unsupported schema");
            }
            validate(template);

            boolean fresh = !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            byte[] source = fresh ? null : readConfig(path);
            String physicalText = fresh ? templateText : decode(source);
            int version = physicalVersion(physicalText);
            if (version > CONFIG_VERSION) {
                throw new ConfigException("config.yml uses a future config-version; supported schema is 1");
            }
            YamlConfiguration physical = parse(physicalText);
            YamlConfiguration candidate = physical;
            if (version == 0) {
                // Explicit sequential schema-0 -> schema-1 adoption. Defaults keep template order;
                // operator values and extension keys are overlaid without changing their meaning.
                candidate = migrateZeroToOne(physical, template);
            }
            Settings settings = validate(candidate);
            byte[] expected = source;
            String state = "current";
            if (fresh || version < CONFIG_VERSION) {
                byte[] promoted = candidate.saveToString().getBytes(StandardCharsets.UTF_8);
                writer.write(path, promoted, source);
                expected = readConfig(path);
                if (!Arrays.equals(promoted, expected)) {
                    throw new ConfigException("config.yml changed after atomic installation; activation is blocked");
                }
                String installedText = decode(expected);
                if (physicalVersion(installedText) != CONFIG_VERSION) {
                    throw new ConfigException("the installed config.yml did not retain its expected schema");
                }
                settings = validate(parse(installedText));
                state = fresh ? "created" : "migrated";
            }
            Prepared prepared = new Prepared(path, expected, settings, fresh ? CONFIG_VERSION : version, state);
            prepared.recheck();
            return prepared;
        } catch (ConfigException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ConfigException("config.yml could not be read or atomically installed safely");
        } catch (RuntimeException failure) {
            throw new ConfigException("config.yml contains invalid or unsupported configuration");
        }
    }

    private static YamlConfiguration migrateZeroToOne(YamlConfiguration physical, YamlConfiguration template) {
        overlay(template, physical, true);
        template.set(VERSION_KEY, CONFIG_VERSION);
        return template;
    }

    private static void overlay(ConfigurationSection target, ConfigurationSection physical, boolean root) {
        Set<String> known = new HashSet<>(target.getKeys(false));
        for (String key : physical.getKeys(false)) {
            if (root && VERSION_KEY.equals(key)) {
                continue;
            }
            Object value = physical.get(key);
            ConfigurationSection sourceSection = physical.getConfigurationSection(key);
            ConfigurationSection targetSection = target.getConfigurationSection(key);
            if (sourceSection != null && targetSection != null && known.contains(key)) {
                if (sourceSection.getKeys(false).isEmpty()) {
                    target.createSection(key);
                } else {
                    // A site-only token map must remain site-only. Introducing a new default
                    // credential would change the existing authentication policy.
                    if (root && "tokens".equals(key) && !sourceSection.contains("default")) {
                        targetSection.set("default", null);
                    }
                    overlay(targetSection, sourceSection, false);
                }
            } else {
                List<String> comments = target.getComments(key);
                List<String> inlineComments = target.getInlineComments(key);
                if (sourceSection != null) {
                    copySection(target.createSection(key), sourceSection);
                } else {
                    target.set(key, value);
                }
                target.setComments(key, comments);
                target.setInlineComments(key, inlineComments);
            }
        }
    }

    private static void copySection(ConfigurationSection target, ConfigurationSection source) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child == null) {
                target.set(key, source.get(key));
            } else {
                copySection(target.createSection(key), child);
            }
        }
    }

    private static Settings validate(YamlConfiguration cfg) throws ConfigException {
        Object hostValue = cfg.get("host");
        if (!(hostValue instanceof String host) || host.length() > 253
                || !(NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host))) {
            throw new ConfigException("host must be a literal IPv4 or IPv6 address; DNS hostnames are unsupported");
        }
        Object portValue = cfg.get("port");
        if (!(portValue instanceof Integer port) || (port != -1 && (port < 1 || port > 65535))) {
            throw new ConfigException("port must be an integer from 1 to 65535, or -1 to disable TCP");
        }
        if (!(cfg.get("disable-v1-protocol") instanceof Boolean disableV1)) {
            throw new ConfigException("disable-v1-protocol must be a boolean");
        }
        if (!disableV1) {
            throw new ConfigException("legacy protocol v1 is unsupported; set disable-v1-protocol to true and configure providers for authenticated protocol v2");
        }
        if (!(cfg.get("debug") instanceof Boolean debug)) {
            throw new ConfigException("debug must be a boolean");
        }
        if (cfg.contains("quiet")) {
            if (!(cfg.get("quiet") instanceof Boolean quiet)) {
                throw new ConfigException("quiet must be a boolean when present");
            }
            debug = !quiet;
        }
        ConfigurationSection tokens = cfg.getConfigurationSection("tokens");
        if (tokens == null || tokens.getKeys(false).isEmpty()) {
            throw new ConfigException("tokens must contain at least one nonempty authentication token");
        }
        Map<String, Key> keys = new LinkedHashMap<>();
        for (String service : tokens.getKeys(false)) {
            Object value = tokens.get(service);
            if (!(value instanceof String token) || token.isBlank()
                    || token.getBytes(StandardCharsets.UTF_8).length < 16
                    || token.getBytes(StandardCharsets.UTF_8).length > 4096
                    || token.chars().anyMatch(Character::isISOControl)) {
                throw new ConfigException("every tokens entry must be a string with 16 to 4096 UTF-8 bytes and no control characters");
            }
            keys.put(service, KeyCreator.createKeyFrom(token));
        }
        ConfigurationSection forwarding = cfg.getConfigurationSection("forwarding");
        if (forwarding == null || !(forwarding.get("method") instanceof String method)) {
            throw new ConfigException("forwarding.method must be a supported method name");
        }
        if ("pluginmessaging".equalsIgnoreCase(method)) {
            throw new ConfigException("unauthenticated plugin messaging is unsupported; use authenticated protocol v2 forwarding");
        }
        if (!"none".equalsIgnoreCase(method)) {
            throw new ConfigException("forwarding.method is unsupported; use none with authenticated protocol v2 forwarding");
        }
        return new Settings(host, port, disableV1, debug, keys);
    }

    private static int physicalVersion(String text) throws ConfigException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setNestingDepthLimit(50);
            options.setCodePointLimit(MAX_CONFIG_BYTES);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Event event : yaml.parse(new StringReader(text))) {
                if (event instanceof ScalarEvent scalar && scalar.getTag() != null
                        || event instanceof CollectionStartEvent collection && collection.getTag() != null) {
                    throw new ConfigException("config.yml contains an explicit YAML tag that cannot be preserved");
                }
            }
            Iterator<Node> documents = yaml.composeAll(new StringReader(text)).iterator();
            if (!documents.hasNext()) {
                if (text.lines().map(String::trim).anyMatch(line -> !line.isEmpty() && !line.startsWith("#"))) {
                    throw new ConfigException("config.yml contains an empty YAML document that cannot be preserved");
                }
                return 0;
            }
            Node root = documents.next();
            if (documents.hasNext() || !(root instanceof MappingNode mapping)) {
                throw new ConfigException("config.yml must contain exactly one top-level YAML mapping");
            }
            inspect(root, Collections.newSetFromMap(new IdentityHashMap<>()));
            // Construction supplies another duplicate-key and unsupported-tag check.
            yaml.load(text);
            for (NodeTuple tuple : mapping.getValue()) {
                ScalarNode key = (ScalarNode) tuple.getKeyNode();
                if (!VERSION_KEY.equals(key.getValue())) {
                    continue;
                }
                if (key.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN
                        || !(tuple.getValueNode() instanceof ScalarNode value)
                        || value.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN
                        || !Tag.INT.equals(value.getTag())
                        || !value.getValue().matches("0|[1-9][0-9]*")) {
                    throw new ConfigException("config-version must be a plain, unquoted nonnegative decimal integer");
                }
                try {
                    return Integer.parseInt(value.getValue());
                } catch (NumberFormatException failure) {
                    throw new ConfigException("config-version is outside the supported integer range");
                }
            }
            return 0;
        } catch (ConfigException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ConfigException("config.yml contains invalid, duplicate, or ambiguous YAML");
        }
    }

    private static void inspect(Node node, Set<Node> active) throws ConfigException {
        if (node instanceof AnchorNode || node.getAnchor() != null || !active.add(node)) {
            throw new ConfigException("config.yml contains a YAML anchor or alias that cannot be preserved");
        }
        if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())
                        || key.getValue().isEmpty() || key.getValue().contains(".")) {
                    throw new ConfigException("config.yml contains a mapping key that cannot be represented safely");
                }
                if (!keys.add(key.getValue())) {
                    throw new ConfigException("config.yml contains duplicate or ambiguous YAML keys");
                }
                inspect(tuple.getKeyNode(), active);
                inspect(tuple.getValueNode(), active);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                inspect(child, active);
            }
        } else if (!(node instanceof ScalarNode) || !(Tag.STR.equals(node.getTag())
                || Tag.INT.equals(node.getTag()) || Tag.FLOAT.equals(node.getTag()) || Tag.BOOL.equals(node.getTag()))) {
            throw new ConfigException("config.yml contains a null or unsupported YAML value that cannot be preserved");
        }
        active.remove(node);
    }

    private static YamlConfiguration parse(String text) throws ConfigException {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().parseComments(true);
            yaml.loadFromString(text);
            return yaml;
        } catch (InvalidConfigurationException | RuntimeException failure) {
            throw new ConfigException("config.yml contains invalid YAML or values that cannot be represented safely");
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] readConfig(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_CONFIG_BYTES) {
            throw new IOException("config.yml must be a regular file within its size limit");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IOException("config.yml exceeds its size limit");
        }
        return bytes;
    }

    static void writeAtomic(Path target, byte[] candidate, byte[] expectedSource) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("config.yml has no parent directory");
        }
        Path temporary = createPrivateTemporary(parent);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer contents = ByteBuffer.wrap(candidate);
                while (contents.hasRemaining()) {
                    channel.write(contents);
                }
                channel.force(true);
            }
            if (expectedSource == null) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("config.yml appeared while preparing its initial generation");
                }
            } else if (!Arrays.equals(expectedSource, readConfig(target))) {
                throw new IOException("config.yml changed while preparing its atomic replacement");
            }
            // NIO provides atomic rename, not compare-and-swap rename. Keep this exact-source
            // check adjacent to rename; mandatory rereads also prevent activating a raced file.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createPrivateTemporary(Path parent) throws IOException {
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            return Files.createTempFile(parent, ".nuvotifier-config-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        }
        Path temporary = Files.createTempFile(parent, ".nuvotifier-config-", ".tmp");
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (acl == null) {
                throw new IOException("the filesystem cannot enforce owner-only configuration permissions");
            }
            AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            acl.setAcl(List.of(owner));
            if (!acl.getAcl().equals(List.of(owner))) {
                throw new IOException("owner-only configuration permissions could not be verified");
            }
            return temporary;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /** Offline server-config adoption uses the exact same strict loader as plugin startup. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException("Expected config.yml path and optional default host");
        }
        String template;
        try (InputStream resource = BukkitConfigLoader.class.getClassLoader().getResourceAsStream("bukkitConfig.yml")) {
            if (resource == null) {
                throw new IOException("the bundled NuVotifier config template is unavailable");
            }
            template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            Prepared prepared = new BukkitConfigLoader().prepare(Path.of(arguments[0]), template,
                    arguments.length == 2 ? arguments[1] : "0.0.0.0");
            System.out.println("NuVotifier config supported=1 installed=1 source=" + prepared.sourceVersion()
                    + " state=" + prepared.state());
        } catch (ConfigException failure) {
            System.err.println("NuVotifier config supported=1 installed=" + installedVersion(Path.of(arguments[0]))
                    + " state=blocked: " + failure.getMessage());
            System.exit(1);
        }
    }
}
