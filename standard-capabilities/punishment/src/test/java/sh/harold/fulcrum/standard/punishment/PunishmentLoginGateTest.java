package sh.harold.fulcrum.standard.punishment;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PunishmentLoginGateTest {
    private static final Instant NOW = Instant.parse("2026-06-16T17:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000601"));
    private static final SubjectId OTHER_SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000602"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-punishment-authority");

    @Test
    void deniesLoginWhenActivePunishmentProjectionApplies() {
        PunishmentLoginDecision decision = PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW.plusSeconds(30)),
                Optional.of(snapshot(SUBJECT, NOW.plusSeconds(60))));

        assertFalse(decision.allowed());
        assertEquals(Optional.of("ban evasion"), decision.denialReason());
    }

    @Test
    void allowsLoginWhenProjectionIsMissingOrExpired() {
        assertTrue(PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW),
                Optional.empty()).allowed());
        assertTrue(PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW.plusSeconds(60)),
                Optional.of(snapshot(SUBJECT, NOW.plusSeconds(60)))).allowed());
    }

    @Test
    void rejectsProjectionRowsForTheWrongSubject() {
        assertThrows(IllegalArgumentException.class, () -> PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW),
                Optional.of(snapshot(OTHER_SUBJECT, NOW.plusSeconds(60)))));
    }

    private static ActivePunishmentSnapshot snapshot(SubjectId subjectId, Instant expiresAt) {
        return new ActivePunishmentSnapshot(
                subjectId,
                "punishment-login-test",
                "ban evasion",
                PRINCIPAL,
                NOW,
                expiresAt);
    }
}
