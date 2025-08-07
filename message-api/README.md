# Message-API (Hypixel Styled Chat Feedback & Dynamic Scoreboards)

## 1. Setup

### 1.1. Add the API to Your Build

Add `message-api` as a dependency in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":message-api"))
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
}
```

If using as a plugin, also add:

```kotlin
compileOnly("sh.harold.fulcrum:message-api:1.0-SNAPSHOT") // Replace with latest version
```

### 1.2. Plugin Dependency

Add `Fulcrum` to your `plugin.yml`'s `depend` list:

```yaml
depend: [ Fulcrum ]
```

---

## 2. Initialization

**You do NOT need to register or initialize the MessageService yourself.**
The runtime plugin (`player-core`) handles all setup automatically. You can use the `Message` facade anywhere in your
plugin code without extra configuration.

---

## 3. Sending Messages

### 3.1. Basic Styled Messages

```java
import sh.harold.fulcrum.api.message.Message;
import net.kyori.adventure.audience.Audience;
import java.util.UUID;

UUID playerId = player.getUniqueId();
Audience audience = player;

// Success (by UUID)
Message.success("banking.deposit.success").send(playerId);
// Success (by Audience)
Message.success("banking.deposit.success", 10000).send(audience);

// Info (by Audience)
Message.info("skills.levelup", player.level).send(audience);

// Error (by Audience)
Message.error("generic.no_permission").send(audience);

// Debug (by Audience)
Message.debug("debug.player.data_saved", playerName).send(audience);

// Raw (MiniMessage colors, by Audience)
Message.raw("custom.dragon_event").send(audience);
```

### 3.2. Broadcasting

```java
// Broadcast to all players
Message.success("event.start", "Summer Festival").broadcast();
// Broadcast to a custom audience (e.g., staff)
Message.info("server.maintenance", "1 hour").broadcast();
```

---

## 4. Scoreboard System (NEW)

### 4.1. Creating Scoreboards

```java
import sh.harold.fulcrum.api.message.scoreboard.*;
import sh.harold.fulcrum.api.message.scoreboard.module.*;

// Define a scoreboard with modules
ScoreboardDefinition scoreboard = new ScoreboardBuilder("lobby")
    .title("&6&lLobby Server")
    .module(createHeaderModule())
    .module(createStatsModule())
    .module(createFooterModule())
    .build();

// Register with the service
ScoreboardService service = ServiceLocator.get(ScoreboardService.class);
service.registerScoreboard(scoreboard);
service.setPlayerScoreboard(playerId, "lobby");
```

### 4.2. Content Providers

```java
// Static content - fixed text
ContentProvider staticProvider = new StaticContentProvider(Arrays.asList(
    "&7Server: &aLobby",
    "&7Version: &a1.20.1"
));

// Dynamic content - updates over time
ContentProvider dynamicProvider = new DynamicContentProvider(
    () -> Arrays.asList(
        "&7Players: &a" + getOnlineCount(),
        "&7Time: &a" + getCurrentTime()
    ),
    5000 // refresh every 5 seconds
);

// Player-specific dynamic content
ContentProvider playerProvider = new DynamicContentProvider(
    playerId -> Arrays.asList(
        "&7Rank: &6" + getRank(playerId),
        "&7Coins: &e" + getCoins(playerId)
    )
);
```

### 4.3. Module Creation

```java
public ScoreboardModule createStatsModule() {
    return new ScoreboardModule(
        "stats",
        new DynamicContentProvider(playerId -> {
            PlayerStats stats = getStats(playerId);
            return Arrays.asList(
                "&7Kills: &a" + stats.getKills(),
                "&7Deaths: &c" + stats.getDeaths(),
                "&7KDR: &e" + stats.getKDR()
            );
        }, 10000) // Update every 10 seconds
    );
}
```

### 4.4. Player Customization

```java
// Custom title for specific player
service.setPlayerTitle(playerId, "&c&lVIP LOBBY");

// Toggle module for player
PlayerScoreboardState state = service.getPlayerState(playerId);
state.setModuleOverride("stats", ModuleOverride.disabled("stats"));

// Force refresh
service.updatePlayerScoreboard(playerId);
```

---

## 5. Localization

- Message keys map to YAML files:  
  `banking.deposit.success` -> `lang/en_US/banking.yml`
- Arguments use `{0}`, `{1}`.
- If a key is missing, it is auto-added with a placeholder. (Including args)

**Example `lang/en_US/banking.yml`:**

```yaml
deposit:
  success: "You successfully deposited {0} into your account." # Defined as deposit.success
  insufficient_funds: "You cannot withdraw {0}, you only have {1}." # Defined as deposit.insufficient_funds
```

---

## 6. API Changes & Migration (v2.0)



#### New Way
```java
// Direct service usage
ScoreboardDefinition definition = new ScoreboardBuilder("lobby")
    .title("Lobby")
    .build();
