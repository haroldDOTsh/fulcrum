package sh.harold.fulcrum.message.builder;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.message.MessageFacade;
import sh.harold.fulcrum.message.MessageStyle;
import sh.harold.fulcrum.message.debug.DebugTier;
import sh.harold.fulcrum.message.payload.MessageDescriptor;
import sh.harold.fulcrum.message.payload.MessagePayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class MessageBuilder {
    private final MessageFacade facade;
    private final MessageStyle style;
    private final MessagePayload payload;
    private final Object[] arguments;
    private final List<String> tags = new ArrayList<>();

    private DebugTier debugTier = DebugTier.NONE;
    private boolean skipTranslation;

    public MessageBuilder(MessageFacade facade, MessageStyle style, MessagePayload payload, Object[] args) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.style = Objects.requireNonNull(style, "style");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.arguments = args != null ? Arrays.copyOf(args, args.length) : new Object[0];
    }

    public MessageBuilder builder() {
        return this;
    }

    public MessageBuilder tag(String id) {
        if (id != null && !id.isBlank()) {
            tags.add(id);
        }
        return this;
    }

    public MessageBuilder tags(Iterable<String> identifiers) {
        if (identifiers != null) {
            for (String id : identifiers) {
                tag(id);
            }
        }
        return this;
    }

    public MessageBuilder staff() {
        return tag("staff");
    }

    public MessageBuilder skipTranslation() {
        this.skipTranslation = true;
        return this;
    }

    public MessageBuilder debugTier(DebugTier tier) {
        this.debugTier = tier == null ? DebugTier.NONE : tier;
        return this;
    }

    public void send(Audience audience) {
        facade.send(audience, buildDescriptor());
    }

    public void sendIf(Audience audience, BooleanSupplier condition) {
        facade.sendIf(audience, buildDescriptor(), condition);
    }

    public Component component() {
        return facade.render(facade.defaultLocale(), buildDescriptor());
    }

    public Component component(Audience audience) {
        return facade.render(audience, buildDescriptor());
    }

    public String plain() {
        return facade.renderPlain(facade.defaultLocale(), buildDescriptor());
    }

    public MessageDescriptor buildDescriptor() {
        return new MessageDescriptor(
                style,
                payload,
                Arrays.copyOf(arguments, arguments.length),
                List.copyOf(tags),
                debugTier,
                skipTranslation
        );
    }
}
