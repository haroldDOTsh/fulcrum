package sh.harold.fulcrum.api.module.impl.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.impl.RedisMessageBus;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleMetadata;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class ModuleListCommand {

    private final JavaPlugin plugin;
    private final ModuleManager moduleManager;

    public ModuleListCommand(JavaPlugin plugin, ModuleManager moduleManager) {
        this.plugin = plugin;
        this.moduleManager = moduleManager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("runtimeinfo")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
                .executes(ctx -> showRuntimeOverview(ctx.getSource()))
                .then(literal("list")
                        .executes(ctx -> showModuleList(ctx.getSource())))
                .then(literal("environment")
                        .executes(ctx -> showEnvironment(ctx.getSource())))
                .then(literal("reload")
                        .requires(source -> RankUtils.isAdmin(source.getSender()))
                        .executes(ctx -> handleEnvironmentReload(ctx.getSource())))
                .build();
    }

    private int showRuntimeOverview(CommandSourceStack source) {
        CommandSender sender = source.getSender();

        sender.sendMessage(Component.text("=== Fulcrum Runtime Info ===", NamedTextColor.GOLD, TextDecoration.BOLD));

        if (plugin != null) {
            var description = plugin.getDescription();
            sender.sendMessage(line("Plugin", description.getFullName()));
            sender.sendMessage(line("Version", description.getVersion()));
        }

        String environment = FulcrumEnvironment.getCurrent();
        sender.sendMessage(line("Environment", environment != null ? environment : "unknown"));

        List<ModuleMetadata> modules = moduleManager != null ? moduleManager.getLoadedModules() : null;
        if (modules != null) {
            sender.sendMessage(line("Loaded Modules", String.valueOf(modules.size())));
        } else {
            sender.sendMessage(line("Loaded Modules", "unavailable"));
        }

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        ServerIdentifier identifier = locator != null
                ? locator.findService(ServerIdentifier.class).orElse(null)
                : null;
        MessageBus messageBus = locator != null
                ? locator.findService(MessageBus.class).orElse(null)
                : null;
        SimpleSlotOrchestrator orchestrator = locator != null
                ? locator.findService(SimpleSlotOrchestrator.class).orElse(null)
                : null;

        if (identifier != null) {
            sender.sendMessage(line("Server ID", identifier.getServerId()));
            sender.sendMessage(line("Server Type", identifier.getType() + " (role=" + identifier.getRole() + ")"));
        } else {
            sender.sendMessage(line("Server ID", "unavailable"));
        }

        if (messageBus != null) {
            String busId = messageBus.currentServerId();
            String summary = messageBus.getClass().getSimpleName();
            if (busId != null && !busId.isBlank()) {
                summary += " (id=" + busId + ")";
            }
            if (messageBus instanceof RedisMessageBus redis) {
                summary += ", connected=" + redis.isConnected();
            }
            sender.sendMessage(line("Message Bus", summary));
        } else {
            sender.sendMessage(line("Message Bus", "unavailable"));
        }

        if (orchestrator != null) {
            Map<String, Integer> capacities = orchestrator.getFamilyCapacities();
            Map<String, Integer> active = orchestrator.getActiveSlotsByFamily();
            String families = capacities.isEmpty()
                    ? "none"
                    : capacities.entrySet().stream()
                    .map(entry -> entry.getKey() + "(" + active.getOrDefault(entry.getKey(), 0) + "/" + entry.getValue() + ")")
                    .collect(Collectors.joining(", "));
            sender.sendMessage(line("Slot Families", families));

            List<String> slots = orchestrator.getActiveSlotSummaries();
            sender.sendMessage(line("Active Slots", slots.isEmpty() ? "none" : String.join(", ", slots)));
        } else {
            sender.sendMessage(line("Slot Orchestrator", "unavailable"));
        }

        sender.sendMessage(Component.text("Use /runtimeinfo list for module details.", NamedTextColor.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private int showModuleList(CommandSourceStack source) {
        CommandSender sender = source.getSender();

        if (moduleManager == null || moduleManager.getLoadedModules() == null) {
            sender.sendMessage(Component.text("Module system is not initialized.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        List<ModuleMetadata> modules = moduleManager.getLoadedModules();

        sender.sendMessage(Component.text("Loaded Fulcrum Modules:", NamedTextColor.GOLD));
        sender.sendMessage(line("Current Environment", FulcrumEnvironment.getCurrent()));

        if (modules.isEmpty()) {
            sender.sendMessage(Component.text("No modules are currently loaded.", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        for (ModuleMetadata metadata : modules) {
            if (metadata == null) {
                continue;
            }

            String name = metadata.name() != null ? metadata.name() : "Unknown Module";
            String description = metadata.description() != null ? metadata.description() : "No description available";

            Component line = Component.text("- ", NamedTextColor.GRAY)
                    .append(Component.text(name, NamedTextColor.GREEN))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(description, NamedTextColor.WHITE));
            sender.sendMessage(line);
        }

        return Command.SINGLE_SUCCESS;
    }

    private int showEnvironment(CommandSourceStack source) {
        CommandSender sender = source.getSender();

        sender.sendMessage(Component.text("=== Environment Info ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(line("Current Environment", FulcrumEnvironment.getCurrent()));
        sender.sendMessage(line("Config Location", "./ENVIRONMENT"));
        sender.sendMessage(Component.text("To change: edit the ENVIRONMENT file and restart the server.", NamedTextColor.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private int handleEnvironmentReload(CommandSourceStack source) {
        CommandSender sender = source.getSender();

        sender.sendMessage(Component.text("Environment reload via command is not yet implemented.", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Please restart the server to reload environment configuration.", NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private Component line(String label, String value) {
        String safeValue = value != null ? value : "unknown";
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(safeValue, NamedTextColor.WHITE));
    }
}
