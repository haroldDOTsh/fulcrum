# Fulcrum Player Data API Documentation

This module provides a unified, backend-agnostic player data layer for Minecraft plugins. Define your own schemas and
persist player data using SQL or JSON backends, with explicit schema→backend registration. All persistence is routed
through the `PlayerDataBackend` interface, supporting hybrid storage, schema evolution, and robust async/sync access.
The API is designed for plugin interoperability, modularity, and developer ergonomics.

**Key Concepts:**

- **PlayerProfile**: Represents a single player's data, including all registered schemas.
- **PlayerProfileManager**: Manages lifecycle, loading, and saving of player profiles.
- **PlayerStorageManager**: Handles schema→backend registration and delegates all persistence.
- **PlayerDataBackend**: Central interface for all load/save operations (e.g., `SqlDataBackend`, `JsonFileBackend`).
- **Schema Engines**: `TableSchema` (SQL), `JsonSchema` (JSON), and custom schemas.

---

## Core API

### PlayerProfile

- `boolean isLoaded()` — True if all data is loaded and available.
- `<T> T get(Class<T> schemaType)` — Retrieve the data object for a schema type (null if not loaded/registered).
- `<T> void set(Class<T> schemaType, T value)` — Update the data object for a schema type.
- `void save()` — Synchronously save all data (main thread only).
- `CompletableFuture<Void> saveAsync()` — Asynchronously save all data (any thread).

#### Example

```java
PlayerProfile profile = playerProfileManager.getProfile(playerUUID);
if (profile.isLoaded()) {
    PlayerStats stats = profile.get(PlayerStats.class);
    stats.setKills(stats.getKills() + 1);
    profile.set(PlayerStats.class, stats);
    profile.saveAsync();
}
```

### PlayerProfileManager

- `PlayerProfile getProfile(UUID playerId)` — Synchronously get or load a profile.
- `CompletableFuture<PlayerProfile> loadProfileAsync(UUID playerId)` — Asynchronously load a profile.
- `void saveAll()` — Synchronously save all loaded profiles.
- `CompletableFuture<Void> saveAllAsync()` — Asynchronously save all loaded profiles.

#### Example

```java
playerProfileManager.loadProfileAsync(playerUUID)
    .thenAccept(profile -> {
        PlayerStats stats = profile.get(PlayerStats.class);
        // ...
    });
```

### PlayerStorageManager

- `void registerSchema(PlayerDataSchema<?> schema, PlayerDataBackend backend)` — Register a schema with its backend.
  Must be called before any profile loads.
- `Collection<PlayerDataSchema<?>> getRegisteredSchemas()` — Get all registered schemas.

#### Example

```java
playerStorageManager.registerSchema(new PlayerStatsSchema(), new SqlDataBackend(dataSource));
playerStorageManager.registerSchema(new PlayerSettingsSchema(), new JsonFileBackend(dataDir));
```

---

## Unified Backend System

All persistence is routed through the `PlayerDataBackend` interface. Schemas no longer perform direct I/O; instead, you
must explicitly register each schema with its backend. This enables hybrid storage (SQL, JSON, etc.) and future
extensibility (e.g., Redis, network transfer).

### Registering Schemas

```java
// SQL-backed schema
playerStorageManager.registerSchema(new AutoTableSchema<>(PlayerStats.class), new SqlDataBackend(dataSource));

// JSON-backed schema
playerStorageManager.registerSchema(new GenericJsonSchema<>(PlayerSettings.class), new JsonFileBackend(dataDir));
```

- Registration must occur before any player data is loaded.
- Each schema is mapped to exactly one backend.
- All load/save operations are delegated to the backend for that schema.

### Implementing a Custom Backend

Implement the `PlayerDataBackend` interface to support new storage types (e.g., Redis, network, etc). See
`SqlDataBackend` and `JsonFileBackend` for reference.

---

## Schema Engines

### TableSchema (SQL)

- Use for structured, queryable data (e.g., stats, achievements).
- Supports schema evolution and migration.
- Use `@Table`, `@Column`, and `@SchemaVersion` annotations for mapping.
- All SQL I/O is handled by the backend; schemas only provide serialization logic.

#### Example

```java
@Table("player_stats")
public class PlayerStats {
    @Column(primary = true)
    public UUID id;
    public int kills;
    public int deaths;
    public String name;
    public boolean online;
}

playerStorageManager.registerSchema(new AutoTableSchema<>(PlayerStats.class), new SqlDataBackend(dataSource));
```

### JsonSchema (JSON)

- Use for flexible, nested, or rapidly evolving data.
- Stores data as JSON blobs (in files or SQL JSON columns).
- All I/O is handled by the backend; schemas only provide serialization logic.

#### Example

```java
public class PlayerSettings {
    // ...fields...
}

playerStorageManager.registerSchema(new GenericJsonSchema<>(PlayerSettings.class), new JsonFileBackend(dataDir));
```

---

## Migration and Advanced Patterns

- Migrate existing schemas by registering them with a new backend.
- Use custom backends for advanced scenarios (e.g., Redis, sharding, network transfer).
- All test and production code must use explicit schema→backend registration.
- No direct I/O in schemas; all persistence is backend-driven.

---

## Testing

- All test schemas must be registered with a dummy or in-memory backend.
- Use JUnit 5 for pure Java logic; no Bukkit mocking required.
- Example:

```java
@BeforeEach
void setup() {
    playerStorageManager.registerSchema(new AutoTableSchema<>(TestSchema.class), new InMemoryBackend());
}
```

---

## FAQ

**Q: How do I add a new data model?**
A: Define a POJO, create a schema (e.g., `AutoTableSchema` or `GenericJsonSchema`), and register it with a backend.

**Q: Can I use both SQL and JSON for different schemas?**
A: Yes. Register each schema with the backend best suited for its data.

**Q: How do I migrate data between backends?**
A: Register the schema with the new backend and implement migration logic as needed.

---

## TODO

- Implement Redis/transfer packet backends
- Add advanced migration utilities
- Expand backend support

---

For further details, see Javadoc on each public interface and class.
