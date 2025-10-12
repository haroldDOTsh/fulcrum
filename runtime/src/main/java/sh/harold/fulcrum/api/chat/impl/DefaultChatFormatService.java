package sh.harold.fulcrum.api.chat.impl;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.ChatFormatter;

/**
 * Simple implementation of ChatFormatService.
 * Manages a single formatter instance.
 */
public class DefaultChatFormatService implements ChatFormatService {
    private final ChatFormatter defaultFormatter;
    private ChatFormatter formatter;

    public DefaultChatFormatService(ChatFormatter defaultFormatter) {
        this.defaultFormatter = defaultFormatter;
        this.formatter = defaultFormatter;
    }

    @Override
    public synchronized Component formatMessage(Player player, Component message) {
        return formatter.format(player, message);
    }

    @Override
    public synchronized ChatFormatter getFormatter() {
        return formatter;
    }

    @Override
    public synchronized void setFormatter(ChatFormatter formatter) {
        this.formatter = formatter != null ? formatter : defaultFormatter;
    }
}