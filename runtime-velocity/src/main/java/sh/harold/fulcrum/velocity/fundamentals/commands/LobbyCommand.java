package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LobbyCommand implements SimpleCommand {
    private static final String DEFAULT_LOBBY_FAMILY = "lobby.main";
    private static final Component NO_LOBBY_COMPONENT = Component.text(
            "No lobby servers are currently available. Please reconnect in a moment.",
            NamedTextColor.RED);

    private final ProxyServer proxy;
    private final PlayerRoutingFeature routingFeature;
    private final SlotFamilyCache familyCache;
    private final Logger logger;

    LobbyCommand(ProxyServer proxy,
                 PlayerRoutingFeature routingFeature,
                 SlotFamilyCache familyCache,
                 Logger logger) {
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.familyCache = familyCache;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        Optional<String> lobbyFamily = resolveLobbyFamily();
        if (lobbyFamily.isEmpty()) {
            logger.debug("No lobby family advertised; disconnecting {} from /l command", player.getUsername());
            player.disconnect(NO_LOBBY_COMPONENT);
            return;
        }

        if (!hasLobbyFamilyAvailable(lobbyFamily.get())) {
            logger.debug("Lobby family {} advertised but no capacity; disconnecting {}", lobbyFamily.get(), player.getUsername());
            player.disconnect(NO_LOBBY_COMPONENT);
            return;
        }

        Optional<String> originId = resolveCurrentServerId(player);
        if (originId.isEmpty()) {
            player.sendMessage(Component.text("We could not determine your current server. Please try again.", NamedTextColor.RED));
            return;
        }

        routeViaFamily(player, originId.get(), lobbyFamily.get());
    }

    private Optional<String> resolveLobbyFamily() {
        if (familyCache == null) {
            return Optional.empty();
        }

        if (familyCache.hasFamily(DEFAULT_LOBBY_FAMILY)) {
            return Optional.of(DEFAULT_LOBBY_FAMILY);
        }

        Set<String> families = familyCache.families();
        return families.stream()
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> name.startsWith("lobby"))
                .findFirst();
    }

    private boolean hasLobbyFamilyAvailable(String familyId) {
        Map<String, Map<String, Integer>> perServer = familyCache.snapshotPerServer();
        for (Map.Entry<String, Map<String, Integer>> entry : perServer.entrySet()) {
            Integer capacity = entry.getValue().get(familyId);
            if (capacity != null && capacity > 0 && isServerReachable(entry.getKey())) {
                return true;
            }
        }
        return false;
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

    private void routeViaFamily(Player player, String originServerId, String familyId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("reason", "command:lobby");
        metadata.put("originServer", originServerId);
        metadata.put("family", familyId);

        routingFeature.getPlayerLocation(player.getUniqueId()).ifPresent(snapshot -> {
            if (snapshot.getServerId() != null && !snapshot.getServerId().isBlank()) {
                metadata.put("currentServerId", snapshot.getServerId());
            }
            if (snapshot.getSlotId() != null && !snapshot.getSlotId().isBlank()) {
                metadata.put("currentSlotId", snapshot.getSlotId());
            }
        });

        routingFeature.sendSlotRequest(player, familyId, metadata);
        logger.debug("Queued lobby route request for {} via family {} (origin={})",
                player.getUsername(), familyId, originServerId);
    }
}
