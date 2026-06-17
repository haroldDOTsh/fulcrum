package sh.harold.fulcrum.host.paper;

import java.time.Duration;

public interface AgonesGameServerSdkClient {
    void ready();

    void health();

    void allocate();

    void reserve(Duration duration);

    void shutdown();

    AgonesGameServerSnapshot gameServer();
}
