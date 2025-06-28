package sh.harold.fulcrum.api.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface MessageService {

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

    void sendMacroMessageWithTags(UUID playerId, MessageStyle style, String macroKey, List<MessageTag> tags, Object... args);

    void broadcastMacroMessageWithTags(MessageStyle style, String macroKey, List<MessageTag> tags, Object... args);

    Component getMacroMessageWithTags(UUID playerId, MessageStyle style, String macroKey, List<MessageTag> tags, Object... args);

    Component getMacroMessageWithTags(Locale locale, MessageStyle style, String macroKey, List<MessageTag> tags, Object... args);

    void setTagFormatter(TagFormatter formatter);

    Locale getPlayerLocale(UUID uniqueId);
}

