package sh.harold.fulcrum.core;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.backend.sql.SqliteDialect;
import sh.harold.fulcrum.backend.sql.PostgresDialect;
import sh.harold.fulcrum.backend.sql.SqlDialect;

import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {
    @Test
    void testConfigParsingAndDialectDetection_sqlite() throws Exception {
        File tmp = File.createTempFile("dbtest", ".yml");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write("type: sqlite\nfile: :memory:\n");
        }
        DatabaseManager.setupFromConfig(tmp.getParentFile());
        assertNotNull(DatabaseManager.getConnection());
        assertTrue(DatabaseManager.getDialect() instanceof SqliteDialect);
    }

    @Test
    void testConfigParsingAndDialectDetection_postgres() throws Exception {
        // This test assumes a local test Postgres is available; skip if not
        File tmp = File.createTempFile("dbtest", ".yml");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write("type: postgres\nhost: localhost\nport: 5432\ndatabase: playerdata\nusername: postgres\npassword: secret\n");
        }
        try {
            DatabaseManager.setupFromConfig(tmp.getParentFile());
            assertNotNull(DatabaseManager.getConnection());
            assertTrue(DatabaseManager.getDialect() instanceof PostgresDialect);
        } catch (RuntimeException e) {
            // Acceptable if no local Postgres is running
        }
    }

    @Test
    void testUseMockConnection() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqlDialect dialect = new SqliteDialect();
        DatabaseManager.useMockConnection(conn, dialect);
        assertSame(conn, DatabaseManager.getConnection());
        assertSame(dialect, DatabaseManager.getDialect());
    }
}
