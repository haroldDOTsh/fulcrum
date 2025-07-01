# Fulcrum Command API

A modern, annotation-driven command registration and execution system for Paper plugins, designed for modular, testable, and maintainable Minecraft server development.

---

## Features

- **Reflective Auto-Discovery (Explicit Registration):**
  - Command definitions are discovered and registered at runtime using reflection, but only for the classes you explicitly provide to the registration method.
  - No global classpath scanning: each plugin must register its own commands with `CommandRegistrationBridge.registerCommands(...)`.
  - All public fields annotated with `@Argument` are automatically mapped to Brigadier arguments.
- **Annotation-Driven:**
  - Use annotations like `@Sync`, `@Async`, `@Cooldown`, `@Executor`, and `@Argument` to control command behavior, threading, cooldowns, and argument parsing.
- **Executor Type Enforcement:**
  - Enforces player/console/any executor types at runtime, with clear error messages.
- **Cooldowns:**
  - Per-player, per-command cooldowns with bypass support.
- **Adventure API Integration:**
  - All command senders are treated as `Audience` for rich messaging.
- **Internal Messaging:**
  - All internal/system messages use the `internal.` prefix for easy localization and separation from user-facing messages.

---

## Usage

### 1. Define a Command Implementation

```java
import sh.harold.fulcrum.command.annotations.Argument;
import sh.harold.fulcrum.command.Sync;
import sh.harold.fulcrum.command.Cooldown;
import sh.harold.fulcrum.command.Executor;
import sh.harold.fulcrum.command.CommandExecutorType;

@Sync
@Cooldown(seconds = 10)
@Executor(CommandExecutorType.PLAYER)
public class ExampleCommand {
    @Argument("target")
    public String targetName;

    public void execute(CommandContext ctx) {
        var player = ctx.getPlayer();
        player.sendMessage("Hello, " + targetName + "!");
    }
}
```

### 2. Register Commands

```java
List<Object> commands = List.of(new ExampleCommand());
CommandRegistrationBridge.registerCommands(plugin, commands);
```

- All commands are discovered and registered at plugin startup via reflection, but only for the definitions you provide.
- **If you are developing a separate plugin, you must call `registerCommands` with your own command definitions.**
- Aliases and command names are read from the `CommandDefinition` record.

### 3. Arguments

- All public fields annotated with `@Argument` are mapped to Brigadier arguments.
- Supported types: `String`, `int`, `Integer`, `Player` (as player name).
- Suggestions and tab completion can be added via custom `SuggestionProviderAdapter`.

### 4. Cooldowns & Bypass

- Use `@Cooldown(seconds = N)` to set a per-player cooldown.
- Players can toggle cooldown bypass with a utility method:
  ```java
  CommandRegistrationBridge.toggleBypassCooldown(player);
  ```

### 5. Executor Types

- Use `@Executor(CommandExecutorType.PLAYER)` to restrict to players, or `CONSOLE` for console-only commands.
- Error messages are sent using the internal message keys (e.g., `internal.command.executor.invalid`).

### 6. Error Handling & Messaging

- All error and system messages use the `Message` API and are sent to the command sender as an `Audience`.
- Internal/system messages use the `internal.` prefix for easy localization.
- Example:
  ```java
  Message.error("internal.command.executor.invalid").send(sender);
  Message.error(GenericResponse.ERROR_COOLDOWN).send(sender);
  ```

### 7. Best Practices

- Always annotate your command classes for clear, declarative behavior.
- Use the `internal.` prefix for all system/internal messages.
- Prefer Adventure `Component` messaging for rich feedback.
- Keep command logic modular and testable—avoid static state.
- Use the provided cooldown and executor enforcement; do not reimplement these checks manually.

### 8. Quirks & Notes

- All command senders are treated as `Audience`—no need to check for null or cast.
- Reflection is used for argument and annotation discovery; ensure your command classes are public and fields are accessible.
- Only supported argument types are auto-mapped; for custom types, extend the system or use string arguments and parse manually.
- Aliases and command names must be provided via the `CommandDefinition` record.

---

## Example: Full Command Registration

```java
// Example command definition record
public record MyCommandDefinition(String name, String[] aliases, Class<?> implementationClass) implements CommandDefinition {}

// Registering commands
List<CommandDefinition> definitions = List.of(
    new MyCommandDefinition("example", new String[]{"ex"}, ExampleCommand.class)
);
CommandRegistrationBridge.registerCommands(plugin, definitions);
```

---

## Extending

- To add new argument types or custom suggestions, implement and register a `SuggestionProviderAdapter`.
- For advanced permission checks, extend the TODO section in the registration bridge with your own logic.

---

## License
MIT
