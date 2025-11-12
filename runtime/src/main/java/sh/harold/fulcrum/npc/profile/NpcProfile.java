package sh.harold.fulcrum.npc.profile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.*;

/**
 * Style + identity details for an NPC.
 */
public final class NpcProfile {
    private static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_DESCRIPTOR_LENGTH = 64;
    private static final int MAX_LORE_LINES = 6;
    private static final int MAX_LORE_LENGTH = 64;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();
    private static final String DEFAULT_DESCRIPTOR_COLOR = "&7";
    private static final String DEFAULT_INTERACTION_HINT = "&e&lCLICK &r&eto interact!";

    private final String displayName;
    private final String descriptor;
    private final List<String> lore;
    private final boolean interactable;
    private final boolean showInteractionHint;
    private final List<String> audioCues;
    private final NpcSkin skin;

    private NpcProfile(Builder builder) {
        this.displayName = sanitizeDisplayName(builder.displayName);
        this.descriptor = formatDescriptor(builder.description);
        this.lore = List.copyOf(formatLore(builder.lore));
        this.interactable = builder.interactable;
        this.showInteractionHint = builder.interactable && builder.showInteractionHint;
        this.audioCues = builder.audioCues.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(NpcProfile::translateColors)
                .toList();
        this.skin = Objects.requireNonNull(builder.skin, "skin");
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String sanitizeDisplayName(String raw) {
        String trimmed = normalize(raw);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("NPC display name is required");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("NPC display name exceeds " + MAX_NAME_LENGTH + " chars");
        }
        return translateColors(trimmed);
    }

    private static String formatDescriptor(String raw) {
        String trimmed = normalize(raw);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("NPC description is required");
        }
        String descriptor = DEFAULT_DESCRIPTOR_COLOR + "[" + trimmed + "]";
        if (descriptor.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException("NPC descriptor exceeds " + MAX_DESCRIPTOR_LENGTH + " chars");
        }
        return translateColors(descriptor);
    }

    private static List<String> formatLore(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        if (input.size() > MAX_LORE_LINES) {
            throw new IllegalArgumentException("NPC lore cannot exceed " + MAX_LORE_LINES + " lines");
        }
        List<String> formatted = new ArrayList<>(input.size());
        for (String line : input) {
            String sanitized = translateColors(normalize(line));
            if (sanitized.length() > MAX_LORE_LENGTH) {
                throw new IllegalArgumentException("NPC lore line exceeds " + MAX_LORE_LENGTH + " chars");
            }
            formatted.add(sanitized);
        }
        return formatted;
    }

    private static String normalize(String input) {
        return input == null ? "" : input.trim();
    }

    private static String translateColors(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public String displayName() {
        return displayName;
    }

    public Component displayNameComponent() {
        return LEGACY_SERIALIZER.deserialize(displayName);
    }

    public String descriptor() {
        return descriptor;
    }

    public Component descriptorComponent() {
        return LEGACY_SERIALIZER.deserialize(descriptor);
    }

    public List<String> lore() {
        return Collections.unmodifiableList(lore);
    }

    public List<Component> loreComponents() {
        return lore.stream()
                .map(LEGACY_SERIALIZER::deserialize)
                .map(Component.class::cast)
                .toList();
    }

    public boolean interactable() {
        return interactable;
    }

    public Optional<String> interactionHint() {
        if (!showInteractionHint) {
            return Optional.empty();
        }
        return Optional.of(translateColors(DEFAULT_INTERACTION_HINT));
    }

    public NpcSkin skin() {
        return skin;
    }

    public List<String> audioCues() {
        return Collections.unmodifiableList(audioCues);
    }

    public List<String> hologramLines() {
        List<String> lines = new ArrayList<>();
        lines.add(displayName);
        lines.add(descriptor);
        interactionHint().ifPresent(lines::add);
        lines.addAll(lore);
        return List.copyOf(lines);
    }

    public static final class Builder {
        private final List<String> lore = new ArrayList<>();
        private final List<String> audioCues = new ArrayList<>();
        private String displayName;
        private String description;
        private boolean interactable;
        private boolean showInteractionHint = true;
        private NpcSkin skin;

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addLoreLine(String loreLine) {
            this.lore.add(loreLine);
            return this;
        }

        public Builder interactable(boolean interactable) {
            this.interactable = interactable;
            return this;
        }

        /**
         * Allows toggle of the default CLICK hint when interactable is true.
         */
        public Builder interactionHint(boolean showHint) {
            this.showInteractionHint = showHint;
            return this;
        }

        public Builder skin(NpcSkin skin) {
            this.skin = skin;
            return this;
        }

        public Builder audioCue(String audioCue) {
            this.audioCues.add(audioCue);
            return this;
        }

        public NpcProfile build() {
            return new NpcProfile(this);
        }
    }
}
