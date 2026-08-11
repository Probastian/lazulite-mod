# Lazuli

Lazuli is a Fabric Minecraft mod built around Steamworks-powered social
and cloud features.

## What Lazuli aims to do

- Steam Cloud sync for player data and settings
- Steam Friends integration and join-from-friends support
- Steam Workshop content sharing
- Steam server discovery and multiplayer matchmaking
- Expand into additional Steamworks features over time

## Current status

- Mod ID: `lazuli`
- Package namespace: `de.lazuli`
- Current example feature: `features/hello-world-main-menu`
- Steamworks support is planned (Cloud, Friends, Workshop, server discovery)

## Repository

- `api/`: stable public abstractions
- `common/`: version-independent logic
- `services/`: shared cross-feature systems
- `features/`: self-contained feature modules
- `platform/`: Fabric adapters for supported Minecraft versions

## Supported Fabric versions

- `platform/fabric-26.2`
- `platform/fabric-26.1`
- `platform/fabric-1.21.11`

## Requirements

- JDK 21 (root project toolchain) — the `platform/fabric-26.1` and
  `platform/fabric-26.2` modules additionally require JDK 25, resolved
  automatically via the Gradle toolchain resolver as long as a JDK 25 is
  available (Gradle will provision one if none is found locally).
- The Foojay toolchain resolver plugin (declared in `settings.gradle`)
  handles JDK provisioning, so a manually installed matching JDK isn't
  required.

## Quick start

Build the whole project:

```powershell
./gradlew build
```

Run the Fabric client for a specific supported Minecraft version:

```powershell
./gradlew :platform:fabric-26.2:runClient
./gradlew :platform:fabric-26.1:runClient
./gradlew :platform:fabric-1.21.11:runClient
```

This launches a development Minecraft client with the mod loaded. First run
downloads and deobfuscates the relevant Minecraft/Fabric artifacts, so it
will take longer than subsequent runs.

Each platform module also has a matching `runServer` task (e.g.
`./gradlew :platform:fabric-26.2:runServer`) for launching a development
dedicated server with the mod loaded.

## License

CC0 — see [LICENSE](LICENSE).

