package sh.harold.fulcrum.api.messagebus.impl;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryMessageBusTest {
    @Test
    void requestReturnsPayloadFromRegisteredHandler() {
        InMemoryMessageBus messageBus = new InMemoryMessageBus(new TestAdapter("server-1"));
        messageBus.subscribeRequest("authority.command", envelope ->
            CompletableFuture.completedFuture(Map.of(
                "accepted", true,
                "sender", envelope.getSenderId()
            ))
        );

        Object response = messageBus.request(
            "server-1",
            "authority.command",
            Map.of("commandId", "command-1"),
            Duration.ofSeconds(1)
        ).join();

        assertEquals(Map.of("accepted", true, "sender", "server-1"), response);
    }

    private record TestAdapter(String serverId) implements MessageBusAdapter {
        @Override
        public String getServerId() {
            return serverId;
        }

        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger(InMemoryMessageBusTest.class.getName());
        }

        @Override
        public MessageBusConnectionConfig getConnectionConfig() {
            return MessageBusConnectionConfig.builder()
                .type(MessageBusConnectionConfig.MessageBusType.IN_MEMORY)
                .build();
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
