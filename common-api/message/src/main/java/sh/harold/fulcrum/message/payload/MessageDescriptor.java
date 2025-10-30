package sh.harold.fulcrum.message.payload;

import sh.harold.fulcrum.message.MessageStyle;
import sh.harold.fulcrum.message.debug.DebugTier;

import java.util.List;

public record MessageDescriptor(
        MessageStyle style,
        MessagePayload payload,
        Object[] arguments,
        List<String> tags,
        DebugTier debugTier,
        boolean skipTranslation
) {
}
