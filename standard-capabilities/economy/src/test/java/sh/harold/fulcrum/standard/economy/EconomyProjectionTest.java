package sh.harold.fulcrum.standard.economy;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EconomyProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-16T23:15:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000001201"));
    private static final EconomyAccountId ACCOUNT_ID = new EconomyAccountId(SUBJECT, "coins");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-economy-projection");

    @Test
    void rebuildsBalanceFromAuditableLedgerEntries() {
        EconomyProjection projection = EconomyProjection.rebuild(List.of(
                event("entry-1", 100, 100, 1),
                event("entry-2", -25, 75, 2)));

        assertEquals(75, projection.balance(ACCOUNT_ID).orElseThrow().balanceMinorUnits());
        assertEquals(List.of("entry-1", "entry-2"), projection.ledgerEntriesFor(ACCOUNT_ID).stream()
                .map(EconomyLedgerProjectionRow::entryId)
                .toList());
    }

    @Test
    void rejectsLedgerEntryThatDoesNotMatchRunningBalance() {
        List<EconomyLedgerEntryRecorded> events = List.of(
                event("entry-1", 100, 100, 1),
                event("entry-2", -25, 90, 2));

        assertThrows(IllegalArgumentException.class, () -> EconomyProjection.rebuild(events));
    }

    private static EconomyLedgerEntryRecorded event(String entryId, long delta, long resultingBalance, long revision) {
        Revision nextRevision = new Revision(revision);
        return new EconomyLedgerEntryRecorded(
                new EconomyLedgerEntry(
                        entryId,
                        ACCOUNT_ID,
                        delta,
                        resultingBalance,
                        "projection-test",
                        PRINCIPAL,
                        NOW.plusSeconds(revision),
                        "idem-" + entryId,
                        "command-" + entryId,
                        nextRevision),
                nextRevision);
    }
}
