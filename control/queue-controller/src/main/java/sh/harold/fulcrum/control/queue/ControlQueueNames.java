package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;

public final class ControlQueueNames {
    public static final ContractName CONTRACT = new ContractName("control.queue-roster");
    public static final CommandName SUBMIT_QUEUE_INTENT = new CommandName("ctrl.queue.submit-intent");
    public static final CommandName CANCEL_QUEUE_INTENT = new CommandName("ctrl.queue.cancel-intent");
    public static final CommandName EXPIRE_QUEUE_INTENT = new CommandName("ctrl.queue.expire-intent");
    public static final CommandName FORM_ROSTER_INTENT = new CommandName("ctrl.queue.form-roster");

    private ControlQueueNames() {
    }

    public static AggregateId aggregateId(QueuePartitionKey partitionKey) {
        return new AggregateId("queue-roster:" + partitionKey.canonicalValue());
    }

    public static String stateKey(QueuePartitionKey partitionKey) {
        return "ctrl.state.queue-roster:" + partitionKey.canonicalValue();
    }
}
