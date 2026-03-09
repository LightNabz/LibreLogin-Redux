# LibreLogin Redux

A fork of [LibreLogin](https://github.com/kyngs/LibreLogin) with enhanced features and improvements. LibreLogin Redux builds upon the original authentication plugin with additional functionality, including player location persistence and optimized async operations.

(Originally based on LibrePremium) - an open-source, multiplatform, and highly customizable authentication plugin with outstanding features and API.

# Quick information

<img src="https://img.shields.io/badge/Java%20version-%2017+-blue?style=for-the-badge&logo=java&logoColor=white"
alt="Plugin requires Java 17 or newer"></img>
<img src="https://img.shields.io/badge/Minecraft%20version-%201.21.1+-green?style=for-the-badge&logo=minecraft&logoColor=white"
alt="Plugin supports Minecraft 1.21.1 and newer"></img>

<a href="https://discord.gg/HP3CSfCv2v">
<img src="https://img.shields.io/badge/Discord-%20SUPPORT-blue?style=for-the-badge&logo=discord&logoColor=white" 
alt="Support available on Discord"></img>
</a>
<a href="https://github.com/kyngs/LibreLogin/wiki">
<img src="https://img.shields.io/badge/Documentation-555555?style=for-the-badge&logo=wikipedia" alt="Documentation on the Wiki"></img>
</a>

<a href="https://github.com/kyngs/LibreLogin/graphs/contributors">
<img src="https://img.shields.io/badge/Contributors-Credits-blue?style=for-the-badge" 
alt="Contributors listed"></img>
</a>

## Basic set of features

- AutoLogin for premium players
- TOTP 2FA (Authy, Google Authenticator...) [details](https://github.com/kyngs/LibreLogin/wiki/2FA)
- Session system
- Name validation (including case sensitivity check)
- Automatic data migration for premium players
- Migration of a player's data by using one command
- Geyser (Bedrock) support using [Floodgate](https://github.com/kyngs/LibreLogin/wiki/Floodgate)

## LibreLogin Redux Enhancements

- **Player Location Persistence** *(Paper only)*: Players return to their last logout location instead of spawn. Configurable per-world, with optional safety checks and age limits
- **Floodgate Support** *(Paper only)*: Full location restoration support for Bedrock players (both linked and unlinked accounts)
- **Async Database Operations**: All database queries are executed asynchronously to prevent main-thread blocking and lag
- **Enhanced Minecraft Compatibility**: Updated support for Minecraft 1.21.11+

## Considerations

- When using on proxy, you need to secure your limbo

## Platforms

- [x] Velocity (half)
- [x] BungeeCord (half)
- [x] Paper

## References

- check out [reviews](https://www.spigotmc.org/resources/librelogin-authorization-plugin-automatic-login-2fa.101040/reviews) on spigotmc.org  

# Special thanks

- [Raixo](https://github.com/RAIXOCZ) - for developing the original bungeecord port
- [FastLogin contributors](https://github.com/games647/FastLogin) - for their work, which was used as a base for the
  paper port
- [Fejby](https://github.com/Fejby) - for providing Floodgate test server and helping with testing

# License

LibrePremium is [FOSS](https://en.wikipedia.org/wiki/Free_and_open-source_software), licensed under the Mozilla Public License 2.0.

[Read the license here.](https://github.com/kyngs/LibreLogin/blob/master/LICENSE)

The plugin **is and always will be** completely open-source, so you don't need to worry about malicous copies.
