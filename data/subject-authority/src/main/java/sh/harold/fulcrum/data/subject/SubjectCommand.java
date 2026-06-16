package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

public sealed interface SubjectCommand extends CommandPayload
        permits RegisterSubject, RetireSubject {
    SubjectId subjectId();
}
