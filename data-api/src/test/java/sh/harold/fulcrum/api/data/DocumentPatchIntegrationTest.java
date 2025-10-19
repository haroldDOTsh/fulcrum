package sh.harold.fulcrum.api.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.InMemoryStorageBackend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPatchIntegrationTest {

    private static final String COLLECTION = "players";
    private InMemoryStorageBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryStorageBackend();
    }

    @Test
    void patchCreatesDocumentWhenUpsertEnabled() {
        Document doc = backend.getDocument(COLLECTION, "player-1").join();
        assertThat(doc.exists()).isFalse();

        Map<String, Object> rankInfo = new HashMap<>();
        rankInfo.put("primary", "ADMIN");
        rankInfo.put("all", List.of("ADMIN"));

        DocumentPatch patch = DocumentPatch.builder()
                .upsert(true)
                .set("rank", "ADMIN")
                .set("rankInfo", rankInfo)
                .setOnInsert("username", "Harold")
                .build();

        doc.patch(patch);

        assertThat(doc.exists()).isTrue();
        assertThat(doc.get("rank")).isEqualTo("ADMIN");
        assertThat(doc.get("username")).isEqualTo("Harold");

        Document stored = backend.getDocument(COLLECTION, "player-1").join();
        assertThat(stored.exists()).isTrue();
        assertThat(stored.get("rank")).isEqualTo("ADMIN");
        assertThat(stored.get("rankInfo"))
                .isInstanceOf(Map.class);
    }

    @Test
    void patchUpdatesExistingDocument() {
        Document initial = backend.getDocument(COLLECTION, "player-2").join();
        DocumentPatch createPatch = DocumentPatch.builder()
                .upsert(true)
                .set("rank", "ADMIN")
                .set("rankInfo.primary", "ADMIN")
                .set("rankInfo.all", List.of("ADMIN", "DEFAULT"))
                .build();
        initial.patch(createPatch);

        DocumentPatch updatePatch = DocumentPatch.builder()
                .upsert(true)
                .set("rank", "MODERATOR")
                .set("rankInfo.primary", "MODERATOR")
                .unset("rankInfo.updatedBy")
                .build();

        initial.patch(updatePatch);

        Document stored = backend.getDocument(COLLECTION, "player-2").join();
        assertThat(stored.get("rank")).isEqualTo("MODERATOR");
        @SuppressWarnings("unchecked")
        Map<String, Object> rankInfo = (Map<String, Object>) stored.get("rankInfo");
        assertThat(rankInfo).containsEntry("primary", "MODERATOR");
        assertThat(rankInfo).doesNotContainKey("updatedBy");
    }

    @Test
    void patchDoesNothingWhenUpsertDisabledAndDocumentMissing() {
        Document doc = backend.getDocument(COLLECTION, "player-missing").join();
        assertThat(doc.exists()).isFalse();

        DocumentPatch patch = DocumentPatch.builder()
                .upsert(false)
                .set("rank", "ADMIN")
                .build();

        doc.patch(patch);

        assertThat(doc.exists()).isFalse();
        Document stored = backend.getDocument(COLLECTION, "player-missing").join();
        assertThat(stored.exists()).isFalse();
    }
}
