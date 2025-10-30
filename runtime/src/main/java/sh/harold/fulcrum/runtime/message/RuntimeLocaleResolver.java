package sh.harold.fulcrum.runtime.message;

import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.message.locale.AudienceLocaleResolver;

import java.util.Locale;

public final class RuntimeLocaleResolver implements AudienceLocaleResolver {
    private final Locale fallback;

    public RuntimeLocaleResolver(Locale fallback) {
        this.fallback = fallback == null ? Locale.US : fallback;
    }

    @Override
    public Locale resolve(Audience audience) {
        if (audience instanceof Player player) {
            try {
                Locale playerLocale = player.locale();
                if (playerLocale != null) {
                    return playerLocale;
                }
            } catch (Throwable ignored) {
                // Fallback below
            }
        }
        return fallback;
    }
}
