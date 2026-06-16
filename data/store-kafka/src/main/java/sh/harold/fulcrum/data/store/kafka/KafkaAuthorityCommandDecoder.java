package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;

@FunctionalInterface
public interface KafkaAuthorityCommandDecoder<C extends CommandPayload> {
    AuthorityCommand<C> decode(ConsumerRecord<String, String> record);
}
