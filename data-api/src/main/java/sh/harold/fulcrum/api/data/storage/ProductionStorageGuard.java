package sh.harold.fulcrum.api.data.storage;

/**
 * Central guard for storage choices that are unsafe as production authority.
 */
public final class ProductionStorageGuard {
    private ProductionStorageGuard() {
    }

    public static void requireProductionSafe(StorageType storageType, boolean developmentMode, String context) {
        if (developmentMode || storageType == StorageType.POSTGRES) {
            return;
        }

        throw new IllegalStateException(context + " is configured with " + storageType
            + ". Production Fulcrum data authority must use POSTGRES; JSON and MongoDB are dev/tooling only.");
    }
}
