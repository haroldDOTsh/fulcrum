package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PartyRosterProjection(
        Map<PartyId, PartyRosterProjectionRow> parties,
        Map<SubjectId, PartyId> partyBySubject) {
    public PartyRosterProjection {
        parties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parties, "parties")));
        partyBySubject = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(partyBySubject, "partyBySubject")));
    }

    public static PartyRosterProjection empty() {
        return new PartyRosterProjection(Map.of(), Map.of());
    }

    public static PartyRosterProjection rebuild(List<PartyFormed> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<PartyId, PartyRosterProjectionRow> parties = new LinkedHashMap<>();
        LinkedHashMap<SubjectId, PartyId> partyBySubject = new LinkedHashMap<>();
        for (PartyFormed event : events) {
            PartyRosterProjectionRow row = new PartyRosterProjectionRow(event.snapshot(), event.revision());
            PartyRosterProjectionRow current = parties.get(row.partyId());
            if (current != null && row.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("party projection replay requires increasing revisions per Party");
            }
            for (SubjectId subjectId : row.snapshot().memberSubjectIds()) {
                PartyId existingParty = partyBySubject.get(subjectId);
                if (existingParty != null && !existingParty.equals(row.partyId())) {
                    throw new IllegalArgumentException("Subject cannot be indexed into more than one active Party");
                }
                partyBySubject.put(subjectId, row.partyId());
            }
            parties.put(row.partyId(), row);
        }
        return new PartyRosterProjection(parties, partyBySubject);
    }

    public Optional<PartyRosterProjectionRow> row(PartyId partyId) {
        return Optional.ofNullable(parties.get(Objects.requireNonNull(partyId, "partyId")));
    }

    public Optional<PartyId> partyFor(SubjectId subjectId) {
        return Optional.ofNullable(partyBySubject.get(Objects.requireNonNull(subjectId, "subjectId")));
    }

    public List<SubjectId> membersFor(SubjectId subjectId) {
        return partyFor(subjectId)
                .flatMap(this::row)
                .map(row -> row.snapshot().memberSubjectIds())
                .orElse(List.of());
    }
}
