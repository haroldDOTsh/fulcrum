package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.annotation.Column;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Table("player_stats")
class PlayerStats {
    @Column(primary = true)
    public UUID id;
    public int kills;
    public int deaths;
    public String name;
    public boolean online;

    public PlayerStats() {
    }
}

@Table("no_column_table")
class NoColumnPojo {
    public UUID uuid;
    public int value;

    public NoColumnPojo() {
    }
}

class AutoTableSchemaTest {
    // Helper to create a new in-memory SQLite connection with schema_versions table
    Connection newConnection() throws Exception {
        var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schema_versions (table_name TEXT PRIMARY KEY, version INT)");
        }
        return conn;
    }

    @Test
    void tableCreation_createsTableAndPrimaryKey() throws Exception {
        try (var conn = newConnection()) {
            var schema = new AutoTableSchema<>(PlayerStats.class, conn);
            schema.createTable(conn);
            try (var rs = conn.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='player_stats'")) {
                assertTrue(rs.next(), "Table 'player_stats' should exist");
            }
        }
    }

    @Test
    void schemaVersioning_insertsCorrectVersion() throws Exception {
        @SchemaVersion(42)
        @Table("versioned_table")
        class Versioned {
            @Column(primary = true)
            public UUID id;

            public Versioned() {
            }
        }
        try (var conn = newConnection()) {
            var schema = new AutoTableSchema<>(Versioned.class, conn);
            schema.createTable(conn);
            try (var rs = conn.createStatement().executeQuery("SELECT version FROM schema_versions WHERE table_name='versioned_table'")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
            assertEquals(42, schema.getSchemaVersion());
        }
    }

    @Test
    void schemaVersioning_fallsBackTo1IfMissing() throws Exception {
        try (var conn = newConnection()) {
            var schema = new AutoTableSchema<>(PlayerStats.class, conn);
            assertEquals(1, schema.getSchemaVersion());
        }
    }

    @Test
    void saveAndLoad_roundTripsAllFields() throws Exception {
        Connection conn = newConnection();
        var schema = new AutoTableSchema<>(PlayerStats.class, conn);
        schema.createTable(conn);
        var stats = new PlayerStats();
        stats.id = UUID.randomUUID();
        stats.kills = 5;
        stats.deaths = 2;
        stats.name = "TestPlayer";
        stats.online = true;
        schema.save(stats.id, stats);
        var loaded = schema.load(stats.id);
        assertNotNull(loaded);
        assertEquals(stats.id, loaded.id);
        assertEquals(stats.kills, loaded.kills);
        assertEquals(stats.deaths, loaded.deaths);
        assertEquals(stats.name, loaded.name);
        assertEquals(stats.online, loaded.online);
        conn.close();
    }

    @Test
    void throwsIfMissingTableAnnotation() {
        class NoTable {
        }
        Exception ex = assertThrows(IllegalArgumentException.class, () -> new AutoTableSchema<>(NoTable.class, null));
        assertTrue(ex.getMessage().contains("Missing @Table annotation"));
    }

    @Test
    void conventionBasedFieldDetectionWorks() throws Exception {
        Connection conn = newConnection();
        var schema = new AutoTableSchema<>(NoColumnPojo.class, conn);
        schema.createTable(conn);
        var obj = new NoColumnPojo();
        obj.uuid = UUID.randomUUID();
        obj.value = 123;
        schema.save(obj.uuid, obj);
        var loaded = schema.load(obj.uuid);
        assertNotNull(loaded);
        assertEquals(obj.uuid, loaded.uuid);
        assertEquals(obj.value, loaded.value);
        conn.close();
    }

    @Test
    void throwsIfNullPrimaryKeyOnSave() throws Exception {
        try (var conn = newConnection()) {
            var schema = new AutoTableSchema<>(PlayerStats.class, conn);
            schema.createTable(conn);
            var stats = new PlayerStats();
            stats.id = null;
            Exception ex = assertThrows(RuntimeException.class, () -> schema.save(null, stats));
            // Accept any RuntimeException for null PK, print message for future debugging
            System.out.println("Exception message: " + ex.getMessage());
        }
    }
}
