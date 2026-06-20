package sh.harold.fulcrum.validation.auctionexperience;

import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.host.api.HostMenuClickRequest;
import sh.harold.fulcrum.host.api.HostMenuContribution;
import sh.harold.fulcrum.host.api.HostMenuOpenRequest;
import sh.harold.fulcrum.host.api.HostMenuReceipt;
import sh.harold.fulcrum.host.api.HostMenuRenderFrame;
import sh.harold.fulcrum.host.api.HostMenuSlot;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AuctionPaperMenuContribution implements HostMenuContribution {
    private static final String DEFAULT_BID_AMOUNT_MINOR = "100";
    private static final String DEFAULT_CURRENCY = "COIN";

    private final AuctionExperience experience;
    private final Map<String, ListingContext> listingContexts = new ConcurrentHashMap<>();

    public AuctionPaperMenuContribution() {
        this(new RecordingAuctionCommandPort(), 0);
    }

    public AuctionPaperMenuContribution(AuctionCommandPort commandPort, long fencingEpoch) {
        this.experience = new AuctionExperience(Objects.requireNonNull(commandPort, "commandPort"), fencingEpoch);
    }

    @Override
    public Set<String> commandAliases() {
        return Set.of("ah");
    }

    @Override
    public HostMenuRenderFrame open(HostMenuOpenRequest request) {
        Objects.requireNonNull(request, "request");
        AuctionExperienceSession session = new AuctionExperienceSession(request.sessionId());
        AuctionExperienceResult result = experience.handle(
                session,
                new AhProxyCommand(request.viewerId(), request.command(), request.correlationId(), request.occurredAt()));
        ListingContext.parse(request.command()).ifPresent(context ->
                listingContexts.put(contextKey(request.sessionId(), context.auctionId()), context));
        return frame(request.sessionId(), result);
    }

    @Override
    public HostMenuRenderFrame click(HostMenuClickRequest request) {
        Objects.requireNonNull(request, "request");
        AuctionMenuActionType action = AuctionMenuActionType.valueOf(request.actionId());
        AuctionMenuClick click = switch (action) {
            case CONFIRM_LISTING -> AuctionMenuClick.confirmListing(
                    request.viewerId(),
                    auctionId(request),
                    attribute(request, "itemRef"),
                    attribute(request, "currency"),
                    request.correlationId(),
                    request.occurredAt());
            case PLACE_BID -> AuctionMenuClick.placeBid(
                    request.viewerId(),
                    auctionId(request),
                    Long.parseLong(request.attributes().getOrDefault("amountMinor", DEFAULT_BID_AMOUNT_MINOR)),
                    request.attributes().getOrDefault("currency", DEFAULT_CURRENCY),
                    request.correlationId(),
                    request.occurredAt());
            case SETTLE -> AuctionMenuClick.settle(
                    request.viewerId(),
                    auctionId(request),
                    request.correlationId(),
                    request.occurredAt());
            case CANCEL -> AuctionMenuClick.cancel(
                    request.viewerId(),
                    auctionId(request),
                    request.attributes().getOrDefault("reason", "cancelled-by-player"),
                    request.correlationId(),
                    request.occurredAt());
        };
        AuctionExperienceResult result = experience.handle(new AuctionExperienceSession(request.sessionId()), click);
        return frame(request.sessionId(), result);
    }

    public List<String> supportedCommands() {
        return experience.supportedCommands();
    }

    private HostMenuRenderFrame frame(String sessionId, AuctionExperienceResult result) {
        AuctionMenuView view = result.menuView();
        return new HostMenuRenderFrame(
                menuId(view),
                view.title(),
                slots(sessionId, view),
                result.messages(),
                result.receipts().stream().map(AuctionPaperMenuContribution::receipt).toList(),
                result.refusalReason());
    }

    private List<HostMenuSlot> slots(String sessionId, AuctionMenuView view) {
        List<HostMenuSlot> slots = new ArrayList<>();
        for (int index = 0; index < view.actions().size(); index++) {
            AuctionMenuAction action = view.actions().get(index);
            slots.add(new HostMenuSlot(
                    20 + (index * 2),
                    itemKey(action.type()),
                    action.label(),
                    action.enabled(),
                    action.enabled() ? Optional.of(action.type().name()) : Optional.empty(),
                    attributes(sessionId, view, action),
                    action.refusalReason()));
        }
        return slots;
    }

    private Map<String, String> attributes(String sessionId, AuctionMenuView view, AuctionMenuAction action) {
        Map<String, String> attributes = new LinkedHashMap<>();
        view.auctionId().ifPresent(auctionId -> attributes.put("auctionId", auctionId));
        if (action.type() == AuctionMenuActionType.CONFIRM_LISTING && view.auctionId().isPresent()) {
            ListingContext context = listingContexts.get(contextKey(sessionId, view.auctionId().orElseThrow()));
            if (context != null) {
                attributes.put("itemRef", context.itemRef());
                attributes.put("currency", context.currency());
            }
        }
        if (action.type() == AuctionMenuActionType.PLACE_BID) {
            attributes.put("amountMinor", DEFAULT_BID_AMOUNT_MINOR);
            attributes.put("currency", DEFAULT_CURRENCY);
        }
        if (action.type() == AuctionMenuActionType.CANCEL) {
            attributes.put("reason", "cancelled-by-player");
        }
        return attributes;
    }

    private static HostMenuReceipt receipt(AuctionExperienceReceipt receipt) {
        return new HostMenuReceipt(
                receipt.commandId(),
                receipt.contractName(),
                receipt.commandName(),
                receipt.aggregateId(),
                receipt.correlationId());
    }

    private static String menuId(AuctionMenuView view) {
        return view.auctionId().map(auctionId -> "auction:" + auctionId).orElse("auction:blocked");
    }

    private static String itemKey(AuctionMenuActionType type) {
        return switch (type) {
            case CONFIRM_LISTING -> "emerald";
            case PLACE_BID -> "gold_ingot";
            case SETTLE -> "anvil";
            case CANCEL -> "barrier";
        };
    }

    private static String auctionId(HostMenuClickRequest request) {
        String fromAttributes = request.attributes().get("auctionId");
        if (fromAttributes != null && !fromAttributes.isBlank()) {
            return fromAttributes.trim();
        }
        if (request.menuId().startsWith("auction:")) {
            return request.menuId().substring("auction:".length());
        }
        throw new IllegalArgumentException("auctionId attribute is required");
    }

    private static String attribute(HostMenuClickRequest request, String key) {
        String value = request.attributes().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " attribute is required");
        }
        return value.trim();
    }

    private static String contextKey(String sessionId, String auctionId) {
        return sessionId + ":" + auctionId;
    }

    private record ListingContext(String auctionId, String itemRef, String currency) {
        private ListingContext {
            auctionId = Names.requireNonBlank(auctionId, "auctionId");
            itemRef = Names.requireNonBlank(itemRef, "itemRef");
            currency = Names.requireNonBlank(currency, "currency");
        }

        static Optional<ListingContext> parse(String command) {
            String[] parts = Names.requireNonBlank(command, "command").trim().split("\\s+");
            if (parts.length == 5 && parts[0].equalsIgnoreCase("/ah") && parts[1].equalsIgnoreCase("sell")) {
                return Optional.of(new ListingContext(parts[2], parts[3], parts[4]));
            }
            return Optional.empty();
        }
    }

    private static final class RecordingAuctionCommandPort implements AuctionCommandPort {
        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            return new AuctionExperienceReceipt(
                    command.envelope().commandId(),
                    command.envelope().contractName(),
                    command.envelope().commandName(),
                    command.envelope().aggregateId(),
                    command.envelope().idempotencyKey().value());
        }
    }
}
