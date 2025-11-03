package sh.harold.fulcrum.data.playtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaytimeTrackerTest {

    private PlaytimeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PlaytimeTracker();
    }

    @Test
    void recordSegmentAccumulatesTotals() {
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

        Map<String, Object> playtime = session.getPlaytime();
        assertThat(playtime.get("totalMs")).isEqualTo(1_000L);

        Map<String, Object> families = cast(playtime.get("families"));
        Map<String, Object> family = cast(families.get("spleef"));
        assertThat(family.get("familyId")).isEqualTo("spleef");
        assertThat(family.get("totalMs")).isEqualTo(1_000L);

        Map<String, Object> variants = cast(family.get("variants"));
        Map<String, Object> variant = cast(variants.get("classic"));
        assertThat(variant.get("variantId")).isEqualTo("classic");
        assertThat(variant.get("totalMs")).isEqualTo(1_000L);

        Map<String, Object> processed = cast(playtime.get("processedSegments"));
        assertThat(processed).containsKey("session:segment-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
