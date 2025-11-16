package sh.harold.fulcrum.velocity.friends;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Centralizes rendering of framed friend messaging so command feedback and background
 * notifications stay visually consistent.
 */
final class FriendMessageRenderer {

    private static final Component FRAME_LINE = Component.text(
                    "-----------------------------------------------------",
                    NamedTextColor.BLUE)
            .decorate(TextDecoration.STRIKETHROUGH);

    private FriendMessageRenderer() {
    }

    static Component info(String message) {
        return Component.text(message, NamedTextColor.YELLOW);
    }

    static Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    static void sendFramed(Player player, Component line) {
        sendFramed(player, List.of(line));
    }

    static void sendFramed(Player player, Collection<Component> lines) {
        if (player == null || lines == null || lines.isEmpty()) {
            return;
        }
        player.sendMessage(FRAME_LINE);
        lines.forEach(player::sendMessage);
        player.sendMessage(FRAME_LINE);
    }

    static void sendFramed(ProxyServer proxy, UUID playerId, Component line) {
        sendFramed(proxy, playerId, List.of(line));
    }

    static void sendFramed(ProxyServer proxy, UUID playerId, Collection<Component> lines) {
        if (proxy == null || playerId == null || lines == null || lines.isEmpty()) {
            return;
        }
        proxy.getPlayer(playerId).ifPresent(player -> sendFramed(player, lines));
    }

    static List<Component> friendRequestPrompt(Component formattedName, String commandTarget) {
        Component safeName = Objects.requireNonNullElse(formattedName, info("Unknown"));
        String targetArg = commandTarget == null || commandTarget.isBlank() ? "unknown" : commandTarget;
        Component header = Component.text()
                .append(Component.text("Friend request from ", NamedTextColor.YELLOW))
                .append(safeName)
                .build();

        Component accept = Component.text("[ACCEPT]", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/friend accept " + targetArg))
                .hoverEvent(HoverEvent.showText(Component.text("Click to accept", NamedTextColor.GREEN)));
        Component deny = Component.text("[DENY]", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/friend deny " + targetArg))
                .hoverEvent(HoverEvent.showText(Component.text("Click to deny", NamedTextColor.RED)));
        Component ignore = Component.text("[IGNORE]", NamedTextColor.GRAY)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/friend ignore " + targetArg))
                .hoverEvent(HoverEvent.showText(Component.text("Click to ignore this request", NamedTextColor.GRAY)));
        Component actions = Component.text()
                .append(accept)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(deny)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(ignore)
                .build();

        return List.of(header, actions);
    }
}
