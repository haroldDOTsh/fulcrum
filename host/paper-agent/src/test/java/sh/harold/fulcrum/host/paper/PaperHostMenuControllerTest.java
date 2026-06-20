package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.host.api.HostMenuClickRequest;
import sh.harold.fulcrum.host.api.HostMenuContribution;
import sh.harold.fulcrum.host.api.HostMenuOpenRequest;
import sh.harold.fulcrum.host.api.HostMenuRenderFrame;
import sh.harold.fulcrum.host.api.HostMenuSlot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperHostMenuControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-20T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void opensRegisteredAliasWithViewerScopedRequest() {
        RecordingContribution contribution = new RecordingContribution("ah");
        PaperHostMenuController controller = new PaperHostMenuController(List.of(contribution), CLOCK);

        PaperHostMenuController.OpenedMenu opened = controller
                .open("viewer-a", "paper-session:viewer-a", "/ah sell auction-a item COIN")
                .orElseThrow();

        assertEquals(Set.of("ah"), controller.commandAliases());
        assertEquals("viewer-a", contribution.openRequest.viewerId());
        assertEquals("paper-session:viewer-a", contribution.openRequest.sessionId());
        assertEquals("/ah sell auction-a item COIN", contribution.openRequest.command());
        assertEquals("menu-open-1", contribution.openRequest.correlationId());
        assertEquals("menu:auction-a", opened.frame().menuId());
        assertTrue(opened.slotsByIndex().containsKey(22));
    }

    @Test
    void clicksEnabledSlotWithFrameAttributesAndUpdatesActiveMenu() {
        RecordingContribution contribution = new RecordingContribution("ah");
        PaperHostMenuController controller = new PaperHostMenuController(List.of(contribution), CLOCK);
        PaperHostMenuController.OpenedMenu opened = controller
                .open("viewer-a", "paper-session:viewer-a", "/ah browse auction-a")
                .orElseThrow();

        PaperHostMenuController.OpenedMenu next = controller.click(opened, 22).orElseThrow();

        assertEquals("viewer-a", contribution.clickRequest.viewerId());
        assertEquals("paper-session:viewer-a", contribution.clickRequest.sessionId());
        assertEquals("menu:auction-a", contribution.clickRequest.menuId());
        assertEquals("CONFIRM", contribution.clickRequest.actionId());
        assertEquals("auction-a", contribution.clickRequest.attributes().get("auctionId"));
        assertEquals("menu-click-2", contribution.clickRequest.correlationId());
        assertEquals("menu:auction-a:next", next.frame().menuId());
    }

    @Test
    void ignoresDisabledSlotsInsteadOfDispatchingAContributionClick() {
        RecordingContribution contribution = new RecordingContribution("ah", false);
        PaperHostMenuController controller = new PaperHostMenuController(List.of(contribution), CLOCK);
        PaperHostMenuController.OpenedMenu opened = controller
                .open("viewer-a", "paper-session:viewer-a", "/ah browse auction-a")
                .orElseThrow();

        assertTrue(controller.click(opened, 22).isEmpty());
        assertEquals(null, contribution.clickRequest);
    }

    @Test
    void rejectsDuplicateAliasesAcrossContributions() {
        assertThrows(IllegalArgumentException.class, () -> new PaperHostMenuController(
                List.of(new RecordingContribution("ah"), new RecordingContribution("/AH")),
                CLOCK));
    }

    private static final class RecordingContribution implements HostMenuContribution {
        private final Set<String> aliases;
        private final boolean enabled;
        private HostMenuOpenRequest openRequest;
        private HostMenuClickRequest clickRequest;

        private RecordingContribution(String alias) {
            this(alias, true);
        }

        private RecordingContribution(String alias, boolean enabled) {
            this.aliases = Set.of(alias);
            this.enabled = enabled;
        }

        @Override
        public Set<String> commandAliases() {
            return aliases;
        }

        @Override
        public HostMenuRenderFrame open(HostMenuOpenRequest request) {
            openRequest = request;
            return new HostMenuRenderFrame(
                    "menu:auction-a",
                    "Auction",
                    List.of(new HostMenuSlot(
                            22,
                            "emerald",
                            "Confirm",
                            enabled,
                            enabled ? Optional.of("CONFIRM") : Optional.empty(),
                            Map.of("auctionId", "auction-a"),
                            enabled ? Optional.empty() : Optional.of("not ready"))),
                    List.of(),
                    List.of(),
                    Optional.empty());
        }

        @Override
        public HostMenuRenderFrame click(HostMenuClickRequest request) {
            clickRequest = request;
            return new HostMenuRenderFrame(
                    "menu:auction-a:next",
                    "Auction",
                    List.of(),
                    List.of("submitted"),
                    List.of(),
                    Optional.empty());
        }
    }
}
