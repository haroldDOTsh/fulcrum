package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.PaperObservationSink;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

final class KafkaPaperObservationSink implements PaperObservationSink {
    private final HostSecurityContext securityContext;
    private final Producer<String, String> producer;
    private final String topic;

    KafkaPaperObservationSink(
            HostSecurityContext securityContext,
            Producer<String, String> producer,
            String topic) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = requireNonBlank(topic, "topic");
    }

    @Override
    public void publish(HostObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireObservationGrant();
        try {
            producer.send(new ProducerRecord<>(
                    topic,
                    observation.instanceId().value(),
                    HostObservationWireCodec.encode(observation))).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing Paper host observation", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not publish Paper host observation to " + topic, exception);
        }
    }

    private void requireObservationGrant() {
        if (!securityContext.credentialScope().permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, topic)) {
            throw new SecurityException("Paper Instance is not allowed to produce host observations to " + topic);
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
