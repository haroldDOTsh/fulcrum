package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.host.api.HostObservation;

@FunctionalInterface
public interface PaperObservationSink {
    void publish(HostObservation observation);
}
