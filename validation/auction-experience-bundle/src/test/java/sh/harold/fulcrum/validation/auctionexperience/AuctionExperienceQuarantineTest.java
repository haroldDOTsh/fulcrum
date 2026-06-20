package sh.harold.fulcrum.validation.auctionexperience;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowContract;
import sh.harold.fulcrum.validation.auctionescrow.OpenEscrow;
import sh.harold.fulcrum.validation.auctionescrow.PlaceHold;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionExperienceQuarantineTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Test
    void headlessExperienceEmitsOnlyEscrowContractFrames() {
        RecordingPort port = new RecordingPort();
        AuctionExperience experience = new AuctionExperience(port, 17);
        AuctionExperienceSession session = new AuctionExperienceSession("bot-session");

        AuctionExperienceResult sell = experience.handle(
                session,
                new AhProxyCommand("seller", "/ah sell auction-alpha beacon COIN", "proxy-sell", NOW));
        AuctionExperienceResult opened = experience.handle(
                session,
                AuctionMenuClick.confirmListing("seller", "auction-alpha", "beacon", "COIN", "confirm-open", NOW.plusSeconds(1)));
        AuctionExperienceResult bid = experience.handle(
                session,
                AuctionMenuClick.placeBid("bidder", "auction-alpha", 125, "COIN", "bid-125", NOW.plusSeconds(2)));

        assertTrue(sell.receipts().isEmpty());
        assertEquals("Confirm Auction Listing", sell.menuView().title());
        assertEquals(2, opened.receipts().size() + bid.receipts().size());
        assertEquals(AuctionEscrowContract.CONTRACT, port.commands().getFirst().envelope().contractName());
        assertEquals(AuctionEscrowContract.OPEN, port.commands().getFirst().envelope().commandName());
        assertEquals(AuctionEscrowContract.HOLD, port.commands().get(1).envelope().commandName());
        assertInstanceOf(OpenEscrow.class, port.commands().getFirst().envelope().payload());
        assertInstanceOf(PlaceHold.class, port.commands().get(1).envelope().payload());
    }

    @Test
    void productionClasspathAndBytecodeCannotSeeEscrowBackend() throws Exception {
        ClassLoader loader = AuctionExperience.class.getClassLoader();
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowAuthority", false, loader));
        assertCompiledExperienceDoesNotMentionBackendClasses();
    }

    private static void assertCompiledExperienceDoesNotMentionBackendClasses() throws IOException, URISyntaxException {
        Path classesRoot = Path.of(AuctionExperience.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> forbidden = List.of(
                "AuctionEscrowAuthority",
                "AuctionEscrowState",
                "AuctionEscrowReceipt",
                "EscrowSnapshot",
                "ReleasePlan",
                "ReleaseLine");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(classesRoot)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).toList()) {
                String hex = HexFormat.of().formatHex(Files.readAllBytes(file));
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                forbidden.stream()
                        .filter(term -> text.contains(term) || hex.contains(HexFormat.of().formatHex(term.getBytes(StandardCharsets.UTF_8))))
                        .map(term -> classesRoot.relativize(file) + " mentions " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Experience bytecode leaked backend symbols: " + violations);
    }

    private static final class RecordingPort implements AuctionCommandPort {
        private final List<AuthorityCommand<AuctionEscrowCommand>> commands = new ArrayList<>();

        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            commands.add(command);
            return new AuctionExperienceReceipt(
                    command.envelope().commandId(),
                    command.envelope().contractName(),
                    command.envelope().commandName(),
                    command.envelope().aggregateId(),
                    command.envelope().idempotencyKey().value());
        }

        List<AuthorityCommand<AuctionEscrowCommand>> commands() {
            assertFalse(commands.isEmpty(), "expected at least one command");
            return commands;
        }
    }
}
