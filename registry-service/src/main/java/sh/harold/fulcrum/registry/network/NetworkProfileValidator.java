package sh.harold.fulcrum.registry.network;

import sh.harold.fulcrum.api.rank.Rank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

        if (profile.serverIp().isBlank()) {
            errors.add("serverIp must not be blank");
        }

        List<String> motd = profile.motd();
        if (motd.size() > MAX_MOTD_LINES) {
            errors.add("motd may contain at most " + MAX_MOTD_LINES + " lines");
        }
        for (int i = 0; i < motd.size(); i++) {
            String line = motd.get(i);
            if (line == null || line.isBlank()) {
                errors.add("motd line " + (i + 1) + " must not be blank");
            } else if (line.length() > MAX_MOTD_LENGTH) {
                errors.add("motd line " + (i + 1) + " exceeds " + MAX_MOTD_LENGTH + " characters");
            }
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
}
