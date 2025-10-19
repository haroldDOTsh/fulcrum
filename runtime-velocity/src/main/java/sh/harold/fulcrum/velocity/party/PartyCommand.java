package sh.harold.fulcrum.velocity.party;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import sh.harold.fulcrum.api.party.*;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;

import java.util.*;
import java.util.stream.Collectors;

final class PartyCommand implements SimpleCommand {
    private static final Component FRAME_LINE = Component.text("-----------------------------------------------------", NamedTextColor.BLUE)
            .decorate(TextDecoration.STRIKETHROUGH, true);

    private final PartyService partyService;
    private final PartyReservationService reservationService;
    private final ProxyServer proxy;
    private final PlayerRoutingFeature routingFeature;
    private final PartyMatchRosterStore rosterStore;

    PartyCommand(PartyService partyService,
                 PartyReservationService reservationService,
                 ProxyServer proxy,
                 PlayerRoutingFeature routingFeature,
                 PartyMatchRosterStore rosterStore) {
        this.partyService = partyService;
        this.reservationService = reservationService;
        this.proxy = proxy;
        this.routingFeature = routingFeature;
        this.rosterStore = rosterStore;
    }

    private void sendFramed(Player player, Component... lines) {
        sendFramed(player, Arrays.asList(lines));
    }

    private void sendFramed(Player player, Collection<Component> lines) {
        player.sendMessage(FRAME_LINE);
        lines.forEach(player::sendMessage);
        player.sendMessage(FRAME_LINE);
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
            case "create" -> handleCreate(player);
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
        if (args.length == 0) {
            return suggestions(List.of("help", "create", "invite", "accept", "deny", "list", "leave", "warp",
                    "disband", "promote", "demote", "transfer", "kick", "kickoffline", "mute", "unmute", "settings"));
        }
        if (args.length == 1) {
            return suggestions(List.of("help", "create", "invite", "accept", "deny", "list", "leave", "warp",
                            "disband", "promote", "demote", "transfer", "kick", "kickoffline", "mute", "unmute", "settings"),
                    args[0]);
        }
        if (args.length == 2 && Set.of("accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            CommandSource source = invocation.source();
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
        if (args.length == 2 && Set.of("invite", "promote", "demote", "transfer", "kick").contains(args[0].toLowerCase(Locale.ROOT))) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> startsWithIgnoreCase(name, args[1]))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> suggestions(List<String> base, String prefix) {
        return base.stream()
                .filter(entry -> startsWithIgnoreCase(entry, prefix))
                .collect(Collectors.toList());
    }

    private List<String> suggestions(List<String> base) {
        return new ArrayList<>(base);
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void handleCreate(Player player) {
        PartyOperationResult result = partyService.createParty(player.getUniqueId(), player.getUsername());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Created a new party.", NamedTextColor.GREEN));
        } else {
            sendError(player, result);
        }
    }

    private void handleInvite(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, Component.text(targetName + " is not online.", NamedTextColor.RED));
            return;
        }
        Player target = targetOpt.get();

        if (partyService.getPartyByPlayer(player.getUniqueId()).isEmpty()) {
            PartyOperationResult create = partyService.createParty(player.getUniqueId(), player.getUsername());
            if (!create.isSuccess()) {
                sendError(player, create);
                return;
            }
        }

        PartyOperationResult result = partyService.invitePlayer(
                player.getUniqueId(), player.getUsername(), target.getUniqueId(), target.getUsername());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Invited " + target.getUsername() + " to your party.", NamedTextColor.GREEN));
            result.invite().ifPresent(invite -> {
                long seconds = Math.max(1, (invite.getExpiresAt() - System.currentTimeMillis()) / 1000L);
                Component message = Component.text(player.getUsername() + " invited you to their party. Use /party accept "
                        + player.getUsername() + " to join. (" + seconds + "s)", NamedTextColor.AQUA);
                sendFramed(target, message);
            });
        } else {
            sendError(player, result);
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            sendFramed(player, Component.text("Usage: /party accept <player>", NamedTextColor.RED));
            return;
        }

