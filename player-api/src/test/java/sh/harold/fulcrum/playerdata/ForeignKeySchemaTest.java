package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.*;
import sh.harold.fulcrum.backend.core.AutoTableSchema;
import sh.harold.fulcrum.backend.sql.PostgresDialect;
import sh.harold.fulcrum.backend.sql.SqlDialect;
import sh.harold.fulcrum.backend.sql.SqliteDialect;
import sh.harold.fulcrum.registry.PlayerDataRegistry;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForeignKeySchemaTest {
    @BeforeEach
    void setup() {
        PlayerDataRegistry.clear();
    }

    // Helper: register a schema in the registry
    private <T> void registerSchema(Class<T> type, SqlDialect dialect) {
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(type, null, dialect), null);
    }

    // --- Core Test Classes ---
    @Table("players")
    static class Player {
        @Column(primary = true)
        public UUID id;
        public String name;
    }

    @Table("guilds")
    static class Guild {
        @Column(primary = true)
        public UUID id;
        public String name;
    }

    @Table("guild_members")
    static class GuildMember {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public UUID player_id;
        @ForeignKey(references = Guild.class, onDelete = "CASCADE", onUpdate = "SET NULL")
        public UUID guild_id;
    }

    // --- Basic Foreign Key Creation ---
    @Test
    void foreignKeyConstraintIsEmitted() {
        SqlDialect dialect = new SqliteDialect();
        registerSchema(Player.class, dialect);
        registerSchema(Guild.class, dialect);
        AutoTableSchema<GuildMember> schema = new AutoTableSchema<>(GuildMember.class, null, dialect);
        PlayerDataRegistry.registerSchema(schema, null);
        String ddl = schema.getCreateTableSql();
        String quotedPlayerId = dialect.quoteIdentifier("player_id");
        String quotedPlayers = dialect.quoteIdentifier("players");
        String quotedId = dialect.quoteIdentifier("id");
        String quotedGuildId = dialect.quoteIdentifier("guild_id");
        String quotedGuilds = dialect.quoteIdentifier("guilds");
        String expectedFk1 = "FOREIGN KEY (" + quotedPlayerId + ") REFERENCES " + quotedPlayers + "(" + quotedId + ")";
        String expectedFk2 = "FOREIGN KEY (" + quotedGuildId + ") REFERENCES " + quotedGuilds + "(" + quotedId + ") ON DELETE CASCADE ON UPDATE SET NULL";
        assertTrue(ddl.contains(expectedFk1), "Expected FK constraint: " + expectedFk1 + "\nGenerated DDL:\n" + ddl);
        assertTrue(ddl.contains(expectedFk2), "Expected FK constraint: " + expectedFk2 + "\nGenerated DDL:\n" + ddl);
        assertTrue(ddl.contains(dialect.quoteIdentifier("guild_members")), "Expected table name quoted: " + dialect.quoteIdentifier("guild_members") + "\nDDL:\n" + ddl);
    }

    // --- DDL Generation Consistency ---
    @Test
    void ddlContainsPrimaryAndForeignKeys() {
        SqlDialect dialect = new SqliteDialect();
        registerSchema(Player.class, dialect);
        registerSchema(Guild.class, dialect);
        AutoTableSchema<GuildMember> schema = new AutoTableSchema<>(GuildMember.class, null, dialect);
        String ddl = schema.getCreateTableSql();
        String quotedId = dialect.quoteIdentifier("id");
        String quotedPlayerId = dialect.quoteIdentifier("player_id");
        String quotedPlayers = dialect.quoteIdentifier("players");
        String quotedGuildId = dialect.quoteIdentifier("guild_id");
        String quotedGuilds = dialect.quoteIdentifier("guilds");
        String pk = "PRIMARY KEY (" + quotedId + ")";
        String fk1 = "FOREIGN KEY (" + quotedPlayerId + ") REFERENCES " + quotedPlayers + "(" + quotedId + ")";
        String fk2 = "FOREIGN KEY (" + quotedGuildId + ") REFERENCES " + quotedGuilds + "(" + quotedId + ") ON DELETE CASCADE ON UPDATE SET NULL";
        assertTrue(ddl.contains(pk), "Expected PK: " + pk + "\nDDL:\n" + ddl);
        assertTrue(ddl.contains(fk1), "Expected FK1: " + fk1 + "\nDDL:\n" + ddl);
        assertTrue(ddl.contains(fk2), "Expected FK2: " + fk2 + "\nDDL:\n" + ddl);
    }

    // --- Type Mismatch Handling ---
    @Table("bad_ref")
    static class BadRef {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public String player_id; // Should fail: type mismatch
    }

    @Test
    void throwsOnTypeMismatch() {
        SqlDialect dialect = new SqliteDialect();
        registerSchema(Player.class, dialect);
        AutoTableSchema<BadRef> schema = new AutoTableSchema<>(BadRef.class, null, dialect);
        Exception ex = assertThrows(IllegalArgumentException.class, schema::getCreateTableSql);
        assertTrue(ex.getMessage().contains("Foreign key type mismatch"), "Expected type mismatch error, got: " + ex.getMessage());
    }

    // --- Missing Schema Handling ---
    @Table("unregistered_fk")
    static class UnregisteredFK {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Guild.class)
        public UUID guild_id;
    }

    @Test
    void throwsOnMissingReferencedSchema() {
        SqlDialect dialect = new SqliteDialect();
        AutoTableSchema<UnregisteredFK> schema = new AutoTableSchema<>(UnregisteredFK.class, null, dialect);
        Exception ex = assertThrows(IllegalArgumentException.class, schema::getCreateTableSql);
        assertTrue(ex.getMessage().contains("Referenced schema not registered"), "Expected missing schema error, got: " + ex.getMessage());
    }

    // --- Multiple Foreign Keys ---
    @Table("multi_fk")
    static class MultiFK {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public UUID player_id;
        @ForeignKey(references = Guild.class)
        public UUID guild_id;
    }

    @Test
    void multipleForeignKeysAreEmitted() {
        SqlDialect dialect = new SqliteDialect();
        registerSchema(Player.class, dialect);
        registerSchema(Guild.class, dialect);
        AutoTableSchema<MultiFK> schema = new AutoTableSchema<>(MultiFK.class, null, dialect);
        String ddl = schema.getCreateTableSql();
        String quotedPlayerId = dialect.quoteIdentifier("player_id");
        String quotedPlayers = dialect.quoteIdentifier("players");
        String quotedGuildId = dialect.quoteIdentifier("guild_id");
        String quotedGuilds = dialect.quoteIdentifier("guilds");
        String fk1 = "FOREIGN KEY (" + quotedPlayerId + ") REFERENCES " + quotedPlayers + "(" + dialect.quoteIdentifier("id") + ")";
        String fk2 = "FOREIGN KEY (" + quotedGuildId + ") REFERENCES " + quotedGuilds + "(" + dialect.quoteIdentifier("id") + ")";
        assertTrue(ddl.contains(fk1), "Expected FK1: " + fk1 + "\nDDL:\n" + ddl);
        assertTrue(ddl.contains(fk2), "Expected FK2: " + fk2 + "\nDDL:\n" + ddl);
    }

    // --- Implicit Foreign Key (default column = id) ---
    @Table("implicit_fk")
    static class ImplicitFK {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public UUID player_id;
    }

    @Test
    void implicitForeignKeyDefaultsToPrimaryKey() {
        SqlDialect dialect = new SqliteDialect();
        registerSchema(Player.class, dialect);
        AutoTableSchema<ImplicitFK> schema = new AutoTableSchema<>(ImplicitFK.class, null, dialect);
        String ddl = schema.getCreateTableSql();
        String quotedPlayerId = dialect.quoteIdentifier("player_id");
        String quotedPlayers = dialect.quoteIdentifier("players");
        String quotedId = dialect.quoteIdentifier("id");
        String fk = "FOREIGN KEY (" + quotedPlayerId + ") REFERENCES " + quotedPlayers + "(" + quotedId + ")";
        assertTrue(ddl.contains(fk), "Expected implicit FK: " + fk + "\nDDL:\n" + ddl);
    }

    // --- Circular Reference Handling ---
    @Table("a")
    static class A {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = B.class)
        public UUID b_id;
    }
    @Table("b")
    static class B {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = A.class)
        public UUID a_id;
    }

    @Test
    void circularReferenceSchemasRegisterAndEmitForeignKeys() {
        SqlDialect dialect = new SqliteDialect();
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(A.class, null, dialect), null);
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(B.class, null, dialect), null);
        AutoTableSchema<A> schemaA = new AutoTableSchema<>(A.class, null, dialect);
        AutoTableSchema<B> schemaB = new AutoTableSchema<>(B.class, null, dialect);
        String ddlA = schemaA.getCreateTableSql();
        String ddlB = schemaB.getCreateTableSql();
        String quotedAId = dialect.quoteIdentifier("a_id");
        String quotedA = dialect.quoteIdentifier("a");
        String quotedBId = dialect.quoteIdentifier("b_id");
        String quotedB = dialect.quoteIdentifier("b");
        String quotedId = dialect.quoteIdentifier("id");
        String fkA = "FOREIGN KEY (" + quotedBId + ") REFERENCES " + quotedB + "(" + quotedId + ")";
        String fkB = "FOREIGN KEY (" + quotedAId + ") REFERENCES " + quotedA + "(" + quotedId + ")";
        assertTrue(ddlA.contains(fkA), "Expected circular FK in A: " + fkA + "\nDDL:\n" + ddlA);
        assertTrue(ddlB.contains(fkB), "Expected circular FK in B: " + fkB + "\nDDL:\n" + ddlB);
    }

    // --- Dialect Variation ---
    @Test
    void foreignKeyQuotingRespectsDialect() {
        SqlDialect sqlite = new SqliteDialect();
        SqlDialect pg = new PostgresDialect();
        registerSchema(Player.class, sqlite);
        registerSchema(Player.class, pg);
        AutoTableSchema<ImplicitFK> schemaSqlite = new AutoTableSchema<>(ImplicitFK.class, null, sqlite);
        AutoTableSchema<ImplicitFK> schemaPg = new AutoTableSchema<>(ImplicitFK.class, null, pg);
        String sqlSqlite = schemaSqlite.getCreateTableSql();
        String sqlPg = schemaPg.getCreateTableSql();
        // Sqlite uses backticks, Postgres uses double quotes
        assertTrue(sqlSqlite.contains("`players`"));
        assertTrue(sqlPg.contains('"' + "players" + '"'));
        assertTrue(sqlPg.contains('"' + "player_id" + '"'));
    }
}
