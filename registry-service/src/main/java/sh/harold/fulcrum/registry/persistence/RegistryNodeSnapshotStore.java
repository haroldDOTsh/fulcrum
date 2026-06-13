package sh.harold.fulcrum.registry.persistence;

import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;

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

    void snapshotServer(RegisteredServerData server);

    void snapshotProxy(RegisteredProxyData proxy);

    void markOffline(String nodeId, String nodeType, String state);

    @Override
    default void close() {
    }
}