        List<PartyInvite> invites = partyService.getInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            sendFramed(player, Component.text("You do not have any pending party invites.", NamedTextColor.RED));
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
            sendFramed(player, Component.text("No party invite found from " + selector + ". Pending invites: " + names + ".",
                    NamedTextColor.YELLOW));
            return;
        }

        PartyOperationResult result = partyService.acceptInvite(player.getUniqueId(), player.getUsername(), selected.getPartyId());
        if (result.isSuccess()) {
            String inviter = selected.getInviterUsername() != null ? selected.getInviterUsername() : "the party";
            sendFramed(player, Component.text("You joined " + inviter + "'s party.", NamedTextColor.GREEN));
        } else {
            sendError(player, result);
        }
    }

    private void handleDeny(Player player, String[] args) {
        List<PartyInvite> invites = partyService.getInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            sendFramed(player, Component.text("You do not have any pending party invites.", NamedTextColor.RED));
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
            sendFramed(player, Component.text("No party invite found from " + selector + ".", NamedTextColor.RED));
            return;
        }

        PartyOperationResult result = partyService.declineInvite(player.getUniqueId(), selected.getPartyId());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Declined the party invite from " + selected.getInviterUsername() + ".",
                    NamedTextColor.YELLOW));
        } else {
            sendError(player, result);
        }
    }

    private void handleList(Player player) {
        Optional<PartySnapshot> snapshotOpt = partyService.getPartyByPlayer(player.getUniqueId());
        if (snapshotOpt.isEmpty()) {
            sendFramed(player, Component.text("You are not in a party.", NamedTextColor.RED));
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
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Disbanded your party.", NamedTextColor.YELLOW));
        } else {
            sendError(player, result);
        }
    }

    private void handleRoleChange(Player player, String targetName, RoleChange change) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, Component.text(targetName + " is not online.", NamedTextColor.RED));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = switch (change) {
            case PROMOTE -> partyService.promote(player.getUniqueId(), target.getUniqueId());
            case DEMOTE -> partyService.demote(player.getUniqueId(), target.getUniqueId());
        };
        if (result.isSuccess()) {
            String message;
            if (change == RoleChange.PROMOTE) {
                PartyRole newRole = result.party()
                        .map(snapshot -> snapshot.getMember(target.getUniqueId()))
                        .map(PartyMember::getRole)
                        .orElse(PartyRole.MODERATOR);
                message = (newRole == PartyRole.LEADER)
                        ? "Promoted " + target.getUsername() + " to party leader."
                        : "Promoted " + target.getUsername() + " to party moderator.";
            } else {
                message = "Demoted " + target.getUsername() + " to party member.";
            }
            sendFramed(player, Component.text(message, NamedTextColor.GREEN));
        } else {
            sendError(player, result);
        }
    }

    private void handleTransfer(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, Component.text(targetName + " is not online.", NamedTextColor.RED));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = partyService.transferLeadership(player.getUniqueId(), target.getUniqueId());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Transferred party leadership to " + target.getUsername() + ".", NamedTextColor.GREEN));
        } else {
            sendError(player, result);
        }
    }

    private void handleKick(Player player, String targetName) {
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            sendFramed(player, Component.text(targetName + " is not online.", NamedTextColor.RED));
            return;
        }
        Player target = targetOpt.get();
        PartyOperationResult result = partyService.kick(player.getUniqueId(), target.getUniqueId());
        if (result.isSuccess()) {
            sendFramed(player, Component.text("Kicked " + target.getUsername() + " from the party.", NamedTextColor.YELLOW));
        } else {
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
            case ALREADY_IN_PARTY -> Component.text("You're already in a party.", NamedTextColor.RED);
            case NOT_IN_PARTY -> Component.text("You are not in a party.", NamedTextColor.RED);
            case NOT_LEADER -> Component.text("Only the party leader can do that.", NamedTextColor.RED);
            case NOT_MODERATOR ->
                    Component.text("Only the leader or party moderators can do that.", NamedTextColor.RED);
            case TARGET_ALREADY_IN_PARTY -> Component.text("That player is already in a party.", NamedTextColor.RED);
            case TARGET_NOT_IN_PARTY -> Component.text("That player is not in your party.", NamedTextColor.RED);
            case INVITE_ALREADY_PENDING ->
                    Component.text("That player already has a pending invite.", NamedTextColor.RED);
            case INVITE_NOT_FOUND -> Component.text("You have no pending invites.", NamedTextColor.RED);
            case INVITE_EXPIRED -> Component.text("That party invite expired.", NamedTextColor.RED);
            case PARTY_FULL -> Component.text("Your party is full.", NamedTextColor.RED);
            case LEADER_ONLY_ACTION -> Component.text("Only the party leader can do that.", NamedTextColor.RED);
            case CANNOT_TARGET_SELF -> Component.text("You cannot target yourself.", NamedTextColor.RED);
            case REDIS_UNAVAILABLE -> Component.text("Party service is busy, try again soon.", NamedTextColor.RED);
            default -> Component.text(
                    result.message() != null ? result.message() : "Unable to complete that action.",
                    NamedTextColor.RED);
        };
        sendFramed(player, message);
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
