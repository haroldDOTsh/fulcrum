package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SessionId;

public sealed interface SessionCommand extends CommandPayload
        permits OpenSession, ActivateSession, HeartbeatSession, CloseSession, ExpireSession {
    SessionId sessionId();
}
