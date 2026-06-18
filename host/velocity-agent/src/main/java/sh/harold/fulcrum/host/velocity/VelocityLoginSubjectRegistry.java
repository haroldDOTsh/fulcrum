package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class VelocityLoginSubjectRegistry {
    private final Map<String, SubjectId> subjectsByUsername = new ConcurrentHashMap<>();
    private final Map<SubjectId, String> usernamesBySubject = new ConcurrentHashMap<>();

    void record(String username, SubjectId subjectId) {
        String checkedUsername = key(username);
        SubjectId checkedSubjectId = Objects.requireNonNull(subjectId, "subjectId");
        subjectsByUsername.put(checkedUsername, checkedSubjectId);
        usernamesBySubject.put(checkedSubjectId, checkedUsername);
    }

    SubjectId consume(String username, SubjectId fallback) {
        SubjectId subjectId = subjectsByUsername.remove(key(username));
        return subjectId == null ? Objects.requireNonNull(fallback, "fallback") : subjectId;
    }

    Optional<String> username(SubjectId subjectId) {
        return Optional.ofNullable(usernamesBySubject.get(Objects.requireNonNull(subjectId, "subjectId")));
    }

    void remove(String username) {
        SubjectId subjectId = subjectsByUsername.remove(key(username));
        if (subjectId != null) {
            usernamesBySubject.remove(subjectId);
        }
    }

    private static String key(String username) {
        String checked = Objects.requireNonNull(username, "username").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return checked.toLowerCase(Locale.ROOT);
    }
}
