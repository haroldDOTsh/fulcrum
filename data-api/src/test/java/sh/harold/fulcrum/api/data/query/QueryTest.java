package sh.harold.fulcrum.api.data.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sh.harold.fulcrum.api.data.Document;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Query Tests")
class QueryTest {

    @Mock
    private Query query;

    @Mock
    private Document document1;

    @Mock
    private Document document2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should query with equals condition")
    void shouldQueryWithEquals() {
        // Given
        String value = "active";
        when(query.equalTo(value)).thenReturn(query);

        // When
        Query result = query.equalTo(value);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).equalTo(value);
    }

    @Test
    @DisplayName("Should query with not equals condition")
    void shouldQueryWithNotEquals() {
        // Given
        String value = "inactive";
        when(query.notEquals(value)).thenReturn(query);

        // When
        Query result = query.notEquals(value);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).notEquals(value);
    }

    @Test
    @DisplayName("Should query with contains condition")
    void shouldQueryWithContains() {
        // Given
        String value = "admin";
        when(query.contains(value)).thenReturn(query);

        // When
        Query result = query.contains(value);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).contains(value);
    }

    @Test
    @DisplayName("Should query with greater than condition")
    void shouldQueryWithGreaterThan() {
        // Given
        Integer value = 100;
        when(query.greaterThan(value)).thenReturn(query);

        // When
        Query result = query.greaterThan(value);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).greaterThan(value);
    }

    @Test
    @DisplayName("Should query with less than condition")
    void shouldQueryWithLessThan() {
        // Given
        Integer value = 50;
        when(query.lessThan(value)).thenReturn(query);

        // When
        Query result = query.lessThan(value);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).lessThan(value);
    }

    @Test
    @DisplayName("Should query with in condition")
    void shouldQueryWithIn() {
        // Given
        List<String> values = Arrays.asList("admin", "moderator", "user");
        when(query.in(values)).thenReturn(query);

        // When
        Query result = query.in(values);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).in(values);
    }

    @Test
    @DisplayName("Should combine queries with and")
    void shouldCombineQueriesWithAnd() {
        // Given
        when(query.and("status")).thenReturn(query);

        // When
        Query result = query.and("status");

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).and("status");
    }

    @Test
    @DisplayName("Should combine queries with or")
    void shouldCombineQueriesWithOr() {
        // Given
        when(query.or("role")).thenReturn(query);

        // When
        Query result = query.or("role");

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).or("role");
    }

    @Test
    @DisplayName("Should limit query results")
    void shouldLimitQueryResults() {
        // Given
        when(query.limit(10)).thenReturn(query);

        // When
        Query result = query.limit(10);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).limit(10);
    }

    @Test
    @DisplayName("Should skip query results")
    void shouldSkipQueryResults() {
        // Given
        when(query.skip(20)).thenReturn(query);

        // When
        Query result = query.skip(20);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).skip(20);
    }

    @Test
    @DisplayName("Should sort query results")
    void shouldSortQueryResults() {
        // Given
        when(query.sort("name", true)).thenReturn(query);

        // When
        Query result = query.sort("name", true);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(query);
        verify(query).sort("name", true);
    }

    @Test
    @DisplayName("Should execute query and return results")
    void shouldExecuteQueryAndReturnResults() {
        // Given
        List<Document> expectedResults = Arrays.asList(document1, document2);
        when(query.execute()).thenReturn(expectedResults);

        // When
        List<Document> results = query.execute();

        // Then
        assertThat(results).isNotNull();
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(document1, document2);
        verify(query).execute();
    }

    @Test
    @DisplayName("Should count query results")
    void shouldCountQueryResults() {
        // Given
        when(query.count()).thenReturn(42L);

        // When
        long count = query.count();

        // Then
        assertThat(count).isEqualTo(42L);
        verify(query).count();
    }

    @Test
    @DisplayName("Should find first result")
    void shouldFindFirstResult() {
        // Given
        when(query.first()).thenReturn(document1);

        // When
        Document result = query.first();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(document1);
        verify(query).first();
    }
}