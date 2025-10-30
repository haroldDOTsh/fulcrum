package sh.harold.fulcrum.velocity.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentCommandMessage;
import sh.harold.fulcrum.api.punishment.PunishmentLadder;
import sh.harold.fulcrum.api.punishment.PunishmentReason;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.kyori.adventure.text.Component.text;

public final class PunishCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final MessageBus messageBus;
    private final DataAPI dataAPI;
    private final VelocityPlayerSessionService sessionService;
    private final Logger logger;
    private final FulcrumVelocityPlugin plugin;

    public PunishCommand(ProxyServer proxy,
                         MessageBus messageBus,
                         DataAPI dataAPI,
                         VelocityPlayerSessionService sessionService,
                         Logger logger,
                         FulcrumVelocityPlugin plugin) {
        this.proxy = proxy;
        this.messageBus = messageBus;
        this.dataAPI = dataAPI;
        this.sessionService = sessionService;
        this.logger = logger;
        this.plugin = plugin;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!canAccess(invocation.source())) {
            return List.of();
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return Arrays.stream(PunishmentReason.values())
                    .map(PunishmentReason::getId)
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return canAccess(invocation.source());
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = Arrays.copyOf(invocation.arguments(), invocation.arguments().length);

        if (args.length < 2) {
            source.sendMessage(text("Usage: /punish <player> <reason>", NamedTextColor.RED));
            return;
        }

        PunishmentReason reason = PunishmentReason.fromId(args[1]);
        if (reason == null) {
            source.sendMessage(text("Unknown punishment reason '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }

        Optional<Player> targetOpt = proxy.getPlayer(args[0]);
        if (targetOpt.isEmpty()) {
            source.sendMessage(text("Player '" + args[0] + "' must be online to punish.", NamedTextColor.RED));
            return;
        }

        Rank required = reason.getLadder() == PunishmentLadder.CHAT_MINOR || reason.getLadder() == PunishmentLadder.CHAT_MAJOR
                ? Rank.HELPER
                : Rank.STAFF;

        VelocityRankUtils.hasRankOrHigher(source, required, sessionService, dataAPI, logger)
                .whenComplete((allowed, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Failed to verify rank for /punish", throwable);
                        proxy.getScheduler().buildTask(plugin, () ->
                                        source.sendMessage(text("Unable to verify your permissions right now.", NamedTextColor.RED)))
                                .schedule();
                        return;
                    }

                    if (!Boolean.TRUE.equals(allowed)) {
                        proxy.getScheduler().buildTask(plugin, () ->
                                        source.sendMessage(text("You must be " + required.name() + " or higher to use /punish for this reason.", NamedTextColor.RED)))
                                .schedule();
                        return;
                    }

                    proxy.getScheduler().buildTask(plugin, () -> executeAuthorized(source, targetOpt.get(), reason)).schedule();
                });
    }

    private void executeAuthorized(CommandSource source, Player target, PunishmentReason reason) {
        if (!(source instanceof Player staff)) {
            source.sendMessage(text("Only in-game staff can issue punishments for now.", NamedTextColor.RED));
            return;
        }

        PunishmentCommandMessage command = new PunishmentCommandMessage();
        command.setCommandId(UUID.randomUUID());
        command.setPlayerId(target.getUniqueId());
        command.setPlayerName(target.getUsername());
        command.setReason(reason);
        command.setStaffId(staff.getUniqueId());
        command.setStaffName(staff.getUsername());
        command.setIssuedAt(Instant.now());

        try {
            messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_COMMAND, command);
            source.sendMessage(text("Queued punishment " + reason.getId() + " for " + target.getUsername() + ".", NamedTextColor.GREEN));
        } catch (Exception ex) {
            logger.warn("Failed to broadcast punishment command", ex);
            source.sendMessage(text("Failed to send punishment command. Check logs.", NamedTextColor.RED));
        }
    }

    private boolean canAccess(CommandSource source) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }

        if (source instanceof Player player) {
            return VelocityRankUtils.hasRankOrHigherSync(player, Rank.HELPER, sessionService, dataAPI, logger);
        }

        return false;
    }
}
