package sh.harold.fulcrum.api.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document Tests")
class DocumentTest {

    @Mock
    private Document document;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should get value by path")
    void shouldGetValueByPath() {
        // Given
        String path = "user.name";
        String expectedValue = "John Doe";
        when(document.get(path)).thenReturn(expectedValue);

        // When
        Object result = document.get(path);

        // Then
        assertThat(result).isEqualTo(expectedValue);
        verify(document).get(path);
    }

    @Test
    @DisplayName("Should get value with default")
    void shouldGetValueWithDefault() {
        // Given
        String path = "user.age";
        Integer defaultValue = 0;
        Integer expectedValue = 25;
        when(document.get(path, defaultValue)).thenReturn(expectedValue);

        // When
        Integer result = document.get(path, defaultValue);

        // Then
        assertThat(result).isEqualTo(expectedValue);
        verify(document).get(path, defaultValue);
    }

    @Test
    @DisplayName("Should return default value when path doesn't exist")
    void shouldReturnDefaultWhenPathDoesntExist() {
        // Given
        String path = "nonexistent.path";
        String defaultValue = "default";
        when(document.get(path, defaultValue)).thenReturn(defaultValue);

        // When
        String result = document.get(path, defaultValue);

        // Then
        assertThat(result).isEqualTo(defaultValue);
        verify(document).get(path, defaultValue);
    }

    @Test
    @DisplayName("Should set value at path")
    void shouldSetValueAtPath() {
        // Given
        String path = "user.email";
        String value = "test@example.com";
        when(document.set(path, value)).thenReturn(document);

        // When
        Document result = document.set(path, value);

        // Then
        assertThat(result).isEqualTo(document);
        verify(document).set(path, value);
    }

    @Test
    @DisplayName("Should check if document exists")
    void shouldCheckIfDocumentExists() {
        // Given
        when(document.exists()).thenReturn(true);

        // When
        boolean exists = document.exists();

        // Then
        assertThat(exists).isTrue();
        verify(document).exists();
    }

    @Test
    @DisplayName("Should return false for non-existent document")
    void shouldReturnFalseForNonExistentDocument() {
        // Given
        when(document.exists()).thenReturn(false);

        // When
        boolean exists = document.exists();

        // Then
        assertThat(exists).isFalse();
        verify(document).exists();
    }

    @Test
    @DisplayName("Should convert document to Map")
    void shouldConvertDocumentToMap() {
        // Given
        Map<String, Object> expectedMap = new HashMap<>();
        expectedMap.put("id", "123");
        expectedMap.put("name", "Test");
        when(document.toMap()).thenReturn(expectedMap);

        // When
        Map<String, Object> result = document.toMap();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("id", "123");
        assertThat(result).containsEntry("name", "Test");
        verify(document).toMap();
    }

    @Test
    @DisplayName("Should convert document to JSON")
    void shouldConvertDocumentToJson() {
        // Given
        String expectedJson = "{\"id\":\"123\",\"name\":\"Test\"}";
        when(document.toJson()).thenReturn(expectedJson);

        // When
        String json = document.toJson();

        // Then
        assertThat(json).isEqualTo(expectedJson);
        verify(document).toJson();
    }

    @Test
    @DisplayName("Should support nested path operations")
    void shouldSupportNestedPathOperations() {
        // Given
        String nestedPath = "player.stats.level";
        Integer level = 10;
        when(document.get(nestedPath)).thenReturn(level);
        when(document.set(nestedPath, 11)).thenReturn(document);

        // When
        Object currentLevel = document.get(nestedPath);
        Document updated = document.set(nestedPath, 11);

        // Then
        assertThat(currentLevel).isEqualTo(10);
        assertThat(updated).isEqualTo(document);
        verify(document).get(nestedPath);
        verify(document).set(nestedPath, 11);
    }
}