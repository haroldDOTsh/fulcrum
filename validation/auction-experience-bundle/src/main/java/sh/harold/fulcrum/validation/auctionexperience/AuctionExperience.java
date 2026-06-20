package sh.harold.fulcrum.validation.auctionexperience;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowContract;
import sh.harold.fulcrum.validation.auctionescrow.CancelEscrow;
import sh.harold.fulcrum.validation.auctionescrow.OpenEscrow;
import sh.harold.fulcrum.validation.auctionescrow.PlaceHold;
import sh.harold.fulcrum.validation.auctionescrow.SettleEscrow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AuctionExperience {
    private static final String SOURCE = "auction-experience-bundle";

    private final AuctionCommandPort commandPort;
    private final long fencingEpoch;

    public AuctionExperience(AuctionCommandPort commandPort, long fencingEpoch) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        this.fencingEpoch = fencingEpoch;
    }

    public AuctionExperienceResult handle(AuctionExperienceSession session, AuctionExperienceInput input) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(input, "input");
        if (input instanceof AhProxyCommand command) {
            return handleProxyCommand(command);
        }
        if (input instanceof AuctionMenuClick click) {
            return handleMenuClick(session, click);
        }
        throw new IllegalArgumentException("unknown auction experience input");
    }

    private AuctionExperienceResult handleProxyCommand(AhProxyCommand command) {
        String[] parts = command.rawCommand().trim().split("\\s+");
        if (parts.length == 5 && parts[0].equalsIgnoreCase("/ah") && parts[1].equalsIgnoreCase("sell")) {
            AuctionMenuView view = AuctionMenuView.listingConfirmation(parts[2], parts[3], parts[4]);
            return AuctionExperienceResult.rendered(view, "listing confirmation opened");
        }
        if (parts.length == 3 && parts[0].equalsIgnoreCase("/ah") && parts[1].equalsIgnoreCase("browse")) {
            AuctionMenuView view = AuctionMenuView.auctionBoard(parts[2]);
            return AuctionExperienceResult.rendered(view, "auction board opened");
        }
        return AuctionExperienceResult.blocked(
                AuctionMenuView.blocked("unsupported"),
                "unsupported /ah command");
    }

    private AuctionExperienceResult handleMenuClick(AuctionExperienceSession session, AuctionMenuClick click) {
        AuctionEscrowCommand payload = switch (click.action()) {
            case CONFIRM_LISTING -> new OpenEscrow(
                    click.auctionId(),
                    click.playerId(),
                    click.itemRef().orElseThrow(() -> new IllegalArgumentException("itemRef is required")),
                    click.currency().orElseThrow(() -> new IllegalArgumentException("currency is required")),
                    click.occurredAt());
            case PLACE_BID -> new PlaceHold(
                    click.auctionId(),
                    click.playerId(),
                    click.amountMinor().orElseThrow(() -> new IllegalArgumentException("amountMinor is required")),
                    click.currency().orElseThrow(() -> new IllegalArgumentException("currency is required")),
                    click.occurredAt());
            case SETTLE -> new SettleEscrow(click.auctionId(), click.occurredAt());
            case CANCEL -> new CancelEscrow(
                    click.auctionId(),
                    click.reason().orElse("cancelled-by-player"),
                    click.occurredAt());
        };
        AuctionExperienceReceipt receipt = commandPort.append(command(session, click, payload));
        return AuctionExperienceResult.submitted(
                AuctionMenuView.auctionBoard(click.auctionId()),
                "escrow command submitted",
                receipt);
    }

    private AuthorityCommand<AuctionEscrowCommand> command(
            AuctionExperienceSession session,
            AuctionMenuClick click,
            AuctionEscrowCommand payload) {
        PrincipalId principal = new PrincipalId(click.playerId());
        String commandId = "auction-experience:" + session.sessionId() + ":" + click.correlationId();
        String idempotencyKey = session.sessionId() + ":" + click.correlationId();
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        principal,
                        AuctionEscrowContract.aggregateId(payload.auctionId()),
                        AuctionEscrowContract.CONTRACT,
                        AuctionEscrowContract.commandName(payload),
                        trace(session, click),
                        Optional.empty(),
                        payload),
                principal,
                fencingEpoch,
                Optional.empty(),
                AuctionEscrowContract.payloadFingerprint(payload, idempotencyKey),
                click.occurredAt());
    }

    private TraceEnvelope trace(AuctionExperienceSession session, AuctionMenuClick click) {
        return new TraceEnvelope(
                "trace-" + session.sessionId(),
                "span-" + click.correlationId(),
                Optional.empty(),
                click.occurredAt(),
                SOURCE,
                new InstanceId("instance-" + session.sessionId()));
    }

    public List<String> supportedCommands() {
        return List.of(
                AuctionEscrowContract.OPEN.value(),
                AuctionEscrowContract.HOLD.value(),
                AuctionEscrowContract.SETTLE.value(),
                AuctionEscrowContract.CANCEL.value());
    }
}
