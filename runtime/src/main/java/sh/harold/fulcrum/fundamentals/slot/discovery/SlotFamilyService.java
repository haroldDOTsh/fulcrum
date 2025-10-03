package sh.harold.fulcrum.fundamentals.slot.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleMetadata;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.api.slot.SlotFamilyProvider;

/**
 * Coordinates module-provided slot family descriptors, applying host filters before
 * exposing data to the runtime (docs/slot-family-discovery-notes.md).
 */
public class SlotFamilyService {
    private static final Logger LOGGER = Logger.getLogger(SlotFamilyService.class.getName());

    private final ModuleManager moduleManager;
    private final AtomicReference<List<SlotFamilyDescriptor>> activeDescriptors = new AtomicReference<>(List.of());

    public SlotFamilyService(ModuleManager moduleManager) {
        this.moduleManager = Objects.requireNonNull(moduleManager, "moduleManager");
    }

    public List<SlotFamilyDescriptor> getActiveDescriptors() {
        return activeDescriptors.get();
    }

    public List<SlotFamilyDescriptor> refreshDescriptors(SlotFamilyFilter filter) {
        List<ModuleMetadata> modules = moduleManager.getLoadedModules();
        Map<String, SlotFamilyDescriptor> descriptors = new LinkedHashMap<>();

        for (ModuleMetadata metadata : modules) {
            SlotFamilyProvider provider = resolveProvider(metadata.instance());
            if (provider == null) {
                continue;
            }

            Collection<SlotFamilyDescriptor> providedFamilies;
            try {
                providedFamilies = provider.getSlotFamilies();
            } catch (Exception e) {
                LOGGER.warning(() -> "SlotFamilyProvider from module " + metadata.name() + " threw an exception: " + e.getMessage());
                continue;
            }

            if (providedFamilies == null || providedFamilies.isEmpty()) {
                continue;
            }

            for (SlotFamilyDescriptor descriptor : providedFamilies) {
                if (descriptor == null) {
                    continue;
                }
                String familyId = descriptor.getFamilyId();
                if (!filter.isAllowed(familyId)) {
                    LOGGER.fine(() -> "Filtered slot family " + familyId + " from module " + metadata.name());
                    continue;
                }
                SlotFamilyDescriptor previous = descriptors.put(familyId, descriptor);
                if (previous != null) {
                    LOGGER.warning(() -> "Slot family " + familyId + " provided by multiple modules; overriding previous descriptor");
                }
            }
        }

        List<SlotFamilyDescriptor> snapshot = List.copyOf(descriptors.values());
        activeDescriptors.set(snapshot);
        logSummary(snapshot, filter);
        return snapshot;
    }

    private void logSummary(List<SlotFamilyDescriptor> snapshot, SlotFamilyFilter filter) {
        Set<String> allow = filter.getAllow();
        Set<String> deny = filter.getDeny();
        LOGGER.info(() -> "Discovered " + snapshot.size() + " slot families (allow=" + allow + ", deny=" + deny + ")");
    }

    private SlotFamilyProvider resolveProvider(Object moduleInstance) {
        if (moduleInstance instanceof SlotFamilyProvider provider) {
            return provider;
        }
        return null;
    }
}
