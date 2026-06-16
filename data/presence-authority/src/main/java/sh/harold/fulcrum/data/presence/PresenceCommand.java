package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

public sealed interface PresenceCommand extends CommandPayload
        permits ClaimPresence, HeartbeatPresence, ReleasePresence {
    SubjectId subjectId();
}
