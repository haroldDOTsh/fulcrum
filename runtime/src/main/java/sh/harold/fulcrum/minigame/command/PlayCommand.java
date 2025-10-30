package sh.harold.fulcrum.minigame.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Brigadier command that lets players join a minigame variant via the registry pipeline.
 */
public final class PlayCommand {
    private static final long COOLDOWN_MS = 5_000L;
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private final MessageBus messageBus;
    private final PlayerRouteRegistry routeRegistry;
    private final SimpleSlotOrchestrator orchestrator;
    private final ServerIdentifier serverIdentifier;
    private final ServerLifecycleFeature lifecycleFeature;
    private final MinigameEngine engine;

    public PlayCommand(MessageBus messageBus,
                       PlayerRouteRegistry routeRegistry,
                       SimpleSlotOrchestrator orchestrator,
                       ServerIdentifier serverIdentifier,
                       ServerLifecycleFeature lifecycleFeature,
                       MinigameEngine engine) {
        this.messageBus = messageBus;
        this.routeRegistry = routeRegistry;
        this.orchestrator = orchestrator;
        this.serverIdentifier = serverIdentifier;
        this.lifecycleFeature = lifecycleFeature;
        this.engine = engine;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("play")
                .requires(this::canExecute)
                .then(argument("variantId", StringArgumentType.word())
                        .executes(ctx -> handlePlay(ctx.getSource(), ctx.getArgument("variantId", String.class))))
                .executes(ctx -> {
                    Message.info("play.usage").send(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private boolean canExecute(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            return false;
        }
        return RankUtils.hasRankOrHigher(player, Rank.DEFAULT);
    }

    private int handlePlay(CommandSourceStack source, String raw) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            Message.error("play.player-only").send(sender);
            return Command.SINGLE_SUCCESS;
        }

        if (!RankUtils.hasRankOrHigher(player, Rank.DEFAULT)) {
            Message.error("play.rank-required").send(player);
            return Command.SINGLE_SUCCESS;
        }

        if (messageBus == null || routeRegistry == null || engine == null) {
            Message.error("play.unavailable").send(player);
            return Command.SINGLE_SUCCESS;
        }

        long now = System.currentTimeMillis();
        long last = COOLDOWNS.getOrDefault(player.getUniqueId(), 0L);
        long elapsed = now - last;
        if (elapsed < COOLDOWN_MS) {
            long remaining = COOLDOWN_MS - elapsed;
            long seconds = Math.max(1, (long) Math.ceil(remaining / 1000.0D));
            Message.error("play.cooldown", seconds).send(player);
            return Command.SINGLE_SUCCESS;
        }

        VariantSelection selection = VariantSelection.from(raw);
        if (selection == null) {
            Message.error("play.invalid-variant", raw).send(player);
            return Command.SINGLE_SUCCESS;
        }

        Optional<MinigameRegistration> registration = engine.getRegistration(selection.familyId());
        if (registration.isEmpty()) {
            registration = engine.getRegistration(selection.familyKey());
        }

        if (registration.isEmpty()) {
            Message.error("play.unknown-variant", selection.display()).send(player);
            return Command.SINGLE_SUCCESS;
        }

        String familyId = registration.get().getFamilyId();

        if (!hasAvailableBackend(familyId)) {
            Message.error("play.no-backend", selection.display()).send(player);
            return Command.SINGLE_SUCCESS;
        }

        String proxyId = routeRegistry.get(player.getUniqueId())
                .map(PlayerRouteRegistry.RouteAssignment::proxyId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
        if ((proxyId == null || proxyId.isBlank()) && lifecycleFeature != null) {
            proxyId = lifecycleFeature.getCurrentProxyId().orElse(null);
        }
        if (proxyId == null || proxyId.isBlank()) {
            Message.error("play.proxy-missing").send(player);
            return Command.SINGLE_SUCCESS;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "play-command");
        metadata.put("initiator", player.getName());
        metadata.put("requestedAt", Long.toString(now));
        metadata.put("requestedServer", serverIdentifier != null ? serverIdentifier.getServerId() : "unknown");
        metadata.put("family", familyId);

        String variantValue = selection.variantKey().isBlank() ? familyId : selection.variantKey();
        metadata.put("variant", variantValue);

        routeRegistry.get(player.getUniqueId())
                .map(PlayerRouteRegistry.RouteAssignment::slotId)
                .filter(id -> id != null && !id.isBlank())
                .ifPresent(id -> metadata.put("currentSlotId", id));

        try {
            PlayerSlotRequest request = new PlayerSlotRequest();
            request.setPlayerId(player.getUniqueId());
            request.setPlayerName(player.getName());
            request.setProxyId(proxyId);
            request.setFamilyId(familyId);
            request.setMetadata(metadata);
            messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);
            COOLDOWNS.put(player.getUniqueId(), now);
            Message.success("play.queued", selection.display()).send(player);
        } catch (Exception exception) {
            Message.error("play.failed", exception.getMessage()).send(player);
        }

        return Command.SINGLE_SUCCESS;
    }

    private boolean hasAvailableBackend(String familyId) {
        if (orchestrator == null) {
            return true;
        }
        Map<String, Integer> capacities = orchestrator.getFamilyCapacities();
        if (capacities.getOrDefault(familyId, 0) > 0) {
            return true;
        }
        Map<String, Integer> active = orchestrator.getActiveSlotsByFamily();
        return active.getOrDefault(familyId, 0) > 0;
    }

    private record VariantSelection(String rawInput, String familyId, String variantId,
                                    String familyKey, String variantKey) {

        static VariantSelection from(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }

            String family = trimmed;
            String variant = trimmed;
            int index = findSeparator(trimmed);
            if (index > 0 && index < trimmed.length() - 1) {
                family = trimmed.substring(0, index);
                variant = trimmed.substring(index + 1);
            }

            String familyKey = normalise(family);
            String variantKey = normalise(variant);
            return new VariantSelection(trimmed, family, variant, familyKey, variantKey);
        }

        private static int findSeparator(String value) {
            int colon = value.indexOf(':');
            if (colon > 0) {
                return colon;
            }
            int slash = value.indexOf('/');
            if (slash > 0) {
                return slash;
            }
            int dot = value.indexOf('.');
            return dot > 0 ? dot : -1;
        }

        private static String normalise(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }

        String display() {
            return rawInput;
        }
    }
}
