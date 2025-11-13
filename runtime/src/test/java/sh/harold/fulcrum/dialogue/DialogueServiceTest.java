package sh.harold.fulcrum.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.common.cooldown.InMemoryCooldownRegistry;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class DialogueServiceTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private InMemoryCooldownRegistry registry;
    private DialogueService service;
    private List<Component> messages;
    private Player player;
    private UUID playerId;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        registry = new InMemoryCooldownRegistry();
        clock = new MutableClock();
        service = new DefaultDialogueService(registry, Logger.getLogger("dialogue-test"), clock);
        messages = new ArrayList<>();
        playerId = UUID.randomUUID();
        player = fakePlayer(playerId, messages);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void startConversationSendsFirstLine() {
        Dialogue dialogue = baseDialogue();
        DialogueStartRequest request = DialogueStartRequest.builder(player, dialogue)
                .displayName("&6Rhea")
                .build();

        DialogueStartResult result = service.startConversation(request).toCompletableFuture().join();

        assertInstanceOf(DialogueStartResult.Started.class, result);
        assertEquals(1, messages.size());
        assertEquals("[NPC] Rhea [1/2]: Welcome to Fulcrum!", PLAIN.serialize(messages.get(0)));

        DialogueSession session = ((DialogueStartResult.Started) result).session();
        assertEquals(1, session.nextStepIndex());
        assertEquals(2, session.totalSteps());
        assertTrue(service.activeSession(playerId).isPresent());
        assertSame(session, service.activeSession(playerId).orElseThrow());
    }

    @Test
    void cooldownBlocksSecondStart() {
        Dialogue dialogue = baseDialogue();
        DialogueStartRequest request = DialogueStartRequest.builder(player, dialogue)
                .displayName("&6Rhea")
                .build();

        DialogueStartResult first = service.startConversation(request).toCompletableFuture().join();
        assertInstanceOf(DialogueStartResult.Started.class, first);

        DialogueStartResult second = service.startConversation(request).toCompletableFuture().join();
        assertInstanceOf(DialogueStartResult.CooldownRejected.class, second);
        Duration remaining = ((DialogueStartResult.CooldownRejected) second).remaining();
        assertTrue(remaining.toMillis() > 0);
    }

    @Test
    void advanceCompletesSession() {
        Dialogue dialogue = baseDialogue();
        DialogueStartRequest request = DialogueStartRequest.builder(player, dialogue)
                .displayName("&6Rhea")
                .build();

        DialogueStartResult result = service.startConversation(request).toCompletableFuture().join();
        DialogueSession session = ((DialogueStartResult.Started) result).session();

        Optional<DialogueSession> advanced = service.advance(playerId);
        assertTrue(advanced.isPresent());
        assertSame(session, advanced.orElseThrow());
        assertEquals(2, messages.size());
        assertEquals("[NPC] Rhea [2/2]: Grab a kit at the vendor to start.", PLAIN.serialize(messages.get(1)));

        assertTrue(service.activeSession(playerId).isEmpty());
        assertTrue(session.isComplete());
    }

    @Test
    void predicateFiltersLinesAndFiresCallbacks() {
        AtomicInteger startCalls = new AtomicInteger();
        AtomicInteger advanceCalls = new AtomicInteger();
        AtomicInteger completeCalls = new AtomicInteger();

        Dialogue dialogue = Dialogue.builder()
                .id("conversation.filter")
                .cooldown(Duration.ofSeconds(5))
                .callbacks(DialogueCallbacks.builder()
                        .onStart(ctx -> startCalls.incrementAndGet())
                        .onAdvance(ctx -> advanceCalls.incrementAndGet())
                        .onComplete(ctx -> completeCalls.incrementAndGet())
                        .build())
                .lines(List.of(
                        DialogueLine.of("&fVisible line"),
                        DialogueLine.conditional("&fHidden line", ctx -> false)
                ))
                .build();

        DialogueStartRequest request = DialogueStartRequest.builder(player, dialogue)
                .displayName("&6Rhea")
                .build();

        DialogueStartResult result = service.startConversation(request).toCompletableFuture().join();
        assertInstanceOf(DialogueStartResult.Started.class, result);

        assertEquals(1, messages.size());
        assertEquals("[NPC] Rhea [1/1]: Visible line", PLAIN.serialize(messages.get(0)));

        assertEquals(1, startCalls.get());
        assertEquals(1, advanceCalls.get());
        assertEquals(1, completeCalls.get());
    }

    @Test
    void timeoutReleasesStaleSessions() {
        Dialogue dialogue = Dialogue.builder()
                .id("conversation.timeout")
                .cooldown(Duration.ofSeconds(60))
                .timeout(Duration.ofSeconds(30))
                .lines(List.of(
                        DialogueLine.of("&fHello"),
                        DialogueLine.of("&fGoodbye")
                ))
                .build();

        DialogueStartRequest request = DialogueStartRequest.builder(player, dialogue)
                .displayName("&6Rhea")
                .build();

        DialogueStartResult first = service.startConversation(request).toCompletableFuture().join();
        assertInstanceOf(DialogueStartResult.Started.class, first);

        clock.advance(Duration.ofSeconds(31));

        DialogueStartResult second = service.startConversation(request).toCompletableFuture().join();
        assertInstanceOf(DialogueStartResult.Started.class, second);
    }

    private Dialogue baseDialogue() {
        return Dialogue.builder()
                .id("tutorial.greeter")
                .cooldown(Duration.ofSeconds(30))
                .timeout(Duration.ofSeconds(30))
                .lines(List.of(
                        DialogueLine.of("&fWelcome to Fulcrum!"),
                        DialogueLine.of("&fGrab a kit at the vendor to start.")
                ))
                .build();
    }

    private Player fakePlayer(UUID playerId, List<Component> sink) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getUniqueId".equals(name)) {
                        return playerId;
                    }
                    if ("getName".equals(name)) {
                        return "Player-" + playerId.toString().substring(0, 4);
                    }
                    if ("sendMessage".equals(name) && args != null && args.length == 1 && args[0] instanceof Component component) {
                        sink.add(component);
                        return null;
                    }
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "FakePlayer{" + playerId + "}";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType.equals(boolean.class)) {
                        return false;
                    }
                    if (returnType.equals(int.class)) {
                        return 0;
                    }
                    if (returnType.equals(long.class)) {
                        return 0L;
                    }
                    if (returnType.equals(double.class)) {
                        return 0D;
                    }
                    if (returnType.equals(float.class)) {
                        return 0F;
                    }
                    if (returnType.equals(char.class)) {
                        return '\0';
                    }
                    return null;
                }
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2025-01-01T00:00:00Z");
        private ZoneId zone = ZoneId.of("UTC");

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            Objects.requireNonNull(zone, "zone");
            MutableClock copy = new MutableClock();
            copy.instant = this.instant;
            copy.zone = zone;
            return copy;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
