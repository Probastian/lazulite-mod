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

## Quick start

Build the project:

```powershell
./gradlew build
```

Run the Fabric client:

```powershell
./gradlew :platform:fabric-26.2:runClient
```

## License

CC0 — see [LICENSE](LICENSE).

