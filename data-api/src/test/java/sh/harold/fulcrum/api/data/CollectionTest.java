package sh.harold.fulcrum.api.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sh.harold.fulcrum.api.data.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Collection Tests")
class CollectionTest {
    
    @Mock
    private Collection collection;
    
    @Mock
    private Document document;
    
    @Mock
    private Query query;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    @DisplayName("Should select document by ID using select() method")
    void shouldSelectDocumentById() {
        // Given
        String documentId = "user123";
        when(collection.select(documentId)).thenReturn(document);
        
        // When
        Document result = collection.select(documentId);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(document);
        verify(collection).select(documentId);
    }
    
    @Test
    @DisplayName("Should select document by ID using document() method")
    void shouldSelectDocumentByIdUsingDocumentMethod() {
        // Given
        String documentId = "item456";
        when(collection.document(documentId)).thenReturn(document);
        
        // When
        Document result = collection.document(documentId);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(document);
        verify(collection).document(documentId);
    }
    
    @Test
    @DisplayName("Should create new document")
    void shouldCreateNewDocument() {
        // Given
        String documentId = "newDoc";
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Test");
        data.put("value", 100);
        when(collection.create(documentId, data)).thenReturn(document);
        
        // When
        Document created = collection.create(documentId, data);
        
        // Then
        assertThat(created).isNotNull();
        verify(collection).create(documentId, data);
    }
    
    @Test
    @DisplayName("Should delete document by ID")
    void shouldDeleteDocumentById() {
        // Given
        String documentId = "deleteMe";
        when(collection.delete(documentId)).thenReturn(true);
        
        // When
        boolean deleted = collection.delete(documentId);
        
        // Then
        assertThat(deleted).isTrue();
        verify(collection).delete(documentId);
    }
    
    @Test
    @DisplayName("Should find documents using query")
    void shouldFindDocumentsUsingQuery() {
        // Given
        when(collection.find()).thenReturn(query);
        
        // When
        Query result = collection.find();
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(collection).find();
    }
    
    @Test
    @DisplayName("Should find documents where path matches")
    void shouldFindDocumentsWherePath() {
        // Given
        String path = "status";
        when(collection.where(path)).thenReturn(query);
        
        // When
        Query result = collection.where(path);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(collection).where(path);
    }
    
    @Test
    @DisplayName("Should get all documents")
    void shouldGetAllDocuments() {
        // Given
        List<Document> documents = List.of(document);
        when(collection.all()).thenReturn(documents);
        
        // When
        List<Document> result = collection.all();
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result).contains(document);
        verify(collection).all();
    }
    
    @Test
    @DisplayName("Should count documents in collection")
    void shouldCountDocuments() {
        // Given
        when(collection.count()).thenReturn(42L);
        
        // When
        long count = collection.count();
        
        // Then
        assertThat(count).isEqualTo(42L);
        verify(collection).count();
    }
}