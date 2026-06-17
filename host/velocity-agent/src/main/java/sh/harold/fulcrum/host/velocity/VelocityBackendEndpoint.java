package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.InstanceId;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;

public record VelocityBackendEndpoint(
        InstanceId instanceId,
        String host,
        int port) {
    public VelocityBackendEndpoint {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        host = requireNonBlank(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in TCP range");
        }
    }

    public String backendName() {
        StringBuilder builder = new StringBuilder("fulcrum-");
        for (char character : instanceId.value().toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            } else {
                builder.append('-');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    public InetSocketAddress socketAddress() {
        return InetSocketAddress.createUnresolved(host, port);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
