package sh.harold.fulcrum.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.payload.MessageDescriptor;

import java.util.Locale;
import java.util.function.BooleanSupplier;

public interface MessageFacade {

    void send(Audience audience, MessageDescriptor descriptor);

    default void sendIf(Audience audience, MessageDescriptor descriptor, BooleanSupplier condition) {
        if (condition == null || condition.getAsBoolean()) {
            send(audience, descriptor);
        }
    }

    Component render(Audience audience, MessageDescriptor descriptor);

    Component render(Locale locale, MessageDescriptor descriptor);

    String renderPlain(Locale locale, MessageDescriptor descriptor);

    Locale defaultLocale();

    DebugGate debugGate();
}
