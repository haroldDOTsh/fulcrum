# Message API

Messaging system with localization support and scoreboard management.

## Setup

Add to `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":message-api"))
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
}
```

Add to `plugin.yml`:
```yaml
depend: [Fulcrum]
```

## Messages

### Basic Usage

```java
import sh.harold.fulcrum.api.message.Message;

// Send messages
Message.success("banking.deposit.success", amount).send(playerId);
Message.info("skills.levelup", player.level).send(player);
Message.error("generic.no_permission").send(player);
Message.debug("debug.player.data_saved", playerName).send(player);

// Broadcast
Message.success("event.start", "Summer Festival").broadcast();
```

### Message Styles
- `success()` - Green text with yellow highlights
- `info()` - Gray text with aqua highlights
- `error()` - Red text
- `debug()` - Dark gray text
- `raw()` - Custom MiniMessage colors from lang file

### Localization

Messages map to YAML files: `banking.deposit.success` → `lang/en_US/banking.yml`

```yaml
# lang/en_US/banking.yml
deposit:
  success: "You deposited {0} into your account."
  insufficient_funds: "Cannot withdraw {0}, balance: {1}."
```

Arguments use `{0}`, `{1}`, etc. Missing keys are auto-added with placeholders.

## Scoreboards

### Create Scoreboard

```java
import sh.harold.fulcrum.api.message.scoreboard.*;

ScoreboardDefinition scoreboard = new ScoreboardBuilder("lobby")
    .title("&6&lLobby")
    .module(createHeaderModule())
    .module(createStatsModule())
    .module(createFooterModule())
    .build();

ScoreboardService service = ServiceLocator.get(ScoreboardService.class);
service.registerScoreboard(scoreboard);
service.setPlayerScoreboard(playerId, "lobby");
```

### Content Providers

```java
// Static content
ContentProvider staticProvider = new StaticContentProvider(Arrays.asList(
    "&7Server: &aLobby",
    "&7Version: &a1.20.1"
));

// Dynamic content (time-based refresh)
ContentProvider dynamicProvider = new DynamicContentProvider(
    () -> Arrays.asList(
        "&7Players: &a" + getOnlineCount(),
        "&7Time: &a" + getCurrentTime()
    ),
    5000 // refresh every 5 seconds
);

// Player-specific content
ContentProvider playerProvider = new DynamicContentProvider(
    playerId -> Arrays.asList(
        "&7Rank: &6" + getRank(playerId),
        "&7Coins: &e" + getCoins(playerId)
    )
);
```

### Modules

```java
private ScoreboardModule createStatsModule() {
    return new ScoreboardModule(
        "stats",
        new DynamicContentProvider(playerId -> {
            PlayerStats stats = getStats(playerId);
            return Arrays.asList(
                "&7Kills: &a" + stats.getKills(),
                "&7Deaths: &c" + stats.getDeaths(),
                "&7KDR: &e" + String.format("%.2f", stats.getKDR())
            );
        }, 10000) // Update every 10 seconds
    );
}
```

### Player Customization

```java
// Custom title
service.setPlayerTitle(playerId, "&c&lVIP LOBBY");

// Toggle modules
PlayerScoreboardState state = service.getPlayerState(playerId);
state.setModuleOverride("stats", ModuleOverride.disabled("stats"));

// Force refresh
service.updatePlayerScoreboard(playerId);
```

## Complete Example

```java
public class LobbyScoreboard {
    private final ScoreboardService service;
    
    public void setupScoreboard() {
        ScoreboardDefinition lobby = new ScoreboardBuilder("lobby")
            .title("&6&lLOBBY")
            .module(new ScoreboardModule(
                "header",
                new StaticContentProvider(Arrays.asList(
                    "&7&m                    ",
                    ""
                ))
            ))
            .module(new ScoreboardModule(
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
                }, 10000)
            ))
            .module(new ScoreboardModule(
                "server",
                new DynamicContentProvider(() -> Arrays.asList(
                    "&b&lServer Info:",
                    "&7Online: &a" + Bukkit.getOnlinePlayers().size() + "/100",
                    "&7TPS: &a" + getTPS(),
                    ""
                ), 5000)
            ))
            .module(new ScoreboardModule(
                "footer",
                new StaticContentProvider(Arrays.asList(
                    "&eplay.harold.sh",
                    "&7&m                    "
                ))
            ))
            .build();
        
        service.registerScoreboard(lobby);
    }
}
```

## API Reference

### Message Methods
- `Message.success(key, args...)` - Success message
- `Message.info(key, args...)` - Info message
- `Message.error(key, args...)` - Error message
- `Message.debug(key, args...)` - Debug message
- `Message.raw(key, args...)` - Custom formatted

### ScoreboardService
- `registerScoreboard(definition)` - Register scoreboard
- `setPlayerScoreboard(uuid, id)` - Assign to player
- `removePlayerScoreboard(uuid)` - Remove from player
- `updatePlayerScoreboard(uuid)` - Force refresh
- `setPlayerTitle(uuid, title)` - Custom title
- `getPlayerState(uuid)` - Get player state

### ContentProvider Types
- `StaticContentProvider` - Fixed content
- `DynamicContentProvider` - Time-based updates with optional player-specific support