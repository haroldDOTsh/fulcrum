package sh.harold.fulcrum.data.store.valkey;

import io.valkey.HostAndPort;
import io.valkey.UnifiedJedis;

import java.util.Objects;

public final class ValkeyClientHandle implements AutoCloseable {
    private final String host;
    private final int port;
    private final UnifiedJedis client;

    private ValkeyClientHandle(String host, int port, UnifiedJedis client) {
        this.host = requireNonBlank(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.client = Objects.requireNonNull(client, "client");
    }

    public static ValkeyClientHandle create(String host, int port) {
        String checkedHost = requireNonBlank(host, "host");
        return new ValkeyClientHandle(checkedHost, port, new UnifiedJedis(new HostAndPort(checkedHost, port)));
    }

    public UnifiedJedis client() {
        return client;
    }

    public String description() {
        return host + ":" + port;
    }

    @Override
    public void close() {
        client.close();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
