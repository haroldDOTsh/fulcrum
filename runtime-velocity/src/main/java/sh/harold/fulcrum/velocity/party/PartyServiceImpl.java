package sh.harold.fulcrum.velocity.party;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyMessageAction;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage;
import sh.harold.fulcrum.api.party.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

final class PartyServiceImpl implements PartyService {
    private final PartyRepository repository;
    private final PartyLockManager lockManager;
    private final MessageBus messageBus;
    private final Logger logger;
    private final long inviteTtlSeconds;
    private final long soloDisbandGraceMillis;
    private final long disconnectGraceMillis;

    PartyServiceImpl(PartyRepository repository,
                     PartyLockManager lockManager,
                     MessageBus messageBus,
                     Logger logger) {
        this.repository = repository;
        this.lockManager = lockManager;
        this.messageBus = messageBus;
        this.logger = logger;
        this.inviteTtlSeconds = PartyConstants.INVITE_TTL_SECONDS;
        this.soloDisbandGraceMillis = PartyConstants.IDLE_DISBAND_GRACE_SECONDS * 1000L;
        this.disconnectGraceMillis = PartyConstants.DISCONNECT_GRACE_SECONDS * 1000L;
    }

    @Override
    public Optional<PartySnapshot> getParty(UUID partyId) {
        return repository.load(partyId);
    }

    @Override
    public Optional<PartySnapshot> getPartyByPlayer(UUID playerId) {
        return repository.findPartyIdForPlayer(playerId).flatMap(repository::load);
    }

    @Override
    public PartyOperationResult createParty(UUID leaderId, String leaderName) {
        if (leaderId == null || leaderName == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "invalid-leader");
        }

        if (repository.findPartyIdForPlayer(leaderId).isPresent()) {
            return PartyOperationResult.failure(PartyErrorCode.ALREADY_IN_PARTY, "already-in-party");
        }

        PartySnapshot snapshot = new PartySnapshot();
        UUID partyId = UUID.randomUUID();
        snapshot.setPartyId(partyId);
        snapshot.setLeaderId(leaderId);

        PartyMember leader = new PartyMember(leaderId, leaderName, PartyRole.LEADER);
        Map<UUID, PartyMember> members = new LinkedHashMap<>();
        members.put(leaderId, leader);
        snapshot.setMembers(members);
        snapshot.setInvites(new LinkedHashMap<>());
        snapshot.setSettings(PartySettings.defaults());
        long now = System.currentTimeMillis();
        snapshot.setLastActivityAt(now);
        updateSoloPartyExpiry(snapshot, now);

