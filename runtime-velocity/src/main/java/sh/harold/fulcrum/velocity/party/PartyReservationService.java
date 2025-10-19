package sh.harold.fulcrum.velocity.party;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationCreatedMessage;
import sh.harold.fulcrum.api.party.*;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache.FamilyVariantInfo;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class PartyReservationService {
    private final PartyService partyService;
    private final PartyReservationStore store;
    private final PartyReservationLifecycleTracker lifecycleTracker;
    private final SlotFamilyCache familyCache;
    private final MessageBus messageBus;
    private final Logger logger;

    PartyReservationService(PartyService partyService,
                            PartyReservationStore store,
                            PartyReservationLifecycleTracker lifecycleTracker,
                            SlotFamilyCache familyCache,
                            MessageBus messageBus,
                            Logger logger) {
        this.partyService = partyService;
        this.store = store;
        this.lifecycleTracker = lifecycleTracker;
        this.familyCache = familyCache;
        this.messageBus = messageBus;
        this.logger = logger;
    }

    public PartyReservationResult reserveForPlay(PartySnapshot snapshot,
                                                 String familyId,
                                                 String variantId,
                                                 String targetServerId,
                                                 Collection<Player> participants) {
        if (snapshot == null) {
            return PartyReservationResult.failure(Component.text("Party data unavailable.", NamedTextColor.RED));
        }
        List<Player> onlineMembers = participants == null ? List.of() : participants.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (onlineMembers.isEmpty()) {
            return PartyReservationResult.failure(Component.text("No online party members to queue.", NamedTextColor.RED));
        }

        long now = Instant.now().toEpochMilli();

        Map<UUID, PartyReservationToken> tokens = new LinkedHashMap<>();
        for (PartyMember member : snapshot.getMembers().values()) {
            if (member == null || !member.isOnline()) {
                continue;
            }
            UUID memberId = member.getPlayerId();
            if (memberId == null) {
                continue;
            }
            String username = member.getUsername() != null ? member.getUsername() : "Unknown";
            PartyReservationToken token = new PartyReservationToken(
                    UUID.randomUUID().toString(),
                    snapshot.getPartyId(),
                    memberId,
                    username,
                    null,
                    now + PartyConstants.RESERVATION_TOKEN_TTL_SECONDS * 1000L
            );
            tokens.put(memberId, token);
        }

        int partySize = tokens.size();
        if (partySize == 0) {
            return PartyReservationResult.failure(Component.text("Your party does not have any online members ready to queue.", NamedTextColor.RED));
        }
        FamilyVariantInfo info = familyCache.getVariantInfo(familyId, variantId)
                .or(() -> familyCache.getAnyVariantInfo(familyId))
                .orElse(new FamilyVariantInfo(PartyConstants.HARD_SIZE_CAP, PartyConstants.HARD_SIZE_CAP, 1));

        int maxTeamSize = info.maxTeamSize() > 0 ? info.maxTeamSize() : PartyConstants.HARD_SIZE_CAP;
        if (partySize > maxTeamSize) {
            Component message = Component.text("Your party size of ", NamedTextColor.RED)
                    .append(Component.text(partySize, NamedTextColor.AQUA))
                    .append(Component.text(" is too large for ", NamedTextColor.RED))
                    .append(Component.text(displayVariant(familyId, variantId), NamedTextColor.AQUA));
            return PartyReservationResult.failure(message);
        }

        String reservationId = UUID.randomUUID().toString();
        PartyReservationSnapshot reservation = new PartyReservationSnapshot(reservationId, snapshot.getPartyId());
        reservation.setFamilyId(familyId);
        reservation.setVariantId(variantId);
        reservation.setTargetServerId(targetServerId);
        reservation.setCreatedAt(now);
        reservation.setExpiresAt(now + PartyConstants.RESERVATION_TOKEN_TTL_SECONDS * 1000L);

        reservation.setTokens(tokens);

        store.save(reservation);
        lifecycleTracker.trackReservation(reservation);

        PartyOperationResult setResult = partyService.setActiveReservation(snapshot.getPartyId(), reservationId, targetServerId);
        if (!setResult.isSuccess()) {
            logger.warn("Failed to mark active reservation for party {}", snapshot.getPartyId());
            return PartyReservationResult.failure(setResult.message() != null
                    ? Component.text(setResult.message(), NamedTextColor.RED)
                    : Component.text("Unable to reserve party queue.", NamedTextColor.RED));
        }

        publishReservationCreated(reservation, familyId, variantId);
        return PartyReservationResult.success(reservation, onlineMembers);
    }

    private void publishReservationCreated(PartyReservationSnapshot reservation,
                                           String familyId,
                                           String variantId) {
        if (messageBus == null) {
            return;
        }
        try {
            PartyReservationCreatedMessage message = new PartyReservationCreatedMessage();
            message.setReservationId(reservation.getReservationId());
            message.setPartyId(reservation.getPartyId());
            message.setFamilyId(familyId);
            message.setVariantId(variantId);
            message.setTargetServerId(reservation.getTargetServerId());
            message.setReservation(reservation);
            messageBus.broadcast(ChannelConstants.PARTY_RESERVATION_CREATED, message);
        } catch (Exception ex) {
            logger.warn("Failed to broadcast PartyReservationCreatedMessage", ex);
        }
    }

    private String displayVariant(String familyId, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return familyId;
        }
        return familyId + ":" + variantId;
    }
}
