package sh.harold.fulcrum.api.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import sh.harold.fulcrum.api.message.util.GenericResponse;
import sh.harold.fulcrum.api.message.util.MessageTag;
import sh.harold.fulcrum.api.message.util.TagFormatter;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface MessageService {

    default void setArgCountContext(int argCount) {
        // No-op by default
    }

    String getTranslation(String key, Locale locale);

    void sendMessage(UUID playerUuid, Component message);

    void broadcastMessage(Component message);

    void sendStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args);

    void sendGenericResponse(UUID playerId, GenericResponse response);

    void broadcastStyledMessage(MessageStyle style, String translationKey, Object... args);

    Component getStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args);

    default Component getStyledMessage(Locale locale, MessageStyle style, String translationKey, Object... args) {
        String message = getTranslation(translationKey, locale);
        String argColorTag = style.getArgumentColorTag();

        Object[] formattedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (argColorTag.isEmpty()) {
                formattedArgs[i] = args[i];
            } else {
                formattedArgs[i] = argColorTag + args[i].toString() + "</" + argColorTag.substring(1);
            }
        }

        String formattedMessage = String.format(message, formattedArgs);
        return MiniMessage.miniMessage().deserialize(style.getPrefix() + formattedMessage);
    }

    void sendStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);

    void broadcastStyledMessageWithTags(MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);

    Component getStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);

    Component getStyledMessageWithTags(Locale locale, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);

    void setTagFormatter(TagFormatter formatter);

    Locale getPlayerLocale(UUID uniqueId);

    void sendMessage(Audience audience, Component message);

    void sendStyledMessage(Audience audience, MessageStyle style, String translationKey, Object... args);

    void sendGenericResponse(Audience audience, GenericResponse response);

    Component getStyledMessage(Audience audience, MessageStyle style, String translationKey, Object... args);

    void sendStyledMessageWithTags(Audience audience, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);

    Component getStyledMessageWithTags(Audience audience, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args);
}

