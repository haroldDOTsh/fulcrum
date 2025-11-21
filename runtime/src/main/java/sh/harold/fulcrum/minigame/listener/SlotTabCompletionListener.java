package sh.harold.fulcrum.minigame.listener;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;
import sh.harold.fulcrum.fundamentals.slot.presence.SlotPresenceService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filters chat and command tab completions so that only players sharing the same
 * slot as the requester appear in suggestions.
 */
public final class SlotTabCompletionListener implements Listener {

    private final SlotPresenceService slotPresence;

    public SlotTabCompletionListener(SlotPresenceService slotPresence) {
        this.slotPresence = slotPresence;
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
        if (slotPresence == null) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> slotId = resolveSlotId(player.getUniqueId(), player.getWorld().getName());
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
        if (slotPresence == null) {
            return;
        }
        if (!(event.getSender() instanceof Player player)) {
            return;
        }
        Optional<String> slotId = resolveSlotId(player.getUniqueId(), player.getWorld().getName());
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
        Set<String> names = new HashSet<>(slotPresence.getPlayerNamesInSlot(slotId));
        if (fallbackName != null && !fallbackName.isBlank()) {
            names.add(fallbackName);
        }
        names.removeIf(Objects::isNull);
        return names;
    }

    private Optional<String> resolveSlotId(UUID playerId, String worldName) {
        if (slotPresence == null) {
            return Optional.empty();
        }
        Optional<String> resolved = slotPresence.resolveSlotId(playerId);
        if (resolved.isPresent()) {
            return resolved;
        }
        return slotPresence.resolveSlotId(worldName);
    }
}
