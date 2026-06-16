package sh.harold.fulcrum.core.session;

import sh.harold.fulcrum.api.kernel.SessionId;

public record EffectOrigin(String originType, String originId) {
    public static final String SESSION = "session";

    public EffectOrigin {
        originType = RuntimeNames.requireNonBlank(originType, "originType");
        originId = RuntimeNames.requireNonBlank(originId, "originId");
    }

    public static EffectOrigin session(SessionId sessionId) {
        return new EffectOrigin(SESSION, sessionId.value());
    }
}
