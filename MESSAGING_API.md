# Fulcrum/Eternum Message API: Developer Guide

---

> **Changelog (2025-06-30):**
> - Added overload methods to the Message API that accept `net.kyori.adventure.audience.Audience` directly for all send/broadcast operations. See examples below.

The Message API provides a robust, platform-agnostic, and localizable messaging system for Minecraft plugins, built on the [Adventure](https://docs.adventure.kyori.net/) library. It supports styled messages, localization, macro messages, and a fluent API for message construction.

---

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

Add `FulcrumPlayerData` to your `plugin.yml`'s `depend` list:

```yaml
depend: [ FulcrumPlayerData ]
```

---

## 2. Initialization

**You do NOT need to register or initialize the MessageService yourself.**
The runtime plugin (`player-core`) handles all setup automatically. You can use the `Message` facade anywhere in your plugin code without extra configuration.

---

## 3. Sending Messages

> **New in 2025:** All `send` and `broadcast` methods are now overloaded to accept an `Audience` directly, in addition to UUIDs. This allows you to send messages to any Adventure-compatible audience (e.g., players, console, groups) without needing to resolve UUIDs.

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
Message.success("event.start").arg("Summer Festival").broadcast();
// Broadcast to a custom audience (e.g., staff)
Message.info("server.maintenance").arg("1 hour").system().broadcast(audience);
```

---

## 4. Chaining Tags

Add context tags for prefixes:

```java
Message.info("server.restart_warning").arg(5).system().staff().send(playerId);
Message.success("database.purge.complete").arg("2,450").daemon().send(playerId);
```

**Available tags:** `.system()`, `.staff()`, `.daemon()`, `.debug()`

---

## 5. Retrieving Components

For GUIs, scoreboards, etc.:

```java
import net.kyori.adventure.text.Component;
Component motd = Message.raw("server.motd").arg(player.getName()).get(playerId);
player.sendPlayerListHeader(motd);
```

---

## 6. Localization

- Message keys map to YAML files:  
  `banking.deposit.success` → `lang/en_US/banking.yml`
- Arguments use `{0}`, `{1}` in YAML, or `%s`/`%d` if using Java formatting.

**Example `lang/en_US/banking.yml`:**
```yaml
deposit:
  success: "You successfully deposited <yellow>{0}</yellow> into your account."
  insufficient_funds: "<red>You cannot withdraw {0}, you only have {1}.</red>"
```

If a key is missing, it is auto-added with a placeholder.

---

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

---

## 8. Building

```powershell
./gradlew :message-api:build
```

---

## 9. API Reference & Usage Guide

### 9.1. Message Entry Points

- `Message.success(String key)`
- `Message.info(String key)`
- `Message.error(String key)`
- `Message.debug(String key)`
- `Message.raw(String key)`

### 9.2. Generic Responses

- `GenericResponse.ERROR` → "generic.error"
- `GenericResponse.ERROR_GENERAL` → "generic.error.general"
- `GenericResponse.ERROR_NO_PERMISSION` → "generic.error.nopermission"
- `GenericResponse.ERROR_COOLDOWN` → "generic.error.cooldown"

Use with:
```java
Message.error(playerId, GenericResponse.ERROR_NO_PERMISSION);
Message.error(audience, GenericResponse.ERROR_COOLDOWN);
```

### 9.3. Sending & Broadcasting

All message types support:
- `.send(UUID playerId)`
- `.send(Audience audience)`
- `.broadcast()` (to all players)
- `.broadcast(Audience audience)` (to a custom audience)

### 9.4. Arguments

- `.arg(Object value)` — Add a single argument (repeatable)
- `.args(Object... values)` — Add multiple arguments

### 9.5. Tags (Context Prefixes)

Chainable for context and formatting:
- `.system()` — System message prefix
- `.staff()` — Staff-only prefix
- `.daemon()` — Daemon/background prefix
- `.debug()` — Debug prefix

Tags can be chained in any order, e.g.:
```java
Message.info("server.restart").system().staff().send(audience);
```

### 9.6. Retrieving Components

- `.get(UUID playerId)` — Returns `Component` for a player
- `.get(Audience audience)` — Returns `Component` for an audience

### 9.7. MessageStyle Enum

- `MessageStyle.SUCCESS`
- `MessageStyle.INFO`
- `MessageStyle.ERROR`
- `MessageStyle.DEBUG`

### 9.8. Localization

- Message keys map to YAML files: `lang/<locale>/<namespace>.yml`
- Arguments: `{0}`, `{1}` in YAML, or `%s`/`%d` for Java formatting
- Missing keys are auto-added with a placeholder

### 9.9. Audience Support

All `send`, `broadcast`, and `get` methods accept both `UUID` and `Audience`.

---

## 10. Notes

- The API is pure Java, no direct Bukkit/Paper dependencies.
- All runtime plugin code should use the `player-core` module; shared code lives in `-api` modules.

---

**This consolidated documentation supersedes all previous Message API docs.**