package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RankState(Optional<EffectiveRankSnapshot> current) {
    public RankState(EffectiveRankSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public RankState {
        current = current == null ? Optional.empty() : current;
    }

    public static RankState empty() {
        return new RankState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision.value()))
                .orElse("empty=true\nrevision=" + revision.value());
    }

    public static RankState parse(String payload) {
        Map<String, String> fields = fields(payload);
        long revision = Long.parseLong(required(fields, "revision"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (Boolean.parseBoolean(fields.getOrDefault("empty", "false"))) {
            return empty();
        }
        return new RankState(new EffectiveRankSnapshot(
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                unescape(required(fields, "primaryRankKey")),
                unescape(required(fields, "permissions")),
                new PrincipalId(required(fields, "updatedBy")),
                Instant.parse(required(fields, "updatedAt"))));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("rank state payload must not be blank");
        }
        for (String line : payload.split("\\R")) {
            int separator = separatorIndex(line);
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed rank state line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static int separatorIndex(String line) {
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (currentChar == '\\') {
                escaped = true;
            } else if (currentChar == '=') {
                return index;
            }
        }
        return -1;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing rank state field " + key);
        }
        return value;
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (!escaped) {
                if (currentChar == '\\') {
                    escaped = true;
                } else {
                    builder.append(currentChar);
                }
                continue;
            }
            switch (currentChar) {
                case 'n' -> builder.append('\n');
                case '=', '\\' -> builder.append(currentChar);
                default -> builder.append(currentChar);
            }
            escaped = false;
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
