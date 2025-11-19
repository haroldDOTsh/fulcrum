package sh.harold.fulcrum.api.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPatchTest {

    @Test
    void applyToMapAppliesSetUnsetAndSetOnInsert() {
        DocumentPatch patch = DocumentPatch.builder()
                .set("profile.display", "Admin")
                .set("settings.theme", "LIGHT")
                .unset("tags.legacy")
                .setOnInsert("username", "Harold")
                .upsert(true)
                .build();

        Map<String, Object> target = new HashMap<>();
        Map<String, Object> tags = new HashMap<>();
        tags.put("legacy", "vip");
        target.put("tags", tags);

        patch.applyToMap(target, true);

        assertThat(target)
                .containsEntry("username", "Harold");

        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) target.get("profile");
        assertThat(profile).isNotNull().containsEntry("display", "Admin");

        assertThat(target).doesNotContainKeys("tags.legacy", "tags");
    }

    @Test
    void setOnInsertIsSkippedWhenFlagFalse() {
        DocumentPatch patch = DocumentPatch.builder()
                .set("settings.hints", true)
                .setOnInsert("username", "Harold")
                .upsert(false)
                .build();

        Map<String, Object> target = new HashMap<>();
        patch.applyToMap(target, false);

        assertThat(target).containsEntry("settings.hints", true);
        assertThat(target).doesNotContainKey("username");
    }
}
