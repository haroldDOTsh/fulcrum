package sh.harold.fulcrum.minigame;

import java.util.Collection;
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.slot.SlotFamilyProvider;

/**
 * Marker for Fulcrum modules that expose minigame blueprints.
 */
public interface MinigameModule extends FulcrumModule, SlotFamilyProvider {

    /**
     * Register blueprints and slot families with the engine.
     *
     * @param engine active minigame engine
     * @return registrations to attach to the engine
     */
    Collection<MinigameRegistration> registerMinigames(MinigameEngine engine);
}
