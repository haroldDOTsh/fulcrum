package sh.harold.fulcrum.minigame;

import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.minigame.data.MinigameCollection;
import sh.harold.fulcrum.minigame.data.MinigameDataRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Metadata binding a slot family to a blueprint and configuration.
 */
public final class MinigameRegistration {
    private static final String PRE_LOBBY_PROP_KEY = "preLobbyProp";
    private static final String PRE_LOBBY_OFFSET_KEY = "preLobbyOffset";
    private static final String DEFAULT_PRE_LOBBY_PROP = "prelobby";
    private static final int DEFAULT_PRE_LOBBY_OFFSET = 50;

    private final String familyId;
    private final SlotFamilyDescriptor descriptor;
    private final MinigameBlueprint blueprint;
    private final Consumer<RegistrationContext> registrationHandler;

    public MinigameRegistration(String familyId,
                                SlotFamilyDescriptor descriptor,
                                MinigameBlueprint blueprint) {
        this(familyId, descriptor, blueprint, null);
    }

    public MinigameRegistration(String familyId,
                                SlotFamilyDescriptor descriptor,
                                MinigameBlueprint blueprint,
                                Consumer<RegistrationContext> registrationHandler) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.registrationHandler = registrationHandler;
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

    public Optional<Consumer<RegistrationContext>> getRegistrationHandler() {
        return Optional.ofNullable(registrationHandler);
    }

    /**
     * Resolve the prop identifier to use for the pre-game lobby cage.
     * Providers can disable the lobby by setting the metadata value to "none" or "disabled".
     */
    public Optional<String> getPreLobbyPropName() {
        String value = descriptor.getMetadata().get(PRE_LOBBY_PROP_KEY);
        if (value == null || value.isBlank()) {
            return Optional.of(DEFAULT_PRE_LOBBY_PROP);
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

    public static final class RegistrationContext {
        private final String familyId;
        private final MinigameDataRegistry dataRegistry;
        private final MinigameCollection<?> defaultCollection;

        public RegistrationContext(String familyId,
                                   MinigameDataRegistry dataRegistry,
                                   MinigameCollection<?> defaultCollection) {
            this.familyId = Objects.requireNonNull(familyId, "familyId");
            this.dataRegistry = Objects.requireNonNull(dataRegistry, "dataRegistry");
            this.defaultCollection = defaultCollection;
        }

        public String familyId() {
            return familyId;
        }

        public MinigameDataRegistry collections() {
            return dataRegistry;
        }

        public Optional<MinigameCollection<?>> defaultCollection() {
            return Optional.ofNullable(defaultCollection);
        }
    }
}
