package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record FriendConnectionId(String value) {
    public FriendConnectionId {
        value = requireNonBlank(value, "friendConnectionId");
    }

    public static FriendConnectionId from(SubjectId first, SubjectId second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("friend connection requires two distinct Subjects");
        }
        List<SubjectId> ordered = List.of(first, second).stream()
                .sorted(Comparator.comparing(subject -> subject.value().toString()))
                .toList();
        return new FriendConnectionId(ordered.get(0).value() + ":" + ordered.get(1).value());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