service.registerScoreboard(definition);

// Flash states removed - use dynamic content providers instead

// Simplified module override
ModuleOverride override = ModuleOverride.disabled("stats");
```

---

## 7. Complete Examples

### 7.1. Message Integration Example

```java
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.registry.PlayerProfileManager;
import sh.harold.fulcrum.registry.PlayerProfile;

public void displayPlayerBalance(UUID playerId) {
    PlayerProfileManager.getProfile(playerId).thenAccept(profileOpt -> {
        if (profileOpt.isPresent()) {
            var profile = profileOpt.get();
            Message.info("player.balance.current", profile.getBalance()).send(playerId);
        } else {
            Message.error("player.not_found").send(playerId);
        }
    });
}
```

### 7.2. Complete Scoreboard Example

```java
public class LobbyScoreboard {
    private final ScoreboardService service;
    
    public LobbyScoreboard(ScoreboardService service) {
        this.service = service;
    }
    
    public void setupScoreboard() {
        // Build scoreboard definition
        ScoreboardDefinition lobby = new ScoreboardBuilder("lobby")
            .title("&6&lLOBBY")
            .module(createHeaderModule())
            .module(createPlayerStatsModule())
            .module(createServerInfoModule())
            .module(createFooterModule())
            .build();
        
        // Register
        service.registerScoreboard(lobby);
    }
    
    private ScoreboardModule createHeaderModule() {
        return new ScoreboardModule(
            "header",
            new StaticContentProvider(Arrays.asList(
                "&7&m                    ",
                ""
            ))
        );
    }
    
    private ScoreboardModule createPlayerStatsModule() {
        return new ScoreboardModule(
            "stats",
            new DynamicContentProvider(playerId -> {
                PlayerStats stats = getStats(playerId);
                return Arrays.asList(
                    "&e&lYour Stats:",
                    "&7Kills: &a" + stats.getKills(),
                    "&7Deaths: &c" + stats.getDeaths(),
                    "&7KDR: &e" + String.format("%.2f", stats.getKDR()),
                    ""
                );
            }, 10000) // Update every 10 seconds
        );
    }
    
    private ScoreboardModule createServerInfoModule() {
        return new ScoreboardModule(
            "server",
            new DynamicContentProvider(() -> Arrays.asList(
                "&b&lServer Info:",
                "&7Online: &a" + Bukkit.getOnlinePlayers().size() + "/100",
                "&7TPS: &a" + getTPS(),
                ""
            ), 5000) // Update every 5 seconds
        );
    }
    
    private ScoreboardModule createFooterModule() {
        return new ScoreboardModule(
            "footer",
            new StaticContentProvider(Arrays.asList(
                "&eplay.harold.sh",
                "&7&m                    "
            ))
        );
    }
}
```

---

## 8. API Reference

### 8.1. Message API

#### Entry Points
- `Message.success(String key, Object... args)` - Green text, Yellow highlighting
- `Message.info(String key, Object... args)` - Gray text, Aqua highlighting
- `Message.error(String key, Object... args)` - Red text
- `Message.debug(String key, Object... args)` - Dark Gray text
- `Message.raw(String key, Object... args)` - Custom colors from lang file

#### Generic Responses
- `GenericResponse.ERROR` → "generic.error"
- `GenericResponse.ERROR_GENERAL` → "generic.error.general"
- `GenericResponse.ERROR_NO_PERMISSION` → "generic.error.nopermission"
- `GenericResponse.ERROR_COOLDOWN` → "generic.error.cooldown"

### 8.2. Scoreboard API

#### ScoreboardService
- `registerScoreboard(ScoreboardDefinition)` - Register a new scoreboard
- `unregisterScoreboard(String id)` - Remove a scoreboard
- `setPlayerScoreboard(UUID, String)` - Assign scoreboard to player
- `removePlayerScoreboard(UUID)` - Remove player's scoreboard
- `updatePlayerScoreboard(UUID)` - Force refresh
- `setPlayerTitle(UUID, String)` - Custom title for player
- `getPlayerTitle(UUID)` - Get custom title

#### ContentProvider Types
- `StaticContentProvider` - Fixed content
- `DynamicContentProvider` - Time-based updates
- Player-specific support via `Function<UUID, List<String>>`

#### PlayerScoreboardState
- `getCurrentScoreboardId()` - Active scoreboard
- `setModuleOverride(String, ModuleOverride)` - Toggle modules
- `markForRefresh()` - Request update

---

## 9. Performance & Best Practices

### Best Practices
1. **Use appropriate refresh intervals** - Don't update too frequently (5-10 seconds minimum)
2. **Leverage content providers** - Use static for fixed content, dynamic for changing data
3. **Module reusability** - Create shared modules across scoreboards
4. **Thread safety** - All APIs are thread-safe, can be called from any thread
5. **Locale handling** - Always use translation keys rather than hardcoded strings

---

## That's it!
