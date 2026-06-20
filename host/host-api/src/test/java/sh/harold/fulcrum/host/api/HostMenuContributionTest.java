package sh.harold.fulcrum.host.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HostMenuContributionTest {
    @Test
    void menuFramesCopyMutableInputsAndValidateSlots() {
        List<HostMenuSlot> slots = new ArrayList<>();
        slots.add(new HostMenuSlot(
                22,
                "emerald",
                "Confirm",
                true,
                Optional.of("CONFIRM"),
                Map.of("menuId", "listing-alpha"),
                Optional.empty()));

        HostMenuRenderFrame frame = new HostMenuRenderFrame(
                "menu:listing-alpha",
                "Confirm Listing",
                slots,
                List.of("listing confirmation opened"),
                List.of(),
                Optional.empty());
        slots.clear();

        assertEquals(1, frame.slots().size());
        assertEquals("Confirm", frame.slots().getFirst().label());
        assertThrows(IllegalArgumentException.class, () -> new HostMenuSlot(
                54,
                "barrier",
                "Bad Slot",
                false,
                Optional.empty(),
                Map.of(),
                Optional.of("outside menu")));
    }

    @Test
    void menuRequestsCarryViewerSessionAndCorrelation() {
        Instant now = Instant.parse("2026-06-20T12:00:00Z");
        HostMenuOpenRequest open = new HostMenuOpenRequest("seller", "session-a", "/menu open listing-alpha", "open", now);
        HostMenuClickRequest click = new HostMenuClickRequest(
                "seller",
                "session-a",
                "menu:listing-alpha",
                "CONFIRM",
                Map.of("menuId", "listing-alpha"),
                "confirm",
                now.plusSeconds(1));

        assertEquals("seller", open.viewerId());
        assertEquals("open", open.correlationId());
        assertEquals("CONFIRM", click.actionId());
        assertEquals("listing-alpha", click.attributes().get("menuId"));
    }

    @Test
    void menuContributionsDeclareCommandAliasesWhenTheyNeedPaperCommands() {
        HostMenuContribution contribution = new HostMenuContribution() {
            @Override
            public Set<String> commandAliases() {
                return Set.of("menu");
            }

            @Override
            public HostMenuRenderFrame open(HostMenuOpenRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HostMenuRenderFrame click(HostMenuClickRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        assertEquals(Set.of("menu"), contribution.commandAliases());
    }
}