        repository.save(snapshot);
        repository.assignPlayerToParty(leaderId, partyId);
        publishUpdate(snapshot, PartyMessageAction.CREATED, leaderId, null, "party created");
        logger.info("Created party {} leader={}", partyId, leaderName);
        return PartyOperationResult.success(snapshot);
    }

    @Override
    public PartyOperationResult invitePlayer(UUID actorId, String actorName, UUID targetId, String targetName) {
        if (actorId == null || targetId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-ids");
        }
        if (actorId.equals(targetId)) {
            return PartyOperationResult.failure(PartyErrorCode.CANNOT_TARGET_SELF, "self-target");
        }
        var maybeParty = getPartyByPlayer(actorId);
        if (maybeParty.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        PartySnapshot snapshot = maybeParty.get();
        PartyMember actor = snapshot.getMember(actorId);
        if (actor == null) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        if (actor.getRole() != PartyRole.LEADER && actor.getRole() != PartyRole.MODERATOR) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_MODERATOR, "no-invite-permission");
        }

        if (snapshot.getSize() >= PartyConstants.HARD_SIZE_CAP) {
            return PartyOperationResult.failure(PartyErrorCode.PARTY_FULL, "party-full");
        }

        if (snapshot.isMember(targetId)) {
            return PartyOperationResult.failure(PartyErrorCode.TARGET_ALREADY_IN_PARTY, "target-in-party");
        }

        Optional<PartyInvite> pending = repository.findInvite(targetId, snapshot.getPartyId());
        if (pending.isPresent()) {
            return PartyOperationResult.failure(PartyErrorCode.INVITE_ALREADY_PENDING, "invite-already-pending");
        }

        PartyInvite invite = new PartyInvite(
                snapshot.getPartyId(),
                targetId,
                targetName,
                actorId,
                actorName,
                System.currentTimeMillis() + inviteTtlSeconds * 1000L
        );

        mutateWithLock(snapshot.getPartyId(), locked -> {
            PartySnapshot party = repository.load(locked.getPartyId()).orElse(locked);
            party.getInvites().put(targetId, invite);
            party.setLastActivityAt(System.currentTimeMillis());
            repository.save(party);
            return party;
        });
        repository.saveInvite(invite, inviteTtlSeconds);
        publishUpdate(snapshot, PartyMessageAction.INVITE_SENT, actorId, targetId, "invite sent");
        return PartyOperationResult.success(snapshot, invite);
    }

    @Override
    public PartyOperationResult acceptInvite(UUID playerId, String playerName, UUID partyId) {
        if (playerId == null || partyId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<PartyInvite> inviteOpt = repository.findInvite(playerId, partyId);
        if (inviteOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.INVITE_NOT_FOUND, "invite-missing");
        }
        PartyInvite invite = inviteOpt.get();
        long now = System.currentTimeMillis();
        if (invite.isExpired(now)) {
            repository.deleteInvite(playerId, partyId);
            mutateWithLock(partyId, snapshot -> {
                if (snapshot != null) {
                    snapshot.getInvites().remove(playerId);
                    snapshot.setLastActivityAt(now);
                    repository.save(snapshot);
                }
                return snapshot;
            });
            return PartyOperationResult.failure(PartyErrorCode.INVITE_EXPIRED, "invite-expired");
        }

        PartyOperationResult result = executeWithLock(invite.getPartyId(), snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (snapshot.getSize() >= PartyConstants.HARD_SIZE_CAP) {
                return PartyOperationResult.failure(PartyErrorCode.PARTY_FULL, "party-full");
            }
            if (repository.findPartyIdForPlayer(playerId).isPresent()) {
                return PartyOperationResult.failure(PartyErrorCode.ALREADY_IN_PARTY, "already-in-party");
            }

            snapshot.getInvites().remove(playerId);
            PartyMember member = new PartyMember(playerId, playerName, PartyRole.MEMBER);
            snapshot.getMembers().put(playerId, member);
            snapshot.setLastActivityAt(now);
            updateSoloPartyExpiry(snapshot, now);
            repository.save(snapshot);
            repository.assignPlayerToParty(playerId, snapshot.getPartyId());
            return PartyOperationResult.success(snapshot);
        });

        repository.deleteInvite(playerId, partyId);
        result.party().ifPresent(party -> publishUpdate(party, PartyMessageAction.INVITE_ACCEPTED,
                invite.getInviterPlayerId(), playerId, "invite accepted"));
        return result;
    }

    @Override
    public PartyOperationResult declineInvite(UUID playerId, UUID partyId) {
        if (playerId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        if (partyId == null) {
            List<PartyInvite> invites = repository.findInvites(playerId);
            invites.forEach(invite -> mutateWithLock(invite.getPartyId(), snapshot -> {
                if (snapshot != null) {
                    snapshot.getInvites().remove(playerId);
                    snapshot.setLastActivityAt(System.currentTimeMillis());
                    repository.save(snapshot);
                    publishUpdate(snapshot, PartyMessageAction.INVITE_REVOKED,
                            invite.getInviterPlayerId(), playerId, "invite declined");
                }
                return snapshot;
            }));
            repository.deleteInvites(playerId);
            return PartyOperationResult.success();
        }
        Optional<PartyInvite> inviteOpt = repository.findInvite(playerId, partyId);
        inviteOpt.ifPresent(invite -> mutateWithLock(invite.getPartyId(), snapshot -> {
            if (snapshot != null) {
                snapshot.getInvites().remove(playerId);
                snapshot.setLastActivityAt(System.currentTimeMillis());
                repository.save(snapshot);
                publishUpdate(snapshot, PartyMessageAction.INVITE_REVOKED, invite.getInviterPlayerId(), playerId, "invite declined");
            }
            return snapshot;
        }));
        repository.deleteInvite(playerId, partyId);
        return PartyOperationResult.success();
    }

    @Override
    public PartyOperationResult leaveParty(UUID playerId) {
        if (playerId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(playerId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();

        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                repository.clearPlayerParty(playerId);
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            PartyMember target = snapshot.getMember(playerId);
            if (target == null) {
                repository.clearPlayerParty(playerId);
                return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
            }

            if (snapshot.getLeaderId().equals(playerId)) {
                snapshot.getMembers().remove(playerId);
                repository.clearPlayerParty(playerId);
                if (snapshot.getMembers().isEmpty()) {
                    repository.delete(partyId);
                    publishUpdate(snapshot, PartyMessageAction.DISBANDED, playerId, null, "leader left and disbanded");
                    return PartyOperationResult.success();
                }
                UUID nextLeaderId = selectNextLeader(snapshot);
                snapshot.setLeaderId(nextLeaderId);
                PartyMember nextLeader = snapshot.getMember(nextLeaderId);
                if (nextLeader != null) {
                    nextLeader.setRole(PartyRole.LEADER);
                }
                long now = System.currentTimeMillis();
                snapshot.setLastActivityAt(now);
                updateSoloPartyExpiry(snapshot, now);
                repository.save(snapshot);
                publishUpdate(snapshot, PartyMessageAction.TRANSFERRED, playerId, nextLeaderId, "leader left party");
                return PartyOperationResult.success(snapshot);
            } else {
                snapshot.getMembers().remove(playerId);
                repository.clearPlayerParty(playerId);
                long now = System.currentTimeMillis();
                snapshot.setLastActivityAt(now);
                updateSoloPartyExpiry(snapshot, now);
                repository.save(snapshot);
                publishUpdate(snapshot, PartyMessageAction.MEMBER_LEFT, playerId, null, "member left");
                return PartyOperationResult.success(snapshot);
            }
        });
    }

    @Override
    public PartyOperationResult disbandParty(UUID actorId) {
        if (actorId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();

        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (!actorId.equals(snapshot.getLeaderId())) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_LEADER, "leader-only");
            }

            snapshot.getMembers().keySet().forEach(repository::clearPlayerParty);
            repository.delete(partyId);
            publishUpdate(snapshot, PartyMessageAction.DISBANDED, actorId, null, "party disbanded");
            return PartyOperationResult.success();
        });
    }

    @Override
    public PartyOperationResult promote(UUID actorId, UUID targetId) {
        if (actorId == null || targetId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (!actorId.equals(snapshot.getLeaderId())) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_LEADER, "leader-only");
            }
            PartyMember target = snapshot.getMember(targetId);
            if (target == null) {
                return PartyOperationResult.failure(PartyErrorCode.TARGET_NOT_IN_PARTY, "target-missing");
            }
            if (target.getRole() == PartyRole.LEADER) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "already-leader");
            }

            long now = System.currentTimeMillis();
            if (target.getRole() == PartyRole.MODERATOR) {
                PartyMember currentLeader = snapshot.getMember(actorId);
                if (currentLeader != null) {
                    currentLeader.setRole(PartyRole.MODERATOR);
                }
                target.setRole(PartyRole.LEADER);
                snapshot.setLeaderId(targetId);
                snapshot.setLastActivityAt(now);
                repository.save(snapshot);
                publishUpdate(snapshot, PartyMessageAction.TRANSFERRED, actorId, targetId, "promotion transferred");
                return PartyOperationResult.success(snapshot);
            }

            target.setRole(PartyRole.MODERATOR);
            snapshot.setLastActivityAt(now);
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.ROLE_CHANGED, actorId, targetId, "role change");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult demote(UUID actorId, UUID targetId) {
        return changeRole(actorId, targetId, PartyRole.MEMBER);
    }

    @Override
    public PartyOperationResult transferLeadership(UUID actorId, UUID targetId) {
        if (actorId == null || targetId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (!actorId.equals(snapshot.getLeaderId())) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_LEADER, "leader-only");
            }
            PartyMember target = snapshot.getMember(targetId);
            if (target == null) {
                return PartyOperationResult.failure(PartyErrorCode.TARGET_NOT_IN_PARTY, "target-missing");
            }
            PartyMember currentLeader = snapshot.getMember(actorId);
            if (currentLeader != null) {
                currentLeader.setRole(PartyRole.MODERATOR);
            }
            target.setRole(PartyRole.LEADER);
            snapshot.setLeaderId(targetId);
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.TRANSFERRED, actorId, targetId, "leadership transferred");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult kick(UUID actorId, UUID targetId) {
        if (actorId == null || targetId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            PartyMember actor = snapshot.getMember(actorId);
            if (actor == null || (actor.getRole() != PartyRole.LEADER && actor.getRole() != PartyRole.MODERATOR)) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_MODERATOR, "no-kick-permission");
            }
            PartyMember target = snapshot.getMember(targetId);
            if (target == null) {
                return PartyOperationResult.failure(PartyErrorCode.TARGET_NOT_IN_PARTY, "target-missing");
            }
            if (target.getRole() == PartyRole.LEADER) {
                return PartyOperationResult.failure(PartyErrorCode.LEADER_ONLY_ACTION, "cannot-kick-leader");
            }
            if (actor.getRole() == PartyRole.MODERATOR && target.getRole() == PartyRole.MODERATOR) {
                return PartyOperationResult.failure(PartyErrorCode.LEADER_ONLY_ACTION, "mods-cannot-kick-mods");
            }
            snapshot.getMembers().remove(targetId);
            repository.clearPlayerParty(targetId);
            long now = System.currentTimeMillis();
            snapshot.setLastActivityAt(now);
            updateSoloPartyExpiry(snapshot, now);
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.MEMBER_KICKED, actorId, targetId, "member kicked");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult kickOffline(UUID actorId, long offlineThresholdMillis) {
        if (actorId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            PartyMember actor = snapshot.getMember(actorId);
            if (actor == null || (actor.getRole() != PartyRole.LEADER && actor.getRole() != PartyRole.MODERATOR)) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_MODERATOR, "no-kick-permission");
            }

            Map<UUID, String> removed = new LinkedHashMap<>();
            Iterator<Map.Entry<UUID, PartyMember>> iterator = snapshot.getMembers().entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PartyMember> entry = iterator.next();
                PartyMember member = entry.getValue();
                if (member == null || member.getRole() == PartyRole.LEADER) {
                    continue;
                }
                if (!member.isOnline()) {
                    removed.put(entry.getKey(), member.getUsername());
                    iterator.remove();
                }
            }
            removed.keySet().forEach(repository::clearPlayerParty);
            if (!removed.isEmpty()) {
                long now = System.currentTimeMillis();
                snapshot.setLastActivityAt(now);
                updateSoloPartyExpiry(snapshot, now);
                repository.save(snapshot);
                removed.forEach((target, name) -> publishUpdate(snapshot,
                        PartyMessageAction.MEMBER_KICKED,
                        actorId,
                        target,
                        composeRemovalReason("offline-kick", name)));
            }
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult toggleMute(UUID actorId, boolean muted) {
        return updateSettings(actorId, settings -> settings.setPartyChatMuted(muted));
    }

    @Override
    public PartyOperationResult updateSettings(UUID actorId, PartySettingsMutator mutator) {
        if (actorId == null || mutator == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (!actorId.equals(snapshot.getLeaderId()) && !isModerator(snapshot, actorId)) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_MODERATOR, "no-settings-permission");
            }
            PartySettings settings = snapshot.getSettings();
            mutator.apply(settings);
            snapshot.setSettings(settings);
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.SETTINGS_UPDATED, actorId, null, "settings updated");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public List<PartyInvite> getInvites(UUID playerId) {
        return repository.findInvites(playerId);
    }

    @Override
    public void refreshPresence(UUID playerId, String username, boolean online) {
        if (playerId == null) {
            return;
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(playerId);
        partyIdOpt.ifPresent(partyId -> mutateWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                repository.clearPlayerParty(playerId);
                return null;
            }
            PartyMember member = snapshot.getMember(playerId);
            if (member == null) {
                repository.clearPlayerParty(playerId);
                return null;
            }
            member.setOnline(online);
            member.setLastSeenAt(System.currentTimeMillis());
            if (username != null && !username.isBlank()) {
                member.setUsername(username);
            }
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);
            return snapshot;
        }));
    }

    @Override
    public void purgeExpiredInvites() {
        Set<UUID> parties = repository.listActiveParties();
        long now = System.currentTimeMillis();
        for (UUID partyId : parties) {
            mutateWithLock(partyId, snapshot -> {
                if (snapshot == null) {
                    repository.removeActiveParty(partyId);
                    return null;
                }
                boolean changed = false;
                List<UUID> expired = new ArrayList<>();
                for (Map.Entry<UUID, PartyInvite> entry : snapshot.getInvites().entrySet()) {
                    PartyInvite invite = entry.getValue();
                    if (invite == null || invite.isExpired(now)) {
                        expired.add(entry.getKey());
                        repository.deleteInvite(entry.getKey(), snapshot.getPartyId());
                        changed = true;
                    }
                }
                expired.forEach(snapshot.getInvites()::remove);
                if (changed) {
                    snapshot.setLastActivityAt(now);
                    repository.save(snapshot);
                    publishUpdate(snapshot, PartyMessageAction.INVITE_EXPIRED, null, null, "invite cleanup");
                }
                return snapshot;
            });
        }
    }

    void performMaintenance() {
        Set<UUID> parties = repository.listActiveParties();
        if (parties.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long offlineCutoff = now - disconnectGraceMillis;

        for (UUID partyId : parties) {
            mutateWithLock(partyId, snapshot -> {
                if (snapshot == null) {
                    repository.removeActiveParty(partyId);
                    return null;
                }

                boolean changed = false;
                Map<UUID, String> removedMembers = new LinkedHashMap<>();

                Iterator<Map.Entry<UUID, PartyMember>> iterator = snapshot.getMembers().entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<UUID, PartyMember> entry = iterator.next();
                    PartyMember member = entry.getValue();
                    UUID memberId = entry.getKey();
                    if (member == null) {
                        iterator.remove();
                        if (memberId != null) {
                            repository.clearPlayerParty(memberId);
                        }
                        changed = true;
                        continue;
                    }
                    if (member.getRole() == PartyRole.LEADER) {
                        continue;
                    }
                    if (!member.isOnline() && member.getLastSeenAt() > 0L && member.getLastSeenAt() < offlineCutoff) {
                        removedMembers.put(memberId, member.getUsername());
                        iterator.remove();
                    }
                }

                if (!removedMembers.isEmpty()) {
                    removedMembers.keySet().forEach(repository::clearPlayerParty);
                    changed = true;
                }

                if (snapshot.getMembers().isEmpty()) {
                    repository.delete(snapshot.getPartyId());
                    publishUpdate(snapshot, PartyMessageAction.DISBANDED, null, null, "empty-party-pruned");
                    return null;
                }

                boolean soloChanged = updateSoloPartyExpiry(snapshot, now);
                if (soloChanged) {
                    changed = true;
                }

                if (snapshot.getPendingIdleDisbandAt() > 0
                        && snapshot.getPendingIdleDisbandAt() <= now
                        && snapshot.getSize() <= 1) {
                    snapshot.getMembers().keySet().forEach(repository::clearPlayerParty);
                    repository.delete(snapshot.getPartyId());
                    publishUpdate(snapshot, PartyMessageAction.DISBANDED, null, null, "solo-party-expired");
                    return null;
                }

                if (changed) {
                    snapshot.setLastActivityAt(now);
                    repository.save(snapshot);
                }

                removedMembers.forEach((removed, name) -> publishUpdate(snapshot,
                        PartyMessageAction.MEMBER_KICKED,
                        null,
                        removed,
                        composeRemovalReason("offline-timeout", name)));
                return snapshot;
            });
        }
    }

    private PartyOperationResult changeRole(UUID actorId, UUID targetId, PartyRole targetRole) {
        if (actorId == null || targetId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "missing-player");
        }
        Optional<UUID> partyIdOpt = repository.findPartyIdForPlayer(actorId);
        if (partyIdOpt.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.NOT_IN_PARTY, "not-in-party");
        }
        UUID partyId = partyIdOpt.get();
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            if (!actorId.equals(snapshot.getLeaderId())) {
                return PartyOperationResult.failure(PartyErrorCode.NOT_LEADER, "leader-only");
            }
            PartyMember target = snapshot.getMember(targetId);
            if (target == null) {
                return PartyOperationResult.failure(PartyErrorCode.TARGET_NOT_IN_PARTY, "target-missing");
            }
            if (targetRole == PartyRole.LEADER) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "invalid-role");
            }
            if (targetRole == PartyRole.MODERATOR && target.getRole() == PartyRole.MODERATOR) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "already-mod");
            }
            if (targetRole == PartyRole.MEMBER && target.getRole() == PartyRole.MEMBER) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "already-member");
            }
            target.setRole(targetRole);
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.ROLE_CHANGED, actorId, targetId, "role change");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult setActiveReservation(UUID partyId, String reservationId, String targetServerId) {
        if (partyId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
        }
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            snapshot.setActiveReservationId(reservationId);
            snapshot.setActiveServerId(targetServerId);
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);
            publishUpdate(snapshot, PartyMessageAction.RESERVATION_CREATED, null, null, "reservation updated");
            return PartyOperationResult.success(snapshot);
        });
    }

    @Override
    public PartyOperationResult clearActiveReservation(UUID partyId,
                                                       String reservationId,
                                                       boolean success,
                                                       String reason) {
        if (partyId == null) {
            return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
        }
        return executeWithLock(partyId, snapshot -> {
            if (snapshot == null) {
                return PartyOperationResult.failure(PartyErrorCode.UNKNOWN, "party-missing");
            }
            String currentReservation = snapshot.getActiveReservationId();
            if (currentReservation == null) {
                return PartyOperationResult.success(snapshot);
            }
            if (reservationId != null && !reservationId.equals(currentReservation)) {
                logger.info("Clearing reservation {} on party {} but active reservation is {}",
                        reservationId, partyId, currentReservation);
            }

            snapshot.setActiveReservationId(null);
            snapshot.setActiveServerId(null);
            snapshot.setLastActivityAt(System.currentTimeMillis());
            repository.save(snapshot);

            String updateReason = (reason != null && !reason.isBlank())
                    ? reason
                    : (success ? "reservation completed" : "reservation cleared");
            publishUpdate(snapshot, PartyMessageAction.RESERVATION_CLAIMED, null, null, updateReason);
            return PartyOperationResult.success(snapshot);
        });
    }

    private boolean updateSoloPartyExpiry(PartySnapshot snapshot, long referenceTimeMillis) {
        if (snapshot == null) {
            return false;
        }
        if (snapshot.getSize() <= 1) {
            if (snapshot.getPendingIdleDisbandAt() == 0L) {
                snapshot.setPendingIdleDisbandAt(referenceTimeMillis + soloDisbandGraceMillis);
                return true;
            }
        } else if (snapshot.getPendingIdleDisbandAt() != 0L) {
            snapshot.setPendingIdleDisbandAt(0L);
            return true;
        }
        return false;
    }

    private String composeRemovalReason(String base, String username) {
        if (base == null || base.isBlank()) {
            return username != null ? username : "";
        }
        if (username == null || username.isBlank()) {
            return base;
        }
        return base + ":" + username;
    }

    private boolean isModerator(PartySnapshot snapshot, UUID playerId) {
        PartyMember member = snapshot.getMember(playerId);
        if (member == null) {
            return false;
        }
        return member.getRole() == PartyRole.LEADER || member.getRole() == PartyRole.MODERATOR;
    }

    private PartyOperationResult executeWithLock(UUID partyId, Function<PartySnapshot, PartyOperationResult> action) {
        Optional<String> lockToken = lockManager.acquire(partyId);
        if (lockToken.isEmpty()) {
            return PartyOperationResult.failure(PartyErrorCode.REDIS_UNAVAILABLE, "lock-failed");
        }
        try {
            PartySnapshot snapshot = repository.load(partyId).orElse(null);
            return action.apply(snapshot);
        } finally {
            lockManager.release(partyId, lockToken.get());
        }
    }

    private PartySnapshot mutateWithLock(UUID partyId, Function<PartySnapshot, PartySnapshot> action) {
        Optional<String> lockToken = lockManager.acquire(partyId);
        if (lockToken.isEmpty()) {
            return null;
        }
        try {
            PartySnapshot snapshot = repository.load(partyId).orElse(null);
            return action.apply(snapshot);
        } finally {
            lockManager.release(partyId, lockToken.get());
        }
    }

    private UUID selectNextLeader(PartySnapshot snapshot) {
        return snapshot.getMembers().values().stream()
                .sorted(Comparator
                        .comparing(PartyMember::getRole, (a, b) -> {
                            if (a == b) {
                                return 0;
                            }
                            if (a == PartyRole.MODERATOR) {
                                return -1;
                            }
                            if (b == PartyRole.MODERATOR) {
                                return 1;
                            }
                            return 0;
                        })
                        .thenComparingLong(PartyMember::getJoinedAt))
                .map(PartyMember::getPlayerId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Party missing fallback leader"));
    }

    private void publishUpdate(PartySnapshot snapshot,
                               PartyMessageAction action,
                               UUID actorId,
                               UUID targetId,
                               String reason) {
        if (messageBus == null || snapshot == null) {
            return;
        }
        PartyUpdateMessage message = new PartyUpdateMessage();
        message.setPartyId(snapshot.getPartyId());
        message.setSnapshot(snapshot);
        message.setAction(action);
        message.setActorPlayerId(actorId);
        message.setTargetPlayerId(targetId);
        message.setReason(reason);
        message.setTimestamp(Instant.now().toEpochMilli());
        try {
            messageBus.broadcast(ChannelConstants.PARTY_UPDATE, message);
        } catch (Exception ex) {
            logger.warn("Failed to broadcast party update", ex);
        }
    }
}
