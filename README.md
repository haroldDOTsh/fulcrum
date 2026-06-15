# Fulcrum

Minecraft server framework with modular APIs for data persistence, inter-server messaging, Creative Library player UI, and more.

## Installation

Add JitPack repository:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add dependencies:

```gradle
implementation 'com.github.haroldDOTsh.fulcrum:data-api:VERSION'
implementation 'com.github.haroldDOTsh.fulcrum:message-bus-api:VERSION'
implementation 'com.github.haroldDOTsh.fulcrum:runtime:VERSION'
```

## Modules

### Data Authority
PostgreSQL-backed durable command and projection ports for player, session, rank, and match data.

```java
long now = System.currentTimeMillis();
commands.submit(new DataAuthority.PlayerRankCommand(
    DataAuthority.CommandManifest.create(
        UUID.randomUUID(),
        "GRANT_RANK",
        "rank-admin:" + actorId,
        "player:" + playerId,
        "rank-grant:" + playerId + ":" + now,
        now + 5000L,
        "",
        currentRankRevision
    ),
    playerId,
    "VIP",
    List.of("VIP")
));
```

### Message Bus API
Inter-server messaging with Redis/in-memory backends.

```java
messageBus.broadcast("event.start", eventData);
messageBus.send("lobby-1", "player.transfer", playerData);
```

### Creative Library Player UI
Player-facing messages, menus, sounds, and scoreboards are provided by Creative Library.

```java
Message.success("Payment complete: {amount}", Message.slot("amount", amount)).send(player);
```

### Rank API
Unified rank system with priorities and expiration.

```java
rankService.setRank(playerId, Rank.VIP, expiration);
```

## Requirements

- Java 25 for the Paper runtime; Java 17/21 for individual API and service modules as configured
- Paper 26.1.2+
- Optional: Redis for message bus
- PostgreSQL for durable authority data

## Documentation

- [Data Authority](data-api/README.md)
- [Message Bus API](message-bus-api/README.md)
- Creative Library provides player-facing messages, menus, sounds, and scoreboards
- [Rank API](runtime/src/main/java/sh/harold/fulcrum/api/rank/README.md)
- [Module API](runtime/src/main/java/sh/harold/fulcrum/api/module/README.md)

## License

MIT
