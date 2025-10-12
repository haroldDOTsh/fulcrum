package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

/**
 * Feature that registers and manages Velocity proxy commands.
 */
public class VelocityCommandFeature implements VelocityFeature {

    private ProxyServer proxy;
    private Logger logger;
    private CommandManager commandManager;
    private ServiceLocator serviceLocator;
    private MessageBus messageBus;
    private PlayerRoutingFeature playerRoutingFeature;
    private FulcrumVelocityPlugin plugin;

    @Override
    public String getName() {
        return "VelocityCommands";
    }

    @Override
    public int getPriority() {
        return 100; // After most other features are loaded
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;

        // Get required services
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.commandManager = proxy.getCommandManager();
        this.messageBus = serviceLocator.getRequiredService(MessageBus.class);
        this.playerRoutingFeature = serviceLocator.getRequiredService(sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);

        // Register proxy commands when they are introduced
        registerLocatePlayerCommand();

        logger.info("VelocityCommandFeature initialized");
    }


    @Override
    public void shutdown() {
        // No commands to unregister currently
        logger.info("VelocityCommandFeature shut down");
    }

    private void registerLocatePlayerCommand() {
        CommandMeta meta = commandManager.metaBuilder("locateplayer")
                .plugin(plugin)
                .build();

        commandManager.register(meta, new LocatePlayerCommand(proxy, messageBus, playerRoutingFeature, plugin, logger));
    }
}
