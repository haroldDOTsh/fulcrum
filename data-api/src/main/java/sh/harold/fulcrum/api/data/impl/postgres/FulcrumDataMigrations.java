package sh.harold.fulcrum.api.data.impl.postgres;

import java.util.List;

/**
 * Canonical classpath migration order for Fulcrum data authority storage.
 */
public final class FulcrumDataMigrations {
    public static final String SCHEMA_MIGRATION = "migrations/001_create_fulcrum_data_schema.sql";

    private static final List<String> ALL = List.of(SCHEMA_MIGRATION);

    private FulcrumDataMigrations() {
    }

    public static List<String> all() {
        return ALL;
    }
}
