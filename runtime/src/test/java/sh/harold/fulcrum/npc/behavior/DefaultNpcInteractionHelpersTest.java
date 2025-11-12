package sh.harold.fulcrum.npc.behavior;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.common.cooldown.InMemoryCooldownRegistry;
import sh.harold.fulcrum.dialogue.DefaultDialogueService;
import sh.harold.fulcrum.dialogue.Dialogue;
import sh.harold.fulcrum.dialogue.DialogueLine;
import sh.harold.fulcrum.dialogue.DialogueService;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.profile.NpcSkin;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DefaultNpcInteractionHelpersTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private InMemoryCooldownRegistry cooldownRegistry;
    private DialogueService dialogueService;
    private DefaultNpcInteractionHelpers helpers;
    private List<Component> messages;
    private Player player;
    private UUID playerId;
    private Dialogue dialogue;
    private InteractionContext context;

    @BeforeEach
    void setUp() {
        cooldownRegistry = new InMemoryCooldownRegistry();
        dialogueService = new DefaultDialogueService(cooldownRegistry, Logger.getLogger("npc-dialogue-test"));
        helpers = new DefaultNpcInteractionHelpers(null, null, dialogueService, Logger.getLogger("npc-dialogue-test"), null);
        messages = new ArrayList<>();
        playerId = UUID.randomUUID();
        player = fakePlayer(playerId, messages);
        dialogue = Dialogue.builder()
                .id("guide.walkthrough")
                .lines(List.of(
                        DialogueLine.of("&fStep one"),
                        DialogueLine.of("&fStep two"),
                        DialogueLine.of("&fStep three")
                ))
                .build();
        NpcDefinition definition = NpcDefinition.builder()
                .id("tutorial:guide")
                .profile(NpcProfile.builder()
                        .displayName("&6Guide")
                        .description("Helper")
                        .skin(NpcSkin.fromMojangUsername("Notch"))
                        .build())
                .poiAnchor("spawn")
                .build();
        context = new TestInteractionContext(UUID.randomUUID(), player, definition);
    }

    @AfterEach
    void tearDown() {
        cooldownRegistry.close();
    }

    @Test
    void startAdvancesActiveSessionBeforeRestarting() {
        NpcInteractionHelpers.DialogueHelper helper = helpers.dialogues();

        helper.start(context, dialogue);
        awaitMessages(1);
        assertEquals("[NPC] Guide [1/3]: Step one", PLAIN.serialize(messages.get(0)));

        helper.start(context, dialogue);
        awaitMessages(2);
        assertEquals("[NPC] Guide [2/3]: Step two", PLAIN.serialize(messages.get(1)));

        helper.start(context, dialogue);
        awaitMessages(3);
        assertEquals("[NPC] Guide [3/3]: Step three", PLAIN.serialize(messages.get(2)));

        helper.start(context, dialogue);
        awaitMessages(4);
        assertEquals("[NPC] Guide [1/3]: Step one", PLAIN.serialize(messages.get(3)));
    }

    private void awaitMessages(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (messages.size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(expected, messages.size());
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
                    if ("isOnline".equals(name)) {
                        return true;
                    }
                    if ("sendMessage".equals(name)
                            && args != null
                            && args.length == 1
                            && args[0] instanceof Component component) {
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

    private record TestInteractionContext(UUID npcInstanceId, Player player,
                                          NpcDefinition definition) implements InteractionContext {

        @Override
            public UUID playerId() {
                return player.getUniqueId();
            }

            @Override
            public Location location() {
                return null;
            }

            @Override
            public Collection<Player> viewers() {
                return List.of();
            }

            @Override
            public NpcInteractionHelpers helpers() {
                return NpcInteractionHelpers.NOOP;
            }
        }
}
