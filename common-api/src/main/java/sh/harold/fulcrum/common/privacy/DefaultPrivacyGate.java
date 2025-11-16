package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.api.friends.FriendService;
import sh.harold.fulcrum.api.friends.FriendSnapshot;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.common.settings.SettingLevel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DefaultPrivacyGate implements PrivacyGate {
    private final PlayerSettingsService playerSettings;
    private final FriendService friendService;
    private final RankService rankService;
    private final PrivacySignals signals;
    private final PrivacyDomainRegistry registry;

    public DefaultPrivacyGate(PlayerSettingsService playerSettings,
                              FriendService friendService,
                              RankService rankService,
                              PrivacySignals signals,
                              PrivacyDomainRegistry registry) {
        this.playerSettings = playerSettings;
        this.friendService = friendService;
        this.rankService = rankService;
        this.signals = signals != null ? signals : PrivacySignals.none();
        this.registry = registry;
    }

    private static <T> CompletableFuture<T> toFuture(CompletionStage<T> stage, T fallback) {
        if (stage == null) {
            return CompletableFuture.completedFuture(fallback);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        stage.whenComplete((value, throwable) -> {
            if (throwable != null) {
                future.complete(fallback);
            } else if (value == null) {
                future.complete(fallback);
            } else {
                future.complete(value);
            }
        });
        return future;
    }

    @Override
    public CompletionStage<PrivacyResult> canSendPartyInvite(UUID actorId, UUID targetId) {
        return evaluate(actorId, targetId, PrivacyDomain.PARTY_INVITES);
    }

    @Override
    public CompletionStage<PrivacyResult> canSendFriendRequest(UUID actorId, UUID targetId) {
        return evaluate(actorId, targetId, PrivacyDomain.FRIEND_INVITES);
    }

    @Override
    public CompletionStage<PrivacyResult> canSendDirectMessage(UUID actorId, UUID targetId) {
        return evaluate(actorId, targetId, PrivacyDomain.DIRECT_MESSAGES);
    }

    @Override
    public CompletionStage<PrivacyResult> evaluate(UUID actorId, UUID targetId, PrivacyDomain domain) {
        if (actorId == null || targetId == null) {
            return CompletableFuture.completedFuture(PrivacyResult.deny("Invalid actor or target."));
        }
        if (actorId.equals(targetId)) {
            return CompletableFuture.completedFuture(PrivacyResult.deny("You cannot target yourself."));
        }
        if (registry == null) {
            return CompletableFuture.completedFuture(PrivacyResult.allow());
        }
        PrivacyDomainConfig config = registry.get(domain).orElse(null);
        if (config == null) {
            return CompletableFuture.completedFuture(PrivacyResult.allow());
        }
        CompletionStage<SettingLevel> levelStage = playerSettings != null
                ? playerSettings.getLevel(targetId, domain.settingKey(), config.defaultLevel())
                : CompletableFuture.completedFuture(config.defaultLevel());
        return levelStage.thenCompose(level -> resolveContext(actorId, targetId)
                .thenApply(context -> decide(config, level, context)));
    }

    private PrivacyResult decide(PrivacyDomainConfig config, SettingLevel requested, PrivacyContext context) {
        if (context.staff()) {
            return PrivacyResult.allow();
        }
        SettingLevel effective = config.resolveLevel(requested);
        if (effective == SettingLevel.NONE) {
            return PrivacyResult.allow();
        }
        PrivacyResult result = config.requirement().evaluate(effective, context);
        if (!result.allowed() && result.denialReason().isEmpty()) {
            return config.fallbackDeny();
        }
        return result;
    }

    private CompletionStage<PrivacyContext> resolveContext(UUID actorId, UUID targetId) {
        CompletableFuture<Boolean> staffFuture = toFuture(rankService != null
                ? rankService.isStaff(actorId)
                : CompletableFuture.completedFuture(false), false);
        CompletableFuture<FriendSnapshot> actorSnapshotFuture = toFuture(friendService != null
                ? friendService.getSnapshot(actorId, false)
                : CompletableFuture.completedFuture(FriendSnapshot.empty()), FriendSnapshot.empty());
        CompletableFuture<FriendSnapshot> targetSnapshotFuture = toFuture(friendService != null
                ? friendService.getSnapshot(targetId, false)
                : CompletableFuture.completedFuture(FriendSnapshot.empty()), FriendSnapshot.empty());
        CompletableFuture<Boolean> sharedServerFuture = toFuture(signals.shareServer(actorId, targetId), false);
        CompletableFuture<Boolean> sharedPartyFuture = toFuture(signals.shareParty(actorId, targetId), false);

        return CompletableFuture.allOf(staffFuture, actorSnapshotFuture, targetSnapshotFuture,
                        sharedServerFuture, sharedPartyFuture)
                .thenApply(ignored -> new PrivacyContext(
                        actorId,
                        targetId,
                        staffFuture.join(),
                        actorSnapshotFuture.join(),
                        targetSnapshotFuture.join(),
                        sharedServerFuture.join(),
                        sharedPartyFuture.join()));
    }
}
