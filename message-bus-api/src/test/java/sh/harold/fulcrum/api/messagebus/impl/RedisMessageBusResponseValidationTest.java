package sh.harold.fulcrum.api.messagebus.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisMessageBusResponseValidationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void acceptsExpectedResponderTargetAndResponseType() {
        RedisMessageBus.PendingRequest pending = pendingRequest(
            "authority-1",
            "game-node-1",
            "authority.command_response"
        );

        MessageEnvelope response = responseEnvelope(
            "authority.command_response",
            "authority-1",
            "game-node-1"
        );

        assertTrue(RedisMessageBus.validateResponseEnvelope(response, pending).accepted());
    }

    @Test
    void rejectsUnexpectedResponder() {
        RedisMessageBus.PendingRequest pending = pendingRequest(
            "authority-1",
            "game-node-1",
            "authority.command_response"
        );

        MessageEnvelope response = responseEnvelope(
            "authority.command_response",
            "other-node",
            "game-node-1"
        );

        assertFalse(RedisMessageBus.validateResponseEnvelope(response, pending).accepted());
    }

    @Test
    void rejectsUnexpectedTarget() {
        RedisMessageBus.PendingRequest pending = pendingRequest(
            "authority-1",
            "game-node-1",
            "authority.command_response"
        );

        MessageEnvelope response = responseEnvelope(
            "authority.command_response",
            "authority-1",
            "other-node"
        );

        assertFalse(RedisMessageBus.validateResponseEnvelope(response, pending).accepted());
    }

    @Test
    void rejectsUnexpectedResponseType() {
        RedisMessageBus.PendingRequest pending = pendingRequest(
            "authority-1",
            "game-node-1",
            "authority.command_response"
        );

        MessageEnvelope response = responseEnvelope(
            "authority.read_response",
            "authority-1",
            "game-node-1"
        );

        assertFalse(RedisMessageBus.validateResponseEnvelope(response, pending).accepted());
    }

    @Test
    void responseChannelBypassesDuplicateTrackingBeforeValidation() {
        MessageEnvelope response = responseEnvelope(
            "authority.command_response",
            "other-node",
            "game-node-1"
        );

        assertFalse(RedisMessageBus.shouldCheckDuplicateBeforeHandling(
            ChannelConstants.getResponseChannel("game-node-1"),
            "game-node-1",
            response
        ));
    }

    @Test
    void requestAndDirectChannelsStillUseDuplicateTracking() {
        MessageEnvelope request = responseEnvelope(
            "authority.command",
            "game-node-1",
            "authority-1"
        );

        assertTrue(RedisMessageBus.shouldCheckDuplicateBeforeHandling(
            ChannelConstants.getRequestChannel("game-node-1"),
            "game-node-1",
            request
        ));
        assertTrue(RedisMessageBus.shouldCheckDuplicateBeforeHandling(
            ChannelConstants.getServerDirectChannel("game-node-1"),
            "game-node-1",
            request
        ));
    }

    private static RedisMessageBus.PendingRequest pendingRequest(
        String expectedResponderId,
        String requesterId,
        String responseType
    ) {
        return new RedisMessageBus.PendingRequest(
            new CompletableFuture<>(),
            expectedResponderId,
            requesterId,
            responseType
        );
    }

    private static MessageEnvelope responseEnvelope(
        String type,
        String senderId,
        String targetId
    ) {
        return new MessageEnvelope(
            type,
            senderId,
            targetId,
            UUID.randomUUID(),
            System.currentTimeMillis(),
            1,
            OBJECT_MAPPER.valueToTree(Map.of("accepted", true))
        );
    }
}
