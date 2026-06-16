package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SessionId;

public sealed interface ExperienceSessionCommand extends CommandPayload permits
        RequestExperienceSession,
        PlaceExperienceSession,
        ActivateExperienceSession,
        EndExperienceSession {
    SessionId sessionId();
}
