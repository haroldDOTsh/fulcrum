package sh.harold.fulcrum.registry.persistence;

import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;

import java.util.List;

/**
 * Durable registry metadata sink. Heartbeats stay ephemeral; this stores node snapshots.
 */
public interface RegistryNodeSnapshotStore extends AutoCloseable {
    RegistryNodeSnapshotStore NOOP = new RegistryNodeSnapshotStore() {
        @Override
        public void snapshotServer(RegisteredServerData server) {
        }

        @Override
        public void snapshotProxy(RegisteredProxyData proxy) {
        }

        @Override
        public void markOffline(String nodeId, String nodeType, String state) {
        }
    };

    /**
     * Load the latest durable node snapshots for registry rehydration.
     *
     * @return persisted backend and proxy snapshots.
     */
    default List<RegistryNodeSnapshot> loadSnapshots() {
        return List.of();
    }

    /**
     * Returns schema custody evidence for startup receipts.
     *
     * @return schema evidence for this snapshot store.
     */
    default SnapshotSchemaEvidence schemaEvidence() {
        return SnapshotSchemaEvidence.disabled("snapshot-store-disabled");
    }

    /**
     * Persist the latest backend server metadata snapshot.
     *
     * @param server server state to snapshot.
     */
    void snapshotServer(RegisteredServerData server);

    /**
     * Persist the latest proxy metadata snapshot.
     *
     * @param proxy proxy state to snapshot.
     */
    void snapshotProxy(RegisteredProxyData proxy);

    /**
     * Persist a terminal or unavailable state for a node.
     *
     * @param nodeId stable node identifier.
     * @param nodeType registry node type.
     * @param state lifecycle state to persist.
     */
    void markOffline(String nodeId, String nodeType, String state);

    @Override
    default void close() {
    }

    record SnapshotSchemaEvidence(
        boolean enabled,
        int contractVersion,
        String contractFingerprint,
        String tableName,
        String ddlOwner,
        String dataOwner,
        String createdBy,
        String schemaMigrationVersion,
        String schemaMigrationResource,
        String schemaMigrationReceipt,
        String disabledReason
    ) {
        public SnapshotSchemaEvidence {
            contractFingerprint = text(contractFingerprint);
            tableName = text(tableName);
            ddlOwner = text(ddlOwner);
            dataOwner = text(dataOwner);
            createdBy = text(createdBy);
            schemaMigrationVersion = text(schemaMigrationVersion);
            schemaMigrationResource = text(schemaMigrationResource);
            schemaMigrationReceipt = text(schemaMigrationReceipt);
            disabledReason = text(disabledReason);
        }

        public static SnapshotSchemaEvidence enabled(int contractVersion,
                                                     String contractFingerprint,
                                                     String tableName,
                                                     String ddlOwner,
                                                     String dataOwner,
                                                     String createdBy,
                                                     String schemaMigrationVersion,
                                                     String schemaMigrationResource,
                                                     String schemaMigrationReceipt) {
            return new SnapshotSchemaEvidence(
                true,
                contractVersion,
                contractFingerprint,
                tableName,
                ddlOwner,
                dataOwner,
                createdBy,
                schemaMigrationVersion,
                schemaMigrationResource,
                schemaMigrationReceipt,
                ""
            );
        }

        public static SnapshotSchemaEvidence disabled(String reason) {
            return new SnapshotSchemaEvidence(
                false,
                0,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                reason
            );
        }

        public String summary() {
            if (!enabled) {
                return "{enabled=false, disabledReason=" + disabledReason + "}";
            }
            return "{enabled=true"
                + ", contractVersion=" + contractVersion
                + ", contractFingerprint=" + contractFingerprint
                + ", tableName=" + tableName
                + ", ddlOwner=" + ddlOwner
                + ", dataOwner=" + dataOwner
                + ", createdBy=" + createdBy
                + ", schemaMigrationVersion=" + schemaMigrationVersion
                + ", schemaMigrationResource=" + schemaMigrationResource
                + ", schemaMigrationReceipt=" + schemaMigrationReceipt + "}";
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
