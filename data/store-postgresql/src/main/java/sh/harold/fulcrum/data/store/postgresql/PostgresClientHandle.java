package sh.harold.fulcrum.data.store.postgresql;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.Objects;

public final class PostgresClientHandle implements AutoCloseable {
    private final String jdbcUrl;
    private final String username;
    private final DataSource dataSource;

    private PostgresClientHandle(String jdbcUrl, String username, DataSource dataSource) {
        this.jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
        this.username = requireNonBlank(username, "username");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static PostgresClientHandle create(String jdbcUrl, String username, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(requireNonBlank(jdbcUrl, "jdbcUrl"));
        dataSource.setUser(requireNonBlank(username, "username"));
        dataSource.setPassword(requireNonBlank(password, "password"));
        return new PostgresClientHandle(jdbcUrl, username, dataSource);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String redactedDescription() {
        return "jdbcUrl=" + jdbcUrl + "|username=" + username + "|password=<redacted>";
    }

    @Override
    public void close() {
        // PGSimpleDataSource owns no pool or socket until a connection is requested.
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
