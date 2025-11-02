package sh.harold.fulcrum.registry.console.inspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector.ProxyView;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector.ServerView;
import sh.harold.fulcrum.registry.heartbeat.store.RedisHeartbeatStore;
import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.proxy.store.RedisProxyRegistryStore;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.store.RedisServerRegistryStore;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRegistryInspectorTest extends RedisIntegrationTestSupport {

    private RedisManager manager;
    private RedisRegistryInspector inspector;
    private RedisServerRegistryStore serverStore;
    private RedisProxyRegistryStore proxyStore;
    private RedisHeartbeatStore heartbeatStore;

    @BeforeEach
    void setUp() {
        manager = newRedisManager();
        manager.sync().flushall();
        inspector = new RedisRegistryInspector(manager);
        serverStore = new RedisServerRegistryStore(manager);
        proxyStore = new RedisProxyRegistryStore(manager);
        heartbeatStore = new RedisHeartbeatStore(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.sync().flushall();
            manager.close();
        }
    }

    @Test
    void fetchServersIncludesDeadSnapshots() {
        long now = Instant.now().toEpochMilli();

        RegisteredServerData active = new RegisteredServerData(
                "mini-1",
                "temp-1",
                "mini",
                "127.0.0.1",
                25565,
                100
        );
        active.setStatus(RegisteredServerData.Status.AVAILABLE);
        active.setPlayerCount(12);
        active.setTps(19.9);
        serverStore.save(active);
        heartbeatStore.updateServerHeartbeat(active.getServerId(), now);

        RegisteredServerData dead = new RegisteredServerData(
                "mini-2",
                "temp-2",
                "mini",
                "127.0.0.1",
                25575,
                80
        );
        dead.setStatus(RegisteredServerData.Status.DEAD);
        dead.setPlayerCount(0);
        dead.setTps(0.0);
        dead.setLastHeartbeat(now - 300_000L);
        heartbeatStore.storeDeadServerSnapshot(dead);
        heartbeatStore.markServerDead(dead.getServerId(), now - 120_000L);

        List<ServerView> servers = inspector.fetchServers();

        assertThat(servers)
                .extracting(ServerView::serverId)
                .containsExactlyInAnyOrder(active.getServerId(), dead.getServerId());

        ServerView activeView = servers.stream()
                .filter(view -> view.serverId().equals(active.getServerId()))
                .findFirst()
                .orElseThrow();
        assertThat(activeView.recentlyDead()).isFalse();
        assertThat(activeView.snapshot().getStatus()).isEqualTo(RegisteredServerData.Status.AVAILABLE);
        assertThat(activeView.snapshot().getLastHeartbeat()).isEqualTo(now);

        ServerView deadView = servers.stream()
                .filter(view -> view.serverId().equals(dead.getServerId()))
                .findFirst()
                .orElseThrow();
        assertThat(deadView.recentlyDead()).isTrue();
        assertThat(deadView.snapshot().getStatus()).isEqualTo(RegisteredServerData.Status.DEAD);
        assertThat(deadView.deadSince()).isEqualTo(now - 120_000L);
    }

    @Test
    void fetchProxiesMergesUnavailableAndDeadStates() {
        long now = Instant.now().toEpochMilli();

        ProxyIdentifier activeId = ProxyIdentifier.create(1);
        RegisteredProxyData active = new RegisteredProxyData(activeId, "127.0.0.1", 25565);
        active.setStatus(RegisteredProxyData.Status.AVAILABLE);
        active.setLastHeartbeat(now);
        proxyStore.saveActive(active);

        ProxyIdentifier unavailableId = ProxyIdentifier.create(2);
        RegisteredProxyData unavailable = new RegisteredProxyData(unavailableId, "127.0.0.2", 25566);
        unavailable.setStatus(RegisteredProxyData.Status.UNAVAILABLE);
        unavailable.setLastHeartbeat(now - 90_000L);
        proxyStore.saveUnavailable(unavailable, now - 90_000L);

        ProxyIdentifier deadId = ProxyIdentifier.create(3);
        RegisteredProxyData dead = new RegisteredProxyData(deadId, "127.0.0.3", 25567);
        dead.setStatus(RegisteredProxyData.Status.DEAD);
        dead.setLastHeartbeat(now - 240_000L);
        heartbeatStore.storeDeadProxySnapshot(dead);
        heartbeatStore.markProxyDead(dead.getProxyIdString(), now - 120_000L);

        // Shut down state machines created by test fixtures
        active.shutdown();
        unavailable.shutdown();
        dead.shutdown();

        List<ProxyView> proxies = inspector.fetchProxies();

        assertThat(proxies)
                .extracting(ProxyView::proxyId)
                .containsExactlyInAnyOrder(
                        activeId.getFormattedId(),
                        unavailableId.getFormattedId(),
                        deadId.getFormattedId()
                );

        ProxyView unavailableView = proxies.stream()
                .filter(view -> view.proxyId().equals(unavailableId.getFormattedId()))
                .findFirst()
                .orElseThrow();
        assertThat(unavailableView.status()).isEqualTo(RegisteredProxyData.Status.UNAVAILABLE);
        assertThat(unavailableView.unavailableSince()).isNotNull();

        ProxyView deadView = proxies.stream()
                .filter(view -> view.proxyId().equals(deadId.getFormattedId()))
                .findFirst()
                .orElseThrow();
        assertThat(deadView.recentlyDead()).isTrue();
        assertThat(deadView.status()).isEqualTo(RegisteredProxyData.Status.DEAD);
        assertThat(deadView.deadSince()).isEqualTo(now - 120_000L);
    }
}
