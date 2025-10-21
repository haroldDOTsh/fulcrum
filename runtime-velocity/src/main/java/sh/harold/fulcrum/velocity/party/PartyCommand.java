package sh.harold.fulcrum.velocity.party;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.party.*;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.*;
import java.util.stream.Collectors;

final class PartyCommand implements SimpleCommand {
    private static final Component FRAME_LINE = Component.text("-----------------------------------------------------", NamedTextColor.BLUE)
            .decorate(TextDecoration.STRIKETHROUGH);

    private final PartyService partyService;
    private final PartyReservationService reservationService;
    private final ProxyServer proxy;
    private final PlayerRoutingFeature routingFeature;
    private final PartyMatchRosterStore rosterStore;
    private final DataAPI dataAPI;
    private final VelocityPlayerSessionService sessionService;
    private final Logger logger;

    PartyCommand(PartyService partyService,
                 PartyReservationService reservationService,
                 ProxyServer proxy,
                 PlayerRoutingFeature routingFeature,
                 PartyMatchRosterStore rosterStore,
                 DataAPI dataAPI,
                 VelocityPlayerSessionService sessionService,
                 Logger logger) {
        this.partyService = partyService;
        this.reservationService = reservationService;
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.rosterStore = rosterStore;
        this.dataAPI = dataAPI;
        this.sessionService = sessionService;
        this.logger = logger;
    }

    private void sendFramed(Player player, Component... lines) {
        sendFramed(player, Arrays.asList(lines));
    }

    private void sendFramed(Player player, Collection<Component> lines) {
        player.sendMessage(FRAME_LINE);
        lines.forEach(player::sendMessage);
        player.sendMessage(FRAME_LINE);
    }

    private Component formatName(UUID playerId, String fallbackName) {
        return PartyTextFormatter.formatName(playerId, fallbackName, dataAPI, sessionService, logger);
    }

    private Component yellow(String text) {
        return PartyTextFormatter.yellow(text);
    }

    private Component redNumber(long value) {
        return PartyTextFormatter.redNumber(value);
    }

    private Component error(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use party commands.", NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(player);
            case "list" -> handleList(player);
            case "leave" -> handleLeave(player);
            case "disband" -> handleDisband(player);
            case "warp" -> handleWarp(player);
            case "mute" -> handleMute(player, true);
            case "unmute" -> handleMute(player, false);
            case "invite" -> {
                if (args.length < 2) {
                    sendFramed(player, Component.text("Usage: /party invite <player>", NamedTextColor.RED));
                    return;
                }
                handleInvite(player, args[1]);
            }
            case "accept" -> handleAccept(player, args);
            case "deny" -> handleDeny(player, args);
            case "hijack" -> handleHijack(player, args);
            case "yoink" -> handleYoink(player, args);
            case "promote" -> {
                if (args.length < 2) {
                    sendFramed(player, Component.text("Usage: /party promote <player>", NamedTextColor.RED));
                    return;
                }
                handleRoleChange(player, args[1], RoleChange.PROMOTE);
            }
            case "demote" -> {
                if (args.length < 2) {
                    sendFramed(player, Component.text("Usage: /party demote <player>", NamedTextColor.RED));
                    return;
                }
                handleRoleChange(player, args[1], RoleChange.DEMOTE);
            }
            case "transfer" -> {
                if (args.length < 2) {
                    sendFramed(player, Component.text("Usage: /party transfer <player>", NamedTextColor.RED));
                    return;
                }
                handleTransfer(player, args[1]);
            }
            case "kick" -> {
                if (args.length < 2) {
                    sendFramed(player, Component.text("Usage: /party kick <player>", NamedTextColor.RED));
                    return;
                }
                handleKick(player, args[1]);
            }
            case "kickoffline" -> handleKickOffline(player);
            case "settings" -> handleSettings(player);
            default -> {
                // Support /party <player> shorthand invite
                if (args.length == 1) {
                    handleInvite(player, args[0]);
                } else {
                    sendHelp(player);
                }
            }
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();
        if (args.length == 0) {
            return suggestions(filterStaffCommands(List.of("help", "invite", "accept", "deny", "list", "leave", "warp",
                    "disband", "promote", "demote", "transfer", "kick", "kickoffline", "mute", "unmute", "settings",
                    "hijack", "yoink"), source), source);
        }
        if (args.length == 1) {
            return suggestions(filterStaffCommands(List.of("help", "invite", "accept", "deny", "list", "leave", "warp",
                            "disband", "promote", "demote", "transfer", "kick", "kickoffline", "mute", "unmute", "settings",
                            "hijack", "yoink"), source),
                    args[0], source);
        }
        if (args.length == 2 && Set.of("accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (source instanceof Player player) {
                return partyService.getInvites(player.getUniqueId()).stream()
                        .map(PartyInvite::getInviterUsername)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .filter(name -> startsWithIgnoreCase(name, args[1]))
                        .collect(Collectors.toList());
            }
            return List.of();
        }
        if (args.length == 2 && Set.of("invite", "promote", "demote", "transfer", "kick", "yoink", "hijack")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Set.of("yoink", "hijack").contains(sub) && !isStaffSource(source)) {
                return List.of();
            }
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> startsWithIgnoreCase(name, args[1]))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> suggestions(List<String> base, String prefix, CommandSource source) {
        return filterStaffCommands(base, source).stream()
                .filter(entry -> startsWithIgnoreCase(entry, prefix))
                .collect(Collectors.toList());
    }

