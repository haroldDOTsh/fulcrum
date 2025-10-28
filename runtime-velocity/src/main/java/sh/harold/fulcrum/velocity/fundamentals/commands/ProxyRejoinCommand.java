package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ProxyRejoinCommand implements SimpleCommand {
    private static final Duration REJOIN_TTL = Duration.ofSeconds(120);
    private static final Duration COOLDOWN = Duration.ofSeconds(5);
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private final ProxyServer proxy;
    private final PlayerRoutingFeature routingFeature;
    private final VelocityPlayerSessionService sessionService;
    private final Logger logger;

    ProxyRejoinCommand(ProxyServer proxy,
                       PlayerRoutingFeature routingFeature,
                       VelocityPlayerSessionService sessionService,
                       Logger logger) {
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.sessionService = sessionService;
        this.logger = logger;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use /rejoin.", NamedTextColor.RED));
            return;
        }

        if (isOnCooldown(player)) {
            long remaining = cooldownRemaining(player);
            player.sendMessage(Component.text("Please wait " + remaining + "s before using /rejoin again.", NamedTextColor.RED));
            return;
        }

        Optional<PlayerSessionRecord> sessionOpt = sessionService.getSession(player.getUniqueId());
        if (sessionOpt.isEmpty()) {
            player.sendMessage(Component.text("We could not find any recent match to rejoin.", NamedTextColor.RED));
            return;
        }

        Optional<RejoinSnapshot> snapshotOpt = resolveSnapshot(sessionOpt.get());
        if (snapshotOpt.isEmpty()) {
            player.sendMessage(Component.text("You do not have a match to rejoin.", NamedTextColor.RED));
            return;
        }

        RejoinSnapshot snapshot = snapshotOpt.get();
        if (!"pre_lobby".equalsIgnoreCase(snapshot.state())) {
            player.sendMessage(Component.text("Your previous match has already started or ended.", NamedTextColor.RED));
            return;
        }

        long age = System.currentTimeMillis() - snapshot.updatedAt();
        if (age > REJOIN_TTL.toMillis()) {
            player.sendMessage(Component.text("The window to rejoin that match has expired.", NamedTextColor.RED));
            return;
        }

        String familyId = snapshot.familyId();
        String slotId = snapshot.slotId();
        String variantId = snapshot.variantId();
        if (familyId == null || slotId == null) {
            player.sendMessage(Component.text("We were unable to locate the match to rejoin.", NamedTextColor.RED));
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "velocity-rejoin-command");
        metadata.put("initiator", player.getUsername());
        metadata.put("requestedAt", Long.toString(System.currentTimeMillis()));
        metadata.put("family", familyId);
        if (variantId != null && !variantId.isBlank()) {
            metadata.put("variant", variantId);
        }
        metadata.put("rejoinSlotId", slotId);
        metadata.put("rejoin", "true");

        routingFeature.sendSlotRequest(player, familyId, metadata)
                .whenComplete((requestId, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Failed to submit rejoin request for {}", player.getUsername(), throwable);
                        player.sendMessage(Component.text("We could not contact the matchmaker. Try again in a moment.", NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text("Attempting to rejoin your previous match...", NamedTextColor.GREEN));
                    COOLDOWNS.put(player.getUniqueId(), System.currentTimeMillis());
                });
    }

    private Optional<RejoinSnapshot> resolveSnapshot(PlayerSessionRecord record) {
        Object raw = record.getMinigames().get("rejoin");
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        String family = asString(map.get("family"));
        String variant = asString(map.get("variant"));
        String slotId = asString(map.get("slotId"));
        String state = asString(map.get("state"));
        long updatedAt = parseLong(map.get("updatedAt"));
        if (family == null || slotId == null || state == null) {
            return Optional.empty();
        }
        return Optional.of(new RejoinSnapshot(family, variant, slotId, updatedAt, state));
    }

    private boolean isOnCooldown(Player player) {
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - last;
        return elapsed < COOLDOWN.toMillis();
    }

    private long cooldownRemaining(Player player) {
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long remainingMs = COOLDOWN.toMillis() - (System.currentTimeMillis() - last);
        return Math.max(1L, remainingMs / 1000L);
    }

    private record RejoinSnapshot(String familyId, String variantId, String slotId, long updatedAt, String state) {
    }
}
