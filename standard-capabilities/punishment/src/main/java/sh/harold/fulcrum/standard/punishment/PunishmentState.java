package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PunishmentState(Optional<ActivePunishmentSnapshot> active) {
    public PunishmentState(ActivePunishmentSnapshot active) {
        this(Optional.of(Objects.requireNonNull(active, "active")));
    }

    public PunishmentState {
        active = active == null ? Optional.empty() : active;
    }

    public static PunishmentState empty() {
        return new PunishmentState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return active.map(snapshot -> snapshot.wireValue(revision.value()))
                .orElse("empty=true\nrevision=" + revision.value());
    }

    public static PunishmentState parse(String payload) {
        Map<String, String> fields = fields(payload);
        long revision = Long.parseLong(required(fields, "revision"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (Boolean.parseBoolean(fields.getOrDefault("empty", "false"))) {
            return empty();
        }
        return new PunishmentState(new ActivePunishmentSnapshot(
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                unescape(required(fields, "punishmentId")),
                unescape(required(fields, "reason")),
                new PrincipalId(required(fields, "issuedBy")),
                Instant.parse(required(fields, "issuedAt")),
                Instant.parse(required(fields, "expiresAt"))));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("punishment state payload must not be blank");
        }
        for (String line : payload.split("\\R")) {
            int separator = separatorIndex(line);
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed punishment state line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static int separatorIndex(String line) {
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '=') {
                return index;
            }
        }
        return -1;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing punishment state field " + key);
        }
        return value;
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    builder.append(current);
                }
                continue;
            }
            switch (current) {
                case 'n' -> builder.append('\n');
                case '=', '\\' -> builder.append(current);
                default -> builder.append(current);
            }
            escaped = false;
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
