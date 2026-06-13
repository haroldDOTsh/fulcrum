package sh.harold.fulcrum.registry.persistence;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryNodeSnapshotStoreWiringTest {

    @Test
    void serverRegistryEmitsSnapshotsForLifecycleChanges() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ServerRegistry registry = new ServerRegistry(new IdAllocator(false));
        registry.setSnapshotStore(snapshots);

        String serverId = registry.registerServer(registration("temp-mini-1"));
        registry.updateStatus(serverId, RegisteredServerData.Status.AVAILABLE.name());
        registry.deregisterServer(serverId);

        assertThat(snapshots.serverStates).containsExactly(
            RegisteredServerData.Status.STARTING.name(),
            RegisteredServerData.Status.AVAILABLE.name()
        );
        assertThat(snapshots.offlineNodes).containsExactly(serverId + ":BACKEND:DEAD");
    }

    @Test
    void proxyRegistryEmitsSnapshotsForLifecycleChanges() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ProxyRegistry registry = new ProxyRegistry(new IdAllocator(false), false);
        registry.setSnapshotStore(snapshots);

        try {
            String proxyId = registry.registerProxy("temp-proxy-1", "127.0.0.1", 25577);
            registry.updateHeartbeat(proxyId);
            registry.deregisterProxy(proxyId);

            assertThat(snapshots.proxyStates).contains(
                RegisteredProxyData.Status.AVAILABLE.name(),
                RegisteredProxyData.Status.UNAVAILABLE.name()
            );
        } finally {
            registry.shutdown();
        }
    }

    private static RegistrationRequest registration(String tempId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setTempId(tempId);
        request.setServerType("mini");
        request.setAddress("127.0.0.1");
        request.setPort(25565);
        request.setMaxCapacity(20);
        return request;
    }

    private static final class RecordingSnapshotStore implements RegistryNodeSnapshotStore {
        private final List<String> serverStates = new ArrayList<>();
        private final List<String> proxyStates = new ArrayList<>();
        private final List<String> offlineNodes = new ArrayList<>();

        @Override
        public void snapshotServer(RegisteredServerData server) {
            serverStates.add(server.getStatus().name());
        }

        @Override
        public void snapshotProxy(RegisteredProxyData proxy) {
            proxyStates.add(proxy.getStatus().name());
        }

        @Override
        public void markOffline(String nodeId, String nodeType, String state) {
            offlineNodes.add(nodeId + ":" + nodeType + ":" + state);
        }
    }
}
