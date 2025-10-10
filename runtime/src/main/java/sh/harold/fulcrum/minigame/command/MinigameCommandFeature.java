package sh.harold.fulcrum.minigame.command;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.logging.Logger;

/**
 * Registers player-facing minigame commands.
 */
public final class MinigameCommandFeature implements PluginFeature {
    private PlayCommand playCommand;

    @Override
    public int getPriority() {
        return 225;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        Logger logger = plugin.getLogger();

        MessageBus messageBus = container.getOptional(MessageBus.class).orElse(null);
        PlayerRouteRegistry routeRegistry = container.getOptional(PlayerRouteRegistry.class).orElse(null);
        MinigameEngine engine = container.getOptional(MinigameEngine.class).orElse(null);

        if (messageBus == null || routeRegistry == null || engine == null) {
            logger.warning("[Fulcrum] Skipping /play command registration (dependencies unavailable).");
            return;
        }

        SimpleSlotOrchestrator orchestrator = container.getOptional(SimpleSlotOrchestrator.class).orElse(null);
        ServerIdentifier serverIdentifier = container.getOptional(ServerIdentifier.class).orElse(null);
        ServerLifecycleFeature lifecycleFeature = container.getOptional(ServerLifecycleFeature.class).orElse(null);

        playCommand = new PlayCommand(
            messageBus,
            routeRegistry,
            orchestrator,
            serverIdentifier,
            lifecycleFeature,
            engine
        );

        CommandRegistrar.register(playCommand.build());
        logger.info("[Fulcrum] Registered /play command");
    }

    @Override
    public void shutdown() {
        playCommand = null;
    }
}
