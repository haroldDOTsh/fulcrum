# Fulcrum Data API Documentation
This module is meant as a data layer for any custom plugins built on top of it. Developers will be able to define their own schemas, and have it all stored in one central database. We support hybrid SQL and JSON storage engines, automatic schema evolution, and robust async/sync access patterns. The API prioritizes plugin interoperability and developer UX.

**Important Classes:**
- `PlayerProfile`: Represents a single player's data, including all registered schemas.
- `PlayerProfileManager`: Manages lifecycle, loading, and saving of player profiles.
- `PlayerStorageManager`: Abstracts storage backends and schema registration.
- Schema Engines: `TableSchema` (SQL) and `JsonSchema` (JSON).

## API References

### PlayerProfile

- `boolean isLoaded()`
  - **Description:** Returns true if the profile's data is fully loaded and available for access.
  - **Usage:** Check before accessing data synchronously.
  - **Thread-Safety:** Immutable after load; safe for concurrent reads.

- `<T> T get(Class<T> schemaType)`
  - **Description:** Retrieves the data object for the given schema type.
  - **Usage:** Use to access custom or built-in data models.
  - **Null Handling:** Returns null if schema is not registered or not loaded.
  - **Thread-Safety:** Safe for concurrent reads.

- `<T> void set(Class<T> schemaType, T value)`
  - **Description:** Updates the data object for the given schema type.
  - **Usage:** Use to modify player data before saving.
  - **Thread-Safety:** Not thread-safe for concurrent writes; use async methods for cross-thread access.

- `void save()`
  - **Description:** Synchronously saves all data to storage.
  - **Usage:** Use only on the main thread; blocks until complete.
  - **Thread-Safety:** Not thread-safe; do not call from async threads.

- `CompletableFuture<Void> saveAsync()`
  - **Description:** Asynchronously saves all data to storage.
  - **Usage:** Use for non-blocking saves from any thread.
  - **Thread-Safety:** Safe for concurrent use.

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

- `PlayerProfile getProfile(UUID playerId)`
  - **Description:** Returns the profile for the given player, loading if necessary.
  - **Usage:** Use for synchronous access on the main thread.
  - **Thread-Safety:** Not thread-safe for concurrent loads.

- `CompletableFuture<PlayerProfile> loadProfileAsync(UUID playerId)`
  - **Description:** Loads the profile asynchronously.
  - **Usage:** Use for async plugin logic or background tasks.
  - **Thread-Safety:** Safe for concurrent use.

- `void saveAll()`
  - **Description:** Synchronously saves all loaded profiles.
  - **Usage:** Use for plugin shutdown or periodic flush.
  - **Thread-Safety:** Not thread-safe; call from main thread only.

- `CompletableFuture<Void> saveAllAsync()`
  - **Description:** Asynchronously saves all loaded profiles.
  - **Usage:** Use for non-blocking global saves.
  - **Thread-Safety:** Safe for concurrent use.

#### Example
```java
playerProfileManager.loadProfileAsync(playerUUID)
    .thenAccept(profile -> {
        PlayerStats stats = profile.get(PlayerStats.class);
        // ...
    });
```

### PlayerStorageManager

- `void registerSchema(PlayerDataSchema<?> schema)`
  - **Description:** Registers a new schema for player data.
  - **Usage:** Call during plugin startup before loading profiles.
  - **Thread-Safety:** Not thread-safe; call from main thread only.

- `Collection<PlayerDataSchema<?>> getRegisteredSchemas()`
  - **Description:** Returns all registered schemas.
  - **Usage:** For introspection or plugin interoperability.
  - **Thread-Safety:** Read-only after startup.

#### Example
```java
playerStorageManager.registerSchema(new PlayerStatsSchema());
```

### Schema Engines

- **TableSchema (SQL):**
  - Uses JDBC for persistent, normalized storage.
  - Supports schema evolution and migration.
  - Best for structured, queryable data (e.g., stats, achievements).

- **JsonSchema (JSON):**
  - Stores data as JSON blobs (e.g., in SQLite or flat files).
  - Flexible for unstructured or plugin-specific data.
  - Best for rapidly evolving or nested data models.

