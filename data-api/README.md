# Fulcrum Data API

A modular, high-performance data management API for player-centric applications. The Fulcrum Data API provides a unified interface for structured and flexible data storage, efficient change tracking, batch processing, and seamless integration with multiple backends.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [API Reference](#api-reference)
- [Usage Examples](#usage-examples)
- [Advanced Features](#advanced-features)
- [Integration Guide](#integration-guide)
- [Optimizations](#optimizations)

---

## Overview

The Fulcrum Data API is designed to simplify player data management. It supports multiple storage backends (SQL, MongoDB, JSON), automatic change tracking, batch operations, and asynchronous processing. This makes it suitable for both small plugins and large-scale platforms.

---

## Architecture

The API is structured around modular components:

- **Query Builder:** [`CrossSchemaQueryBuilder`](data-api/src/main/java/sh/harold/fulcrum/api/data/query/CrossSchemaQueryBuilder.java:1)
- **Backends:** [`SqlDataBackend`](data-api/src/main/java/sh/harold/fulcrum/api/data/backend/sql/SqlDataBackend.java:1), [`MongoDataBackend`](data-api/src/main/java/sh/harold/fulcrum/api/data/backend/mongo/MongoDataBackend.java:1), [`JsonFileBackend`](data-api/src/main/java/sh/harold/fulcrum/api/data/backend/json/JsonFileBackend.java:1)
- **Registry:** [`PlayerDataRegistry`](data-api/src/main/java/sh/harold/fulcrum/api/data/registry/PlayerDataRegistry.java:1)
- **Streaming:** [`AsyncResultStream`](data-api/src/main/java/sh/harold/fulcrum/api/data/query/streaming/AsyncResultStream.java:1)
- **Batch Processing:** [`BatchExecutor`](data-api/src/main/java/sh/harold/fulcrum/api/data/query/batch/BatchExecutor.java:1)
- **Integration:** [`PlayerDataBackend`](data-api/src/main/java/sh/harold/fulcrum/api/data/backend/PlayerDataBackend.java:1), [`PlayerProfileManager`](data-api/src/main/java/sh/harold/fulcrum/api/data/registry/PlayerProfileManager.java:1)

---

## Quick Start

### 1. Define Your Data Schema

```java
// PlayerStats.java
@Table("player_stats")
@SchemaVersion(1)
public class PlayerStats {
    @Column(primary = true, generation = PrimaryKeyGeneration.PLAYER_UUID)
    public UUID playerId;

    @Column
    public String displayName;

    @Column
    public int kills = 0;

    @Column
    public int deaths = 0;

    @Column
    public long lastLogin = System.currentTimeMillis();
}
```

### 2. Register the Schema

```java
// In your plugin's onEnable()
public void onEnable() {
    PlayerDataRegistry.registerSchema(
        new AutoTableSchema<>(PlayerStats.class)
    );
    getLogger().info("Data API initialized!");
}
```

### 3. Access and Update Player Data

```java
// When a player joins
@EventHandler
public void onPlayerJoin(PlayerJoinEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();

    // Get or create profile
    PlayerProfile profile = PlayerProfileManager.getProfile(playerId);

    // Get or initialize stats
    PlayerStats stats = profile.get(PlayerStats.class);
    if (stats == null) {
        stats = new PlayerStats();
        stats.playerId = playerId;
        stats.displayName = event.getPlayer().getName();
    }

    // Update last login
    stats.lastLogin = System.currentTimeMillis();

    // Save (uses dirty tracking)
    profile.set(PlayerStats.class, stats);

    event.getPlayer().sendMessage("Welcome back! You have " + stats.kills + " kills!");
}
```

### 4. Query Player Data

Use the query builder to fetch player stats matching specific criteria. This example retrieves all players with more than 100 kills:

```java
CrossSchemaQueryBuilder
    .from(PlayerStats.class)
    .where("kills", greaterThan(100))
    .executeAsync()
    .thenAccept(results -> {
        for (CrossSchemaResult result : results) {
            PlayerStats stats = result.get(PlayerStats.class);
            System.out.println(stats.displayName + ": " + stats.kills + " kills");
        }
    });
```

---

## Core Concepts

### PlayerProfile

Represents a player's data wallet, holding all their information (stats, settings, achievements, etc.) and automatically tracking changes.

```java
PlayerProfile profile = PlayerProfileManager.getProfile(playerId);
PlayerStats stats = profile.get(PlayerStats.class);
stats.kills++;
profile.set(PlayerStats.class, stats); // Change is tracked automatically
```

### Data Schemas

Define the structure and storage of your data.

```java
// Flexible JSON data
public class PlayerSettings extends JsonSchema<PlayerSettings> {
    public Map<String, Object> settings = new HashMap<>();
}

// Structured SQL data
@Table("player_stats")
public class PlayerStats {
    @Column(primary = true)
    public UUID playerId;

    @Column
    public int kills = 0;
}
```

### Dirty Data Tracking

The system provides dual persistence strategies for optimal data safety and performance:

- **Event-Based Persistence**: Immediate persistence with throttling (30-second intervals) to prevent data loss
- **Time-Based Persistence**: Batched persistence every 5 minutes for efficient cleanup and backup

This dual approach ensures both immediate data safety and efficient resource utilization.

---

## API Reference

### PlayerProfile

**getAsync**: Asynchronously retrieves data for the specified schema type.

**set**: Sets and tracks data for the specified schema type.

**save**: Saves data for the schema type, with an option for immediate persistence.

**saveAsync**: Asynchronously saves data for the schema type.

**loadAll**: Loads all schemas for the profile.

**loadAllAsync**: Asynchronously loads all schemas.

**saveAll**: Saves all schemas. When called without parameters, forces immediate persistence for critical operations like logout. When called with `immediate: false`, uses dirty tracking with event-based persistence.

**saveAllAsync**: Asynchronously saves all schemas using the dirty tracking system.

**setDirtyTrackingEnabled**: Enables or disables dirty data tracking for this profile.

### PlayerProfileManager

**load**: Loads a player profile synchronously.

**unload**: Removes a player profile from memory.

**findPlayers**: Finds player profiles matching a query asynchronously.

**loadProfiles**: Loads multiple player profiles asynchronously.

**unloadAll**: Removes all player profiles from memory.

**fromSchema**: Sets the schema for the query.

**where**: Adds a filter to the query.

**withRank**: Filters by player rank.

**withMinLevel**: Filters by minimum level.

**inGuild**: Filters by guild name.

**online**: Filters for online players.

**offline**: Filters for offline players.

**limit**: Limits the number of results.

**offset**: Sets the result offset.

**findAsync**: Executes the query asynchronously.

**find**: Executes the query synchronously.

**countAsync**: Asynchronously counts matching profiles.

### DirtyDataManager

**initialize**: Initializes the dirty data manager with a cache.

**shutdown**: Shuts down the dirty data manager and releases resources.

**getCache**: Returns the current dirty data cache.

**markDirty**: Marks a schema as dirty for a player.

**persistDirtyData**: Persists dirty data for a specific player.

**persistAllDirtyData**: Persists all dirty data using the time-based persistence strategy.

**persistEntries**: Persists a collection of dirty data entries.

**persistDirtyDataAsync**: Asynchronously persists dirty data for a player.

**persistAllDirtyDataAsync**: Asynchronously persists all dirty data.

**cleanupOldEntries**: Removes old dirty data entries.

**getStats**: Returns current dirty data statistics.

### CrossSchemaQueryBuilder

**from**: Starts a new query from the given schema.

**where**: Adds a filter to the current schema.

**join**: Joins another schema by UUID.

**orderBy**: Sorts results by the given field.

**limit**: Limits the number of results.

**offset**: Sets the result offset.

**executeAsync**: Executes the query asynchronously.

**stream**: Streams results asynchronously.

**forEachAsync**: Processes each result asynchronously.

**collectAsync**: Collects results asynchronously using a collector.

**after**: Sets a cursor for pagination.

**pageSize**: Sets the page size for pagination.

**getPage**: Retrieves a specific page of results.

**countAsync**: Counts results asynchronously.

**bufferSize**: Sets the buffer size for streaming.

**withBackpressure**: Configures backpressure handling for streaming.

**batch**: Enables batch mode for bulk operations.

**withBatchConfig**: Configures batch operations.

**batchSize**: Sets the batch size for operations.

**parallelBatches**: Sets the number of parallel batches.

**withConnectionPooling**: Configures connection pooling.

**withPreparedStatementCache**: Configures prepared statement caching.

**withMemoryPooling**: Enables or disables memory pooling.

**withBulkOperations**: Enables or disables bulk operations.

**executeInBatches**: Executes the query in batches.

**beginBatchTransaction**: Begins a batch transaction.

**withBackend**: Specifies the backend for the query.

**batchLoad**: Loads a batch of data by UUID.

**batchUpdate**: Performs a batch update.

**sortBy**: Sorts results by the given field and direction.

**filter**: Adds a filter to the query.

**streamLazy**: Streams results lazily.

---

## Usage Examples

### Basic Query

```java
CrossSchemaQueryBuilder
    .from(RankSchema.class)
    .where("rank", equals("MVP++"))
    .executeAsync()
    .thenAccept(results -> { /* handle results */ })
    .exceptionally(ex -> { /* handle error */ return null; });
```

### Streaming Large Datasets

```java
CrossSchemaQueryBuilder
    .from(PlayerStatsSchema.class)
    .where("kills", greaterThan(100))
    .bufferSize(1000)
    .withBackpressure(BackpressureHandler.Strategy.ADAPTIVE)
    .stream()
    .thenCompose(stream -> stream.forEachAsync(this::processPlayer));
```

### Batch Update

```java
CrossSchemaQueryBuilder
    .from(PlayerStatsSchema.class)
    .where("lastLogin", lessThan(oneWeekAgo))
    .batch()
    .batchUpdate(Map.of("status", "inactive"))
    .thenApply(result -> logBatchResult(result));
```

### Integration with PlayerDataBackend

```java
PlayerDataBackend backend = getBackend();
backend.createQueryBuilder(RankSchema.class)
    .where("rank", equals("MVP++"))
    .executeAsync();
```

---

## Persistence Strategies

The Fulcrum Data API uses a sophisticated dual persistence strategy to balance data safety and performance:

### Event-Based Persistence (Default: Enabled)

- **Immediate persistence** with 30-second throttling
- Triggers on every data change via `saveWithDirtyTracking()`
- Prevents data loss during unexpected shutdowns
- Optimal for critical operations like logout saves

### Time-Based Persistence (Default: Enabled)

- **Batched persistence** every 5 minutes
- Handles cleanup and backup of any missed data
- Efficient for bulk operations and system maintenance
- Reduces database load during high-traffic periods

### Configuration Options

```java
// In your PlayerDataFeature configuration
config.dirtyTrackingEnabled = true;           // Enable dirty tracking
config.eventBasedPersistence = true;          // Enable immediate persistence
config.timeBasedPersistence = true;           // Enable batched persistence
config.persistenceInterval = Duration.ofMinutes(5); // Batch interval

// For pure batching (no immediate persistence)
config.eventBasedPersistence = false;
config.timeBasedPersistence = true;

// For immediate-only persistence (no batching)
config.eventBasedPersistence = true;
config.timeBasedPersistence = false;
```

### When to Use Each Strategy

- **Both enabled (recommended)**: Maximum data safety with efficient batching
- **Event-based only**: Critical applications requiring immediate persistence
- **Time-based only**: High-performance applications with acceptable data loss risk

---

## Advanced Features

### Asynchronous Operations

Always use async patterns to avoid blocking server threads.

```java
public CompletableFuture<PlayerStats> getStatsAsync(UUID playerId) {
    return CompletableFuture.supplyAsync(() -> {
        PlayerProfile profile = PlayerProfileManager.getProfile(playerId);
        return profile.get(PlayerStats.class);
    });
}
```

### Database Relationships

Define relationships using foreign keys.

```java
@Table("guild_members")
public class GuildMember {
    @Column(primary = true, generation = PrimaryKeyGeneration.RANDOM_UUID)
    public UUID id;

    @Column
    public UUID playerId;

    @ForeignKey(references = Guild.class, onDelete = "CASCADE")
    public UUID guildId;

    @Column
    public String role;

    @Column
    public long joinedAt;
}
```

### Batch Processing

Efficiently process multiple players and save in bulk.

```java
public void processBatch(Collection<UUID> playerIds) {
    CompletableFuture.allOf(
        playerIds.stream()
            .map(this::processPlayerAsync)
            .toArray(CompletableFuture[]::new)
    ).thenRun(() -> {
        // Note: Individual changes are automatically persisted via event-based persistence
        // This call ensures any remaining dirty data is persisted via time-based persistence
        DirtyDataManager.persistAllDirtyDataAsync();
    });
}
```

---

## Integration Guide

### Using PlayerDataBackend

```java
PlayerDataBackend backend = getBackend();
backend.createQueryBuilder(RankSchema.class)
    .where("rank", equals("MVP++"))
    .executeAsync();
```

### Using PlayerDataRegistry

```java
PlayerDataRegistry.queryBuilder(RankSchema.class)
    .join(GuildSchema.class)
    .executeAsync();
```

### PlayerProfileManager Integration

```java
PlayerProfileManager.find()
    .withRank("MVP++")
    .inGuild("Titans")
    .findAsync();
```

---

## Optimizations

- Use `.bufferSize()` to control memory usage during streaming.
- Adaptive backpressure prevents overload.
- Tune batch size for updates and deletes to balance throughput and memory.
- Use `MemoryPool` for buffer reuse.
- Monitor JVM heap usage and tune garbage collection as needed.
- Use indexed fields for joins and filters (SQL, MongoDB).
- Always use async patterns; never block server threads.
- Monitor query latency and throughput, and log slow queries for tuning.

---

## Additional Resources

- See the source code for more advanced usage and backend-specific features.
- For questions or contributions, open an issue or pull request.
