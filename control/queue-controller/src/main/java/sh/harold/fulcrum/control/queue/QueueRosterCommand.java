package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface QueueRosterCommand extends CommandPayload permits
        SubmitQueueIntent,
        CancelQueueIntent,
        ExpireQueueIntent,
        FormRosterIntent {
    QueuePartitionKey partitionKey();
}
