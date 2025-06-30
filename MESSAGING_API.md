# Fulcrum Message API: A Developer's Guide

The Fulcrum Message API provides a powerful, standardized system for sending styled, localizable messages to players.
It's designed to be simple to use while handling all the complexity of translation management, formatting, and styling
behind the scenes.

This guide is documentation-by-example. Less talk, more code.

---

## 1. Setup for Plugin Developers

To use the `message-api` in your plugin, you only need to do two things.

### 1.1. Add the API to Your Build

Add `message-api` as a dependency in your `build.gradle.kts` or `build.gradle` file.

**`build.gradle.kts` (Kotlin DSL)**

```kotlin
dependencies {
    compileOnly("sh.harold.fulcrum:message-api:1.0-SNAPSHOT") // Replace with the latest version
}
```

**`build.gradle` (Groovy DSL)**

```groovy
dependencies {
    compileOnly 'sh.harold.fulcrum:message-api:1.0-SNAPSHOT' // Replace with the latest version
}
```

### 1.2. Declare a Dependency on the Core Plugin

Add `FulcrumPlayerData` to the `depend` list in your `plugin.yml` file. This is critical, as it ensures the message
service is running before your plugin loads.

**`plugin.yml`**

```yaml
name: MyAwesomePlugin
version: 1.0
main: com.example.myplugin.MyPlugin
depend: [ FulcrumPlayerData ]
```

That's it! You do **not** need to register or initialize the `MessageService` yourself. You can now use the `Message`
facade anywhere in your plugin.

---

## 2. Sending Messages to Players

All messages are sent using the `Message` facade. You provide a localization key and any arguments to fill in. The
system automatically handles finding the right translation for the player's locale.

### 2.1. Basic Styled Messages

These methods send a message immediately.

```java
import sh.harold.fulcrum.api.message.Message;

import java.util.UUID;

UUID playerId = player.getUniqueId();

// Success Message
// -> lang/en_US/banking.yml: "You successfully deposited $1,000.00 into your account."
Message.

success("banking.deposit.success")
    .

arg("$1,000.00")
    .

send(playerId);

// Informational Message
// -> lang/en_US/skills.yml: "Your Woodcutting level is now 50."
Message.

info("skills.levelup")
    .

arg(50)
    .

send(playerId);

// Error Message
// -> lang/en_US/generic.yml: "You do not have permission to do that."
Message.

error("generic.no_permission").

send(playerId);

// Debug Message (often for developers/admins)
// -> lang/en_US/debug.yml: "Player data for Notch saved successfully."
Message.

debug("debug.player.data_saved")
    .

arg("Notch")
    .

send(playerId);

// Raw Message (uses colors from the translation file)
// -> lang/en_US/custom.yml: "<gold>A <bold><red>DRAGON</red></bold> has appeared!"
Message.

raw("custom.dragon_event").

send(playerId);
```

### 2.2. Using Pre-defined `GenericResponse` Enums

For common situations, you can use an enum instead of a key.

```java
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.message.GenericResponse;

import java.util.UUID;

UUID playerId = player.getUniqueId();

// -> lang/en_US/generic.yml: "An unknown error occurred."
Message.

error(playerId, GenericResponse.ERROR);
```

---

## 3. Chaining Tags for Prefixes

You can chain tags to a message to add prefixes. Chained messages require `.send()` to be called.

```java
import sh.harold.fulcrum.api.message.Message;

import java.util.UUID;

UUID playerId = player.getUniqueId();

// [SYSTEM] [STAFF] The server is restarting in 5 minutes.
Message.

info("server.restart_warning")
    .

arg(5)
    .

system() // Adds a [SYSTEM] prefix
    .

staff()  // Adds a [STAFF] prefix
    .

send(playerId);

// [DAEMON] Purged 2,450 old records from the database.
Message.

success("database.purge.complete")
    .

arg("2,450")
    .

daemon()
    .

send(playerId);
```

### Available Tags

| Method      | Resulting Prefix |
|-------------|------------------|
| `.system()` | `[SYSTEM]`       |
| `.staff()`  | `[STAFF]`        |
| `.daemon()` | `[DAEMON]`       |
| `.debug()`  | `[DEBUG]`        |

---

## 4. Broadcasting Messages

Broadcasting sends a message to all online players. The syntax is identical to sending a message to a single player.

```java
import sh.harold.fulcrum.api.message.Message;

// Broadcasts a success message to everyone.
// -> "The 'Summer Festival' event has started!"
Message.success("event.start").
arg("Summer Festival")
    .

broadcast();

// Broadcasts a tagged message.
// -> "[SYSTEM] The server will be going down for maintenance in 1 hour."
Message.

info("server.maintenance")
    .

arg("1 hour")
    .

system()
    .

broadcast();
```

---

## 5. Getting a Message as a Component

If you need to use a message in a GUI, scoreboard, or other custom component, you can get the formatted `Component`
object instead of sending it.

```java
import sh.harold.fulcrum.api.message.Message;
import net.kyori.adventure.text.Component;

import java.util.UUID;

UUID playerId = player.getUniqueId();

// Get a formatted component for the player's locale.
Component motd = Message.raw("server.motd")
        .arg(player.getName())
        .get(playerId);

// You can now use the 'motd' component wherever you need it.
player.

sendPlayerListHeader(motd);
```

---

## 6. Localization (`lang`) Files

The message system is built around localization files. `player-core` automatically creates and manages these for you.

### 6.1. Key Structure

Message keys are namespaces that map directly to a file path.

- `banking.deposit.success` → `lang/en_US/banking.yml`
- `skills.levelup` → `lang/en_US/skills.yml`
- `generic.no_permission` → `lang/en_US/generic.yml`

### 6.2. File Structure Example

The `lang` folder is located inside the `player-core` plugin directory.

```
/plugins/FulcrumPlayerData/
└── lang/
    └── en_US/
        ├── banking.yml
        ├── skills.yml
        └── generic.yml
```

### 6.3. Example Translation File

Arguments in the file are referenced using `{0}`, `{1}`, `{2}`, etc.

**`lang/en_US/banking.yml`**

```yaml
deposit:
  success: "You successfully deposited <yellow>{0}</yellow> into your account."
  insufficient_funds: "<red>You cannot withdraw {0}, you only have {1}.</red>"
withdraw:
  success: "You withdrew <yellow>{0}</yellow>."
```

If you use a key that doesn't exist (e.g., `banking.transfer.failed`), the system will **automatically add it** to the
correct file with a placeholder message, making it easy to add new messages.