package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;

import java.time.Instant;
import java.util.*;
import java.util.Objects;

/**
 * Console command that requests a backend to provision a minigame slot for a variant.
 * Mirrors the /play pipeline without routing a player.
 */
public record ProvisionMinigameCommand(SlotProvisionService slotProvisionService,
                                       RedisRegistryInspector inspector) implements CommandHandler {

    public ProvisionMinigameCommand {
        Objects.requireNonNull(slotProvisionService, "slotProvisionService");
        Objects.requireNonNull(inspector, "inspector");
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 3) {
            if (args.length == 2 && looksLikeLegacyVariant(args[1])) {
                System.out.println("Legacy syntax detected (<family[:variant]>). Use: " + getUsage());
            } else {
                System.out.println("Usage: " + getUsage());
            }
            return false;
        }

        VariantSelection selection = VariantSelection.from(args[1], args[2]);
        if (selection == null) {
            System.out.println("Invalid minigame or variant. Usage: " + getUsage());
            return false;
        }

        Optional<FamilyAvailability> availabilityOpt = resolveFamilyAvailability(selection.familyKey());
        if (availabilityOpt.isEmpty()) {
            System.out.println("Minigame '" + selection.familyId() + "' is disabled or does not exist.");
            List<String> availableFamilies = listAvailableFamilies();
            if (!availableFamilies.isEmpty()) {
                System.out.println("Available minigames: " + String.join(", ", availableFamilies));
            }
            return false;
        }

        FamilyAvailability availability = availabilityOpt.get();
        if (!availability.supportsVariant(selection.variantKey())) {
            System.out.println("Variant '" + selection.variantId() + "' is not available for '" + availability.familyId() + "'.");
            List<String> variants = availability.variants();
            if (!variants.isEmpty()) {
                System.out.println("Available variants: " + String.join(", ", variants));
            } else {
                System.out.println("No variants are currently advertised for this minigame.");
            }
            return false;
        }

        String familyId = availability.familyId();
        String variantId = availability.resolveVariant(selection.variantKey());

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "provisionminigame-command");
        metadata.put("initiator", "registry-console");
        metadata.put("requestedAt", Long.toString(Instant.now().toEpochMilli()));
        metadata.put("family", familyId);
        metadata.put("variant", variantId);

        mergeAdditionalMetadata(metadata, args);

        Optional<SlotProvisionService.ProvisionResult> result =
                slotProvisionService.requestProvision(familyId, metadata);

        if (result.isEmpty()) {
            System.out.println("No backend available to provision family '" + familyId + "'.");
            return false;
        }

        SlotProvisionService.ProvisionResult provision = result.get();
        System.out.println("Provisioned " + familyId + ":" + variantId
                + " on server " + provision.serverId()
                + " (remaining slots: " + provision.remainingSlots() + ")");
        return true;
    }

    @Override
    public String getName() {
        return "provisionminigame";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"pminigame"};
    }

    @Override
    public String getDescription() {
        return "Provision a minigame instance for the specified variant";
    }

    private void mergeAdditionalMetadata(Map<String, String> metadata, String[] args) {
        for (int i = 3; i < args.length; i++) {
            String entry = args[i];
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                metadata.put(entry, "");
            } else {
                String key = entry.substring(0, idx).trim();
                String value = entry.substring(idx + 1).trim();
                if (!key.isEmpty()) {
                    metadata.put(key, value);
                }
            }
        }
    }

    @Override
    public String getUsage() {
        return "provisionminigame <familyId> <variantId> [key=value ...]";
    }

    private Optional<FamilyAvailability> resolveFamilyAvailability(String familyKey) {
        if (familyKey == null || familyKey.isBlank()) {
            return Optional.empty();
        }

        String canonicalFamilyId = null;
        Map<String, String> variants = new LinkedHashMap<>();
        boolean foundFamily = false;

        for (RedisRegistryInspector.ServerView view : inspector.fetchServers()) {
            RegisteredServerData server = view.snapshot();
            String matchedFamilyId = findMatchingFamily(server, familyKey);
            if (matchedFamilyId == null) {
                continue;
            }
            foundFamily = true;
            if (canonicalFamilyId == null) {
                canonicalFamilyId = matchedFamilyId;
            }
            collectVariants(server, familyKey, variants);
        }

        if (!foundFamily) {
            return Optional.empty();
        }

        return Optional.of(new FamilyAvailability(
                canonicalFamilyId != null ? canonicalFamilyId : familyKey,
                variants
        ));
    }

    private String findMatchingFamily(RegisteredServerData server, String familyKey) {
        for (String candidate : server.getSlotFamilyCapacities().keySet()) {
            String normalized = normalize(candidate);
            if (normalized != null && normalized.equals(familyKey)) {
                return candidate;
            }
        }
        return null;
    }

    private void collectVariants(RegisteredServerData server,
                                 String familyKey,
                                 Map<String, String> variants) {
        Map<String, Set<String>> advertised = server.getSlotFamilyVariants();
        for (Map.Entry<String, Set<String>> entry : advertised.entrySet()) {
            String normalizedFamily = normalize(entry.getKey());
            if (normalizedFamily == null || !normalizedFamily.equals(familyKey)) {
                continue;
            }
            Set<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (String variant : values) {
                String normalizedVariant = normalize(variant);
                if (normalizedVariant != null) {
                    variants.putIfAbsent(normalizedVariant, variant);
                }
            }
        }
    }

    private List<String> listAvailableFamilies() {
        Map<String, String> families = new LinkedHashMap<>();
        for (RedisRegistryInspector.ServerView view : inspector.fetchServers()) {
            RegisteredServerData server = view.snapshot();
            for (String family : server.getSlotFamilyCapacities().keySet()) {
                String normalized = normalize(family);
                if (normalized != null) {
                    families.putIfAbsent(normalized, family);
                }
            }
        }
        return List.copyOf(families.values());
    }

    private boolean looksLikeLegacyVariant(String token) {
        if (token == null) {
            return false;
        }
        return token.contains(":") || token.contains("/") || token.contains(".");
    }

    private record VariantSelection(String familyId, String variantId,
                                    String familyKey, String variantKey) {

        static VariantSelection from(String family, String variant) {
            String normalizedFamily = normalize(family);
            String normalizedVariant = normalize(variant);
            if (normalizedFamily == null || normalizedVariant == null) {
                return null;
            }
            return new VariantSelection(
                    family.trim(),
                    variant.trim(),
                    normalizedFamily,
                    normalizedVariant
            );
        }
    }

    private record FamilyAvailability(String familyId, Map<String, String> variantsByKey) {

        boolean supportsVariant(String variantKey) {
            if (variantKey == null || variantKey.isBlank()) {
                return false;
            }
            if (variantsByKey.isEmpty()) {
                return false;
            }
            return variantsByKey.containsKey(variantKey);
        }

        String resolveVariant(String variantKey) {
            return variantsByKey.getOrDefault(variantKey, variantKey);
        }

        List<String> variants() {
            if (variantsByKey.isEmpty()) {
                return List.of();
            }
            return List.copyOf(new LinkedHashSet<>(variantsByKey.values()));
        }
    }
}
