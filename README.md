# Fulcrum


## Installation

Add JitPack repository to your `build.gradle`:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Import specific modules:

```gradle
implementation 'com.github.haroldDOTsh.fulcrum:data-api:VERSION'
implementation 'com.github.haroldDOTsh.fulcrum:message-api:VERSION'
implementation 'com.github.haroldDOTsh.fulcrum:menu-api:VERSION'
implementation 'com.github.haroldDOTsh.fulcrum:rank-api:VERSION'
```

## Modules

- **data-api** - Player data persistence with multiple backend support
- **message-api** - Message building and scoreboard management
- **menu-api** - Inventory-based menu system with instance management and parent-child navigation
- **rank-api** - Player rank management with expiration support
- **runtime** - Implementation module for Minecraft servers

## Requirements

- Java 21
- Minecraft server (Paper/Spigot)

## License

MIT
