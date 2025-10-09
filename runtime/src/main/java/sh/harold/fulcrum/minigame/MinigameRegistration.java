package sh.harold.fulcrum.minigame;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;

/**
 * Metadata binding a slot family to a blueprint and configuration.
 */
public final class MinigameRegistration {
    private static final String PRE_LOBBY_SCHEMATIC_KEY = "preLobbySchematic";
    private static final String PRE_LOBBY_OFFSET_KEY = "preLobbyOffset";
    private static final String DEFAULT_PRE_LOBBY_SCHEMATIC = "prelobby";
    private static final int DEFAULT_PRE_LOBBY_OFFSET = 50;

    private final String familyId;
    private final SlotFamilyDescriptor descriptor;
    private final MinigameBlueprint blueprint;

    public MinigameRegistration(String familyId,
                                SlotFamilyDescriptor descriptor,
                                MinigameBlueprint blueprint) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
    }

    public String getFamilyId() {
        return familyId;
    }

    public SlotFamilyDescriptor getDescriptor() {
        return descriptor;
    }

    public MinigameBlueprint getBlueprint() {
        return blueprint;
    }

    /**
     * Resolve the schematic identifier to use for the pre-game lobby cage.
     * Providers can disable the lobby by setting the metadata value to "none" or "disabled".
     */
    public Optional<String> getPreLobbySchematicId() {
        String value = descriptor.getMetadata().get(PRE_LOBBY_SCHEMATIC_KEY);
        if (value == null || value.isBlank()) {
            return Optional.of(DEFAULT_PRE_LOBBY_SCHEMATIC);
        }
        String normalised = value.trim();
        String lower = normalised.toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || "disabled".equals(lower)) {
            return Optional.empty();
        }
        return Optional.of(normalised);
    }

    /**
     * Height offset (in blocks) above the map origin where the pre-lobby should be pasted.
     */
    public int getPreLobbyHeightOffset() {
        String value = descriptor.getMetadata().get(PRE_LOBBY_OFFSET_KEY);
        if (value == null || value.isBlank()) {
            return DEFAULT_PRE_LOBBY_OFFSET;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return DEFAULT_PRE_LOBBY_OFFSET;
        }
    }
}

