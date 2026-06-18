package sh.harold.fulcrum.data.store.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CassandraClientHandle implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FORCE_CLOSE_TIMEOUT = Duration.ofSeconds(5);

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
    public void close() {
        CqlSession currentSession;
        synchronized (this) {
            currentSession = session;
            session = null;
        }
        if (currentSession == null) {
            return;
        }
        try {
            await(currentSession.closeAsync(), CLOSE_TIMEOUT, "Cassandra session close");
        } catch (RuntimeException gracefulFailure) {
            try {
                await(currentSession.forceCloseAsync(), FORCE_CLOSE_TIMEOUT, "Cassandra session force close");
            } catch (RuntimeException forceFailure) {
                forceFailure.addSuppressed(gracefulFailure);
                throw forceFailure;
            }
        }
    }

    private static void await(CompletionStage<Void> stage, Duration timeout, String action) {
        try {
            stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + " was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(action + " failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException(action + " timed out after " + timeout, exception);
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
