package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.data.registry.PlayerStorageManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerDataBackendMixedTest {
    static final UUID PLAYER_ID = UUID.randomUUID();
    static final String TEST_DIR = "build/test-mixed-json";

    @BeforeEach
    void clear() {
        PlayerDataRegistry.clear();
        new File(TEST_DIR, "json").delete();
    }

    @Test
    void mixedSqlJsonRoundtrip() {
        var jsonSchema = new JsonSchemaImpl();
        var sqlSchema = new SqlSchemaImpl();
        var jsonBackend = new JsonFileBackend(TEST_DIR);
        var sqlBackend = new DummySqlBackend();
        PlayerDataRegistry.registerSchema(jsonSchema, jsonBackend);
        PlayerDataRegistry.registerSchema(sqlSchema, sqlBackend);
        var jsonData = new JsonData("hello");
        var sqlData = new SqlData(42);
        PlayerStorageManager.save(PLAYER_ID, jsonSchema, jsonData);
        PlayerStorageManager.save(PLAYER_ID, sqlSchema, sqlData);
        assertEquals(jsonData, PlayerStorageManager.load(PLAYER_ID, jsonSchema));
        assertEquals(sqlData, PlayerStorageManager.load(PLAYER_ID, sqlSchema));
    }

    static class JsonData {
        public String foo;

        public JsonData() {
        }

        public JsonData(String foo) {
            this.foo = foo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof JsonData d && Objects.equals(foo, d.foo);
        }
    }

    static class SqlData {
        public int bar;

        public SqlData() {
        }

        public SqlData(int bar) {
            this.bar = bar;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SqlData d && bar == d.bar;
        }
    }

    static class JsonSchemaImpl extends JsonSchema<JsonData> {
        @Override
        public String schemaKey() {
            return "json";
        }

        @Override
        public Class<JsonData> type() {
            return JsonData.class;
        }

        @Override
        public JsonData deserialize(UUID uuid, String json) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, JsonData.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String serialize(UUID uuid, JsonData data) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class SqlSchemaImpl extends TableSchema<SqlData> {
        @Override
        public String schemaKey() {
            return "sql";
        }

        @Override
        public Class<SqlData> type() {
            return SqlData.class;
        }
    }

    static class DummySqlBackend implements PlayerDataBackend {
        final Map<UUID, SqlData> map = new HashMap<>();

        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return schema.type().cast(map.get(uuid));
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            map.put(uuid, (SqlData) data);
        }
    }
}
