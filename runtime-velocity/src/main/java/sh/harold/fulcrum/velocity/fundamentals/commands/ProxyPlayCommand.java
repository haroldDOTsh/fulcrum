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
import sh.harold.fulcrum.velocity.party.PartyReservationResult;
import sh.harold.fulcrum.velocity.party.PartyReservationService;
import sh.harold.fulcrum.velocity.party.PartyService;

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
    private final PartyService partyService;
    private final PartyReservationService reservationService;

    ProxyPlayCommand(ProxyServer proxy,
                     PlayerRoutingFeature routingFeature,
                     SlotFamilyCache familyCache,
                     FulcrumVelocityPlugin plugin,
                     Logger logger,
                     PartyService partyService,
                     PartyReservationService reservationService) {
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.familyCache = familyCache;
        this.plugin = plugin;
        this.logger = logger;
        this.partyService = partyService;
        this.reservationService = reservationService;
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

        if (!familyCache.hasFamily(familyId)) {
            source.sendMessage(Component.text("No backend registered for family '" + familyId + "'.", NamedTextColor.RED));
            return;
        }

        Set<String> knownVariants = familyCache.variants(familyId);
        String fallbackVariant = selection.variantToken();
        String variantId;
        if (knownVariants.isEmpty()) {
            variantId = fallbackVariant;
            if (variantId == null || variantId.isBlank()) {
                source.sendMessage(Component.text(
                        "Variant '" + selection.displayValue() + "' is not a valid identifier.",
                        NamedTextColor.RED));
                return;
            }
            logger.debug("Accepting play request for {}:{} without cached variants; awaiting registry status updates.",
                    familyId, variantId);
        } else {
            Optional<String> resolvedVariant = resolveVariant(familyId, selection, knownVariants);
            if (resolvedVariant.isEmpty()) {
                source.sendMessage(Component.text(
                        "Variant '" + selection.displayValue() + "' is not currently advertised for " + familyId + ".",
                        NamedTextColor.RED));
                return;
            }
            variantId = resolvedVariant.get();
        }

        if (isOnCooldown(player)) {
            long remaining = cooldownRemaining(player);
            source.sendMessage(Component.text("Please wait " + remaining + "s before using /play again.", NamedTextColor.RED));
            return;
        }

        if (handlePartyQueue(player, familyId, variantId)) {
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
                            "Queued for " + displayVariant(familyId, variantId) + ".",
                            NamedTextColor.GREEN));
                    COOLDOWNS.put(player.getUniqueId(), System.currentTimeMillis());
                    logger.debug("Submitted play request {} for {} -> {}",
                            requestId, player.getUsername(), familyId + ":" + variantId);
                }).schedule());
    }

    private boolean handlePartyQueue(Player leader, String familyId, String variantId) {
        if (partyService == null || reservationService == null) {
            return false;
        }

        Optional<sh.harold.fulcrum.api.party.PartySnapshot> partyOpt = partyService.getPartyByPlayer(leader.getUniqueId());
        if (partyOpt.isEmpty()) {
            return false;
        }

        sh.harold.fulcrum.api.party.PartySnapshot snapshot = partyOpt.get();
        if (!Objects.equals(snapshot.getLeaderId(), leader.getUniqueId())) {
            leader.sendMessage(Component.text("Only the party leader can use /play.", NamedTextColor.RED));
            return true;
        }

        if (snapshot.getSize() <= 1) {
            return false; // treat single-member party as solo queue
        }

        List<Player> participants = snapshot.getMembers().keySet().stream()
                .map(proxy::getPlayer)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        PartyReservationResult result = reservationService.reserveForPlay(snapshot, familyId, variantId, null, participants);
        if (!result.isSuccess()) {
            result.errorMessage().ifPresent(leader::sendMessage);
            return true;
        }

        COOLDOWNS.put(leader.getUniqueId(), System.currentTimeMillis());
        return true;
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
            return new VariantSelection(family, variant, variant);
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
        String rawVariant = sanitiseToken(token);
        return new VariantSelection(family, variant, rawVariant);
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

    private String displayVariant(String familyId, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return familyId;
        }
        return familyId + ":" + variantId;
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

    private Optional<String> resolveVariant(String familyId, VariantSelection selection, Set<String> knownVariants) {
        String token = sanitiseToken(selection.variantToken());
        String raw = sanitiseToken(selection.rawInput());
        for (String candidate : knownVariants) {
            VariantForms forms = variantForms(familyId, candidate);
            if (forms.matches(token, familyId) || forms.matches(raw, familyId)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
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
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (String variant : variants) {
            VariantForms forms = variantForms(family, variant);
            for (String base : forms.baseTokens()) {
                if (base.startsWith(effectivePrefix)) {
                    suggestions.add(base);
                }
            }
        }
        return List.copyOf(suggestions);
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
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (String variant : variants) {
            VariantForms forms = variantForms(family, variant);
            for (String base : forms.baseTokens()) {
                if (!base.startsWith(effectivePrefix)) {
                    continue;
                }
                suggestions.add(family + " " + base);
            }
        }
        return List.copyOf(suggestions);
    }

    private boolean isEdgeSeparator(char character) {
        return character == '_' || character == '-';
    }

    private VariantForms variantForms(String family, String variantId) {
        LinkedHashSet<String> baseTokens = new LinkedHashSet<>();
        String canonical = sanitiseToken(variantId);
        if (!canonical.isBlank()) {
            baseTokens.add(canonical);
        }
        for (char separator : new char[]{'_', '-', ':', '/', '.'}) {
            String prefix = family + separator;
            if (variantId.startsWith(prefix) && variantId.length() > prefix.length()) {
                String trimmed = sanitiseToken(variantId.substring(prefix.length()));
                if (!trimmed.isBlank()) {
                    baseTokens.add(trimmed);
                }
            }
        }
        return new VariantForms(canonical.isBlank() ? variantId : canonical, List.copyOf(baseTokens));
    }

    private record VariantForms(String canonical, List<String> baseTokens) {
        boolean matches(String candidate, String family) {
            if (candidate == null || candidate.isBlank()) {
                return false;
            }
            if (!canonical.isBlank() && candidate.equals(canonical)) {
                return true;
            }
            for (String token : baseTokens) {
                if (candidate.equals(token)) {
                    return true;
                }
                if (!family.isBlank()) {
                    if (candidate.equals(family + "_" + token)) {
                        return true;
                    }
                    if (candidate.equals(family + token)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private record VariantSelection(String familyId, String variantToken, String rawInput) {
        String displayValue() {
            return rawInput != null && !rawInput.isBlank() ? rawInput : variantToken;
        }
    }
}
