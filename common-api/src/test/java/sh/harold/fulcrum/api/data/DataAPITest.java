package sh.harold.fulcrum.api.data;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("DataAPI Tests")
class DataAPITest {

    @Mock
    private ConnectionAdapter connectionAdapter;

    @Mock
    private MongoConnectionAdapter mongoConnectionAdapter;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private CacheProvider cacheProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mongoConnectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(mongoConnectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        when(mongoConnectionAdapter.getCacheProvider()).thenReturn(Optional.empty());
        when(mongoConnectionAdapter.getJsonStoragePath()).thenReturn(null);
    }

    @Test
    @DisplayName("Should create DataAPI with MongoDB adapter")
    void shouldCreateDataAPIWithMongoDBAdapter() {
        // Given
        when(mongoConnectionAdapter.getCacheProvider()).thenReturn(Optional.empty());

        // When
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // Then
        assertThat(dataAPI).isNotNull();
    }

    @Test
    @DisplayName("Should create DataAPI with JSON adapter")
    void shouldCreateDataAPIWithJSONAdapter() {
        // Given
        Path jsonPath = Paths.get("test-data");
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.JSON);
        when(connectionAdapter.getJsonStoragePath()).thenReturn(jsonPath);
        when(connectionAdapter.getCacheProvider()).thenReturn(Optional.empty());

        // When
        DataAPI dataAPI = DataAPI.create(connectionAdapter);

        // Then
        assertThat(dataAPI).isNotNull();
    }

    @Test
    @DisplayName("Should create DataAPI with cache provider")
    void shouldCreateDataAPIWithCacheProvider() {
        // Given
        when(mongoConnectionAdapter.getCacheProvider()).thenReturn(Optional.of(cacheProvider));

        // When
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // Then
        assertThat(dataAPI).isNotNull();
    }

    @Test
    @DisplayName("Should access collections using from() method")
    void shouldAccessCollectionUsingFromMethod() {
        // Given
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // When
        Collection usersCollection = dataAPI.from("users");

        // Then
        assertThat(usersCollection).isNotNull();
    }

    @Test
    @DisplayName("Should access collections using collection() method")
    void shouldAccessCollectionUsingCollectionMethod() {
        // Given
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // When
        Collection itemsCollection = dataAPI.collection("items");

        // Then
        assertThat(itemsCollection).isNotNull();
    }

    @Test
    @DisplayName("Should provide direct access to players collection")
    void shouldProvideDirectAccessToPlayersCollection() {
        // Given
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // When
        Collection playersCollection = dataAPI.players();

        // Then
        assertThat(playersCollection).isNotNull();
    }

    @Test
    @DisplayName("Should provide direct access to specific player document")
    void shouldProvideDirectAccessToPlayerDocument() {
        // Given
        UUID playerId = UUID.randomUUID();
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // When
        Document playerDoc = dataAPI.player(playerId);

        // Then
        assertThat(playerDoc).isNotNull();
    }

    @Test
    @DisplayName("Should provide direct access to guilds collection")
    void shouldProvideDirectAccessToGuildsCollection() {
        // Given
        DataAPI dataAPI = DataAPI.create(mongoConnectionAdapter);

        // When
        Collection guildsCollection = dataAPI.guilds();

        // Then
        assertThat(guildsCollection).isNotNull();
    }
}
