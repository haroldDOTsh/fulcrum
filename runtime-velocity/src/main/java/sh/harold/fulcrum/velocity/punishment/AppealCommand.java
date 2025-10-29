package sh.harold.fulcrum.velocity.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusCommandMessage;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static net.kyori.adventure.text.Component.text;

public final class AppealCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final MessageBus messageBus;
    private final DataAPI dataAPI;
    private final VelocityPlayerSessionService sessionService;
    private final Logger logger;
    private final FulcrumVelocityPlugin plugin;

    public AppealCommand(ProxyServer proxy,
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
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = Arrays.copyOf(invocation.arguments(), invocation.arguments().length);

        if (args.length < 1) {
            source.sendMessage(text("Usage: /appeal <punishment-id> [note]", NamedTextColor.RED));
            return;
        }

        UUID punishmentId;
        try {
            punishmentId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException ex) {
            source.sendMessage(text("Invalid punishment id '" + args[0] + "'.", NamedTextColor.RED));
            return;
        }

        String note = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim() : null;

        if (!(source instanceof Player staff)) {
            source.sendMessage(text("Only in-game staff can appeal punishments.", NamedTextColor.RED));
            return;
        }

        VelocityRankUtils.hasRankOrHigher(source, Rank.STAFF, sessionService, dataAPI, logger)
                .whenComplete((allowed, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Failed to verify rank for /appeal", throwable);
                        proxy.getScheduler().buildTask(plugin, () ->
                                source.sendMessage(text("Unable to verify your permissions right now.", NamedTextColor.RED))).schedule();
                        return;
                    }

                    if (!Boolean.TRUE.equals(allowed)) {
                        proxy.getScheduler().buildTask(plugin, () ->
                                source.sendMessage(text("You must be STAFF or higher to use /appeal.", NamedTextColor.RED))).schedule();
                        return;
                    }

                    proxy.getScheduler().buildTask(plugin, () ->
                            executeAuthorized(staff, punishmentId, note)).schedule();
                });
    }

    private void executeAuthorized(Player staff, UUID punishmentId, String note) {
        PunishmentStatusCommandMessage message = new PunishmentStatusCommandMessage();
        message.setPunishmentId(punishmentId);
        message.setStatus(PunishmentStatus.APPEALED);
        message.setActorId(staff.getUniqueId());
        message.setActorName(staff.getUsername());
        message.setRequestedAt(Instant.now());
        message.setEffectiveAt(Instant.now());
        if (note != null && !note.isBlank()) {
            message.setNote(note);
        }

        try {
            messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_STATUS_COMMAND, message);
            staff.sendMessage(text("Appeal submitted for punishment " + punishmentId + ".", NamedTextColor.GREEN));
        } catch (Exception ex) {
            logger.warn("Failed to broadcast punishment appeal command", ex);
            staff.sendMessage(text("Failed to submit appeal command. Check logs.", NamedTextColor.RED));
        }
    }
}
