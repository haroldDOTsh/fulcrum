package sh.harold.fulcrum.minigame.listener;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;
import sh.harold.fulcrum.minigame.MinigameEngine;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filters chat and command tab completions so that only players sharing the same
 * slot as the requester appear in suggestions.
 */
public final class SlotTabCompletionListener implements Listener {

    private final MinigameEngine minigameEngine;

    public SlotTabCompletionListener(MinigameEngine minigameEngine) {
        this.minigameEngine = minigameEngine;
    }

    private static boolean isPlayerLikeToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.length() > 16) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChatTabComplete(PlayerChatTabCompleteEvent event) {
        if (minigameEngine == null) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> slotId = minigameEngine.resolveSlotId(player.getUniqueId());
        if (slotId.isEmpty()) {
            return;
        }

        Set<String> candidateNames = gatherNamesForSlot(slotId.get(), player.getName());
        if (candidateNames.isEmpty()) {
            return;
        }

        String lastToken = event.getLastToken();
        String lowerToken = lastToken != null ? lastToken.toLowerCase(Locale.ROOT) : "";

        Collection<String> completions = event.getTabCompletions();
        completions.clear();
        candidateNames.stream()
                .filter(name -> lowerToken.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(lowerToken))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(completions::add);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (minigameEngine == null) {
            return;
        }
        if (!(event.getSender() instanceof Player player)) {
            return;
        }
        Optional<String> slotId = minigameEngine.resolveSlotId(player.getUniqueId());
        if (slotId.isEmpty()) {
            return;
        }

        Set<String> candidateNames = gatherNamesForSlot(slotId.get(), player.getName());
        if (candidateNames.isEmpty()) {
            return;
        }

        List<String> completions = event.getCompletions();
        if (completions == null || completions.isEmpty()) {
            return;
        }

        Set<String> allowedLower = candidateNames.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        completions.removeIf(suggestion -> isPlayerLikeToken(suggestion) && !allowedLower.contains(suggestion.toLowerCase(Locale.ROOT)));
    }

    private Set<String> gatherNamesForSlot(String slotId, String fallbackName) {
        Set<String> names = new HashSet<>(minigameEngine.getPlayerNameSnapshotInSlot(slotId));
        if (fallbackName != null && !fallbackName.isBlank()) {
            names.add(fallbackName);
        }
        names.removeIf(Objects::isNull);
        return names;
    }
}
