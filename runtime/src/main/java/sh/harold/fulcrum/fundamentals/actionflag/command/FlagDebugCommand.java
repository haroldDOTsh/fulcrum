package sh.harold.fulcrum.fundamentals.actionflag.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlag;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.actionflag.OverrideRequest;
import sh.harold.fulcrum.fundamentals.actionflag.OverrideScopeHandle;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;

/**
 * Developer-facing command for inspecting and manipulating action flags.
 */
public final class FlagDebugCommand {
    private final ActionFlagService service;
    private final Map<UUID, EnumMap<ActionFlag, OverrideScopeHandle>> overrides = new ConcurrentHashMap<>();

    public FlagDebugCommand(ActionFlagService service) {
        this.service = service;
    }

    private static Optional<Player> resolvePlayer(CommandContext<CommandSourceStack> ctx, String input) {
        if (input.equalsIgnoreCase("self") || input.equals("~")) {
            return resolveSelf(ctx);
        }
        return Optional.ofNullable(Bukkit.getPlayerExact(input));
    }

    private static Optional<Player> resolveSelf(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            return Optional.of(player);
        }
        return Optional.empty();
    }

    private static ActionFlag parseFlag(String name) throws CommandSyntaxException {
        try {
            return ActionFlag.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
        }
    }

    private static boolean parseState(String state) throws CommandSyntaxException {
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "1", "true", "allow", "on", "enable" -> true;
            case "0", "false", "deny", "off", "disable" -> false;
            default -> throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
        };
    }

    private static CommandSyntaxException unknownPlayer(String input) {
        return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
    }

    public void register(JavaPlugin plugin) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("flags")
                .requires(stack -> RankUtils.isAdmin(stack.getSender()))
                .then(Commands.literal("query")
                        .executes(ctx -> executeQuery(ctx, resolveSelf(ctx)))
                        .then(Commands.argument("player", string())
                                .suggests(playerSuggestions())
                                .executes(ctx -> executeQuery(ctx, resolvePlayer(ctx, getString(ctx, "player"))))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", string())
                                .suggests(playerSuggestions())
                                .then(Commands.argument("flag", string())
                                        .suggests(flagSuggestions())
                                        .then(Commands.argument("state", string())
                                                .suggests(stateSuggestions())
                                                .executes(ctx -> executeSet(ctx))))))
                .then(Commands.literal("unset")
                        .then(Commands.argument("player", string())
                                .suggests(playerSuggestions())
                                .then(Commands.argument("flag", string())
                                        .suggests(flagSuggestions())
                                        .executes(ctx -> executeUnset(ctx)))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", string())
                                .suggests(playerSuggestions())
                                .executes(ctx -> executeReset(ctx))));

        CommandRegistrar.register(root.build());
    }

    public void unregister() {
        overrides.clear();
    }

    private int executeQuery(CommandContext<CommandSourceStack> ctx, Optional<Player> targetOpt) throws CommandSyntaxException {
        if (targetOpt.isEmpty()) {
            ctx.getSource().getSender().sendMessage(Component.text("Specify a player or use 'self'."));
            return 0;
        }
        Player target = targetOpt.get();
        var snapshot = service.snapshot(target.getUniqueId());
        var sender = ctx.getSource().getSender();
        sender.sendMessage(Component.text("=== Action Flags for " + target.getName() + " ==="));
        sender.sendMessage(Component.text("Base context: " + (snapshot.baseContextId().isBlank() ? "<none>" : snapshot.baseContextId())));
        sender.sendMessage(Component.text("Active flags: " + snapshot.activeFlags()));
        if (snapshot.overrides().isEmpty()) {
            sender.sendMessage(Component.text("Overrides: none"));
        } else {
            snapshot.overrides().forEach(override ->
                    sender.sendMessage(Component.text("Override " + override.token()
                            + " enable=" + override.enabled()
                            + " disable=" + override.disabled())));
        }
        return 1;
    }

    private int executeSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = resolvePlayer(ctx, getString(ctx, "player"))
                .orElseThrow(() -> unknownPlayer(getString(ctx, "player")));
        ActionFlag flag = parseFlag(getString(ctx, "flag"));
        boolean allow = parseState(getString(ctx, "state"));

        popOverride(player.getUniqueId(), flag);
        OverrideRequest request = allow ? OverrideRequest.allow(flag) : OverrideRequest.deny(flag);
        OverrideScopeHandle handle = service.pushOverride(player.getUniqueId(), request);
        overrides.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(ActionFlag.class))
                .put(flag, handle);

        ctx.getSource().getSender().sendMessage(Component.text(
                "Set manual override for " + player.getName() + " -> " + flag + "=" + (allow ? "ALLOW" : "DENY")));
        return 1;
    }

    private int executeUnset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = resolvePlayer(ctx, getString(ctx, "player"))
                .orElseThrow(() -> unknownPlayer(getString(ctx, "player")));
        ActionFlag flag = parseFlag(getString(ctx, "flag"));
        boolean removed = popOverride(player.getUniqueId(), flag);
        ctx.getSource().getSender().sendMessage(Component.text(
                removed ? "Removed manual override for " + flag + " on " + player.getName()
                        : "No manual override for " + flag + " on " + player.getName()));
        return 1;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = resolvePlayer(ctx, getString(ctx, "player"))
                .orElseThrow(() -> unknownPlayer(getString(ctx, "player")));
        UUID playerId = player.getUniqueId();
        var snapshot = service.snapshot(playerId);

        // Remove manual overrides first
        var playerOverrides = overrides.remove(playerId);
        if (playerOverrides != null) {
            playerOverrides.values().forEach(service::popOverride);
        }

        // Reset profile to base context
        service.clear(playerId);
        String baseContextId = snapshot.baseContextId();
        if (!baseContextId.isBlank() && service.hasContext(baseContextId)) {
            service.applyContext(playerId, baseContextId);
        }

        ctx.getSource().getSender().sendMessage(Component.text(
                "Reset flags for " + player.getName() + " to base context '" + baseContextId + "'"));
        return 1;
    }

    private boolean popOverride(UUID playerId, ActionFlag flag) {
        var playerOverrides = overrides.get(playerId);
        if (playerOverrides == null) {
            return false;
        }
        OverrideScopeHandle handle = playerOverrides.remove(flag);
        if (playerOverrides.isEmpty()) {
            overrides.remove(playerId);
        }
        if (handle == null) {
            return false;
        }
        service.popOverride(handle);
        return true;
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            if ("self".startsWith(remaining)) {
                builder.suggest("self");
            }
            if ("~".startsWith(remaining)) {
                builder.suggest("~");
            }
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> flagSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (ActionFlag flag : ActionFlag.values()) {
                String option = flag.name().toLowerCase(Locale.ROOT);
                if (option.startsWith(remaining)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> stateSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String option : List.of("allow", "deny", "true", "false", "on", "off")) {
                if (option.startsWith(remaining)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }
}
