package sh.harold.fulcrum.fundamentals.staff.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.fundamentals.staff.StaffVanishService;
import sh.harold.fulcrum.message.Message;

import java.util.Objects;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Brigadier command builder for /vanish.
 */
public final class VanishCommand {
    private final StaffVanishService vanishService;

    public VanishCommand(StaffVanishService vanishService) {
        this.vanishService = Objects.requireNonNull(vanishService, "vanishService");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("vanish")
                .requires(source -> RankUtils.isStaff(source.getSender()))
                .executes(ctx -> toggleSelf(ctx.getSource()))
                .then(literal("on").executes(ctx -> setState(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> setState(ctx.getSource(), false)))
                .build();
    }

    private int toggleSelf(CommandSourceStack source) {
        Player player = requirePlayer(source.getSender());
        if (player == null) {
            return 0;
        }
        boolean vanished = vanishService.toggle(player.getUniqueId());
        if (vanished) {
            Message.success("staff.vanish.enabled").send(player);
        } else {
            Message.info("staff.vanish.disabled").send(player);
        }
        return 1;
    }

    private int setState(CommandSourceStack source, boolean enable) {
        Player player = requirePlayer(source.getSender());
        if (player == null) {
            return 0;
        }
        boolean changed = enable
                ? vanishService.enable(player.getUniqueId())
                : vanishService.disable(player.getUniqueId());
        if (!changed) {
            if (enable) {
                Message.info("staff.vanish.already-enabled").send(player);
            } else {
                Message.info("staff.vanish.already-disabled").send(player);
            }
            return 0;
        }
        if (enable) {
            Message.success("staff.vanish.enabled").send(player);
        } else {
            Message.info("staff.vanish.disabled").send(player);
        }
        return 1;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Message.error("staff.vanish.player-only").send(sender);
        return null;
    }
}
