package sh.harold.fulcrum.npc.validation;

import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.profile.NpcProfile;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates NPC definitions for style consistency.
 */
public final class NpcDefinitionValidator {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_/.-]+$");
    private static final Set<Character> BANNED_COLOR_CODES = Set.of('k', 'K');

    public void validateOrThrow(NpcDefinition definition) {
        List<String> errors = validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    public void validateAllOrThrow(Collection<NpcDefinition> definitions) {
        List<String> errors = new ArrayList<>();
        Map<String, Long> counts = definitions.stream()
                .collect(Collectors.groupingBy(NpcDefinition::id, Collectors.counting()));
        counts.forEach((id, count) -> {
            if (count > 1) {
                errors.add("Duplicate NPC id detected: " + id);
            }
        });
        for (NpcDefinition definition : definitions) {
            errors.addAll(validate(definition));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid NPC definitions:\n - " + String.join("\n - ", errors));
        }
    }

    public List<String> validate(NpcDefinition definition) {
        List<String> errors = new ArrayList<>();
        if (!ID_PATTERN.matcher(definition.id()).matches()) {
            errors.add("NPC id must match namespace:id (allowed [a-z0-9_.-]): " + definition.id());
        }
        if (definition.options() == null) {
            errors.add(definition.id() + ": missing NPC options");
        }
        if (definition.equipment() == null) {
            errors.add(definition.id() + ": missing NPC equipment");
        }

        NpcProfile profile = definition.profile();
        if (profile == null) {
            errors.add("NPC profile missing for " + definition.id());
        } else {
            validateProfile(definition.id(), profile, errors);
        }
        return errors;
    }

    private void validateProfile(String id, NpcProfile profile, List<String> errors) {
        if (profile.displayName().isBlank()) {
            errors.add(id + ": display name is required");
        }
        if (profile.descriptor().isBlank()) {
            errors.add(id + ": description is required");
        }
        if (profile.skin() == null) {
            errors.add(id + ": skin descriptor missing");
        }
        if (containsBannedCodes(profile.displayName())) {
            errors.add(id + ": display name contains banned colour codes (&k/§k)");
        }
        if (containsBannedCodes(profile.descriptor())) {
            errors.add(id + ": description contains banned colour codes (&k/§k)");
        }
        for (String loreLine : profile.lore()) {
            if (containsBannedCodes(loreLine)) {
                errors.add(id + ": lore contains banned colour codes (&k/§k)");
                break;
            }
        }
    }

    private boolean containsBannedCodes(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length() - 1; i++) {
            char marker = input.charAt(i);
            if (marker != '§' && marker != '&') {
                continue;
            }
            char code = input.charAt(i + 1);
            if (BANNED_COLOR_CODES.contains(code)) {
                return true;
            }
        }
        return false;
    }
}