    private List<String> suggestions(List<String> base, CommandSource source) {
        return filterStaffCommands(base, source);
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void handleInvite(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);

        PartyOperationResult result = partyService.invitePlayer(
                player.getUniqueId(),
                player.getUsername(),
                targetOpt.map(Player::getUniqueId).orElse(null),
                targetName);
        if (!result.isSuccess()) {
            sendError(player, result);
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            sendFramed(player, yellow("Usage: /party accept <player>"));
            return;
        }

        List<PartyInvite> invites = partyService.getInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            sendFramed(player, yellow("You do not have any pending party invites."));
            return;
        }

        String selector = args[1];
        PartyInvite selected = invites.stream()
                .filter(invite -> invite.getInviterUsername() != null
                        && invite.getInviterUsername().equalsIgnoreCase(selector))
                .findFirst()
                .orElse(null);

        if (selected == null) {
            String names = invites.stream()
                    .map(PartyInvite::getInviterUsername)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .collect(Collectors.joining(", "));
            if (names.isBlank()) {
                names = "your pending parties";
            }
            sendFramed(player, yellow("No party invite found from " + selector + ". Pending invites: " + names + "."));
            return;
        }

        PartyOperationResult result = partyService.acceptInvite(player.getUniqueId(), player.getUsername(), selected.getPartyId());
        if (result.isSuccess()) {
            Component joined = Component.text()
                    .append(yellow("You have joined "))
                    .append(formatName(selected.getInviterPlayerId(), selected.getInviterUsername()))
                    .append(yellow("'s party!"))
                    .build();
            sendFramed(player, joined);
        } else {
            sendError(player, result);
        }
    }

    private void handleDeny(Player player, String[] args) {
        List<PartyInvite> invites = partyService.getInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            sendFramed(player, yellow("You do not have any pending party invites."));
            return;
        }

        String selector = args.length >= 2 ? args[1] : null;
        if (selector == null) {
            partyService.declineInvite(player.getUniqueId(), null);
            sendFramed(player, Component.text("Cleared all pending party invites.", NamedTextColor.YELLOW));
            return;
        }

        PartyInvite selected = invites.stream()
                .filter(invite -> invite.getInviterUsername() != null
                        && invite.getInviterUsername().equalsIgnoreCase(selector))
                .findFirst()
                .orElse(null);

        if (selected == null) {
            sendFramed(player, yellow("No party invite found from " + selector + "."));
            return;
        }

        PartyOperationResult result = partyService.declineInvite(player.getUniqueId(), selected.getPartyId());
        if (result.isSuccess()) {
            sendFramed(player, yellow("Declined the party invite from " + selected.getInviterUsername() + "."));
        } else {
            sendError(player, result);
        }
    }

    private void handleHijack(Player player, String[] args) {
        if (!ensureStaff(player)) {
            return;
        }
        if (args.length < 2) {
            sendFramed(player, Component.text("Usage: /party hijack <player|uuid|partyId>", NamedTextColor.RED));
            return;
        }
        String selector = args[1];
        Optional<PartySnapshot> targetParty = resolveParty(selector);
        if (targetParty.isEmpty()) {
            sendFramed(player, error("Couldn't find a party for that selector."));
            return;
        }
        PartySnapshot snapshot = targetParty.get();
        UUID partyId = snapshot.getPartyId();
        PartyOperationResult result = partyService.hijackParty(player.getUniqueId(), player.getUsername(), partyId);
        if (!result.isSuccess()) {
            sendError(player, result);
            return;
        }
        PartySnapshot updated = result.party().orElseGet(() -> partyService.getParty(partyId).orElse(snapshot));
        if (updated != null) {
            Component message = Component.text()
                    .append(formatName(player.getUniqueId(), player.getUsername()))
                    .append(yellow(" has hijacked the party!"))
                    .build();
            broadcastToParty(updated, message);
        }
    }

    private void handleYoink(Player player, String[] args) {
        if (!ensureStaff(player)) {
            return;
        }
        if (args.length < 2) {
            sendFramed(player, Component.text("Usage: /party yoink <player|uuid>", NamedTextColor.RED));
            return;
        }

        Optional<Player> targetOpt = resolveOnlinePlayer(args[1]);
        if (targetOpt.isEmpty()) {
            sendFramed(player, error("Couldn't find that player online."));
            return;
        }
        Player target = targetOpt.get();
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sendFramed(player, error("You cannot target yourself."));
            return;
        }

        PartyOperationResult result = partyService.yoinkPlayer(
                player.getUniqueId(),
                player.getUsername(),
                target.getUniqueId(),
                target.getUsername());
        if (!result.isSuccess()) {
            sendError(player, result);
            return;
        }

        PartySnapshot snapshot = result.party()
                .orElseGet(() -> partyService.getPartyByPlayer(player.getUniqueId()).orElse(null));
        Component victimMessage = Component.text()
                .append(formatName(player.getUniqueId(), player.getUsername()))
                .append(yellow(" has yoinked you into the party!"))
                .build();
        sendFramed(target, victimMessage);
        if (snapshot != null) {
            Component othersMessage = Component.text()
                    .append(formatName(player.getUniqueId(), player.getUsername()))
                    .append(yellow(" has yoinked "))
                    .append(formatName(target.getUniqueId(), target.getUsername()))
                    .append(yellow(" into the party!"))
                    .build();
            broadcastToParty(snapshot, othersMessage, target.getUniqueId());
        }
    }

    private void handleList(Player player) {
        Optional<PartySnapshot> snapshotOpt = partyService.getPartyByPlayer(player.getUniqueId());
        if (snapshotOpt.isEmpty()) {
            sendFramed(player, yellow("You are not currently in a party!"));
            return;
        }
        PartySnapshot snapshot = snapshotOpt.get();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Party Members (" + snapshot.getSize() + "/" + PartyConstants.HARD_SIZE_CAP + "):",
                NamedTextColor.AQUA));
        snapshot.getMembers().values().stream()
                .sorted(Comparator.comparing(PartyMember::getRole)
                        .thenComparing(PartyMember::getJoinedAt))
                .map(this::formatMemberLine)
                .forEach(lines::add);

        if (!snapshot.getInvites().isEmpty()) {
            lines.add(Component.text("Pending invites:", NamedTextColor.GRAY));
            for (PartyInvite invite : snapshot.getInvites().values()) {
                long remaining = Math.max(0, (invite.getExpiresAt() - System.currentTimeMillis()) / 1000L);
                lines.add(Component.text("- " + invite.getTargetUsername() + " (" + remaining + "s)", NamedTextColor.GRAY));
            }
        }
        sendFramed(player, lines);
    }

    private void handleLeave(Player player) {
        PartyOperationResult result = partyService.leaveParty(player.getUniqueId());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("You left the party.", NamedTextColor.YELLOW));
        } else {
            sendError(player, result);
        }
    }

    private void handleDisband(Player player) {
        PartyOperationResult result = partyService.disbandParty(player.getUniqueId());
        if (!result.isSuccess()) {
            sendError(player, result);
        }
    }

    private void handleRoleChange(Player player, String targetName, RoleChange change) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, yellow("Couldn't find a player with that name!"));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = switch (change) {
            case PROMOTE -> partyService.promote(player.getUniqueId(), target.getUniqueId());
            case DEMOTE -> partyService.demote(player.getUniqueId(), target.getUniqueId());
        };
        if (result.isSuccess()) {
            PartySnapshot snapshot = result.party().orElse(null);
            PartyMember updated = snapshot != null ? snapshot.getMember(target.getUniqueId()) : null;
            Component response;
            if (change == RoleChange.PROMOTE) {
                PartyRole newRole = updated != null ? updated.getRole() : PartyRole.MODERATOR;
                if (newRole == PartyRole.LEADER) {
                    response = Component.text()
                            .append(formatName(player.getUniqueId(), player.getUsername()))
                            .append(yellow(" has promoted "))
                            .append(formatName(target.getUniqueId(), target.getUsername()))
                            .append(yellow(" to Party Leader."))
                            .build();
                } else {
                    response = Component.text()
                            .append(formatName(player.getUniqueId(), player.getUsername()))
                            .append(yellow(" has promoted "))
                            .append(formatName(target.getUniqueId(), target.getUsername()))
                            .append(yellow(" to Party Moderator."))
                            .build();
                }
            } else {
                response = Component.text()
                        .append(formatName(player.getUniqueId(), player.getUsername()))
                        .append(yellow(" has demoted "))
                        .append(formatName(target.getUniqueId(), target.getUsername()))
                        .append(yellow(" to Party Member."))
                        .build();
            }
            sendFramed(player, response);
        } else {
            sendError(player, result);
        }
    }

    private void handleTransfer(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, yellow("Couldn't find a player with that name!"));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = partyService.transferLeadership(player.getUniqueId(), target.getUniqueId());
        if (result.isSuccess()) {
            Component promoted = Component.text()
                    .append(formatName(player.getUniqueId(), player.getUsername()))
                    .append(yellow(" has promoted "))
                    .append(formatName(target.getUniqueId(), target.getUsername()))
                    .append(yellow(" to Party Leader."))
                    .build();
            Component demoted = Component.text()
                    .append(formatName(player.getUniqueId(), player.getUsername()))
                    .append(yellow(" is now a Party Moderator."))
                    .build();
            sendFramed(player, promoted, demoted);
        } else {
            sendError(player, result);
        }
    }

    private void handleKick(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, yellow("Couldn't find a player with that name!"));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = partyService.kick(player.getUniqueId(), target.getUniqueId());
        if (!result.isSuccess()) {
            sendError(player, result);
        }
    }

    private void handleKickOffline(Player player) {
        long threshold = PartyConstants.DISCONNECT_GRACE_SECONDS * 1000L;
        PartyOperationResult result = partyService.kickOffline(player.getUniqueId(), threshold);
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Removed offline party members.", NamedTextColor.YELLOW));
        } else {
            sendError(player, result);
        }
    }

    private void handleMute(Player player, boolean muted) {
        PartyOperationResult result = partyService.toggleMute(player.getUniqueId(), muted);
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Party chat is now " + (muted ? "muted." : "unmuted."),
                    muted ? NamedTextColor.RED : NamedTextColor.GREEN));
        } else {
            sendError(player, result);
        }
    }

    private void handleSettings(Player player) {
        Optional<PartySnapshot> snapshotOpt = partyService.getPartyByPlayer(player.getUniqueId());
        if (snapshotOpt.isEmpty()) {
            sendFramed(player, Component.text("You are not in a party.", NamedTextColor.RED));
            return;
        }
        PartySnapshot snapshot = snapshotOpt.get();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Party Settings:", NamedTextColor.AQUA));
        lines.add(Component.text("- Invite Privacy: " + snapshot.getSettings().getInvitePrivacy(), NamedTextColor.GRAY));
        lines.add(Component.text("- Auto Queue Accept: " + snapshot.getSettings().isAutoQueueAccept(), NamedTextColor.GRAY));
        lines.add(Component.text("- Require Mod Warp Confirmation: " + snapshot.getSettings().isRequireModWarpConfirmation(), NamedTextColor.GRAY));
        lines.add(Component.text("- Require In-Match Warp Confirmation: " + snapshot.getSettings().isRequireInMatchWarpConfirmation(), NamedTextColor.GRAY));
        lines.add(Component.text("- Party Chat Muted: " + snapshot.getSettings().isPartyChatMuted(), NamedTextColor.GRAY));
        sendFramed(player, lines);
    }

    private void handleWarp(Player player) {
        Optional<PartySnapshot> snapshotOpt = partyService.getPartyByPlayer(player.getUniqueId());
        if (snapshotOpt.isEmpty()) {
            sendFramed(player, Component.text("You are not in a party.", NamedTextColor.RED));
            return;
        }

        PartySnapshot snapshot = snapshotOpt.get();
        PartyMember actor = snapshot.getMember(player.getUniqueId());
        if (actor == null) {
            sendFramed(player, Component.text("You are not in a party.", NamedTextColor.RED));
            return;
        }

        if (snapshot.getActiveReservationId() != null) {
            sendFramed(player, Component.text("Your party is already queued.", NamedTextColor.RED));
            return;
        }

        boolean isLeader = actor.getRole() == PartyRole.LEADER;
        boolean isModerator = actor.getRole() == PartyRole.MODERATOR;
        if (!isLeader) {
            if (!isModerator) {
                sendFramed(player, Component.text("Only the party leader can warp the party.", NamedTextColor.RED));
                return;
            }
            if (snapshot.getSettings().isRequireModWarpConfirmation()) {
                sendFramed(player, Component.text("A leader confirmation is required before moderators can warp.", NamedTextColor.RED));
                return;
            }
        }

        String targetServerId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (targetServerId == null || targetServerId.isBlank()) {
            sendFramed(player, Component.text("You are not connected to a server.", NamedTextColor.RED));
            return;
        }

        String familyId = null;
        String variantId = null;
        String slotId = null;
        if (routingFeature != null) {
            Optional<PlayerRoutingFeature.PlayerLocationSnapshot> locationOpt = routingFeature.getPlayerLocation(player.getUniqueId());
            if (locationOpt.isPresent()) {
                PlayerRoutingFeature.PlayerLocationSnapshot location = locationOpt.get();
                Map<String, String> metadata = location.getMetadata();
                familyId = firstNonBlank(location.getFamilyId(),
                        metadata.get("family"),
                        metadata.get("familyId"));
                variantId = firstNonBlank(
                        metadata.get("variant"),
                        metadata.get("familyVariant"),
                        metadata.get("variantId"));
                slotId = location.getSlotId();
            }
        }

        if (familyId == null || familyId.isBlank()) {
            sendFramed(player, Component.text("Unable to determine the matchmaking family for this server.", NamedTextColor.RED));
            return;
        }

        List<Player> participants = snapshot.getMembers().keySet().stream()
                .map(proxy::getPlayer)
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
        if (participants.isEmpty()) {
            sendFramed(player, Component.text("No online party members to warp.", NamedTextColor.RED));
            return;
        }

        Set<UUID> allowedRoster = null;
        if (rosterStore != null && slotId != null && !slotId.isBlank()) {
            allowedRoster = rosterStore.getRoster(slotId)
                    .map(PartyMatchRosterStore.RosterSnapshot::players)
                    .orElse(null);
        }

        List<Player> allowedParticipants = participants;
        List<Player> disallowed = List.of();
        if (allowedRoster != null && !allowedRoster.isEmpty()) {
            List<Player> permitted = new ArrayList<>();
            List<Player> rejected = new ArrayList<>();
            for (Player member : participants) {
                if (allowedRoster.contains(member.getUniqueId())) {
                    permitted.add(member);
                } else {
                    rejected.add(member);
                }
            }
            if (permitted.isEmpty()) {
                sendFramed(player, Component.text(
                        "None of your online party members are part of this match.",
                        NamedTextColor.RED));
                return;
            }
            allowedParticipants = permitted;
            disallowed = rejected;
        }

        PartyReservationResult result = reservationService.reserveForPlay(snapshot, familyId, variantId, targetServerId, allowedParticipants);
        if (!result.isSuccess()) {
            result.errorMessage().ifPresent(message -> sendFramed(player, message));
            return;
        }

        Component confirmation = Component.text("Warping party to ", NamedTextColor.GREEN)
                .append(Component.text(targetServerId, NamedTextColor.AQUA))
                .append(Component.text(". Reservation expires in " + PartyConstants.RESERVATION_TOKEN_TTL_SECONDS + "s.", NamedTextColor.GREEN));
        sendFramed(player, confirmation);

        List<Player> disallowedParticipants = disallowed;
        if (!disallowedParticipants.isEmpty()) {
            sendFramed(player, Component.text(
                    "Skipped " + disallowedParticipants.size() + " player" + (disallowedParticipants.size() == 1 ? "" : "s") + " who were not part of this match.",
                    NamedTextColor.YELLOW));
            disallowedParticipants.forEach(skipped -> sendFramed(skipped, Component.text(
                    "You were not part of that match, so the warp skipped you.",
                    NamedTextColor.RED)));
        }

        result.participants().stream()
                .filter(member -> !member.getUniqueId().equals(player.getUniqueId()))
                .forEach(member -> sendFramed(member, Component.text(
                        player.getUsername() + " is warping the party to " + targetServerId + ".",
                        NamedTextColor.GOLD)));
    }

    private void sendHelp(Player player) {
        List<Component> lines = List.of(
                Component.text("Party Commands:", NamedTextColor.AQUA),
                Component.text("/party <player> - Invite a player.", NamedTextColor.GRAY),
                Component.text("/party invite <player> - Invite a player.", NamedTextColor.GRAY),
                Component.text("/party accept <player> - Accept an invite from that player.", NamedTextColor.GRAY),
                Component.text("/party deny <player> - Decline an invite (no name clears all).", NamedTextColor.GRAY),
                Component.text("/party list - Show party members.", NamedTextColor.GRAY),
                Component.text("/party leave - Leave your current party.", NamedTextColor.GRAY),
                Component.text("/party warp - Warp your party to this server.", NamedTextColor.GRAY),
                Component.text("/party disband - Disband your party.", NamedTextColor.GRAY),
                Component.text("/party promote <player> - Promote a member to moderator.", NamedTextColor.GRAY),
                Component.text("/party demote <player> - Demote a moderator to member.", NamedTextColor.GRAY),
                Component.text("/party transfer <player> - Transfer leadership.", NamedTextColor.GRAY),
                Component.text("/party kick <player> - Remove a player.", NamedTextColor.GRAY),
                Component.text("/party kickoffline - Remove offline members.", NamedTextColor.GRAY),
                Component.text("/party mute - Mute party chat.", NamedTextColor.GRAY));
        sendFramed(player, lines);
    }

    private void sendError(Player player, PartyOperationResult result) {
        Component message = switch (result.errorCode()) {
            case ALREADY_IN_PARTY -> error("You're already in a party!");
            case NOT_IN_PARTY -> error("You are not currently in a party!");
            case NOT_LEADER -> error("Only the party leader can do that.");
            case NOT_MODERATOR -> error("Only the leader or party moderators can do that.");
            case TARGET_ALREADY_IN_PARTY -> error("That player is already in a party.");
            case TARGET_NOT_IN_PARTY -> error("That player is not in your party.");
            case INVITE_ALREADY_PENDING -> error("That player already has a pending invite.");
            case INVITE_NOT_FOUND -> error("You have no pending invites.");
            case INVITE_EXPIRED -> error("That party invite has expired.");
            case PARTY_FULL -> error("Your party is full.");
            case LEADER_ONLY_ACTION -> error("Only the party leader can do that.");
            case CANNOT_TARGET_SELF -> error("You cannot target yourself.");
            case REDIS_UNAVAILABLE -> error("Party service is busy, try again soon.");
            default -> error(result.message() != null ? result.message() : "Unable to complete that action.");
        };
        sendFramed(player, message);
    }

    private boolean ensureStaff(Player player) {
        boolean allowed = VelocityRankUtils.hasRankOrHigherSync(player, Rank.HELPER, sessionService, dataAPI, logger);
        if (!allowed) {
            sendError(player, PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-ids"));
        }
        return allowed;
    }

    private Optional<PartySnapshot> resolveParty(String selector) {
        UUID uuid = tryParseUuid(selector);
        if (uuid != null) {
            Optional<PartySnapshot> byId = partyService.getParty(uuid);
            if (byId.isPresent()) {
                return byId;
            }
            Optional<PartySnapshot> byPlayer = partyService.getPartyByPlayer(uuid);
            if (byPlayer.isPresent()) {
                return byPlayer;
            }
        }
        return proxy.getPlayer(selector)
                .flatMap(player -> partyService.getPartyByPlayer(player.getUniqueId()));
    }

    private Optional<Player> resolveOnlinePlayer(String selector) {
        UUID uuid = tryParseUuid(selector);
        if (uuid != null) {
            Optional<Player> byId = proxy.getPlayer(uuid);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return proxy.getPlayer(selector);
    }

    private UUID tryParseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void broadcastToParty(PartySnapshot snapshot, Component message, UUID... excluded) {
        if (snapshot == null || message == null) {
            return;
        }
        Set<UUID> excludeSet = (excluded == null || excluded.length == 0)
                ? Collections.emptySet()
                : Arrays.stream(excluded)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        snapshot.getMembers().keySet().forEach(memberId -> {
            if (excludeSet.contains(memberId)) {
                return;
            }
            proxy.getPlayer(memberId).ifPresent(target -> sendFramed(target, message));
        });
    }

    private List<String> filterStaffCommands(List<String> base, CommandSource source) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        boolean staff = isStaffSource(source);
        return base.stream()
                .filter(entry -> staff || !Set.of("hijack", "yoink").contains(entry.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private boolean isStaffSource(CommandSource source) {
        if (!(source instanceof Player player)) {
            return true;
        }
        return VelocityRankUtils.hasRankOrHigherSync(player, Rank.HELPER, sessionService, dataAPI, logger);
    }

    private Component formatMemberLine(PartyMember member) {
        NamedTextColor color = switch (member.getRole()) {
            case LEADER -> NamedTextColor.GOLD;
            case MODERATOR -> NamedTextColor.AQUA;
            default -> NamedTextColor.GRAY;
        };
        String suffix = member.isOnline() ? "" : " (offline)";
        String marker = switch (member.getRole()) {
            case LEADER -> " *";
            case MODERATOR -> " +";
            default -> "";
        };
        return Component.text(member.getUsername() + marker + suffix, color);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private enum RoleChange {
        PROMOTE,
        DEMOTE
    }
}
