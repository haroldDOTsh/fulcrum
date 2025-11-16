package sh.harold.fulcrum.registry.network;

import sh.harold.fulcrum.api.rank.Rank;

import java.util.*;

final class NetworkProfileValidator {
    private static final int MAX_MOTD_LINES = 2;
    private static final int MAX_MOTD_LENGTH = 64;
    private static final int MAX_SCOREBOARD_LENGTH = 32;
    private static final Set<String> REQUIRED_RANKS = Set.of(
            Rank.DEFAULT.name(),
            Rank.DONATOR_1.name(),
            Rank.DONATOR_2.name(),
            Rank.DONATOR_3.name(),
            Rank.DONATOR_4.name(),
            Rank.HELPER.name(),
            Rank.STAFF.name()
    );

    private NetworkProfileValidator() {
    }

    static void validate(NetworkProfileDocument profile) {
        List<String> errors = new ArrayList<>();

        NetworkProfileDocument.GeneralInfo info = profile.info();
        if (info.serverName().isBlank()) {
            errors.add("info.serverName must not be blank");
        }
        if (info.serverIp().isBlank()) {
            errors.add("info.serverIp must not be blank");
        }

        validateMotdLines(profile.motd(), "motd.live", errors);
        List<String> maintenanceLines = extractMaintenanceLines(profile);
        if (!maintenanceLines.isEmpty()) {
            validateMotdLines(maintenanceLines, "motd.maintenance", errors);
        }

        if (profile.scoreboardTitle().isBlank()) {
            errors.add("scoreboard.title must not be blank");
        } else if (profile.scoreboardTitle().length() > MAX_SCOREBOARD_LENGTH) {
            errors.add("scoreboard.title exceeds " + MAX_SCOREBOARD_LENGTH + " characters");
        }

        if (profile.scoreboardFooter().isBlank()) {
            errors.add("scoreboard.footer must not be blank");
        } else if (profile.scoreboardFooter().length() > MAX_SCOREBOARD_LENGTH) {
            errors.add("scoreboard.footer exceeds " + MAX_SCOREBOARD_LENGTH + " characters");
        }

        Set<String> ranks = profile.ranks().keySet();
        for (String required : REQUIRED_RANKS) {
            if (!ranks.contains(required)) {
                errors.add("ranks." + required.toLowerCase(Locale.ROOT) + " is required");
            }
        }

        profile.ranks().forEach((rankId, visual) -> {
            if (visual.displayName().isBlank()) {
                errors.add("ranks." + rankId.toLowerCase(Locale.ROOT) + ".displayName must not be blank");
            }
            if (visual.nameColor().isBlank()) {
                errors.add("ranks." + rankId.toLowerCase(Locale.ROOT) + ".nameColor must not be blank");
            }
        });

        if (!errors.isEmpty()) {
            throw new NetworkProfileValidationException(profile.profileId(), errors);
        }
    }

    private static void validateMotdLines(List<String> lines, String label, List<String> errors) {
        if (lines.size() > MAX_MOTD_LINES) {
            errors.add(label + " may contain at most " + MAX_MOTD_LINES + " lines");
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                errors.add(label + " line " + (i + 1) + " must not be blank");
            } else if (line.length() > MAX_MOTD_LENGTH) {
                errors.add(label + " line " + (i + 1) + " exceeds " + MAX_MOTD_LENGTH + " characters");
            }
        }
    }

    private static List<String> extractMaintenanceLines(NetworkProfileDocument profile) {
        Object motd = profile.rawData().get("motd");
        if (motd instanceof Map<?, ?> map) {
            Object maintenance = map.get("maintenance");
            if (maintenance instanceof List<?> list) {
                List<String> values = new ArrayList<>(list.size());
                for (Object entry : list) {
                    if (entry != null) {
                        values.add(entry.toString());
                    }
                }
                return values;
            }
        }
        return List.of();
    }
}
