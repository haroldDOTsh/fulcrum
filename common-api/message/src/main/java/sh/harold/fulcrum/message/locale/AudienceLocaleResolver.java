package sh.harold.fulcrum.message.locale;

import net.kyori.adventure.audience.Audience;

import java.util.Locale;

@FunctionalInterface
public interface AudienceLocaleResolver {
    static AudienceLocaleResolver constant(Locale locale) {
        Locale fallback = locale == null ? Locale.US : locale;
        return audience -> fallback;
    }

    Locale resolve(Audience audience);
}
