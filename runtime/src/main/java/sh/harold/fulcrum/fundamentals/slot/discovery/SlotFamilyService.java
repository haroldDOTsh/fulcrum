package sh.harold.fulcrum.fundamentals.slot.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleMetadata;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;
import sh.harold.fulcrum.api.slot.SlotFamilyProvider;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

/**
 * Coordinates module-provided slot family descriptors, applying host filters before
 * exposing data to the runtime (docs/slot-family-discovery-notes.md).
 */
public class SlotFamilyService {
    private static final Logger LOGGER = Logger.getLogger(SlotFamilyService.class.getName());

    private final ModuleManager moduleManager;
    private final AtomicReference<List<SlotFamilyDescriptor>> activeDescriptors = new AtomicReference<>(List.of());
    private final CopyOnWriteArrayList<SlotFamilyProvider> dynamicProviders = new CopyOnWriteArrayList<>();

    public SlotFamilyService(ModuleManager moduleManager) {
        this.moduleManager = Objects.requireNonNull(moduleManager, "moduleManager");
    }

    public List<SlotFamilyDescriptor> getActiveDescriptors() {
        return activeDescriptors.get();
    }

    /**
     * Registers an additional provider contributed by the runtime itself (fundamental modules).
     */
    public void registerDynamicProvider(SlotFamilyProvider provider) {
        if (provider == null) {
            return;
        }
        dynamicProviders.addIfAbsent(provider);
    }

    /**
     * Removes a previously registered dynamic provider.
     */
    public void unregisterDynamicProvider(SlotFamilyProvider provider) {
        if (provider == null) {
            return;
        }
        dynamicProviders.remove(provider);
    }

    public List<SlotFamilyDescriptor> refreshDescriptors(SlotFamilyFilter filter) {
        List<ModuleMetadata> modules = moduleManager.getLoadedModules();
        Map<String, SlotFamilyDescriptor> descriptors = new LinkedHashMap<>();

        for (ModuleMetadata metadata : modules) {
            SlotFamilyProvider provider = resolveProvider(metadata.instance());
            if (provider == null) {
                continue;
            }
            collectDescriptors(descriptors, filter, provider, metadata.name());
        }

        for (SlotFamilyProvider provider : dynamicProviders) {
            collectDescriptors(descriptors, filter, provider, provider.getClass().getSimpleName());
        }

        List<SlotFamilyDescriptor> snapshot = List.copyOf(descriptors.values());
        activeDescriptors.set(snapshot);
        logSummary(snapshot, filter);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.findService(SimpleSlotOrchestrator.class)
                .ifPresent(orchestrator -> orchestrator.configureFamilies(snapshot));
        }
        return snapshot;
    }

    private void collectDescriptors(Map<String, SlotFamilyDescriptor> descriptors,
                                    SlotFamilyFilter filter,
                                    SlotFamilyProvider provider,
                                    String sourceName) {
        Collection<SlotFamilyDescriptor> providedFamilies;
        try {
            providedFamilies = provider.getSlotFamilies();
        } catch (Exception e) {
            LOGGER.warning(() -> "SlotFamilyProvider from " + sourceName + " threw an exception: " + e.getMessage());
            return;
        }

        if (providedFamilies == null || providedFamilies.isEmpty()) {
            return;
        }

        for (SlotFamilyDescriptor descriptor : providedFamilies) {
            if (descriptor == null) {
                continue;
            }
            String familyId = descriptor.getFamilyId();
            if (!filter.isAllowed(familyId)) {
                LOGGER.fine(() -> "Filtered slot family " + familyId + " from " + sourceName);
                continue;
            }
            SlotFamilyDescriptor previous = descriptors.put(familyId, descriptor);
            if (previous != null) {
                LOGGER.warning(() -> "Slot family " + familyId + " provided by multiple sources; overriding previous descriptor");
            }
        }
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
