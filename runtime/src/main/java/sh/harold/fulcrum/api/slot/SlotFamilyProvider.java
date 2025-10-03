package sh.harold.fulcrum.api.slot;

import java.util.Collection;

/**
 * Implemented by Fulcrum modules that can host logical slot families on the current node
 * (docs/slot-family-discovery-notes.md).
 */
public interface SlotFamilyProvider {

    /**
     * Discover the slot families supported by this module on the current server.
     *
     * @return A collection of slot family descriptors. Implementations should return an empty
     * collection when the module cannot currently host any families.
     */
    Collection<SlotFamilyDescriptor> getSlotFamilies();
}
