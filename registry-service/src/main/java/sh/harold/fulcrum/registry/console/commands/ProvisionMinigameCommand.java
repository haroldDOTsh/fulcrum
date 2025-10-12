package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Console command that requests a backend to provision a minigame slot for a variant.
 * Mirrors the /play pipeline without routing a player.
 */
public final class ProvisionMinigameCommand implements CommandHandler {

    private final SlotProvisionService slotProvisionService;

    public ProvisionMinigameCommand(SlotProvisionService slotProvisionService) {
        this.slotProvisionService = slotProvisionService;
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: provisionminigame <variantId> [key=value ...]");
            return false;
        }

        VariantSelection selection = VariantSelection.from(args[1]);
        if (selection == null) {
            System.out.println("Invalid variant identifier: " + args[1]);
            System.out.println("Examples: skywars:insane, bedwars/4v4");
            return false;
        }

        String familyId = selection.familyId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "provisionminigame-command");
        metadata.put("initiator", "registry-console");
        metadata.put("requestedAt", Long.toString(Instant.now().toEpochMilli()));
        metadata.put("family", familyId);
        String variantValue = selection.variantKey().isBlank() ? familyId : selection.variantKey();
        metadata.put("variant", variantValue);

        mergeAdditionalMetadata(metadata, args);

        Optional<SlotProvisionService.ProvisionResult> result =
                slotProvisionService.requestProvision(familyId, metadata);

        if (result.isEmpty()) {
            System.out.println("No backend available to provision family '" + familyId + "'.");
            return false;
        }

        SlotProvisionService.ProvisionResult provision = result.get();
        System.out.println("Provisioned " + selection.display()
                + " on server " + provision.serverId()
                + " (remaining slots: " + provision.remainingSlots() + ")");
        return true;
    }

    private void mergeAdditionalMetadata(Map<String, String> metadata, String[] args) {
        for (int i = 2; i < args.length; i++) {
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

    @Override
    public String getUsage() {
        return "provisionminigame <family[:variant]> [key=value ...]";
    }

    private record VariantSelection(String rawInput, String familyId, String variantId,
                                    String familyKey, String variantKey) {

        static VariantSelection from(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }

            String family = trimmed;
            String variant = trimmed;
            int separator = findSeparator(trimmed);
            if (separator > 0 && separator < trimmed.length() - 1) {
                family = trimmed.substring(0, separator);
                variant = trimmed.substring(separator + 1);
            }

            String familyKey = normalise(family);
            String variantKey = normalise(variant);
            return new VariantSelection(trimmed, family, variant, familyKey, variantKey);
        }

        private static int findSeparator(String value) {
            int colon = value.indexOf(':');
            if (colon > 0) {
                return colon;
            }
            int slash = value.indexOf('/');
            if (slash > 0) {
                return slash;
            }
            int dot = value.indexOf('.');
            return dot > 0 ? dot : -1;
        }

        private static String normalise(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }

        String display() {
            return rawInput;
        }
    }
}
