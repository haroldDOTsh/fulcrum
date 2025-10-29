package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.punishment.AppealCommand;
import sh.harold.fulcrum.velocity.punishment.PardonCommand;
import sh.harold.fulcrum.velocity.punishment.PunishCommand;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

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
    private DataAPI dataAPI;
    private SlotFamilyCache familyCache;
    private VelocityPlayerSessionService sessionService;
    private sh.harold.fulcrum.velocity.party.PartyService partyService;
    private sh.harold.fulcrum.velocity.party.PartyReservationService partyReservationService;
    private VelocityServerLifecycleFeature lifecycleFeature;
    private VelocityMessageBusFeature messageBusFeature;

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
        this.dataAPI = serviceLocator.getRequiredService(DataAPI.class);
        this.familyCache = serviceLocator.getRequiredService(SlotFamilyCache.class);
        this.sessionService = serviceLocator.getRequiredService(VelocityPlayerSessionService.class);
        this.lifecycleFeature = serviceLocator.getRequiredService(sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature.class);
        this.messageBusFeature = serviceLocator.getRequiredService(sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature.class);
        this.partyService = serviceLocator.getService(sh.harold.fulcrum.velocity.party.PartyService.class).orElse(null);
        this.partyReservationService = serviceLocator.getService(sh.harold.fulcrum.velocity.party.PartyReservationService.class).orElse(null);

        // Register proxy commands when they are introduced
        registerPlayCommand();
        registerLocatePlayerCommand();
        registerLobbyCommand();
        registerRejoinCommand();
        registerPunishCommand();
        registerAppealCommand();
        registerPardonCommand();

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

        commandManager.register(meta, new LocatePlayerCommand(proxy, messageBus, playerRoutingFeature, plugin, logger, dataAPI, sessionService));
    }

    private void registerPlayCommand() {
        CommandMeta meta = commandManager.metaBuilder("play")
                .plugin(plugin)
                .build();

        commandManager.register(meta, new ProxyPlayCommand(proxy, playerRoutingFeature, familyCache, plugin, logger,
                partyService, partyReservationService));
    }

    private void registerLobbyCommand() {
        CommandMeta meta = commandManager.metaBuilder("lobby")
                .aliases("l")
                .plugin(plugin)
                .build();

        LobbyCommand command = new LobbyCommand(
                proxy,
                messageBus,
                messageBusFeature,
                playerRoutingFeature,
                lifecycleFeature,
                logger
        );

        commandManager.register(meta, command);
    }

    private void registerRejoinCommand() {
        CommandMeta meta = commandManager.metaBuilder("rejoin")
                .aliases("rj")
                .plugin(plugin)
                .build();

        ProxyRejoinCommand command = new ProxyRejoinCommand(
                proxy,
                playerRoutingFeature,
                sessionService,
                logger
        );

        commandManager.register(meta, command);
    }

    private void registerPunishCommand() {
        CommandMeta meta = commandManager.metaBuilder("punish")
                .plugin(plugin)
                .build();

        PunishCommand command = new PunishCommand(
                proxy,
                messageBus,
                dataAPI,
                sessionService,
                logger,
                plugin
        );

        commandManager.register(meta, command);
    }

    private void registerAppealCommand() {
        CommandMeta meta = commandManager.metaBuilder("appeal")
                .plugin(plugin)
                .build();

        AppealCommand command = new AppealCommand(
                proxy,
                messageBus,
                dataAPI,
                sessionService,
                logger,
                plugin
        );

        commandManager.register(meta, command);
    }

    private void registerPardonCommand() {
        CommandMeta meta = commandManager.metaBuilder("pardon")
                .plugin(plugin)
                .build();

        PardonCommand command = new PardonCommand(
                proxy,
                messageBus,
                dataAPI,
                sessionService,
                logger,
                plugin
        );

        commandManager.register(meta, command);
    }
}
