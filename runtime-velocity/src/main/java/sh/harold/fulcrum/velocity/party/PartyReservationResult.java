package sh.harold.fulcrum.velocity.party;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class PartyReservationResult {
    private final boolean success;
    private final PartyReservationSnapshot reservation;
    private final List<Player> participants;
    private final Component errorMessage;

    private PartyReservationResult(boolean success,
                                   PartyReservationSnapshot reservation,
                                   List<Player> participants,
                                   Component errorMessage) {
        this.success = success;
        this.reservation = reservation;
        this.participants = participants != null ? Collections.unmodifiableList(participants) : List.of();
        this.errorMessage = errorMessage;
    }

    static PartyReservationResult success(PartyReservationSnapshot reservation, List<Player> participants) {
        return new PartyReservationResult(true, reservation, participants, null);
    }

    static PartyReservationResult failure(Component message) {
        return new PartyReservationResult(false, null, List.of(), message);
    }

    public boolean isSuccess() {
        return success;
    }

    public Optional<PartyReservationSnapshot> reservation() {
        return Optional.ofNullable(reservation);
    }

    public List<Player> participants() {
        return participants;
    }

    public Optional<Component> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }
}
