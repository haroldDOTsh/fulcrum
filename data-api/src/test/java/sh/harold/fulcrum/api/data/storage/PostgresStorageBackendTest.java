package sh.harold.fulcrum.api.data.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresStorageBackend;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Disabled("Requires PostgreSQL server via Testcontainers")
class PostgresStorageBackendTest {
    
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    
    private PostgresConnectionAdapter connectionAdapter;
    private PostgresStorageBackend storageBackend;
    
    @BeforeEach
    void setUp() {
        connectionAdapter = new PostgresConnectionAdapter(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                POSTGRES.getDatabaseName()
        );
        storageBackend = new PostgresStorageBackend(connectionAdapter);
    }
    
    @AfterEach
    void tearDown() {
        if (storageBackend != null) {
            storageBackend.close();
        }
    }
    
    @Test
    @DisplayName("Should persist and retrieve a document")
    void shouldPersistAndRetrieveDocument() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Skyblock Island");
        data.put("active", true);
        data.put("slots", 12);
        
        storageBackend.saveDocument("maps", "map-01", data).join();
        Document document = storageBackend.getDocument("maps", "map-01").join();
        
        assertThat(document).isNotNull();
        assertThat(document.exists()).isTrue();
        assertThat(document.get("name")).isEqualTo("Skyblock Island");
        assertThat(document.get("active")).isEqualTo(true);
        assertThat(document.get("slots")).isEqualTo(12.0);
    }
    
    @Test
    @DisplayName("DataAPI should route to Postgres backend")
    void dataApiShouldUsePostgresBackend() {
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        Collection collection = dataAPI.collection("players");
        collection.create("player-42", Map.of(
                "username", "Alex",
                "level", 7
        ));
        
        Document document = collection.select("player-42");
        assertThat(document.exists()).isTrue();
        assertThat(document.get("username")).isEqualTo("Alex");
        assertThat(document.get("level")).isEqualTo(7.0);
    }
}