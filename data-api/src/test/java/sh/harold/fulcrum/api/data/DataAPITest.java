package sh.harold.fulcrum.api.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import com.mongodb.client.MongoDatabase;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DataAPI Tests")
class DataAPITest {

    @Mock
    private ConnectionAdapter connectionAdapter;
    
    @Mock
    private MongoDatabase mongoDatabase;
    
    @Mock
    private CacheProvider cacheProvider;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should create DataAPI with MongoDB adapter")
    void shouldCreateDataAPIWithMongoDBAdapter() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        when(connectionAdapter.getCacheProvider()).thenReturn(Optional.empty());
        
        // When
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
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
    @Disabled("Requires MongoDB server")
    @DisplayName("Should create DataAPI with cache provider")
    void shouldCreateDataAPIWithCacheProvider() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        when(connectionAdapter.getCacheProvider()).thenReturn(Optional.of(cacheProvider));
        
        // When
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // Then
        assertThat(dataAPI).isNotNull();
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should access collections using from() method")
    void shouldAccessCollectionUsingFromMethod() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // When
        Collection usersCollection = dataAPI.from("users");
        
        // Then
        assertThat(usersCollection).isNotNull();
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should access collections using collection() method")
    void shouldAccessCollectionUsingCollectionMethod() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // When
        Collection itemsCollection = dataAPI.collection("items");
        
        // Then
        assertThat(itemsCollection).isNotNull();
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should provide direct access to players collection")
    void shouldProvideDirectAccessToPlayersCollection() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // When
        Collection playersCollection = dataAPI.players();
        
        // Then
        assertThat(playersCollection).isNotNull();
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should provide direct access to specific player document")
    void shouldProvideDirectAccessToPlayerDocument() {
        // Given
        UUID playerId = UUID.randomUUID();
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // When
        Document playerDoc = dataAPI.player(playerId);
        
        // Then
        assertThat(playerDoc).isNotNull();
    }
    
    @Test
    @Disabled("Requires MongoDB server")
    @DisplayName("Should provide direct access to guilds collection")
    void shouldProvideDirectAccessToGuildsCollection() {
        // Given
        when(connectionAdapter.getStorageType()).thenReturn(StorageType.MONGODB);
        when(connectionAdapter.getMongoDatabase()).thenReturn(mongoDatabase);
        DataAPI dataAPI = DataAPI.create(connectionAdapter);
        
        // When
        Collection guildsCollection = dataAPI.guilds();
        
        // Then
        assertThat(guildsCollection).isNotNull();
    }
}