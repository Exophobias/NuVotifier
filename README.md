# NuVotifier

NuVotifier is a secure alternative to using the original Votifier project.
NuVotifier will work in place of Votifier - any vote listener that supports
Votifier will also support NuVotifier.

This Patriam 2.7.3 maintenance branch packages a Bukkit/Paper-only JAR. Build it
with JDK 17 and `./gradlew clean build`; the release artifact is
`universal/build/libs/nuvotifier-bukkit-2.7.3-patriam.1-all.jar`. Upstream's
other platform sources remain in the repository but are not part of this build.
The listener closes and its Netty threads still terminate before a reload
rebinds the port, without Netty's default two-second shutdown quiet period.

NuVotifier also adds forwarding and listener test commands not present in the
original version.

[Setup Guide](https://github.com/NuVotifier/NuVotifier/wiki/Setup-Guide)

[Troubleshooting Guide](https://github.com/NuVotifier/NuVotifier/wiki/Troubleshooting-Guide)

[Developer Information](https://github.com/NuVotifier/NuVotifier/wiki/Developer-Documentation)

# License

NuVotifier is GNU GPLv3 licensed. This project's license can be viewed [here](LICENSE).
