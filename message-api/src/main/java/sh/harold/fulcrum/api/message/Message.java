package sh.harold.fulcrum.api.message;

import java.util.UUID;

/**
 * Main message class providing static methods for consistent message formatting.
 * This class serves as the primary interface for developers to send styled messages.
 */
public class Message {

    private static MessageService messageService;

    public static void setMessageService(MessageService service) {
        messageService = service;
    }

    public static MessageService getService() {
        if (messageService == null) {
            throw new IllegalStateException("MessageService not initialized. Call Message.setMessageService() first.");
        }
        return messageService;
    }

    public static MessageBuilder success(String key, Object... args) {
        return MessageBuilder.key(MessageStyle.SUCCESS, key, args);
    }

    public static MessageBuilder info(String key, Object... args) {
        return MessageBuilder.key(MessageStyle.INFO, key, args);
    }

    public static MessageBuilder debug(String key, Object... args) {
        return MessageBuilder.key(MessageStyle.DEBUG, key, args);
    }

    public static MessageBuilder error(String key, Object... args) {
        return MessageBuilder.key(MessageStyle.ERROR, key, args);
    }

    public static void error(UUID playerUuid, GenericResponse response) {
        getService().sendGenericResponse(playerUuid, response);
    }

    public static MessageBuilder raw(String key, Object... args) {
        return MessageBuilder.key(MessageStyle.RAW, key, args);
    }

    public static MessageBuilder macro(String macroKey, Object... args) {
        return MessageBuilder.macro(MessageStyle.RAW, macroKey, args);
    }
}

