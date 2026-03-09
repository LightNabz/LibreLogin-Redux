# LibreLogin Redux

A fork of [LibreLogin](https://github.com/kyngs/LibreLogin) with enhanced features and improvements. LibreLogin Redux builds upon the original authentication plugin with additional functionality, including player location persistence and optimized async operations.

# Quick information

<img src="https://img.shields.io/badge/Java%20version-%2017+-blue?style=for-the-badge&logo=java&logoColor=white"
alt="Plugin requires Java 17 or newer"></img>
<img src="https://img.shields.io/badge/Minecraft%20version-%201.21.1+-green?style=for-the-badge&logo=minecraft&logoColor=white"
alt="Plugin supports Minecraft 1.21.1 and newer"></img>
<a href="https://github.com/kyngs/LibreLogin/wiki">
<img src="https://img.shields.io/badge/Documentation-555555?style=for-the-badge&logo=wikipedia" alt="Documentation on the Wiki"></img>
</a>
<a href="https://github.com/LightNabz/LibreLogin-Redux/releases/latest"><img src="https://img.shields.io/github/v/release/LightNabz/LibreLogin-Redux?style=for-the-badge&logo=github&logoColor=white&label=Release&color=238636" alt="Latest Release"></a>

## Basic set of features (from original LibreLogin)

- AutoLogin for premium players
- TOTP 2FA (Authy, Google Authenticator...) [details](https://github.com/kyngs/LibreLogin/wiki/2FA)
- Session system
- Name validation (including case sensitivity check)
- Automatic data migration for premium players
- Migration of a player's data by using one command
- Geyser (Bedrock) support using [Floodgate](https://github.com/kyngs/LibreLogin/wiki/Floodgate)

## LibreLogin Redux Enhancements

- **Player Location Persistence** *(Paper only)*: Players return to their last logout location instead of spawn. Configurable per-world, with optional safety checks and age limits
- **Better Floodgate Support** *(Paper only)*: Full location restoration support for Bedrock players (both linked and unlinked accounts)
- **Async Database Operations**: All database queries are executed asynchronously to prevent main-thread blocking and lag
- **Enhanced Minecraft Compatibility**: Updated support for Minecraft 1.21.11+

## Platforms

- [] Velocity (partially, no Redux Enhancements)
- [] BungeeCord (partially, no Redux Enhancements)
- [x] Paper

- If you happen to know two or three things about the partially supported platforms, don't hestite to pull a commit <3

# Special thanks

- [kyngs](https://github.com/kyngs) - Original author of LibreLogin
- [FastLogin contributors](https://github.com/games647/FastLogin) - for their work, which was used as a base for the
  paper port

# License

LibrePremium is [FOSS](https://en.wikipedia.org/wiki/Free_and_open-source_software), licensed under the Mozilla Public License 2.0.

[Read the license here.](https://github.com/kyngs/LibreLogin/blob/master/LICENSE)