package sh.harold.fulcrum.minigame.defaults;

import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardBuilder;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardService;
import sh.harold.fulcrum.api.message.scoreboard.module.DynamicContentProvider;
import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameAttributes;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.state.context.StateContext;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper for managing the default pre-lobby scoreboard.
 */
public final class PreLobbyScoreboard {

    private static final String SCOREBOARD_ATTRIBUTE = "minigame.preLobby.scoreboardId";
    private static final long DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(1).toMillis();

    private PreLobbyScoreboard() {
    }

    public static void apply(StateContext context, AtomicInteger remainingSeconds) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(remainingSeconds, "remainingSeconds");

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }

        ScoreboardService scoreboardService = locator.findService(ScoreboardService.class).orElse(null);
        if (scoreboardService == null) {
            return;
        }

        if (resolveScoreboardId(context).isPresent()) {
            return;
        }

        Optional<String> slotOpt = findSlotId(context);
        if (slotOpt.isEmpty()) {
            context.scheduleTask(() -> apply(context, remainingSeconds), 10L);
            return;
        }

        String scoreboardId = buildScoreboardId(context.getMatchId());
        if (!scoreboardService.isScoreboardRegistered(scoreboardId)) {
            ScoreboardDefinition definition = buildDefinition(scoreboardId, context, remainingSeconds);
            scoreboardService.registerScoreboard(scoreboardId, definition);
        }

        context.setAttribute(SCOREBOARD_ATTRIBUTE, scoreboardId);
        context.forEachPlayer(player -> scoreboardService.showScoreboard(player.getUniqueId(), scoreboardId));
        refresh(context);
    }

    public static void showToPlayer(StateContext context, UUID playerId) {
        if (context == null || playerId == null) {
            return;
        }
        String scoreboardId = resolveScoreboardId(context).orElse(null);
        if (scoreboardId == null) {
            return;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }
        ScoreboardService scoreboardService = locator.findService(ScoreboardService.class).orElse(null);
        if (scoreboardService == null) {
            return;
        }
        scoreboardService.showScoreboard(playerId, scoreboardId);
    }

    public static void hideFromPlayer(StateContext context, UUID playerId) {
        if (context == null || playerId == null) {
            return;
        }
        String scoreboardId = resolveScoreboardId(context).orElse(null);
        if (scoreboardId == null) {
            return;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }
        ScoreboardService scoreboardService = locator.findService(ScoreboardService.class).orElse(null);
        if (scoreboardService == null) {
            return;
        }
        scoreboardService.hideScoreboard(playerId);
    }

    public static void refresh(StateContext context) {
        if (context == null) {
            return;
        }
        String scoreboardId = resolveScoreboardId(context).orElse(null);
        if (scoreboardId == null) {
            return;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return;
        }
        ScoreboardService scoreboardService = locator.findService(ScoreboardService.class).orElse(null);
        if (scoreboardService == null) {
            return;
        }
        for (UUID playerId : context.getActivePlayers()) {
            scoreboardService.refreshPlayerScoreboard(playerId);
        }
    }

    public static void teardown(StateContext context) {
        if (context == null) {
            return;
        }
        Optional<String> scoreboardIdOpt = resolveScoreboardId(context);
        if (scoreboardIdOpt.isEmpty()) {
            return;
        }

        String scoreboardId = scoreboardIdOpt.get();
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            context.removeAttribute(SCOREBOARD_ATTRIBUTE);
            return;
        }

        ScoreboardService scoreboardService = locator.findService(ScoreboardService.class).orElse(null);
        if (scoreboardService != null) {
            for (UUID playerId : context.getActivePlayers()) {
                scoreboardService.hideScoreboard(playerId);
            }
            if (scoreboardService.isScoreboardRegistered(scoreboardId)) {
                scoreboardService.unregisterScoreboard(scoreboardId);
            }
        }
        context.removeAttribute(SCOREBOARD_ATTRIBUTE);
    }

    private static Optional<String> resolveScoreboardId(StateContext context) {
        return context.getAttributeOptional(SCOREBOARD_ATTRIBUTE, String.class);
    }

    private static String buildScoreboardId(UUID matchId) {
        return "minigame-prelobby-" + matchId;
    }

    private static ScoreboardDefinition buildDefinition(String scoreboardId,
                                                        StateContext context,
                                                        AtomicInteger remainingSeconds) {
        ScoreboardBuilder builder = new ScoreboardBuilder(scoreboardId)
                .title(resolveTitle(context))
                .headerLabel(resolveServerLabel(context));

        builder.module(new InfoModule(context));
        builder.module(new CountdownModule(remainingSeconds));
        builder.module(new ModeModule(context));

        return builder.build();
    }

    private static String resolveTitle(StateContext context) {
        String familyName = context.getRegistration()
                .map(MinigameRegistration::getDescriptor)
                .map(PreLobbyScoreboard::resolveDisplayName)
                .orElse("FAMILY");
        return "&e&l" + familyName.toUpperCase(Locale.ROOT);
    }

    private static String resolveDisplayName(SlotFamilyDescriptor descriptor) {
        Map<String, String> metadata = descriptor.getMetadata();
        if (metadata != null) {
            String display = metadata.get("displayName");
            if (display != null && !display.isBlank()) {
                return display;
            }
        }
        return descriptor.getFamilyId();
    }

    private static String resolveServerLabel(StateContext context) {
        Optional<String> slotOpt = findSlotId(context);
        if (slotOpt.isPresent()) {
            return slotOpt.get();
        }

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            ServerIdentifier identifier = locator.findService(ServerIdentifier.class).orElse(null);
            if (identifier != null) {
                String id = identifier.getServerId();
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
            }
        }

        return "unknown";
    }

    private static Map<String, String> slotMetadata(StateContext context) {
        return context.getAttributeOptional(MinigameAttributes.SLOT_METADATA, Map.class)
                .map(raw -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> casted = (Map<String, String>) raw;
                    return casted;
                })
                .orElse(Map.of());
    }

    private static String resolveMapDisplayName(StateContext context) {
        Map<String, String> metadata = slotMetadata(context);
        String metadataDisplay = metadata.get("worldDisplayName");
        if (metadataDisplay != null && !metadataDisplay.isBlank()) {
            return metadataDisplay;
        }

        String mapId = context.getAttributeOptional(MinigameAttributes.MATCH_ENVIRONMENT, MatchEnvironment.class)
                .map(MatchEnvironment::mapId)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> metadata.getOrDefault("mapId", null));

        if (mapId != null) {
            ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
            if (locator != null) {
                WorldService worldService = locator.findService(WorldService.class).orElse(null);
                if (worldService != null) {
                    Optional<LoadedWorld> worldOpt = worldService.getWorldByMapId(mapId);
                    if (worldOpt.isEmpty()) {
                        worldOpt = worldService.getWorldByName(mapId);
                    }
                    if (worldOpt.isPresent()) {
                        return worldOpt.get().getDisplayName();
                    }
                }
            }
        }

        return mapId != null ? mapId : "unknown";
    }

    private static String resolveVariant(StateContext context) {
        Map<String, String> metadata = slotMetadata(context);
        String variant = metadata.get("variant");
        if (variant != null && !variant.isBlank()) {
            return variant;
        }
        return context.getRegistration()
                .map(MinigameRegistration::getDescriptor)
                .map(SlotFamilyDescriptor::getMetadata)
                .map(meta -> meta.get("variant"))
                .filter(value -> value != null && !value.isBlank())
                .orElse("default");
    }

    private static Optional<String> findSlotId(StateContext context) {
        Optional<String> attr = context.getAttributeOptional(MinigameAttributes.SLOT_ID, String.class)
                .filter(value -> value != null && !value.isBlank());
        if (attr.isPresent()) {
            return attr;
        }

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return Optional.empty();
        }

        PlayerRouteRegistry registry = locator.findService(PlayerRouteRegistry.class).orElse(null);
        if (registry == null) {
            return Optional.empty();
        }

        for (UUID playerId : context.getActivePlayers()) {
            Optional<PlayerRouteRegistry.RouteAssignment> assignment = registry.get(playerId);
            if (assignment.isPresent()) {
                String slot = assignment.get().slotId();
                if (slot != null && !slot.isBlank()) {
                    return Optional.of(slot);
                }
            }
        }

        return Optional.empty();
    }

    private record InfoModule(DynamicContentProvider provider) implements ScoreboardModule {
            private InfoModule(StateContext provider) {
                this.provider = new DynamicContentProvider(playerId -> buildLines(provider), DEFAULT_REFRESH_INTERVAL);
            }

            private static List<String> buildLines(StateContext context) {
                List<String> lines = new ArrayList<>(2);
                String mapName = resolveMapDisplayName(context);
                long activePlayers = context.roster().activeCount();
                String maxPlayers = context.getRegistration()
                        .map(MinigameRegistration::getDescriptor)
                        .map(SlotFamilyDescriptor::getMaxPlayers)
                        .filter(value -> value > 0)
                        .map(String::valueOf)
                        .orElse("?");

                lines.add("&fMap: &a" + mapName);
                lines.add("&fPlayers: &a" + activePlayers + "&f/&a" + maxPlayers);
                return lines;
            }

            @Override
            public String getModuleId() {
                return "prelobby_info";
            }

            @Override
            public DynamicContentProvider getContentProvider() {
                return provider;
            }
        }

    private static final class CountdownModule implements ScoreboardModule {
        private final AtomicInteger remainingSeconds;
        private final DynamicContentProvider provider;

        private CountdownModule(AtomicInteger remainingSeconds) {
            this.remainingSeconds = remainingSeconds;
            this.provider = new DynamicContentProvider(playerId -> List.of(buildLine(this.remainingSeconds)),
                    DEFAULT_REFRESH_INTERVAL);
        }

        private static String buildLine(AtomicInteger remainingSeconds) {
            int seconds = Math.max(remainingSeconds.get() + 1, 0);
            int minutesPart = seconds / 60;
            int secondsPart = seconds % 60;
            String formatted = String.format("%02d:%02d", minutesPart, secondsPart);
            return "&fStarting in: &a" + formatted;
        }

        @Override
        public String getModuleId() {
            return "prelobby_countdown";
        }

        @Override
        public DynamicContentProvider getContentProvider() {
            return provider;
        }
    }

    private record ModeModule(DynamicContentProvider provider) implements ScoreboardModule {
            private ModeModule(StateContext provider) {
                this.provider = new DynamicContentProvider(playerId -> List.of(buildLine(provider)),
                        DEFAULT_REFRESH_INTERVAL);
            }

            private static String buildLine(StateContext context) {
                String variant = resolveVariant(context);
                return "&fMode: &a" + variant;
            }

            @Override
            public String getModuleId() {
                return "prelobby_mode";
            }

            @Override
            public DynamicContentProvider getContentProvider() {
                return provider;
            }
        }
}
