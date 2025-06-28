package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonFileBackendTest {
    static final String TEST_DIR = "build/test-json";
    static final UUID PLAYER_ID = UUID.randomUUID();

    @BeforeEach
    void clean() {
        new File(TEST_DIR, "test").delete();
    }

    @Test
    void jsonRoundtrip() {
        var backend = new JsonFileBackend(TEST_DIR);
        var schema = new TestSchema();
        var data = new TestData("foo");
        backend.save(PLAYER_ID, schema, data);
        var loaded = backend.load(PLAYER_ID, schema);
        assertEquals(data, loaded);
    }

    static class TestData {
        public String value;

        public TestData() {
        }

        public TestData(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestData t && value.equals(t.value);
        }
    }

    static class TestSchema extends JsonSchema<TestData> {
        @Override
        public String schemaKey() {
            return "test";
        }

        @Override
        public Class<TestData> type() {
            return TestData.class;
        }

        @Override
        public TestData deserialize(UUID uuid, String json) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, TestData.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String serialize(UUID uuid, TestData data) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
