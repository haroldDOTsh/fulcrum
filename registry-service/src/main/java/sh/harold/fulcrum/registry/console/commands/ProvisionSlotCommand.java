package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Console command to manually request a slot provision (diagnostic tooling).
 */
public class ProvisionSlotCommand implements CommandHandler {

    private final SlotProvisionService slotProvisionService;

    public ProvisionSlotCommand(SlotProvisionService slotProvisionService) {
        this.slotProvisionService = slotProvisionService;
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: provisionslot <familyId> [key=value ...]");
            return false;
        }

        String familyId = args[1];
        Map<String, String> metadata = parseMetadata(args);

        Optional<SlotProvisionService.ProvisionResult> result =
            slotProvisionService.requestProvision(familyId, metadata);

        if (result.isEmpty()) {
            System.out.println("No suitable server found for family '" + familyId + "'.");
            return false;
        }

        SlotProvisionService.ProvisionResult decision = result.get();
        System.out.println("Provision command dispatched to " + decision.serverId()
            + " (slots remaining: " + decision.remainingSlots() + ")");
        return true;
    }

    private Map<String, String> parseMetadata(String[] args) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String entry = args[i];
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                metadata.put(entry, "");
            } else {
                String key = entry.substring(0, idx);
                String value = entry.substring(idx + 1);
                metadata.put(key, value);
            }
        }
        return metadata;
    }

    @Override
    public String getName() {
        return "provisionslot";
    }

    @Override
    public String[] getAliases() {
        return new String[] {"pslot"};
    }

    @Override
    public String getDescription() {
        return "Dispatch a SlotProvisionCommand for the specified family (diagnostic)";
    }

    @Override
    public String getUsage() {
        return "provisionslot <familyId> [key=value ...]";
    }
}
