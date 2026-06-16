package sh.harold.fulcrum.standard.guild;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GuildRosterProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-16T22:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-guild-test");
    private static final SubjectId OWNER = subject("00000000-0000-0000-0000-000000001001");
    private static final SubjectId MEMBER = subject("00000000-0000-0000-0000-000000001002");
    private static final SubjectId OTHER = subject("00000000-0000-0000-0000-000000001003");

    @Test
    void projectionIndexesGuildByEachSubject() {
        GuildId guildId = new GuildId("guild-alpha");
        GuildRosterProjection projection = GuildRosterProjection.rebuild(List.of(new GuildCreated(
                snapshot(guildId, OWNER, MEMBER),
                new Revision(1))));

        assertEquals(guildId, projection.guildFor(OWNER).orElseThrow());
        assertEquals(guildId, projection.guildFor(MEMBER).orElseThrow());
        assertEquals(List.of(OWNER, MEMBER), projection.membersFor(MEMBER));
    }

    @Test
    void subjectCannotBelongToTwoActiveGuildsInOneProjection() {
        assertThrows(IllegalArgumentException.class, () -> GuildRosterProjection.rebuild(List.of(
                new GuildCreated(snapshot(new GuildId("guild-alpha"), OWNER, MEMBER), new Revision(1)),
                new GuildCreated(snapshot(new GuildId("guild-beta"), OTHER, MEMBER), new Revision(1)))));
    }

    @Test
    void replayRequiresIncreasingGuildRevision() {
        GuildId guildId = new GuildId("guild-alpha");
        assertThrows(IllegalArgumentException.class, () -> GuildRosterProjection.rebuild(List.of(
                new GuildCreated(snapshot(guildId, OWNER, MEMBER), new Revision(2)),
                new GuildCreated(snapshot(guildId, OWNER, MEMBER), new Revision(2)))));
    }

    private static GuildRosterSnapshot snapshot(GuildId guildId, SubjectId owner, SubjectId member) {
        return new GuildRosterSnapshot(guildId, owner, List.of(owner, member), "Alpha Guild", PRINCIPAL, NOW);
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
