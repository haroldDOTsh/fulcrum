package sh.harold.fulcrum.api.message;

import net.kyori.adventure.audience.Audience;

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

    // New: Overload for Audience
    public static void error(Audience audience, GenericResponse response) {
        getService().sendGenericResponse(audience, response);
    }

    // Overloads for enums (GenericResponse) for all message types
    public static MessageBuilder success(GenericResponse response, Object... args) {
        return success(response.getKey(), args);
    }

    public static MessageBuilder info(GenericResponse response, Object... args) {
        return info(response.getKey(), args);
    }

    public static MessageBuilder debug(GenericResponse response, Object... args) {
        return debug(response.getKey(), args);
    }

    public static MessageBuilder error(GenericResponse response, Object... args) {
        return error(response.getKey(), args);
    }

    public static MessageBuilder raw(GenericResponse response, Object... args) {
        return raw(response.getKey(), args);
    }

}

