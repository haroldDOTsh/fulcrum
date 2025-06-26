# Fulcrum - Modular Minecraft Spigot Plugin System

Fulcrum is a multi-module Gradle project providing a modular API system for Minecraft Spigot plugins. It features a styled messaging system with automatic translation management, statistics tracking, and data persistence.

## Quick Start - Messaging API

```java
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.GenericResponse;

// Direct messages (immediate send)
Message.success(playerId, "banking.deposit.success", 1000, "coins");
Message.info(playerId, "player.balance.current", 15750);
Message.error(playerId, GenericResponse.NO_PERMISSION);

// Tagged messages (require .send())
Message.success(playerId, "admin.command.reload")
    .staff()
    .system()
    .send();

// Broadcasting
Message.broadcastInfo("server.restart.warning", 5)
    .system()
    .send();
```

## Project Structure

```
fulcrum/
├── internal-core/            # Main Spigot plugin
├── message-api/              # Styled messaging with tags
├── stats-api/                # Statistics tracking
├── data-api/                 # Data persistence
└── MESSAGING_API.md          # Detailed messaging documentation
```

### stats-api
Provides a flexible statistics system similar to Hypixel SkyBlock:
- `StatType` enum defines available stat types (damage, health, defense, etc.)
- `StatProvider` interface allows different sources to contribute stat values
- `StatService` interface manages providers and calculates total stats

### message-api
A templated messaging system for consistent player communication:
- `MessageKey` enum defines common message keys
- `MessageService` interface handles formatting, localization, and parameter replacement
- Supports multiple languages and custom message templates

### data-api
A lightweight ORM-like system for data persistence:
- `DataModel` interface defines the base structure for data objects
- `DataService` interface provides async/sync save/load operations
- Supports JSON/YAML serialization with caching

## Building the Project

```bash
# Build all modules
./gradlew build

# Build the main plugin (creates a shaded JAR)
./gradlew :internal-core:shadowJar

# Clean and build
./gradlew clean build
```

The main plugin JAR will be created at `internal-core/build/libs/internal-core-1.0.0.jar`.

## Usage Examples

### Using the Stats API

```java
// Get the stat service
StatService statService = FulcrumPlugin.getStatService();

// Register a stat provider
statService.registerProvider(new MyStatProvider());

// Calculate a player's total damage stat
UUID playerId = player.getUniqueId();
double totalDamage = statService.calculateStat(playerId, StatType.DAMAGE);

// Get detailed breakdown
Map<String, Double> breakdown = statService.getStatBreakdown(playerId, StatType.DAMAGE);
```

### Using the Message API

```java
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.GenericResponse;

// Direct messages (immediate send)
Message.success(playerId, "banking.deposit.success", 1000, "coins");
Message.info(playerId, "player.balance.current", 15750);
Message.error(playerId, GenericResponse.NO_PERMISSION);

// Tagged messages (require .send())
Message.success(playerId, "admin.command.reload")
    .staff()
    .system()
    .send();

Message.info(playerId, "server.maintenance.scheduled", "2 hours")
    .system()
    .send();

// Broadcasting
Message.broadcastInfo("server.restart.warning", 5)
    .system()
    .send();
```

### Using the Data API

```java
// Get the data service
DataService dataService = FulcrumPlugin.getDataService();

// Load player data asynchronously
dataService.load(playerId, PlayerData.class)
    .thenAccept(optionalData -> {
        PlayerData data = optionalData.orElseGet(() -> {
            PlayerData newData = dataService.create(playerId, PlayerData.class);
            newData.setDisplayName(player.getName());
            return newData;
        });
        
        // Modify data
        data.setLastSeen(System.currentTimeMillis());
        
        // Save data
        dataService.save(data);
    });

// Synchronous operations (use sparingly)
Optional<PlayerData> data = dataService.loadSync(playerId, PlayerData.class);
```

## Static Accessors

Other plugins can access Fulcrum services through static methods:

```java
// Check if Fulcrum is loaded
if (FulcrumPlugin.isLoaded()) {
    StatService stats = FulcrumPlugin.getStatService();
    MessageService messages = FulcrumPlugin.getMessageService();
    DataService data = FulcrumPlugin.getDataService();
}
```

## Adding Dependencies

To use Fulcrum APIs in your own plugin, add the API modules as dependencies:

```kotlin
dependencies {
    compileOnly("sh.harold.fulcrum:stats-api:1.0.0")
    compileOnly("sh.harold.fulcrum:message-api:1.0.0")
    compileOnly("sh.harold.fulcrum:data-api:1.0.0")
}
```

Then ensure your plugin depends on Fulcrum in your `plugin.yml`:

```yaml
depend: [Fulcrum]
```

## Requirements

- Java 21
- Spigot 1.21.6+
- Gradle 8.0+

## Message Styles & Tags

The messaging system provides consistent styling:

| Style | Colors | Usage |
|-------|--------|-------|
| SUCCESS | Green base, Yellow args | Successful operations |
| INFO | Gray base, Aqua args | Information messages |
| DEBUG | Dark gray all | Debug output |
| ERROR | Red all | Error messages |

Available tags:
- `.staff()` - `&c[STAFF]&r` - Staff actions
- `.daemon()` - `&5[DAEMON]&r` - System processes  
- `.debug()` - `&8[DEBUG]&r` - Debug information
- `.system()` - `&b[SYSTEM]&r` - System messages

Translation keys follow `feature.detail.detail` format and create files at `/plugins/internal-core/lang/{feature}/en_us.yml`.

## Documentation

- `MESSAGING_API.md` - Complete messaging API documentation
- `copilot-instructions.md` - AI assistant integration guide

## License

This project is provided as an example implementation. Modify as needed for your specific use case.
