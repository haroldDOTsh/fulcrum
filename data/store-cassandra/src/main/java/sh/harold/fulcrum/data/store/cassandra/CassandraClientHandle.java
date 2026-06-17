package sh.harold.fulcrum.data.store.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

public final class CassandraClientHandle implements AutoCloseable {
    private final List<InetSocketAddress> contactPoints;
    private final String localDatacenter;
    private final CqlSessionBuilder builder;
    private CqlSession session;

    private CassandraClientHandle(
            List<InetSocketAddress> contactPoints,
            String localDatacenter,
            CqlSessionBuilder builder) {
        this.contactPoints = List.copyOf(contactPoints);
        if (this.contactPoints.isEmpty()) {
            throw new IllegalArgumentException("contactPoints must not be empty");
        }
        this.localDatacenter = requireNonBlank(localDatacenter, "localDatacenter");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    public static CassandraClientHandle createLazy(List<InetSocketAddress> contactPoints, String localDatacenter) {
        List<InetSocketAddress> checkedContactPoints = List.copyOf(contactPoints);
        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoints(checkedContactPoints)
                .withLocalDatacenter(requireNonBlank(localDatacenter, "localDatacenter"));
        return new CassandraClientHandle(checkedContactPoints, localDatacenter, builder);
    }

    public synchronized CqlSession session() {
        if (session == null) {
            session = builder.build();
        }
        return session;
    }

    public String description() {
        return "contactPoints=" + contactPoints + "|localDatacenter=" + localDatacenter;
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
