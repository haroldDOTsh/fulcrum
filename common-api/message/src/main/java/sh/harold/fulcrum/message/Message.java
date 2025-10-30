package sh.harold.fulcrum.message;

import sh.harold.fulcrum.message.builder.MessageBuilder;
import sh.harold.fulcrum.message.payload.MessagePayload;
import sh.harold.fulcrum.message.util.GenericResponse;

public final class Message {
    private static volatile MessageFacade facade;

    private Message() {
    }

    public static MessageFacade getFacade() {
        MessageFacade instance = facade;
        if (instance == null) {
            throw new IllegalStateException("MessageFacade not initialized");
        }
        return instance;
    }

    public static void setFacade(MessageFacade messageFacade) {
        facade = messageFacade;
    }

    public static MessageBuilder success(String identifier, Object... args) {
        return builder(MessageStyle.SUCCESS, identifier, args);
    }

    public static MessageBuilder info(String identifier, Object... args) {
        return builder(MessageStyle.INFO, identifier, args);
    }

    public static MessageBuilder debug(String identifier, Object... args) {
        return builder(MessageStyle.DEBUG, identifier, args);
    }

    public static MessageBuilder error(String identifier, Object... args) {
        return builder(MessageStyle.ERROR, identifier, args);
    }

    public static MessageBuilder success(GenericResponse response, Object... args) {
        return success(response.key(), args);
    }

    public static MessageBuilder info(GenericResponse response, Object... args) {
        return info(response.key(), args);
    }

    public static MessageBuilder debug(GenericResponse response, Object... args) {
        return debug(response.key(), args);
    }

    public static MessageBuilder error(GenericResponse response, Object... args) {
        return error(response.key(), args);
    }

    private static MessageBuilder builder(MessageStyle style, String identifier, Object... args) {
        return new MessageBuilder(getFacade(), style, MessagePayload.of(identifier), args);
    }
}
