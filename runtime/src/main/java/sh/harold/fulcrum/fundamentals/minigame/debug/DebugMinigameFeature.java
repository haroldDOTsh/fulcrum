package sh.harold.fulcrum.fundamentals.minigame.debug;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.api.slot.SlotFamilyProvider;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlag;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.actionflag.FlagBundle;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyFilter;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.defaults.DefaultPreLobbyState;
import sh.harold.fulcrum.minigame.defaults.PreLobbyOptions;
import sh.harold.fulcrum.minigame.state.AbstractMinigameState;
import sh.harold.fulcrum.minigame.state.context.StateContext;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal debug minigame that exercises state transitions and action flags.
 */
public final class DebugMinigameFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(DebugMinigameFeature.class.getName());
    private static final String FAMILY_ID = "debug";
    private static final String VARIANT_ID = "pipeline";
    private static final SlotFamilyDescriptor DESCRIPTOR = SlotFamilyDescriptor.builder(FAMILY_ID, 1, 4)
            .putMetadata("category", "debug")
            .putMetadata("description", "Debug minigame pipeline verification")
            .putMetadata("mapId", "test")
            .putMetadata("variant", VARIANT_ID)
            .putMetadata("preLobbySchematic", "prelobby")
            .putMetadata("preLobbyOffset", "120")
            .build();
    private static final SlotFamilyProvider PROVIDER = () -> List.of(DESCRIPTOR);
    private static final String STATE_PLAY_A = "play_a";
    private static final String STATE_PLAY_B = "play_b";

    private SlotFamilyService slotFamilyService;

    @Override
    public int getPriority() {
        return 230;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        slotFamilyService = container.getOptional(SlotFamilyService.class).orElse(null);
        MinigameEngine engine = container.getOptional(MinigameEngine.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(MinigameEngine.class).orElse(null)
                        : null);
        ActionFlagService flagService = container.getOptional(ActionFlagService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(ActionFlagService.class).orElse(null)
                        : null);

        if (engine == null || flagService == null) {
            LOGGER.warning("Minigame engine or action flag service unavailable; skipping debug pipeline bootstrap.");
            return;
        }

        registerDebugBundles(flagService);

        engine.registerRegistration(new MinigameRegistration(FAMILY_ID, DESCRIPTOR, buildBlueprint()));
        LOGGER.info("Registered debug minigame blueprint under family '" + FAMILY_ID + "'.");

        if (slotFamilyService != null) {
            slotFamilyService.registerDynamicProvider(PROVIDER);
            slotFamilyService.refreshDescriptors(SlotFamilyFilter.allowAll());
        }
    }

    @Override
    public void shutdown() {
        if (slotFamilyService != null) {
            slotFamilyService.unregisterDynamicProvider(PROVIDER);
        }
    }

    private void registerDebugBundles(ActionFlagService service) {
        service.registerContext(bundleId("play_a"),
                FlagBundle.of(bundleId("play_a"), EnumSet.of(ActionFlag.BLOCK_BREAK, ActionFlag.BLOCK_PLACE, ActionFlag.GAMEMODE))
                        .withGamemode(org.bukkit.GameMode.SURVIVAL));
        service.registerContext(bundleId("play_b"),
                FlagBundle.of(bundleId("play_b"), EnumSet.of(ActionFlag.BLOCK_PLACE, ActionFlag.GAMEMODE))
                        .withGamemode(org.bukkit.GameMode.SURVIVAL));
    }

    private String bundleId(String suffix) {
        return "debug:" + suffix;
    }

    private MinigameBlueprint buildBlueprint() {
        PreLobbyOptions preLobby = new PreLobbyOptions()
                .minimumPlayers(1)
                .countdownSeconds(30);

        MinigameBlueprint.CustomBuilder builder = MinigameBlueprint.custom();

        builder.addState(MinigameBlueprint.STATE_PRE_LOBBY,
                () -> new DefaultPreLobbyState(preLobby, STATE_PLAY_A));

        builder.addState(STATE_PLAY_A,
                () -> new FirstPlayState(bundleId(STATE_PLAY_A), STATE_PLAY_B));
        builder.addState(STATE_PLAY_B,
                () -> new SecondPlayState(bundleId(STATE_PLAY_B), MinigameBlueprint.STATE_END_GAME));
        builder.addState(MinigameBlueprint.STATE_END_GAME, EndState::new);

        builder.startState(MinigameBlueprint.STATE_PRE_LOBBY);
        return builder.build();
    }

    private static final class FirstPlayState extends AbstractMinigameState {
        private final String contextId;
        private final String nextStateId;
        private Listener listener;

        private FirstPlayState(String contextId, String nextStateId) {
            this.contextId = contextId;
            this.nextStateId = nextStateId;
        }

        @Override
        public void onEnter(StateContext context) {
            context.applyFlagContext(contextId);
            context.broadcast("Type 'a' to advance to the next stage.");
            registerListener(context);
        }

        @Override
        public void onExit(StateContext context) {
            unregisterListener();
        }

        private void registerListener(StateContext context) {
            unregisterListener();
            listener = new ChatListener(context);
            Bukkit.getPluginManager().registerEvents(listener, context.getPlugin());
        }

        private void unregisterListener() {
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
        }

        private final class ChatListener implements Listener {
            private final StateContext context;

            private ChatListener(StateContext context) {
                this.context = context;
            }

            @EventHandler(ignoreCancelled = true)
            public void onChat(AsyncPlayerChatEvent event) {
                UUID playerId = event.getPlayer().getUniqueId();
                if (!context.getActivePlayers().contains(playerId)) {
                    return;
                }
                if (!event.getMessage().equalsIgnoreCase("a")) {
                    return;
                }
                BukkitScheduler scheduler = context.getPlugin().getServer().getScheduler();
                scheduler.runTask(context.getPlugin(), () -> {
                    context.broadcast("Advancing to Stage B!");
                    context.requestTransition(nextStateId);
                });
            }
        }
    }

    private static final class SecondPlayState extends AbstractMinigameState {
        private static final long RESPAWN_DELAY = 200L; // 10 seconds
        private final String contextId;
        private final String endStateId;
        private Listener listener;

        private SecondPlayState(String contextId, String endStateId) {
            this.contextId = contextId;
            this.endStateId = endStateId;
        }

        @Override
        public void onEnter(StateContext context) {
            context.applyFlagContext(contextId);
            context.broadcast("Stage B: type 'b' to finish, 'c' to eliminate, 'd' for delayed respawn.");
            registerListener(context);
        }

        @Override
        public void onExit(StateContext context) {
            unregisterListener();
        }

        private void registerListener(StateContext context) {
            unregisterListener();
            listener = new ChatListener(context);
            Bukkit.getPluginManager().registerEvents(listener, context.getPlugin());
        }

        private void unregisterListener() {
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
        }

        private final class ChatListener implements Listener {
            private final StateContext context;

            private ChatListener(StateContext context) {
                this.context = context;
            }

            @EventHandler(ignoreCancelled = true)
            public void onChat(AsyncPlayerChatEvent event) {
                UUID playerId = event.getPlayer().getUniqueId();
                if (!context.getActivePlayers().contains(playerId)) {
                    return;
                }
                String message = event.getMessage().toLowerCase();
                BukkitScheduler scheduler = context.getPlugin().getServer().getScheduler();
                switch (message) {
                    case "b" -> scheduler.runTask(context.getPlugin(), () -> {
                        context.broadcast("Match complete!");
                        context.markMatchComplete();
                        context.requestTransition(endStateId);
                    });
                    case "c" -> scheduler.runTask(context.getPlugin(), () -> {
                        context.broadcast("Player eliminated: " + event.getPlayer().getName());
                        context.eliminatePlayer(playerId, false, 0L);
                        context.markMatchComplete();
                        context.requestTransition(endStateId);
                    });
                    case "d" -> scheduler.runTask(context.getPlugin(), () -> {
                        context.broadcast("Player eliminated for 10 seconds: " + event.getPlayer().getName());
                        context.eliminatePlayer(playerId, true, RESPAWN_DELAY);
                    });
                    default -> {
                    }
                }
            }
        }
    }

    private static final class EndState extends AbstractMinigameState {
        @Override
        public void onEnter(StateContext context) {
            context.broadcast("Debug match concluded.");
            context.markMatchComplete();
        }
    }
}
