package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritySnapshotCacheReaderTest {
    @Test
    void quotedPresenceReadDecodesValkeyStateRecord() {
        UUID subjectId = UUID.randomUUID();
        Map<String, Object> statePayload = Map.of(
            "subjectId", subjectId.toString(),
            "playerId", subjectId.toString(),
            "username", "Richa",
            "online", true,
            "currentServer", "lobby",
            "currentProxy", "proxy-a",
            "revision", 9L
        );
        AuthorityStateRecord record = new AuthorityStateRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            "presence",
            subjectId.toString(),
            9L,
            "session",
            "state.session",
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            statePayload,
            AuthorityStateRecord.stateFingerprint(statePayload),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            Instant.ofEpochMilli(1_000L)
        );
        Map<String, String> fields = AuthoritySnapshotCacheCodec.fields(record, 1_100L);
        AuthoritySnapshotCacheReader reader = new AuthoritySnapshotCacheReader(
            key -> Optional.of(fields),
            1_000L,
            Clock.fixed(Instant.ofEpochMilli(1_200L), ZoneOffset.UTC)
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> read = reader
            .quotePresence(subjectId, DataAuthority.ReadRequirement.atLeast(9L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.subjectId()).isEqualTo(subjectId);
            assertThat(snapshot.online()).isTrue();
            assertThat(snapshot.currentServer()).isEqualTo("lobby");
            assertThat(snapshot.revision()).isEqualTo(9L);
        });
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(read.quote().deliveryReceipt().satisfies(
            "presence",
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            9L
        )).isTrue();
    }
}
