# Eternum Core - Message API

The `message-api` module provides a robust, platform-agnostic messaging system for Minecraft plugins, built on
the [Adventure](https://docs.adventure.kyori.net/) library. It supports styled messages, localization, macro messages,
and a fluent API for message construction.

## Key Concepts

* **Adventure**: The underlying text formatting library. All messages are ultimately converted to Adventure `Component`
  s.
* **MiniMessage**: A powerful serialization format used for parsing messages. It supports a wide range of tags for
  colors, formatting, and more. Legacy color codes (`&a`, `&b`, etc.) are automatically translated.
* **`MessageStyle`**: An enum defining common message styles (e.g., `SUCCESS`, `INFO`, `ERROR`). Each style has a
  default `NamedTextColor` for the main message and a `NamedTextColor` for arguments, providing a MiniMessage tag
  prefix.
* **`MessageTag`**: Represents a key-value pair used for adding contextual tags to messages (e.g., `staff`, `system`).
* **Translation Keys**: Messages are identified by keys (e.g., `feature.message.key`). The `MessageService` resolves
  these keys to localized strings.
* **Macro Keys**: Shorthand keys (e.g., `no_permission`) that map to predefined translation keys, simplifying common
  message sending.

## Basic Usage

The primary entry point for sending messages is the `Message` facade. Before sending any messages, you **must** set the
`MessageService` implementation (typically done in your main plugin class in `player-core`):

```java
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.message.YamlMessageService; // Example implementation

// In your plugin's onEnable() method:
Message.setMessageService(new YamlMessageService(getDataFolder().

toPath()));
```

### Sending Translation Key Messages to a Player

Use `Message.<style>(key, args...)` to start building a message, then call `.send(playerId)`:

```java
import sh.harold.fulcrum.api.message.Message;

import java.util.UUID;

UUID playerId = /* ... get player UUID ... */;

// Simple success message with placeholders
Message.

success("banking.deposit.success",1000,"coins").

send(playerId);
// Assuming "banking.deposit.success" translates to "Successfully deposited %s %s!"
// Output: <green>Successfully deposited <yellow>1000</yellow> <yellow>coins</yellow>!</green>

// Informational message
Message.

info("player.balance.current",15750).

send(playerId);
// Output: <gray>Your current balance is <aqua>15750</aqua>!</gray>

// Error message
Message.

error("command.invalid_argument","deposit","amount").

send(playerId);
// Output: <red>Invalid argument for command 'deposit': <red>'amount'</red></red>
```

### Sending Macro Messages to a Player

Use `Message.macro(macroKey, args...)` for predefined common messages:

```java
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.message.MessageStyle; // Import if you want to specify style

import java.util.UUID;

UUID playerId = /* ... get player UUID ... */;

// Send a "no permission" macro message
Message.

macro("no_permission").

send(playerId);
// Output (assuming macro maps to generic.no_permission): <red>You do not have permission to do that.</red>

// Send a "on cooldown" macro message with an argument
Message.

macro("on_cooldown",5).

send(playerId);
// Output (assuming macro maps to generic.on_cooldown): <red>You are on cooldown for <red>5</red> seconds.</red>

// You can also specify a style for macro messages if needed (defaults to RAW)
Message.

macro(MessageStyle.INFO, "on_cooldown",10).

send(playerId);
// Output: <gray>You are on cooldown for <aqua>10</aqua> seconds.</gray>
```

### Broadcasting Messages

Use `.broadcast()` instead of `.send(playerId)`:

```java
// Broadcast an informational message about server restart
Message.info("server.restart.warning",5).

broadcast();
// Output: <gray>Server will restart in <aqua>5</aqua> minutes!</gray>
```

## Advanced Usage: Chained Methods and Tags

The `MessageBuilder` supports chained methods for adding predefined tags, making messages more contextual.

### Predefined Chained Tags

The following chained methods are available:

* `.staff()`: Adds a `<tag:staff>` tag (e.g., for staff-only messages).
* `.system()`: Adds a `<tag:system>` tag (e.g., for automated system messages).
* `.debug()`: Adds a `<tag:debug>` tag (e.g., for debug output).

```java
// Message for staff and system, sent to a specific player
Message.success("admin.command.reload")
    .

staff()
    .

system()
    .

send(playerId);
// Output (example, actual rendering depends on TagFormatter):
// <red>[STAFF]</red> <blue>[SYSTEM]</blue> <green>Admin command reloaded successfully!</green>

// Broadcast a system message with debug tag
Message.

info("server.maintenance.scheduled","2 hours")
    .

system()
    .

debug()
    .

broadcast();
// Output: <blue>[SYSTEM]</blue> <dark_gray>[DEBUG]</dark_gray> <gray>Server maintenance scheduled in <aqua>2 hours</aqua>.</gray>
```

### Retrieving Components Without Sending

If you need the formatted Adventure `Component` for logging, further processing, or displaying in a custom UI, use
`.get(playerId)` or `.get(locale)`:

```java
import net.kyori.adventure.text.Component;

import java.util.Locale;

// Get a message component for logging
Component logMessage = Message.info("player.login", "Harold")
        .system()
        .get(playerId); // Or .get(Locale.US) if no player context

System.out.

        println("Logged message: "+logMessage.examinableName()); // Example logging

        // Get a message component to display in a custom inventory GUI
        Component guiTitle = Message.info("gui.settings.title").get(playerId);
// ... use guiTitle for your inventory ...
```

## Localization and Placeholders

The `MessageService` implementation (e.g., `YamlMessageService`) is responsible for loading translations. Translation
keys follow a `feature.detail.detail` format (e.g., `banking.deposit.success`). Macro keys are resolved to these
translation keys.

Placeholders in your translation files are standard Java `String.format()` placeholders (`%s`, `%d`, etc.). Arguments
passed to `Message.key()` or `Message.macro()` will be automatically colored according to the `MessageStyle`'s argument
color.

**Example `lang/banking/en_US.yml`:**

```yaml
deposit:
  success: "Successfully deposited %s %s!"
```

**Example `lang/generic/en_US.yml` (for macros):**

```yaml
no_permission: "You do not have permission to do that."
on_cooldown: "You are on cooldown for %s seconds."
```

When you call `Message.success("banking.deposit.success", 1000, "coins")`, the `MessageService` will:

1. Look up `banking.deposit.success` in the `en_US.yml` file.
2. Format the string with `1000` and `coins`, applying the `SUCCESS` style's argument color (yellow) to them.
3. Apply the `SUCCESS` message style's prefix (green color) to the entire message.
4. Convert any legacy color codes (if present in the YAML) to Adventure format.
5. Return the final Adventure `Component`.

## Integration with Player API

This section demonstrates how to combine the `message-api` with the `data-api` to fetch and display player-specific
data.

```java
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.registry.PlayerProfileManager;
import sh.harold.fulcrum.registry.PlayerProfile;

import java.util.UUID;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Note: Ensure PlayerProfileManager is initialized and accessible,
// typically through your main plugin class or a dependency injection system.
// Example: PlayerProfileManager playerProfileManager = PlayerDataPlugin.getPlayerProfileManager();

public class PlayerApiIntegrationExamples {

    // Assuming PlayerProfile has a getBalance() method
    public void displayPlayerBalance(UUID playerId) {
        PlayerProfileManager.getProfile(playerId).thenAccept(profileOptional -> {
            if (profileOptional.isPresent()) {
                PlayerProfile profile = profileOptional.get();
                double balance = profile.getBalance(); // Assuming getBalance() exists
                Message.info("player.balance.current", balance).send(playerId);
            } else {
                Message.error("player.not_found").send(playerId);
            }
        });
    }

    // Assuming PlayerProfile has a getLastLogin() method returning a long (timestamp)
    public void displayPlayerLastLogin(UUID playerId) {
        PlayerProfileManager.getProfile(playerId).thenAccept(profileOptional -> {
            if (profileOptional.isPresent()) {
                PlayerProfile profile = profileOptional.get();
                long lastLoginMillis = profile.getLastLogin();
                LocalDateTime lastLoginDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastLoginMillis), ZoneId.systemDefault());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                String formattedTime = lastLoginDateTime.format(formatter);
                Message.info("player.last_login", formattedTime).send(playerId);
            } else {
                Message.error("player.not_found").send(playerId);
            }
        });
    }

    // Example of sending a macro message based on player data
    public void checkPlayerPermissionAndSendMessage(UUID playerId, boolean hasPermission) {
        if (hasPermission) {
            Message.success("command.executed").send(playerId); // Assuming a generic success message
        } else {
            Message.macro("no_permission").send(playerId);
        }
    }
}
```

## Building

The `message-api` module is a pure Java library and has no direct Bukkit/Paper dependencies.

To build:

```bash
./gradlew :message-api:build
```

## Dependencies

To use `message-api` in your project, add it as a dependency in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":message-api"))
}
```

Ensure your project also includes the Adventure API and MiniMessage dependencies, as `message-api` relies on them:

```kotlin
dependencies {
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
}
```