# Fulcrum Messaging API

Consistent styled messaging system with automatic localization, file management, and chainable tags.

## Features

- Predefined message styles (SUCCESS, INFO, DEBUG, ERROR, RAW)
- Automatic translation file creation and management
- Chainable message tags for prefixes
- Legacy color code to Adventure Component conversion
- Namespaced translation keys with organized file structure

## Basic Usage

```java
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.GenericResponse;

UUID playerId = player.getUniqueId();

// Direct send (immediate)
Message.success(playerId, "banking.deposit.success", 1000, "coins");
Message.info(playerId, "player.balance.current", 15750);
Message.debug(playerId, "debug.profile.current", "hardcore");
Message.error(playerId, "economy.insufficient_funds", 1000, 750);
Message.error(playerId, GenericResponse.NO_PERMISSION);
Message.raw(playerId, "custom.rainbow.message", "awesome");

// Chain with tags (requires .send())
Message.success(playerId, "admin.command.executed", "reload")
    .staff()
    .system()
    .send();

Message.info(playerId, "server.status.update", "online")
    .daemon()
    .send();

Message.debug(playerId, "performance.metrics", 85.2)
    .debug()
    .send();

// Broadcasting
Message.broadcastSuccess("server.event.started", "PvP Tournament");
Message.broadcastInfo("server.restart.warning", 5)
    .system()
    .send();
```

## Message Styles

| Style | Base Color | Arg Color | Usage |
|-------|------------|-----------|-------|
| SUCCESS | `&a` Green | `&e` Yellow | Successful operations |
| INFO | `&7` Gray | `&b` Aqua | Informational messages |
| DEBUG | `&8` Dark Gray | `&8` Dark Gray | Debug information |
| ERROR | `&c` Red | `&c` Red | Error messages |
| RAW | Custom | Custom | Uses translation file colors |

## Available Tags

| Tag | Prefix | Usage |
|-----|--------|-------|
| `.staff()` | `&c[STAFF]&r` | Staff-only actions |
| `.daemon()` | `&5[DAEMON]&r` | System/daemon processes |
| `.debug()` | `&8[DEBUG]&r` | Debug information |
| `.system()` | `&b[SYSTEM]&r` | System messages |

## Translation Keys

Format: `feature.detail.detail`

- `banking.deposit.success` → `/plugins/internal-core/lang/banking/en_us.yml`
- `player.balance.current` → `/plugins/internal-core/lang/player/en_us.yml`
- `admin.command.executed` → `/plugins/internal-core/lang/admin/en_us.yml`

## File Structure

```
/plugins/internal-core/lang/
├── banking/en_us.yml
├── player/en_us.yml
└── admin/en_us.yml
```

Example `banking/en_us.yml`:
```yaml
deposit:
  success: "Successfully deposited {0} {1}!"
withdraw:
  success: "Successfully withdrew {0} {1}!"
```

## Setup

```java
public class YourPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        MessageService messageService = new FulcrumMessageService(this);
        Message.setMessageService(messageService);
    }
}
```

## Generic Responses

```java
GenericResponse.GENERIC_ERROR      // "An error occurred!"
GenericResponse.NO_PERMISSION      // "You don't have permission!"
GenericResponse.PLAYER_NOT_FOUND   // "Player not found!"
GenericResponse.INVALID_ARGUMENT   // "Invalid argument!"
GenericResponse.INSUFFICIENT_FUNDS // "Not enough money!"
```

## Important Notes

- Direct calls (`Message.success()`) send immediately
- Chained calls require `.send()` to actually send the message
- Translation files are created automatically with placeholders
- Arguments use `{0}`, `{1}`, `{2}` format in translation files
- Legacy color codes are converted to Adventure Components automatically
