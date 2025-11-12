package sh.harold.fulcrum.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Represents an individual dialogue line with optional gating.
 */
public record DialogueLine(
        Function<DialogueContext, Component> renderer,
        Predicate<DialogueContext> predicate,
        Consumer<DialogueProgress> action
) {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public DialogueLine {
        Objects.requireNonNull(renderer, "renderer");
        predicate = predicate == null ? ctx -> true : predicate;
        action = action == null ? ctx -> {
        } : action;
    }

    public static DialogueLine of(String legacyMessage) {
        Component component = LEGACY.deserialize(Objects.requireNonNull(legacyMessage, "legacyMessage"));
        return new DialogueLine(ctx -> component, null, null);
    }

    public static DialogueLine conditional(String legacyMessage, Predicate<DialogueContext> predicate) {
        Component component = LEGACY.deserialize(Objects.requireNonNull(legacyMessage, "legacyMessage"));
        return new DialogueLine(ctx -> component, predicate, null);
    }

    public static DialogueLine dynamic(Function<DialogueContext, Component> renderer) {
        return new DialogueLine(renderer, null, null);
    }

    public DialogueLine withAction(Consumer<DialogueProgress> consumer) {
        return new DialogueLine(renderer, predicate, consumer);
    }

    boolean shouldRender(DialogueContext context) {
        return predicate.test(context);
    }

    Component render(DialogueContext context) {
        return renderer.apply(context);
    }

    void runAction(DialogueProgress progress) {
        action.accept(progress);
    }
}
