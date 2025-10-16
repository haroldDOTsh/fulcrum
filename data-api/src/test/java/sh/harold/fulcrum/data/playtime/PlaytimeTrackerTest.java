package sh.harold.fulcrum.data.playtime;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaytimeTrackerTest {

    @Mock
    private MongoDatabase database;

    @Mock
    private MongoCollection<Document> collection;

    private PlaytimeTracker tracker;

    @BeforeEach
    void setUp() {
        when(database.getCollection(anyString())).thenReturn(collection);
        tracker = new PlaytimeTracker(database, Logger.getLogger("test"));
    }

    @Test
    void recordSegmentBootstrapsDocumentAndAvoidsDuplicateUpsert() {
        UUID playerId = UUID.fromString("8d65b9e8-effb-469d-84fa-41f3d3f704a6");
        PlayerSessionRecord session = PlayerSessionRecord.newSession(playerId, "session", "server");

        PlayerSessionRecord.Segment segment = new PlayerSessionRecord.Segment();
        segment.setSegmentId("segment-1");
        segment.setType("MINIGAME");
        segment.setStartedAt(1_000L);
        segment.setEndedAt(2_000L);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("family", "spleef");
        metadata.put("variant", "classic");
        segment.setMetadata(metadata);

        tracker.recordSegment(session, segment);

        ArgumentCaptor<UpdateOptions> optionsCaptor = ArgumentCaptor.forClass(UpdateOptions.class);
        ArgumentCaptor<Document> updateCaptor = ArgumentCaptor.forClass(Document.class);

        verify(collection, times(2)).updateOne(any(), updateCaptor.capture(), optionsCaptor.capture());

        List<UpdateOptions> options = optionsCaptor.getAllValues();
        assertThat(options).hasSize(2);
        assertThat(options.get(0).isUpsert()).isTrue();
        assertThat(options.get(1).isUpsert()).isFalse();

        List<Document> updates = updateCaptor.getAllValues();
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0)).containsKey("$setOnInsert");

        Document guardedUpdate = updates.get(1);
        assertThat(guardedUpdate).containsKeys("$inc", "$set");

        Document setClause = (Document) guardedUpdate.get("$set");
        assertThat(setClause.keySet()).anyMatch(key -> key.startsWith("playtime.processedSegments."));
    }
}