## SQL TableSchema Annotations and Usage

### `@Table`
Annotate your data class with `@Table("table_name")` to specify the SQL table name for the schema.

### `@Column`
Annotate fields with `@Column` to mark them as persistent columns. Use `@Column(primary = true)` to designate the primary key. Optionally, use `@Column("column_name")` to override the default column name (defaults to the field name).

- If no field is marked `@Column(primary = true)`, the first field named `id` or `uuid` of type `UUID` is used as the primary key by convention.
- All private, non-static, non-transient fields are included by default (convention-over-configuration). `@Column` is only required for overrides.

### `@SchemaVersion`
Annotate your class with `@SchemaVersion(n)` to specify the schema version. If omitted, version 1 is assumed. Versioning is tracked in the `schema_versions` table for migration support.

### Example
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
```

### SQL Backend Notes
- The default implementation is optimized for SQLite (UUID fields are stored as TEXT, booleans as BOOLEAN, etc).
- For other databases, you may need to override type mapping and upsert/replace SQL.
- Connections must be managed externally and passed to `AutoTableSchema` for testability and in-memory DB support.
- Only close PreparedStatement and ResultSet in your code; do not close the Connection if you want to reuse it.

### Test Example
> For unit testing and advanced use only. Not required for plugin integration. All connection management is handled internally by the library.

```java
Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
var schema = new AutoTableSchema<>(PlayerStats.class, conn);
schema.createTable(conn);
PlayerStats stats = new PlayerStats();
stats.id = UUID.randomUUID();
stats.kills = 10;
schema.save(stats.id, stats);
PlayerStats loaded = schema.load(stats.id);
```

## Examples

### Simple Plugin Startup
```java
public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // Directly use the library's API; no Bukkit service lookup required
        PlayerStorageManager.registerSchema(new PlayerStatsSchema());
    }
}
```

### Sync vs. Async Data Load/Save
```java
// Synchronous (main thread only)
PlayerProfile profile = playerProfileManager.getProfile(playerUUID);
profile.save();

// Asynchronous (any thread)
playerProfileManager.loadProfileAsync(playerUUID)
    .thenAccept(profile -> profile.saveAsync());
```

### Plugin Interoperability
```java
PlayerProfile profile = playerProfileManager.getProfile(playerUUID);
RankingData ranking = profile.get(RankingData.class);
QuestProgress quest = profile.get(QuestProgress.class);
```

### Nested Data Example: FairySouls
```java
// Schema for storing a set of found fairy souls (UUIDs)
@Table("fairy_souls")
public class FairySouls {
    @Column(primary = true)
    public UUID playerId;
    public Set<String> foundSouls; // e.g. soul IDs as strings
}

// Register schema
PlayerStorageManager.registerSchema(new FairySoulsSchema());

// Usage
FairySouls souls = playerProfile.get(FairySouls.class);
souls.foundSouls.add("hub_1");
playerProfile.set(FairySouls.class, souls);
playerProfile.saveAsync();
```

### Structured Data Example: PlayerStats
```java
// Schema for structured player stats
@Table("player_stats")
public class PlayerStats {
    @Column(primary = true)
    public UUID id;
    public String rank;
    public long firstLogin;
    public long lastLogout;
    public int kills;
    public int deaths;
}

// Schema class for player stats (required for registration)
public class PlayerStatsSchema extends TableSchema<PlayerStats> {
    public PlayerStatsSchema() {
        super(PlayerStats.class, null); // Pass a JDBC connection if needed for direct SQL
    }
}

// Register schema
PlayerStorageManager.registerSchema(new PlayerStatsSchema());

