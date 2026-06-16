package sh.harold.fulcrum.standard.guild;

import java.util.Objects;

public record GuildId(String value) {
    public GuildId {
        value = requireNonBlank(value, "guildId");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
