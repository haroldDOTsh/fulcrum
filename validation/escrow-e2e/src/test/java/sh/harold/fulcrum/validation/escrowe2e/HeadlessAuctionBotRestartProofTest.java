package sh.harold.fulcrum.validation.escrowe2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HeadlessAuctionBotRestartProofTest {
    @Test
    void headlessBotTranscriptSurvivesWorkerReplacementAndDuplicateCloseReplay() {
        HeadlessAuctionBotWitness.SettlementCertificate certificate = new HeadlessAuctionBotWitness().run();

        assertEquals(HeadlessAuctionBotWitness.SCHEMA, certificate.schema());
        assertEquals("jvm-semantic-fixture", certificate.executionMode());
        assertFalse(certificate.podDeletionRequested());
        assertEquals("auction-restart-proof", certificate.auctionId());
        assertEquals("bidder-high", certificate.winningBidder());
        assertEquals(275, certificate.totalHeldMinor());
        assertEquals(175, certificate.totalPayoutMinor());
        assertEquals(100, certificate.totalRefundedMinor());
        assertEquals(1, certificate.settleResponseEmissions());
        assertEquals(1, certificate.replayedCloseDecisions());
        assertEquals(7L, certificate.transcript().stream().filter(step -> step.kind().equals("bot-command")).count());
        assertTrue(certificate.workerIncarnations().contains("escrow-worker-before-restart"));
        assertTrue(certificate.workerIncarnations().contains("escrow-worker-after-restart"));
        assertFalse(certificate.releasePlanFingerprint().isBlank());
        certificate.assertConserved();
    }
}
