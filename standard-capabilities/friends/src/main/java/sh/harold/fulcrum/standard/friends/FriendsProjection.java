package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record FriendsProjection(
        Map<FriendConnectionId, FriendsProjectionRow> connections,
        Map<SubjectId, List<SubjectId>> friendsBySubject) {
    public FriendsProjection {
        connections = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(connections, "connections")));
        LinkedHashMap<SubjectId, List<SubjectId>> checked = new LinkedHashMap<>();
        Objects.requireNonNull(friendsBySubject, "friendsBySubject").forEach((subjectId, friends) -> checked.put(
                Objects.requireNonNull(subjectId, "subjectId"),
                List.copyOf(Objects.requireNonNull(friends, "friends"))));
        friendsBySubject = Collections.unmodifiableMap(checked);
    }

    public static FriendsProjection empty() {
        return new FriendsProjection(Map.of(), Map.of());
    }

    public static FriendsProjection rebuild(List<FriendInviteAccepted> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<FriendConnectionId, FriendsProjectionRow> connections = new LinkedHashMap<>();
        LinkedHashMap<SubjectId, List<SubjectId>> friendsBySubject = new LinkedHashMap<>();
        for (FriendInviteAccepted event : events) {
            FriendsProjectionRow row = new FriendsProjectionRow(event.snapshot(), event.revision());
            FriendsProjectionRow current = connections.get(row.connectionId());
            if (current != null && row.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("friends projection replay requires increasing revisions per connection");
            }
            connections.put(row.connectionId(), row);
            index(friendsBySubject, row.snapshot().subjectOneId(), row.snapshot().subjectTwoId());
            index(friendsBySubject, row.snapshot().subjectTwoId(), row.snapshot().subjectOneId());
        }
        LinkedHashMap<SubjectId, List<SubjectId>> sorted = new LinkedHashMap<>();
        friendsBySubject.forEach((subjectId, friends) -> sorted.put(subjectId, friends.stream()
                .distinct()
                .sorted(Comparator.comparing(friend -> friend.value().toString()))
                .toList()));
        return new FriendsProjection(connections, sorted);
    }

    public Optional<FriendsProjectionRow> row(FriendConnectionId connectionId) {
        return Optional.ofNullable(connections.get(Objects.requireNonNull(connectionId, "connectionId")));
    }

    public List<SubjectId> friendsOf(SubjectId subjectId) {
        return friendsBySubject.getOrDefault(Objects.requireNonNull(subjectId, "subjectId"), List.of());
    }

    private static void index(Map<SubjectId, List<SubjectId>> friendsBySubject, SubjectId subjectId, SubjectId friendSubjectId) {
        friendsBySubject.computeIfAbsent(subjectId, ignored -> new ArrayList<>()).add(friendSubjectId);
    }
}
