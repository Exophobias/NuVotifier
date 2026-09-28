# NuVotifier

NuVotifier is a secure alternative to using the original Votifier project.
NuVotifier will work in place of Votifier - any vote listener that supports
Votifier will also support NuVotifier.

This Patriam 2.7.3 maintenance branch packages a Paper 26.3-only JAR with
Gradle 9.8.0 and Java 25. Build it with JDK 25 and
`./gradlew clean verifyRelease` (tests, static analysis, exact API assertion and packaging);
the release artifact is `universal/build/libs/nuvotifier-bukkit-2.7.3-patriam.2-all.jar`. Upstream's
other platform sources remain in the repository but are not part of this build.
The listener closes and its Netty threads still terminate before a reload
rebinds the port, without Netty's default two-second shutdown quiet period.

This receiver requires authenticated protocol v2 using HMAC-SHA-256. Fresh
tokens and connection challenges contain 32 cryptographically random bytes;
MACs use constant-time comparison and each connection has a unique challenge.
Protocol v1 and unauthenticated plugin-message forwarding are refused. Existing
configured tokens remain unchanged during the schema-0 to schema-1 migration.
Invalid configuration and failed replacement binds retain the known-good
listener; credentials removed on reload are revoked. Secret values are never
included in configuration errors or initial setup logs.

Connections have a 10-second absolute deadline, a 128-connection global cap,
a 16-connection per-address cap, and a global admission budget of 128 per
second with a burst of 256. Votes retain the protocol's 1,024-byte packet limit;
JSON, UTF-8, field types and signatures are validated before delivery. Netty
4.2.18 and Gson 2.14.0 are bundled. Dependency locks, SHA-256 verification
metadata and a checked Gradle distribution pin the build inputs. Protocol v2
authenticates votes; its existing wire format does not encrypt their contents.

Configuration replacement is atomic, preserves administrator values and
extension keys, and uses owner-only temporary files without making backups.
Malformed, ambiguous and future configurations remain unchanged. Detailed
vote diagnostics are disabled by default; keep `debug: false` in production.
The bind host must be a literal IPv4 or IPv6 address. Configure voting providers
for protocol v2 with the matching token from the private `plugins/Votifier/config.yml`.
The other upstream platform sources are retained but are outside this build's
runtime and security qualification.

[Setup Guide](https://github.com/NuVotifier/NuVotifier/wiki/Setup-Guide)

[Troubleshooting Guide](https://github.com/NuVotifier/NuVotifier/wiki/Troubleshooting-Guide)

[Developer Information](https://github.com/NuVotifier/NuVotifier/wiki/Developer-Documentation)

# License

NuVotifier is GNU GPLv3 licensed. This project's license can be viewed [here](LICENSE).
