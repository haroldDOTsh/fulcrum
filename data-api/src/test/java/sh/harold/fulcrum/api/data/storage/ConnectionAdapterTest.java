package sh.harold.fulcrum.api.data.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.mongodb.client.MongoDatabase;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConnectionAdapter Tests")
class ConnectionAdapterTest {
    
    @Mock
    private MongoDatabase mongoDatabase;
    
    @Mock
    private CacheProvider cacheProvider;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    @DisplayName("MongoDB adapter should return correct storage type")
    void mongoDBAdapterShouldReturnCorrectStorageType() {
        // Given
        ConnectionAdapter adapter = new ConnectionAdapter() {
            @Override
            public StorageType getStorageType() {
                return StorageType.MONGODB;
            }
            
            @Override
            public MongoDatabase getMongoDatabase() {
                return mongoDatabase;
            }
            
            @Override
            public Path getJsonStoragePath() {
                return null;
            }
            
            @Override
            public Optional<CacheProvider> getCacheProvider() {
                return Optional.empty();
            }
        };
        
        // When
        StorageType type = adapter.getStorageType();
        
        // Then
        assertThat(type).isEqualTo(StorageType.MONGODB);
        assertThat(adapter.getMongoDatabase()).isEqualTo(mongoDatabase);
    }
    
    @Test
    @DisplayName("JSON adapter should return correct storage type and path")
    void jsonAdapterShouldReturnCorrectStorageTypeAndPath() {
        // Given
        Path jsonPath = Paths.get("data/json");
        ConnectionAdapter adapter = new ConnectionAdapter() {
            @Override
            public StorageType getStorageType() {
                return StorageType.JSON;
            }
            
            @Override
            public MongoDatabase getMongoDatabase() {
                return null;
            }
            
            @Override
            public Path getJsonStoragePath() {
                return jsonPath;
            }
            
            @Override
            public Optional<CacheProvider> getCacheProvider() {
                return Optional.empty();
            }
        };
        
        // When
        StorageType type = adapter.getStorageType();
        Path path = adapter.getJsonStoragePath();
        
        // Then
        assertThat(type).isEqualTo(StorageType.JSON);
        assertThat(path).isEqualTo(jsonPath);
    }
    
    @Test
    @DisplayName("Adapter with cache should return cache provider")
    void adapterWithCacheShouldReturnCacheProvider() {
        // Given
        ConnectionAdapter adapter = new ConnectionAdapter() {
            @Override
            public StorageType getStorageType() {
                return StorageType.MONGODB;
            }
            
            @Override
            public MongoDatabase getMongoDatabase() {
                return mongoDatabase;
            }
            
            @Override
            public Path getJsonStoragePath() {
                return null;
            }
            
            @Override
            public Optional<CacheProvider> getCacheProvider() {
                return Optional.of(cacheProvider);
            }
        };
        
        // When
        Optional<CacheProvider> cache = adapter.getCacheProvider();
        
        // Then
        assertThat(cache).isPresent();
        assertThat(cache.get()).isEqualTo(cacheProvider);
    }
}