// Usage
PlayerStats stats = playerProfile.get(PlayerStats.class);
stats.kills++;
stats.lastLogout = System.currentTimeMillis();
playerProfile.set(PlayerStats.class, stats);
playerProfile.saveAsync();
```

### Deeply Nested/JSON Example: PlayerSettings
```java
// Usage (recommended): dot-path access
PlayerSettings settings = playerProfile.get(PlayerSettings.class);
settings.getSettingsWrapper().set("hud.scale", 1.5);
settings.getSettingsWrapper().set("hud.visible", true);
settings.getSettingsWrapper().set("chat.color", "red");
settings.getSettingsWrapper().set("fairysouls.unlocked.1", true);
settings.getSettingsWrapper().set("fairysouls.unlocked.2", false);
double scale = settings.getSettingsWrapper().get("hud.scale", Double.class);
boolean hudVisible = settings.getSettingsWrapper().get("hud.visible", Boolean.class);
String chatColor = settings.getSettingsWrapper().get("chat.color", String.class);
boolean fairySoul1 = settings.getSettingsWrapper().get("fairysouls.unlocked.1", Boolean.class);

// Usage (power user): direct map access if you're cool like that
settings.getSettingsMap().put("hud", Map.of("scale", 1.5, "visible", true));
settings.getSettingsMap().put("chat", Map.of("color", "red"));
settings.getSettingsMap().put("fairysouls", Map.of("unlocked", Map.of("1", true, "2", false)));

playerProfile.set(PlayerSettings.class, settings);
playerProfile.saveAsync();
```

## Tutorial: Implementing a Data Schema with Fulcrum

This tutorial walks you through the recommended, minimal-boilerplate way to use the Fulcrum Data API for plugin development.

### 1. Define Your Data POJO

Use annotations to describe your data structure. Example:

```java
@Table("player_stats")
public class PlayerStats {
    @Column(primary = true)
    public UUID id;
    public String rank;
    public long firstLogin;
    public long lastLogout;
    public int kills;
    public int deaths;
}
```

### 2. Register Your Schema (No Custom Class Needed)

Use `AutoTableSchema` to avoid boilerplate:

```java
// In your plugin's onEnable or startup logic
PlayerStorageManager.registerSchema(new AutoTableSchema<>(PlayerStats.class));
```

### 3. Access and Modify Data

```java
PlayerProfile profile = playerProfileManager.getProfile(playerUUID);
PlayerStats stats = profile.get(PlayerStats.class);
stats.kills++;
stats.lastLogout = System.currentTimeMillis();
profile.set(PlayerStats.class, stats);
profile.saveAsync();
```

### 4. Nested/JSON Data Example

For flexible, deeply nested settings, use the built-in PlayerSettings:

```java
PlayerSettings settings = profile.get(PlayerSettings.class);
settings.getSettingsWrapper().set("hud.scale", 1.5);
settings.getSettingsWrapper().set("hud.visible", true);
settings.getSettingsWrapper().set("chat.color", "red");
profile.set(PlayerSettings.class, settings);
profile.saveAsync();
```

### 5. Advanced: Custom Schema Logic

If you need custom load/save logic, extend `TableSchema<T>` or `JsonSchema<T>`:

```java
public class CustomStatsSchema extends TableSchema<PlayerStats> {
    public CustomStatsSchema() {
        super(PlayerStats.class, null); // Only needed for manual/test scenarios
    }
    // Override load/save if needed
}
```

## Advanced: Internal Design and Behavior

- **Caching:**
  - Profiles are cached in-memory while players are online.
  - Data is evicted on player disconnect or after a configurable timeout.
  - Write-through cache ensures consistency between memory and storage.

- **Thread-Safety:**
  - All async methods are safe for concurrent use.
  - Sync methods must be called from the main thread.
  - Internal locks prevent race conditions during load/save.

- **Null Handling:**
  - `get(Class<T>)` returns null if schema is missing or not loaded.
  - Plugins should check for null before accessing data.

- **Schema Engines:**
  - `TableSchema` uses SQL DDL/DML for structure and migration.
  - `JsonSchema` serializes/deserializes POJOs to JSON.
  - Both engines support versioned schema evolution.

- **Lifecycle:**
  - Schemas must be registered before any profile loads.
  - Profile data is loaded on demand and saved on explicit request or plugin shutdown.

---
## TODO:
- implement proper dirty data management with redis
- implement transfer packets via redis
- implement actual database implementations



For further details, see Javadoc on each public interface and class.
