package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Proxy-level /play command that queues a player for a specific family variant.
 * Mirrors the runtime behaviour while ensuring the request is routed through the registry.
 */
final class ProxyPlayCommand implements SimpleCommand {
    private static final Pattern SEPARATOR = Pattern.compile("[:/._-]");
    private static final Pattern INVALID_TOKEN_CHARS = Pattern.compile("[^a-z0-9_-]");
    private static final Duration COOLDOWN = Duration.ofSeconds(5);
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private final ProxyServer proxy;
    private final PlayerRoutingFeature routingFeature;
    private final SlotFamilyCache familyCache;
    private final FulcrumVelocityPlugin plugin;
    private final Logger logger;

    ProxyPlayCommand(ProxyServer proxy,
                     PlayerRoutingFeature routingFeature,
                     SlotFamilyCache familyCache,
                     FulcrumVelocityPlugin plugin,
                     Logger logger) {
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.familyCache = familyCache;
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use /play.", NamedTextColor.RED));
            return;
        }

        VariantSelection selection = parseSelection(invocation.arguments());
        if (selection == null) {
            sendUsage(source);
            return;
        }

        String familyId = selection.familyId();
        String variantId = selection.variantId();

        if (!familyCache.hasFamily(familyId)) {
            source.sendMessage(Component.text("No backend registered for family '" + familyId + "'.", NamedTextColor.RED));
            return;
        }

        Set<String> knownVariants = familyCache.variants(familyId);
        if (!knownVariants.isEmpty() && !knownVariants.contains(variantId)) {
            source.sendMessage(Component.text(
                    "Variant '" + variantId + "' is not currently advertised for " + familyId + ".",
                    NamedTextColor.RED));
            return;
        }

        if (isOnCooldown(player)) {
            long remaining = cooldownRemaining(player);
            source.sendMessage(Component.text("Please wait " + remaining + "s before using /play again.", NamedTextColor.RED));
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "velocity-play-command");
        metadata.put("initiator", player.getUsername());
        metadata.put("requestedAt", Long.toString(System.currentTimeMillis()));
        metadata.put("family", familyId);
        metadata.put("variant", variantId);

        routingFeature.sendSlotRequest(player, familyId, metadata)
                .whenComplete((requestId, throwable) -> proxy.getScheduler().buildTask(plugin, () -> {
                    if (throwable != null) {
                        logger.warn("Failed to queue {} for {}:{}",
                                player.getUsername(), familyId, variantId, throwable);
                        player.sendMessage(Component.text(
                                "Unable to queue you right now. Please try again.",
                                NamedTextColor.RED));
                        return;
                    }

                    player.sendMessage(Component.text(
                            "Queued for " + familyId + " (" + variantId + ").",
                            NamedTextColor.GREEN));
                    COOLDOWNS.put(player.getUniqueId(), System.currentTimeMillis());
                    logger.debug("Submitted play request {} for {} -> {}",
                            requestId, player.getUsername(), familyId + ":" + variantId);
                }).schedule());
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
            return 0;
        }
        long remainingMs = COOLDOWN.toMillis() - (System.currentTimeMillis() - last);
        return Math.max(1, remainingMs / 1000);
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text("Usage: /play <family> <variant>", NamedTextColor.RED));
        source.sendMessage(Component.text("Example: /play bedwars four_four", NamedTextColor.GRAY));
        source.sendMessage(Component.text("Example: /play bedwars_four_four", NamedTextColor.GRAY));
    }

    private VariantSelection parseSelection(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return null;
        }
        if (arguments.length >= 2) {
            String family = sanitiseToken(normalise(arguments[0]));
            String variant = sanitiseToken(normalise(joinTail(arguments)));
            if (family.isEmpty() || variant.isEmpty()) {
                return null;
            }
            return new VariantSelection(family, variant);
        }

        String token = normalise(arguments[0]);
        if (token.isBlank()) {
            return null;
        }
        Matcher matcher = SEPARATOR.matcher(token);
        if (!matcher.find()) {
            return null;
        }
        int index = matcher.start();
        String family = sanitiseToken(token.substring(0, index));
        String variant = sanitiseToken(token.substring(index + 1));
        if (family.isEmpty() || variant.isEmpty()) {
            return null;
        }
        return new VariantSelection(family, variant);
    }

    private String joinTail(String[] arguments) {
        if (arguments.length <= 1) {
            return "";
        }
        return Arrays.stream(arguments, 1, arguments.length)
                .map(this::normalise)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("_"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments == null || arguments.length == 0) {
            return suggestFamilies("");
        }

        if (arguments.length == 1) {
            String token = normalise(arguments[0]);
            if (token.isBlank()) {
                return suggestFamilies("");
            }

            Matcher matcher = SEPARATOR.matcher(token);
            if (matcher.find()) {
                String family = sanitiseToken(token.substring(0, matcher.start()));
                String variant = sanitiseToken(token.substring(matcher.start() + 1));
                if (family.isEmpty()) {
                    return suggestFamilies(variant);
                }
                return suggestCombinedVariants(family, variant, token.charAt(matcher.start()));
            }

            String familyPrefix = sanitiseToken(token);
            LinkedHashSet<String> suggestions = new LinkedHashSet<>();
            suggestions.addAll(suggestFamilies(familyPrefix));
            if (!familyPrefix.isEmpty() && familyCache.hasFamily(familyPrefix)) {
                suggestions.addAll(suggestCombinedVariants(familyPrefix, "", ' '));
            }
            return List.copyOf(suggestions);
        }

        String family = sanitiseToken(normalise(arguments[0]));
        if (family.isEmpty()) {
            return suggestFamilies("");
        }

        String variant = sanitiseToken(normalise(joinTail(arguments)));
        return suggestVariantTokens(family, variant);
    }

    private String normalise(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitiseToken(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String cleaned = INVALID_TOKEN_CHARS.matcher(input).replaceAll("");
        int start = 0;
        int end = cleaned.length();
        while (start < end && isEdgeSeparator(cleaned.charAt(start))) {
            start++;
        }
        while (end > start && isEdgeSeparator(cleaned.charAt(end - 1))) {
            end--;
        }
        return start >= end ? "" : cleaned.substring(start, end);
    }

    private List<String> suggestFamilies(String prefix) {
        Set<String> families = familyCache.families();
        if (families.isEmpty()) {
            return List.of();
        }
        String effectivePrefix = prefix == null ? "" : prefix;
        return families.stream()
                .filter(family -> family.startsWith(effectivePrefix))
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    private List<String> suggestVariantTokens(String family, String prefix) {
        if (family == null || family.isEmpty()) {
            return List.of();
        }
        Set<String> variants = familyCache.variants(family);
        if (variants.isEmpty()) {
            return List.of();
        }
        String effectivePrefix = prefix == null ? "" : prefix;
        return variants.stream()
                .filter(variant -> variant.startsWith(effectivePrefix))
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    private List<String> suggestCombinedVariants(String family, String prefix, char separator) {
        if (family == null || family.isEmpty()) {
            return List.of();
        }
        Set<String> variants = familyCache.variants(family);
        if (variants.isEmpty()) {
            return List.of();
        }
        String effectivePrefix = prefix == null ? "" : prefix;
        String joiner = separator == ' ' ? " " : Character.toString(separator);
        return variants.stream()
                .filter(variant -> variant.startsWith(effectivePrefix))
                .map(variant -> family + joiner + variant)
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    private boolean isEdgeSeparator(char character) {
        return character == '_' || character == '-';
    }

    private record VariantSelection(String familyId, String variantId) {
    }
}
