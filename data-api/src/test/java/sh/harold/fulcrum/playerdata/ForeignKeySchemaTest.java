package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.annotation.Column;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.backend.sql.PostgresDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider;
import sh.harold.fulcrum.api.data.backend.sql.SqliteDialect;
import sh.harold.fulcrum.api.data.impl.ForeignKey;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForeignKeySchemaTest {
    @BeforeEach
    void setup() {
        PlayerDataRegistry.clear();
        SqlDialectProvider.setDialect(new SqliteDialect());
        TestBackendResolver.setupTestEnvironment();
    }

    // Helper: register a schema in the registry
    private <T> void registerSchema(Class<T> type) {
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(type, null));
    }

    // --- Basic Foreign Key Creation ---
    @Test
    void foreignKeyConstraintIsEmitted() {
        registerSchema(Player.class);
        registerSchema(Guild.class);
        AutoTableSchema<GuildMember> schema = new AutoTableSchema<>(GuildMember.class, null);
        PlayerDataRegistry.registerSchema(schema);
        SqlDialect dialect = SqlDialectProvider.get();
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
        registerSchema(Player.class);
        registerSchema(Guild.class);
        AutoTableSchema<GuildMember> schema = new AutoTableSchema<>(GuildMember.class, null);
        SqlDialect dialect = SqlDialectProvider.get();
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

    @Test
    void throwsOnTypeMismatch() {
        registerSchema(Player.class);
        AutoTableSchema<BadRef> schema = new AutoTableSchema<>(BadRef.class, null);
        Exception ex = assertThrows(IllegalArgumentException.class, schema::getCreateTableSql);
        assertTrue(ex.getMessage().contains("Foreign key type mismatch"), "Expected type mismatch error, got: " + ex.getMessage());
    }

    @Test
    void throwsOnMissingReferencedSchema() {
        AutoTableSchema<UnregisteredFK> schema = new AutoTableSchema<>(UnregisteredFK.class, null);
        Exception ex = assertThrows(IllegalArgumentException.class, schema::getCreateTableSql);
        assertTrue(ex.getMessage().contains("Referenced schema not registered"), "Expected missing schema error, got: " + ex.getMessage());
    }

    @Test
    void multipleForeignKeysAreEmitted() {
        registerSchema(Player.class);
        registerSchema(Guild.class);
        AutoTableSchema<MultiFK> schema = new AutoTableSchema<>(MultiFK.class, null);
        SqlDialect dialect = SqlDialectProvider.get();
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

    @Test
    void implicitForeignKeyDefaultsToPrimaryKey() {
        registerSchema(Player.class);
        AutoTableSchema<ImplicitFK> schema = new AutoTableSchema<>(ImplicitFK.class, null);
        SqlDialect dialect = SqlDialectProvider.get();
        String ddl = schema.getCreateTableSql();
        String quotedPlayerId = dialect.quoteIdentifier("player_id");
        String quotedPlayers = dialect.quoteIdentifier("players");
        String quotedId = dialect.quoteIdentifier("id");
        String fk = "FOREIGN KEY (" + quotedPlayerId + ") REFERENCES " + quotedPlayers + "(" + quotedId + ")";
        assertTrue(ddl.contains(fk), "Expected implicit FK: " + fk + "\nDDL:\n" + ddl);
    }

    @Test
    void circularReferenceSchemasRegisterAndEmitForeignKeys() {
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(A.class, null));
        PlayerDataRegistry.registerSchema(new AutoTableSchema<>(B.class, null));
        AutoTableSchema<A> schemaA = new AutoTableSchema<>(A.class, null);
        AutoTableSchema<B> schemaB = new AutoTableSchema<>(B.class, null);
        SqlDialect dialect = SqlDialectProvider.get();
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
        SqlDialectProvider.setDialect(new SqliteDialect());
        registerSchema(Player.class);
        AutoTableSchema<ImplicitFK> schemaSqlite = new AutoTableSchema<>(ImplicitFK.class, null);
        String sqlSqlite = schemaSqlite.getCreateTableSql();
        assertTrue(sqlSqlite.contains("`players`"));

        SqlDialectProvider.setDialect(new PostgresDialect());
        registerSchema(Player.class);
        AutoTableSchema<ImplicitFK> schemaPg = new AutoTableSchema<>(ImplicitFK.class, null);
        String sqlPg = schemaPg.getCreateTableSql();
        assertTrue(sqlPg.contains('"' + "players" + '"'));
        assertTrue(sqlPg.contains('"' + "player_id" + '"'));
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

    // --- Type Mismatch Handling ---
    @Table("bad_ref")
    static class BadRef {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public String player_id; // Should fail: type mismatch
    }

    // --- Missing Schema Handling ---
    @Table("unregistered_fk")
    static class UnregisteredFK {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Guild.class)
        public UUID guild_id;
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

    // --- Implicit Foreign Key (default column = id) ---
    @Table("implicit_fk")
    static class ImplicitFK {
        @Column(primary = true)
        public UUID id;
        @ForeignKey(references = Player.class)
        public UUID player_id;
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
}
