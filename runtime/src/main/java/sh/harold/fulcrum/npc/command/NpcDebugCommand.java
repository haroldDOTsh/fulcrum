package sh.harold.fulcrum.npc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.dialogue.Dialogue;
import sh.harold.fulcrum.dialogue.DialogueLine;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.behavior.NpcBehavior;
import sh.harold.fulcrum.npc.behavior.PassiveContext;
import sh.harold.fulcrum.npc.options.NpcEquipment;
import sh.harold.fulcrum.npc.options.NpcOptions;
import sh.harold.fulcrum.npc.orchestration.PoiNpcOrchestrator;
import sh.harold.fulcrum.npc.pose.NpcPose;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.profile.NpcSkin;
import sh.harold.fulcrum.npc.visibility.NpcVisibility;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class NpcDebugCommand {
    private final Supplier<PoiNpcOrchestrator> orchestratorSupplier;
    private static final Dialogue DEBUG_DIALOGUE = Dialogue.builder()
            .id("debug.greeter")
            .cooldown(Duration.ofSeconds(8))
            .lines(List.of(
                    DialogueLine.dynamic(ctx ->
                            LegacyComponentSerializer.legacyAmpersand()
                                    .deserialize("&fHey " + ctx.player().getName() + ", I'm " + ctx.displayName() + ".")),
                    DialogueLine.of("&fThis NPC is wired to the shared dialogue service."),
                    DialogueLine.of("&fCooldown + formatting are centralized now.")
            ))
            .build();

    public NpcDebugCommand(Supplier<PoiNpcOrchestrator> orchestratorSupplier) {
        this.orchestratorSupplier = orchestratorSupplier;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("npcdebugtest")
                .requires(stack -> RankUtils.isStaff(stack.getSender()))
                .executes(context -> execute(context.getSource().getSender(), 5))
                .then(argument("seconds", IntegerArgumentType.integer(1, 600))
                        .executes(context -> execute(
                                context.getSource().getSender(),
                                IntegerArgumentType.getInteger(context, "seconds"))))
                .build();
    }

    private int execute(CommandSender sender, int lifetimeSeconds) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can run this command.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        spawnDebugNpc(player, lifetimeSeconds);
        return Command.SINGLE_SUCCESS;
    }

    private void spawnDebugNpc(Player player, int lifetimeSeconds) {
        PoiNpcOrchestrator orchestrator = orchestratorSupplier.get();
        if (orchestrator == null) {
            player.sendMessage(Component.text("NPC toolkit is still starting; try again in a moment.", NamedTextColor.RED));
            return;
        }
        UUID uniqueId = UUID.randomUUID();
        String anchor = "debug:" + uniqueId;
        NpcProfile profile = NpcProfile.builder()
                .displayName("&a" + player.getName())
                .description("Debug NPC")
                .interactable(true)
                .skin(NpcSkin.fromMojangProfile(player.getUniqueId()))
                .build();

        NpcBehavior behavior = NpcBehavior.simple(builder -> builder
                .passiveIntervalTicks(20)
                .passive(PassiveContext::helpers)
                .interactionCooldownTicks(20)
                .onInteract(ctx -> ctx.helpers().dialogues().start(ctx, DEBUG_DIALOGUE)));

        NpcDefinition definition = NpcDefinition.builder()
                .id("debug:" + uniqueId)
                .profile(profile)
                .pose(NpcPose.standing())
                .behavior(behavior)
                .visibility(NpcVisibility.everyone())
                .options(NpcOptions.builder().build())
                .equipment(NpcEquipment.empty())
                .poiAnchor(anchor)
                .relativeOffset(new Vector(0, 0, 0))
                .build();

        long ticks = Math.max(20L, lifetimeSeconds * 20L);
        orchestrator.spawnTemporaryNpc(definition, player.getLocation(), ticks);
        Message.debug("Spawned debug NPC; despawns in {arg0}s.", lifetimeSeconds)
                .skipTranslation()
                .send(player);
    }
}
