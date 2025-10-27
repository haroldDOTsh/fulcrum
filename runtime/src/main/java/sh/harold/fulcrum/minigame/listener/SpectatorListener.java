package sh.harold.fulcrum.minigame.listener;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingService;
import sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingService.EnvironmentRouteResult;
import sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingService.RouteOptions;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles placeholder interactions for spectator utilities.
 */
public final class SpectatorListener implements Listener {
    private static final String RETURN_TO_LOBBY_LABEL = "Return to Lobby (Right Click)";
    private static final String QUEUE_AGAIN_LABEL = "Queue Again (Right Click)";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }

        String stripped = ChatColor.stripColor(meta.getDisplayName());
        if (item.getType() == Material.RED_BED && RETURN_TO_LOBBY_LABEL.equalsIgnoreCase(stripped)) {
            event.setCancelled(true);
            handleLobbyReturn(event.getPlayer());
            return;
        }

        if (item.getType() == Material.PAPER && QUEUE_AGAIN_LABEL.equalsIgnoreCase(stripped)) {
            event.setCancelled(true);
            handleQueueAgain(event.getPlayer());
        }
    }

    private void handleLobbyReturn(Player player) {
        if (player == null) {
            return;
        }
        EnvironmentRoutingService routingService = locate(EnvironmentRoutingService.class).orElse(null);
        if (routingService == null) {
            player.sendMessage(ChatColor.RED + "Lobby routing is unavailable right now. Please try again in a moment.");
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "spectator-bed");
        metadata.put("initiator", player.getName());
        resolveMatchInfo(player.getUniqueId()).ifPresent(info -> {
            if (info.matchId() != null) {
                metadata.put("matchId", info.matchId().toString());
            }
            if (info.familyId() != null && !info.familyId().isBlank()) {
                metadata.put("family", info.familyId());
            }
            if (info.variantId() != null && !info.variantId().isBlank()) {
                metadata.put("variant", info.variantId());
            }
        });
        metadata.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isBlank());

        RouteOptions options = RouteOptions.builder()
                .failureMode(RouteOptions.FailureMode.REPORT_ONLY)
                .reason("spectator-return")
                .metadata(metadata)
                .build();

        EnvironmentRouteResult result = routingService.routePlayer(player, "lobby", options);
        if (result.success()) {
            player.sendMessage(ChatColor.GRAY + "Sending you to the lobby...");
        } else {
            player.sendMessage(ChatColor.RED + "Could not send you to the lobby (" +
                    Optional.ofNullable(result.message()).orElse("unavailable") + ").");
        }
    }

    private void handleQueueAgain(Player player) {
        if (player == null) {
            return;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            player.sendMessage(ChatColor.RED + "Matchmaking is unavailable right now.");
            return;
        }

        MessageBus messageBus = locator.findService(MessageBus.class).orElse(null);
        PlayerRouteRegistry routeRegistry = locator.findService(PlayerRouteRegistry.class).orElse(null);
        ServerLifecycleFeature lifecycleFeature = locator.findService(ServerLifecycleFeature.class).orElse(null);
        ServerIdentifier serverIdentifier = locator.findService(ServerIdentifier.class).orElse(null);

        if (messageBus == null) {
            player.sendMessage(ChatColor.RED + "Cannot queue you again right now. Please try shortly.");
            return;
        }

        UUID playerId = player.getUniqueId();
        Optional<PlayerRouteRegistry.RouteAssignment> assignmentOpt = routeRegistry != null
                ? routeRegistry.get(playerId)
                : Optional.empty();
        PlayerRouteRegistry.RouteAssignment assignment = assignmentOpt.orElse(null);

        Optional<MatchInfo> matchInfo = resolveMatchInfo(playerId);
        String familyId = Optional.ofNullable(assignment)
                .map(PlayerRouteRegistry.RouteAssignment::familyId)
                .filter(value -> value != null && !value.isBlank())
                .or(() -> matchInfo.map(MatchInfo::familyId))
                .orElse(null);

        if (familyId == null || familyId.isBlank()) {
            player.sendMessage(ChatColor.RED + "Unable to determine which minigame to queue. Please use /play manually.");
            return;
        }

        String proxyId = Optional.ofNullable(assignment)
                .map(PlayerRouteRegistry.RouteAssignment::proxyId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
        if ((proxyId == null || proxyId.isBlank()) && lifecycleFeature != null) {
            proxyId = lifecycleFeature.getCurrentProxyId().orElse(null);
        }
        if (proxyId == null || proxyId.isBlank()) {
            player.sendMessage(ChatColor.RED + "No proxy available to queue you. Please try again soon.");
            return;
        }

        String variantId = Optional.ofNullable(assignment)
                .map(PlayerRouteRegistry.RouteAssignment::variant)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> matchInfo.map(MatchInfo::variantId).filter(value -> !value.isBlank()).orElse(familyId));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "spectator-requeue");
        metadata.put("initiator", player.getName());
        metadata.put("requestedAt", Long.toString(System.currentTimeMillis()));
        metadata.put("family", familyId);
        metadata.put("variant", variantId);
        if (serverIdentifier != null && serverIdentifier.getServerId() != null && !serverIdentifier.getServerId().isBlank()) {
            metadata.put("requestedServer", serverIdentifier.getServerId());
        }
        if (assignment != null && assignment.slotId() != null && !assignment.slotId().isBlank()) {
            metadata.put("previousSlotId", assignment.slotId());
        }
        assignmentOpt.map(PlayerRouteRegistry.RouteAssignment::metadata)
                .filter(map -> map != null && !map.isEmpty())
                .ifPresent(meta -> meta.forEach((key, value) -> {
                    if (key != null && value != null) {
                        metadata.putIfAbsent(key, value);
                    }
                }));
        metadata.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isBlank());

        PlayerSlotRequest request = new PlayerSlotRequest();
        request.setPlayerId(playerId);
        request.setPlayerName(player.getName());
        request.setProxyId(proxyId);
        request.setFamilyId(familyId);
        request.setMetadata(metadata);

        try {
            request.validate();
            messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);
            player.sendMessage(ChatColor.GRAY + "Queued you for " + ChatColor.GOLD + variantId + ChatColor.GRAY + ". Hang tight!");
        } catch (Exception exception) {
            player.sendMessage(ChatColor.RED + "Failed to queue you: " + exception.getMessage());
        }
    }

    private Optional<MatchInfo> resolveMatchInfo(UUID playerId) {
        return locate(MinigameEngine.class)
                .flatMap(engine -> engine.findMatchByPlayer(playerId))
                .flatMap(match -> {
                    MinigameRegistration registration = match.getContext()
                            .getRegistration()
                            .orElse(null);
                    if (registration == null) {
                        return Optional.empty();
                    }
                    String familyId = registration.getFamilyId();
                    String variant = null;
                    if (registration.getDescriptor() != null
                            && registration.getDescriptor().getMetadata() != null) {
                        variant = registration.getDescriptor().getMetadata().getOrDefault("variant", familyId);
                    }
                    if (variant == null || variant.isBlank()) {
                        variant = familyId;
                    }
                    return Optional.of(new MatchInfo(match.getMatchId(), familyId, variant));
                });
    }

    private <T> Optional<T> locate(Class<T> type) {
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return Optional.empty();
        }
        return locator.findService(type);
    }

    private record MatchInfo(UUID matchId, String familyId, String variantId) {
    }
}
