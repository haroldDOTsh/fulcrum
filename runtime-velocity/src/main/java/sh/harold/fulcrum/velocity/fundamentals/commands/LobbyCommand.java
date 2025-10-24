package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.EnvironmentRouteRequestMessage;
import sh.harold.fulcrum.velocity.api.ServerIdentifier;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;

import java.util.Map;
import java.util.Optional;

final class LobbyCommand implements SimpleCommand {
    private static final String LOBBY_ENVIRONMENT = "lobby";

    private final ProxyServer proxy;
    private final MessageBus messageBus;
    private final VelocityMessageBusFeature messageBusFeature;
    private final PlayerRoutingFeature routingFeature;
    private final VelocityServerLifecycleFeature lifecycleFeature;
    private final Logger logger;

    LobbyCommand(ProxyServer proxy,
                 MessageBus messageBus,
                 VelocityMessageBusFeature messageBusFeature,
                 PlayerRoutingFeature routingFeature,
                 VelocityServerLifecycleFeature lifecycleFeature,
                 Logger logger) {
        this.proxy = proxy;
        this.messageBus = messageBus;
        this.messageBusFeature = messageBusFeature;
        this.routingFeature = routingFeature;
        this.lifecycleFeature = lifecycleFeature;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        if (!hasLobbyAvailable()) {
            player.sendMessage(Component.text("No lobby servers are currently available. Please try again shortly.", NamedTextColor.RED));
            return;
        }

        Optional<String> originId = resolveCurrentServerId(player);
        if (originId.isEmpty()) {
            player.sendMessage(Component.text("We could not determine your current server. Please try again.", NamedTextColor.RED));
            return;
        }

        EnvironmentRouteRequestMessage request = buildRouteRequest(player, originId.get());
        try {
            request.validate();
        } catch (IllegalStateException exception) {
            player.sendMessage(Component.text("Unable to route you to a lobby: " + exception.getMessage(), NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Sending you to the lobby...", NamedTextColor.GRAY));
        messageBus.broadcast(ChannelConstants.REGISTRY_ENVIRONMENT_ROUTE_REQUEST, request);
    }

    private boolean hasLobbyAvailable() {
        return lifecycleFeature.getServersByRole(LOBBY_ENVIRONMENT).stream()
                .map(ServerIdentifier::getServerId)
                .filter(id -> id != null && !id.isBlank())
                .filter(this::isServerReachable)
                .anyMatch(lifecycleFeature::isServerActive);
    }

    private boolean isServerReachable(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        if (proxy.getServer(serverId).isPresent()) {
            return true;
        }
        // Some deployments alias lobby servers with different names in Velocity's config;
        // fall back to searching by lowercase match.
        return proxy.getAllServers().stream()
                .map(RegisteredServer::getServerInfo)
                .anyMatch(info -> info.getName().equalsIgnoreCase(serverId));
    }

    private Optional<String> resolveCurrentServerId(Player player) {
        return routingFeature.getPlayerLocation(player.getUniqueId())
                .map(PlayerRoutingFeature.PlayerLocationSnapshot::getServerId)
                .filter(id -> id != null && !id.isBlank())
                .or(() -> player.getCurrentServer()
                        .map(current -> current.getServerInfo().getName())
                        .filter(name -> name != null && !name.isBlank()));
    }

    private EnvironmentRouteRequestMessage buildRouteRequest(Player player, String originServerId) {
        EnvironmentRouteRequestMessage message = new EnvironmentRouteRequestMessage();
        message.setPlayerId(player.getUniqueId());
        message.setPlayerName(player.getUsername());
        message.setProxyId(messageBusFeature.getCurrentProxyId());
        message.setOriginServerId(originServerId);
        message.setTargetEnvironmentId(LOBBY_ENVIRONMENT);
        message.setFailureMode(EnvironmentRouteRequestMessage.FailureMode.REPORT_ONLY);
        message.setMetadata(Map.of(
                "reason", "command:lobby",
                "issuedBy", player.getUniqueId().toString()
        ));
        logger.debug("Dispatching lobby route request for {} (origin={})", player.getUsername(), originServerId);
        return message;
    }
}
