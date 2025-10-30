package sh.harold.fulcrum.message.impl;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import sh.harold.fulcrum.message.MessageFacade;
import sh.harold.fulcrum.message.MessageStyle;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.locale.AudienceLocaleResolver;
import sh.harold.fulcrum.message.payload.MessageDescriptor;
import sh.harold.fulcrum.message.payload.MessagePayload;
import sh.harold.fulcrum.message.storage.TranslationBundle;
import sh.harold.fulcrum.message.storage.TranslationCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StandardMessageFacade implements MessageFacade {
    private final TranslationCache translationCache;
    private final AudienceLocaleResolver localeResolver;
    private final DebugGate debugGate;
    private final MiniMessage miniMessage;
    private final PlainTextComponentSerializer plainSerializer;

    public StandardMessageFacade(TranslationCache translationCache,
                                 AudienceLocaleResolver localeResolver,
                                 DebugGate debugGate) {
        this.translationCache = translationCache;
        this.localeResolver = localeResolver;
        this.debugGate = debugGate;
        this.miniMessage = MiniMessage.miniMessage();
        this.plainSerializer = PlainTextComponentSerializer.plainText();
    }

    @Override
    public void send(Audience audience, MessageDescriptor descriptor) {
        if (audience == null) {
            return;
        }
        if (!debugGate.canView(audience, descriptor.debugTier())) {
            return;
        }
        Component component = render(audience, descriptor);
        if (component != null) {
            audience.sendMessage(component);
        }
    }

    @Override
    public Component render(Audience audience, MessageDescriptor descriptor) {
        Locale locale = localeResolver != null ? localeResolver.resolve(audience) : translationCache.defaultLocale();
        return render(locale, descriptor);
    }

    @Override
    public Component render(Locale locale, MessageDescriptor descriptor) {
        MessagePayload payload = descriptor.payload();
        Locale targetLocale = locale == null ? translationCache.defaultLocale() : locale;
        TranslationBundle bundle = translationCache.bundle(payload.feature(), targetLocale);

        String template;
        if (descriptor.skipTranslation()) {
            template = miniMessage.escapeTags(payload.raw());
        } else {
            template = translationCache.resolveTranslation(
                    payload.feature(),
                    targetLocale,
                    payload.path(),
                    payload.raw(),
                    descriptor.arguments().length
            );
        }

        String formatted = applyArguments(template, descriptor.arguments(), descriptor.style());
        String baseString = descriptor.style().prefixOpenTag() + formatted;
        Component baseComponent = miniMessage.deserialize(baseString);

        List<Component> tagComponents = new ArrayList<>();
        for (String tagId : descriptor.tags()) {
            bundle.tag(tagId).ifPresent(value -> {
                try {
                    tagComponents.add(miniMessage.deserialize(value));
                } catch (Exception ignored) {
                    // invalid tag formatting, skip
                }
            });
        }

        if (tagComponents.isEmpty()) {
            return baseComponent;
        }

        Component prefix = Component.empty();
        for (int i = 0; i < tagComponents.size(); i++) {
            if (i > 0) {
                prefix = prefix.append(Component.text(' '));
            }
            prefix = prefix.append(tagComponents.get(i));
        }
        return prefix.append(Component.text(' ')).append(baseComponent);
    }

    @Override
    public String renderPlain(Locale locale, MessageDescriptor descriptor) {
        Component component = render(locale, descriptor);
        return component == null ? "" : plainSerializer.serialize(component);
    }

    @Override
    public Locale defaultLocale() {
        return translationCache.defaultLocale();
    }

    @Override
    public DebugGate debugGate() {
        return debugGate;
    }

    private String applyArguments(String template, Object[] args, MessageStyle style) {
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            String token = "{arg" + i + "}";
            String value = args[i] == null ? "" : args[i].toString();
            String replacement = miniMessage.escapeTags(value);
            if (style.hasArgumentColor()) {
                replacement = style.argumentOpenTag() + replacement + style.argumentCloseTag();
            }
            result = result.replace(token, replacement);
        }
        return result;
    }
}
