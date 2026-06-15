package sh.harold.fulcrum.velocity.fundamentals.creative;

import org.slf4j.Logger;
import sh.harold.library.message.velocity.VelocityMessageSender;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

public final class VelocityCreativeMessageFeature implements VelocityFeature {
    @Override
    public String getName() {
        return "creative-messages";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        serviceLocator.register(VelocityMessageSender.class, new VelocityMessageSender());
    }

    @Override
    public void shutdown() {
    }
}
