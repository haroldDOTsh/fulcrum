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
import sh.harold.fulcrum.common.cooldown.CooldownAcquisition;
import sh.harold.fulcrum.common.cooldown.CooldownKey;
import sh.harold.fulcrum.common.cooldown.CooldownKeys;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;
import sh.harold.fulcrum.common.cooldown.CooldownSpec;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Brigadier command that lets players join a minigame variant via the registry pipeline.
 */
public final class PlayCommand {
    private static final Duration COOLDOWN = Duration.ofSeconds(5);
    private static final CooldownSpec PLAY_COOLDOWN = CooldownSpec.rejecting(COOLDOWN);
    private static final String COOLDOWN_NAMESPACE = "minigame";
    private static final String DEFAULT_COOLDOWN_GROUP = "play-command";

    private final MessageBus messageBus;
    private final PlayerRouteRegistry routeRegistry;
    private final SimpleSlotOrchestrator orchestrator;
    private final ServerIdentifier serverIdentifier;
    private final ServerLifecycleFeature lifecycleFeature;
    private final MinigameEngine engine;
    private final CooldownRegistry cooldownRegistry;

    public PlayCommand(MessageBus messageBus,
                       PlayerRouteRegistry routeRegistry,
                       SimpleSlotOrchestrator orchestrator,
                       ServerIdentifier serverIdentifier,
                       ServerLifecycleFeature lifecycleFeature,
                       MinigameEngine engine) {
        this(messageBus,
                routeRegistry,
                orchestrator,
                serverIdentifier,
                lifecycleFeature,
                engine,
                resolveCooldownRegistry());
    }

    public PlayCommand(MessageBus messageBus,
                       PlayerRouteRegistry routeRegistry,
                       SimpleSlotOrchestrator orchestrator,
                       ServerIdentifier serverIdentifier,
                       ServerLifecycleFeature lifecycleFeature,
                       MinigameEngine engine,
                       CooldownRegistry cooldownRegistry) {
        this.messageBus = messageBus;
        this.routeRegistry = routeRegistry;
        this.orchestrator = orchestrator;
        this.serverIdentifier = serverIdentifier;
        this.lifecycleFeature = lifecycleFeature;
        this.engine = engine;
        this.cooldownRegistry = cooldownRegistry;
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

        if (!acquireCooldown(player, familyId)) {
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
        metadata.put("requestedAt", Long.toString(System.currentTimeMillis()));
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

    private boolean acquireCooldown(Player player, String familyId) {
        if (cooldownRegistry == null) {
            return true;
        }
        String cooldownGroup = (familyId == null || familyId.isBlank()) ? DEFAULT_COOLDOWN_GROUP : familyId;
        CooldownKey key = CooldownKeys.playerScoped(COOLDOWN_NAMESPACE, cooldownGroup, player.getUniqueId());
        CooldownAcquisition acquisition = cooldownRegistry.acquire(key, PLAY_COOLDOWN)
                .toCompletableFuture()
                .join();
        if (acquisition instanceof CooldownAcquisition.Rejected(Duration remaining)) {
            long seconds = Math.max(1, (long) Math.ceil(remaining.toMillis() / 1000.0D));
            Message.error("play.cooldown", seconds).send(player);
            return false;
        }
        return true;
    }

    private static CooldownRegistry resolveCooldownRegistry() {
        return Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .flatMap(locator -> locator.findService(CooldownRegistry.class))
                .orElse(null);
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

            String familyId = family.toLowerCase(Locale.ROOT);
            String variantId = variant.toLowerCase(Locale.ROOT);
            String familyKey = familyId.replace('-', '_');
            String variantKey = variantId.replace('-', '_');

            return new VariantSelection(trimmed, familyId, variantId, familyKey, variantKey);
        }

        String display() {
            if (rawInput == null) {
                return "unknown";
            }
            return rawInput;
        }

        private static int findSeparator(String raw) {
            for (char separator : new char[]{':', '/', '.', '-'}) {
                int index = raw.indexOf(separator);
                if (index > 0) {
                    return index;
                }
            }
            return -1;
        }
    }
}
