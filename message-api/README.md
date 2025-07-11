# Message-API (Hypixel Styled Chat Feedback)

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
The runtime plugin (`player-core`) handles all setup automatically. You can use the `Message` facade anywhere in your plugin code without extra configuration.

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
Message.success("banking.deposit.success").arg("$1,000.00").send(playerId);
// Success (by Audience)
Message.success("banking.deposit.success").arg("$1,000.00").send(audience);

// Info (by Audience)
Message.info("skills.levelup").arg(50).send(audience);

// Error (by Audience)
Message.error("generic.no_permission").send(audience);

// Debug (by Audience)
Message.debug("debug.player.data_saved").arg("Notch").send(audience);

// Raw (MiniMessage colors, by Audience)
Message.raw("custom.dragon_event").send(audience);
```

### 3.2. Broadcasting

```java
// Broadcast to all players
Message.success("event.start", "Summer Festival").broadcast();
// Broadcast to a custom audience (e.g., staff)
Message.info("server.maintenance", "1 hour").system().broadcast(audience);
```


## 4. Chaining Tags

Add context tags for prefixes:

```java
Message.info("server.restart_warning", 5).system().staff().send(playerId);
Message.success("database.purge.complete", "2,450").daemon().send(playerId);
```

**Available tags:** `.system()`, `.staff()`, `.daemon()`, `.debug()`

---

## 5. Retrieving Components

For GUIs, scoreboards, etc.:

```java
import net.kyori.adventure.text.Component;
Component motd = Message.raw("server.motd", player.getName()).get(playerId);
player.sendPlayerListHeader(motd);
```

## 6. Localization

- Message keys map to YAML files:  
  `banking.deposit.success` -> `lang/en_US/banking.yml`
- Arguments use `{0}`, `{1}`.
- 
- If a key is missing, it is auto-added with a placeholder. (Including args)

**Example `lang/en_US/banking.yml`:**
```yaml
deposit:
  success: "You successfully deposited {0} into your account." # Defined as deposit.success
  insufficient_funds: "You cannot withdraw {0}, you only have {1}." # Defined as deposit.insufficient_funds
```

## 7. Integration Example

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

## 8. API Reference & Usage Guide

### Message Entry Points

- `Message.success(String key)` (Green text, Yellow arg highlighting)
- `Message.info(String key)` (Gray text, Rqua highlighting)
- `Message.error(String key)` (Red text, Red highlighting)
- `Message.debug(String key)` (Dark Gray text, Dark Gray highlighting)
- `Message.raw(String key)` (Colour defined in lang file)

### Generic Responses

- `GenericResponse.ERROR` → "generic.error"
- `GenericResponse.ERROR_GENERAL` → "generic.error.general"
- `GenericResponse.ERROR_NO_PERMISSION` → "generic.error.nopermission"
- `GenericResponse.ERROR_COOLDOWN` → "generic.error.cooldown"

Use with:
```java
Message.error(playerId, GenericResponse.ERROR_NO_PERMISSION);
Message.error(audience, GenericResponse.ERROR_COOLDOWN);
```

### Sending & Broadcasting

All message types support:
- `.send(UUID playerId)`
- `.send(Audience audience)`
- `.broadcast()` (to all players)
- `.broadcast(Audience audience)` (to a custom audience)

### Arguments

- `.arg(Object value)` — Add a single argument (repeatable)
- `.args(Object... values)` — Add multiple arguments

### Tags (Context Prefixes)

Chainable for context and formatting:
- `.system()` — System message prefix
- `.staff()` — Staff-only prefix
- `.daemon()` — Daemon/background prefix
- `.debug()` — Debug prefix

Tags can be chained in any order, e.g.:
```java
Message.info("server.restart").system().staff().send(audience);
```

### Retrieving Components

- `.get(UUID playerId)` — Returns `Component` for a player
- `.get(Audience audience)` — Returns `Component` for an audience

### MessageStyle Enum

- `MessageStyle.SUCCESS`
- `MessageStyle.INFO`
- `MessageStyle.ERROR`
- `MessageStyle.DEBUG`

### Localization

- Message keys map to YAML files: `lang/<locale>/<namespace>.yml`
- Arguments: `{0}`, `{1}` in YAML, or `%s`/`%d` for Java formatting
- Missing keys are auto-added with a placeholder

### Audience Support

All `send`, `broadcast`, and `get` methods accept both `UUID` and `Audience`.

## That's it!