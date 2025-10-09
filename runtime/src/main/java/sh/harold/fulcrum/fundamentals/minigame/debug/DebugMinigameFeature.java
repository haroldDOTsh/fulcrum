package sh.harold.fulcrum.fundamentals.minigame.debug;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.api.slot.SlotFamilyProvider;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyFilter;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.defaults.BaseInGameHandler;
import sh.harold.fulcrum.minigame.defaults.DefaultStates;
import sh.harold.fulcrum.minigame.state.context.StateContext;

/**
 * Minimal debug minigame that exercises the shared pipeline.
 */
public final class DebugMinigameFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(DebugMinigameFeature.class.getName());
    private static final String FAMILY_ID = "debug_pipeline";
    private static final SlotFamilyDescriptor DESCRIPTOR = SlotFamilyDescriptor.builder(FAMILY_ID, 1, 4)
        .putMetadata("category", "debug")
        .putMetadata("description", "Debug minigame pipeline verification")
        .putMetadata("mapId", "test")
        .putMetadata("preLobbySchematic", "prelobby")
        .putMetadata("preLobbyOffset", "120")
        .build();
    private static final SlotFamilyProvider PROVIDER = () -> List.of(DESCRIPTOR);

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

        if (engine == null) {
            LOGGER.warning("Minigame engine unavailable; skipping debug pipeline bootstrap.");
            return;
        }

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

    private MinigameBlueprint buildBlueprint() {
        MinigameBlueprint.StandardBuilder builder = MinigameBlueprint.standard();
        builder.preLobby(options -> options
            .minimumPlayers(1)
            .countdownSeconds(60));
        builder.inGame(new BaseInGameHandler() {
            @Override
            public void onMatchStart(StateContext context) {
                context.scheduleTask(() -> {
                    context.markMatchComplete();
                }, 100L);
            }
        });
        return builder.build();
    }
}
