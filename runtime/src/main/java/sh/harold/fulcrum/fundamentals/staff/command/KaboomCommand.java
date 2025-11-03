package sh.harold.fulcrum.fundamentals.staff.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;

import java.util.List;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.entities;

public final class KaboomCommand {
    private static final double LAUNCH_VERTICAL_VELOCITY = 1.2D;
    private static final double PARTICLE_HORIZONTAL_OFFSET = 0.6D;
    private static final double PARTICLE_VERTICAL_OFFSET = 1.0D;
    private static final double PARTICLE_SPEED = 0.01D;
    private static final int PARTICLE_COUNT = 40;

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("kaboom")
                .requires(this::canUse)
                .then(argument("targets", entities())
                        .executes(this::execute))
                .build();
    }

    private boolean canUse(CommandSourceStack source) {
        return RankUtils.hasRankOrHigher(source.getSender(), Rank.STAFF);
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        CommandSender sender = source.getSender();
        EntitySelectorArgumentResolver resolver = context.getArgument("targets", EntitySelectorArgumentResolver.class);

        List<Player> targets = resolver.resolve(source).stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();

        if (targets.isEmpty()) {
            Message.error("No matching players found.")
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
            return 0;
        }

        for (Player target : targets) {
            triggerKaboom(target);
            Message.success("Launched {arg0}!", target.getName())
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
        }

        return Command.SINGLE_SUCCESS;
    }

    private void triggerKaboom(Player target) {
        World world = target.getWorld();
        Location location = target.getLocation();

        world.strikeLightningEffect(location);

        Vector currentVelocity = target.getVelocity();
        Vector launchVelocity = currentVelocity.clone().setY(Math.max(currentVelocity.getY(), 0.0D) + LAUNCH_VERTICAL_VELOCITY);
        target.setVelocity(launchVelocity);

        world.spawnParticle(
                Particle.END_ROD,
                location,
                PARTICLE_COUNT,
                PARTICLE_HORIZONTAL_OFFSET,
                PARTICLE_VERTICAL_OFFSET,
                PARTICLE_HORIZONTAL_OFFSET,
                PARTICLE_SPEED
        );
    }
}
