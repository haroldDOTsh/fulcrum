package sh.harold.fulcrum.runtime.threading;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.UUID;

public record PlayerSnapshot(UUID playerId,
                             String username,
                             long capturedAtMillis,
                             boolean online,
                             String worldName,
                             double x,
                             double y,
                             double z,
                             String gameMode,
                             int level,
                             float exp,
                             double health,
                             int foodLevel,
                             String ipAddress) {

    public static PlayerSnapshot capture(PaperRuntime runtime, Player player, boolean online) {
        runtime.requirePrimary("capture player snapshot");
        Location location = player.getLocation();
        InetSocketAddress address = player.getAddress();
        String ip = address != null ? address.getAddress().getHostAddress() : null;
        return new PlayerSnapshot(
            player.getUniqueId(),
            player.getName(),
            System.currentTimeMillis(),
            online,
            player.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            player.getGameMode().toString(),
            player.getLevel(),
            player.getExp(),
            player.getHealth(),
            player.getFoodLevel(),
            ip
        );
    }

    public String compactLocation() {
        return String.format("%.2f,%.2f,%.2f", x, y, z);
    }
}
