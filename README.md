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

### Data API
Storage abstraction with MongoDB/JSON backends, transactions, and complex queries.

```java
DataAPI dataAPI = DataAPI.create(adapter);
Document player = dataAPI.player(uuid);
player.set("stats.level", 10);
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

- Java 21
- Minecraft 1.21+ (Paper/Spigot)
- Optional: Redis for message bus
- Optional: MongoDB for data storage

## Documentation

- [Data API](data-api/README.md)
- [Message Bus API](message-bus-api/README.md)
- [Menu API](runtime/src/main/java/sh/harold/fulcrum/api/menu/README.md)
- [Message API](runtime/src/main/java/sh/harold/fulcrum/api/message/README.md)
- [Rank API](runtime/src/main/java/sh/harold/fulcrum/api/rank/README.md)
- [Module API](runtime/src/main/java/sh/harold/fulcrum/api/module/README.md)

## License

MIT