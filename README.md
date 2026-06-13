# Fulcrum

Minecraft server framework with modular APIs for data persistence, messaging, menus, and more.

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
commands.submit(new DataAuthority.CommandEnvelope(
    UUID.randomUUID(),
    DataAuthority.CommandType.GRANT_RANK,
    "player:" + playerId,
    Map.of("playerId", playerId.toString(), "primaryRank", "VIP"),
    "rank-admin:" + actorId,
    UUID.randomUUID().toString(),
    Instant.now()
));
```

### Message Bus API
Inter-server messaging with Redis/in-memory backends.

```java
messageBus.broadcast("event.start", eventData);
messageBus.send("lobby-1", "player.transfer", playerData);
```

### Menu API
Inventory GUIs with pagination and scrollable viewports.

```java
menuService.createMenuBuilder()
    .title("Shop")
    .rows(6)
    .addButton(buyButton, 2, 2)
    .buildAsync(player);
```

### Message API
Localized messages and scoreboards.

```java
Message.success("payment.complete", amount).send(player);
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
- [Menu API](runtime/src/main/java/sh/harold/fulcrum/api/menu/README.md)
- [Message API](runtime/src/main/java/sh/harold/fulcrum/api/message/README.md)
- [Rank API](runtime/src/main/java/sh/harold/fulcrum/api/rank/README.md)
- [Module API](runtime/src/main/java/sh/harold/fulcrum/api/module/README.md)

## License

MIT
