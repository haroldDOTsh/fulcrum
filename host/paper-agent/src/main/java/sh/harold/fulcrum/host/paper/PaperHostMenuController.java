package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.host.api.HostMenuClickRequest;
import sh.harold.fulcrum.host.api.HostMenuContribution;
import sh.harold.fulcrum.host.api.HostMenuOpenRequest;
import sh.harold.fulcrum.host.api.HostMenuRenderFrame;
import sh.harold.fulcrum.host.api.HostMenuSlot;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

final class PaperHostMenuController {
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final Map<String, HostMenuContribution> contributionsByAlias;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    PaperHostMenuController(Collection<HostMenuContribution> contributions, Clock clock) {
        this.contributionsByAlias = contributionsByAlias(contributions);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Set<String> commandAliases() {
        return contributionsByAlias.keySet();
    }

    Optional<OpenedMenu> open(String viewerId, String sessionId, String rawCommand) {
        String alias = alias(rawCommand);
        HostMenuContribution contribution = contributionsByAlias.get(alias);
        if (contribution == null) {
            return Optional.empty();
        }
        HostMenuRenderFrame frame = contribution.open(new HostMenuOpenRequest(
                PaperArtifactNames.requireNonBlank(viewerId, "viewerId"),
                PaperArtifactNames.requireNonBlank(sessionId, "sessionId"),
                PaperArtifactNames.requireNonBlank(rawCommand, "rawCommand"),
                correlationId("menu-open"),
                clock.instant()));
        return Optional.of(openedMenu(viewerId, sessionId, contribution, frame));
    }

    Optional<OpenedMenu> click(OpenedMenu activeMenu, int slot) {
        Objects.requireNonNull(activeMenu, "activeMenu");
        HostMenuSlot clicked = activeMenu.slotsByIndex().get(slot);
        if (clicked == null || !clicked.enabled() || clicked.actionId().isEmpty()) {
            return Optional.empty();
        }
        HostMenuRenderFrame frame = activeMenu.contribution().click(new HostMenuClickRequest(
                activeMenu.viewerId(),
                activeMenu.sessionId(),
                activeMenu.frame().menuId(),
                clicked.actionId().orElseThrow(),
                clicked.attributes(),
                correlationId("menu-click"),
                clock.instant()));
        return Optional.of(openedMenu(activeMenu.viewerId(), activeMenu.sessionId(), activeMenu.contribution(), frame));
    }

    static String rawCommand(String label, String[] args) {
        String checkedLabel = PaperArtifactNames.requireNonBlank(label, "label");
        String command = "/" + checkedLabel.replaceFirst("^/+", "");
        if (args == null || args.length == 0) {
            return command;
        }
        return command + " " + String.join(" ", args);
    }

    private static OpenedMenu openedMenu(
            String viewerId,
            String sessionId,
            HostMenuContribution contribution,
            HostMenuRenderFrame frame) {
        Map<Integer, HostMenuSlot> slotsByIndex = new LinkedHashMap<>();
        for (HostMenuSlot slot : frame.slots()) {
            slotsByIndex.put(slot.slot(), slot);
        }
        return new OpenedMenu(viewerId, sessionId, contribution, frame, slotsByIndex);
    }

    private String correlationId(String prefix) {
        return prefix + "-" + sequence.incrementAndGet();
    }

    private static String alias(String rawCommand) {
        String checked = PaperArtifactNames.requireNonBlank(rawCommand, "rawCommand").trim();
        String first = checked.split("\\s+", 2)[0].replaceFirst("^/+", "");
        return normalizeAlias(first);
    }

    private static Map<String, HostMenuContribution> contributionsByAlias(
            Collection<HostMenuContribution> contributions) {
        Map<String, HostMenuContribution> mapped = new LinkedHashMap<>();
        for (HostMenuContribution contribution : Objects.requireNonNull(contributions, "contributions")) {
            Objects.requireNonNull(contribution, "contribution").commandAliases().stream()
                    .map(PaperHostMenuController::normalizeAlias)
                    .forEach(alias -> {
                        HostMenuContribution previous = mapped.putIfAbsent(alias, contribution);
                        if (previous != null && previous != contribution) {
                            throw new IllegalArgumentException("duplicate Paper menu command alias: " + alias);
                        }
                    });
        }
        return Map.copyOf(mapped);
    }

    private static String normalizeAlias(String alias) {
        String normalized = PaperArtifactNames.requireNonBlank(alias, "alias")
                .replaceFirst("^/+", "")
                .toLowerCase(Locale.ROOT);
        if (!ALIAS.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Paper menu command alias must be a stable command token");
        }
        return normalized;
    }

    record OpenedMenu(
            String viewerId,
            String sessionId,
            HostMenuContribution contribution,
            HostMenuRenderFrame frame,
            Map<Integer, HostMenuSlot> slotsByIndex) {
        OpenedMenu {
            viewerId = PaperArtifactNames.requireNonBlank(viewerId, "viewerId");
            sessionId = PaperArtifactNames.requireNonBlank(sessionId, "sessionId");
            contribution = Objects.requireNonNull(contribution, "contribution");
            frame = Objects.requireNonNull(frame, "frame");
            slotsByIndex = Map.copyOf(Objects.requireNonNull(slotsByIndex, "slotsByIndex"));
        }
    }
}
