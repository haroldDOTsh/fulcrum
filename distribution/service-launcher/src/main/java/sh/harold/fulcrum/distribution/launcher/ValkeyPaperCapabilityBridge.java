package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.PaperCapabilityBridge;
import sh.harold.fulcrum.host.paper.PaperChatDecorationRequest;
import sh.harold.fulcrum.host.paper.PaperChatDecorationResponse;
import sh.harold.fulcrum.host.paper.PaperSubjectCapabilityRequest;
import sh.harold.fulcrum.host.paper.PaperSubjectCapabilityView;
import sh.harold.fulcrum.standard.chat.ChatDecorationInput;
import sh.harold.fulcrum.standard.chat.ChatDecorationRenderer;
import sh.harold.fulcrum.standard.chat.ChatDecorationResult;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankState;

import java.util.Objects;
import java.util.Optional;

final class ValkeyPaperCapabilityBridge implements PaperCapabilityBridge {
    static final String PLAYER_PROFILE_CACHE_RESOURCE = "standard.player-profile.effective";
    static final String RANK_CACHE_RESOURCE = "standard.rank.effective";

    private final ValkeyClientHandle valkey;

    ValkeyPaperCapabilityBridge(HostSecurityContext securityContext, ValkeyClientHandle valkey) {
        Objects.requireNonNull(securityContext, "securityContext");
        this.valkey = Objects.requireNonNull(valkey, "valkey");
        if (!HostInstanceKinds.PAPER.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("Paper capability bridge requires a Paper Instance identity");
        }
        requireCacheRead(securityContext, PLAYER_PROFILE_CACHE_RESOURCE, "player-profile effective cache");
        requireCacheRead(securityContext, RANK_CACHE_RESOURCE, "rank effective cache");
    }

    @Override
    public PaperSubjectCapabilityView subjectView(PaperSubjectCapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<PlayerProfileSnapshot> profile = profile(request).current();
        Optional<EffectiveRankSnapshot> rank = rank(request).current();
        String displayName = profile.map(PlayerProfileSnapshot::displayName).orElse(request.username());
        Optional<String> rankLabel = rank.map(EffectiveRankSnapshot::primaryRankKey);
        return new PaperSubjectCapabilityView(request.subjectId(), displayName, rankLabel);
    }

    @Override
    public PaperChatDecorationResponse decorateChat(PaperChatDecorationRequest request) {
        Objects.requireNonNull(request, "request");
        PaperSubjectCapabilityView view = subjectView(new PaperSubjectCapabilityRequest(
                request.subjectId(),
                request.username()));
        ChatDecorationResult result = ChatDecorationRenderer.decorate(new ChatDecorationInput(
                request.subjectId(),
                view.displayName(),
                view.rankLabel(),
                request.message()));
        return new PaperChatDecorationResponse(result.subjectId(), result.renderedText());
    }

    private PlayerProfileState profile(PaperSubjectCapabilityRequest request) {
        String payload = valkey.client().get(PlayerProfileAuthority.cacheKey(request.subjectId()));
        if (payload == null || payload.isBlank()) {
            return PlayerProfileState.empty();
        }
        PlayerProfileState state = PlayerProfileState.parse(payload);
        state.current().ifPresent(snapshot -> {
            if (!snapshot.subjectId().equals(request.subjectId())) {
                throw new IllegalStateException("player-profile cache entry subject does not match request");
            }
        });
        return state;
    }

    private RankState rank(PaperSubjectCapabilityRequest request) {
        String payload = valkey.client().get(RankAuthority.cacheKey(request.subjectId()));
        if (payload == null || payload.isBlank()) {
            return RankState.empty();
        }
        RankState state = RankState.parse(payload);
        state.current().ifPresent(snapshot -> {
            if (!snapshot.subjectId().equals(request.subjectId())) {
                throw new IllegalStateException("rank cache entry subject does not match request");
            }
        });
        return state;
    }

    private static void requireCacheRead(HostSecurityContext securityContext, String resource, String description) {
        if (!securityContext.credentialScope().permits(HostResourceFamily.CACHE, HostAccessMode.READ, resource)) {
            throw new SecurityException("Paper Instance is not allowed to read " + description);
        }
    }
}
