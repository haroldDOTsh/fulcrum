package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces action flags by cancelling Paper events.
 */
final class ActionFlagListener implements Listener {
    private static final Set<Material> INTERACTABLE_AIR_ITEMS = EnumSet.of(
            Material.ENDER_PEARL,
            Material.ENDER_EYE,
            Material.SNOWBALL,
            Material.SPLASH_POTION,
            Material.LINGERING_POTION,
            Material.FIRE_CHARGE,
            Material.FIREWORK_ROCKET
    );

    private final ActionFlagService service;

    ActionFlagListener(ActionFlagService service) {
        this.service = service;
    }

    private static Player resolveAttackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private static boolean isHostile(LivingEntity entity) {
        if (entity instanceof Monster) {
            return true;
        }
        EntityType type = entity.getType();
        return switch (type) {
            case ENDERMAN, ENDERMITE, GUARDIAN, ELDER_GUARDIAN, PHANTOM, SHULKER -> true;
            default -> false;
        };
    }

    private static boolean isLikelyUseAction(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Right-clicking blocks already handled by INTERACT_BLOCK, but we treat hand usage as well.
            return event.getHand() == EquipmentSlot.HAND;
        }
        Material item = event.getItem() != null ? event.getItem().getType() : null;
        return item != null && INTERACTABLE_AIR_ITEMS.contains(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttackingPlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        UUID attackerId = attacker.getUniqueId();
        Entity victim = event.getEntity();
        if (victim instanceof Player) {
            if (!service.allows(attackerId, ActionFlag.PVP)) {
                event.setCancelled(true);
            }
        } else if (victim instanceof LivingEntity living) {
            if (isHostile(living)) {
                if (!service.allows(attackerId, ActionFlag.DAMAGE_HOSTILE)) {
                    event.setCancelled(true);
                }
            } else {
                if (!service.allows(attackerId, ActionFlag.DAMAGE_PASSIVE)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!service.allows(player.getUniqueId(), ActionFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!service.allows(player.getUniqueId(), ActionFlag.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        UUID playerId = player.getUniqueId();

        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
            if (!service.allows(playerId, ActionFlag.INTERACT_BLOCK)) {
                event.setCancelled(true);
                return;
            }
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (!service.allows(playerId, ActionFlag.GENERAL_USE) && isLikelyUseAction(event)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!service.allows(player.getUniqueId(), ActionFlag.INTERACT_ENTITY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!service.allows(player.getUniqueId(), ActionFlag.ITEM_DROP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!service.allows(player.getUniqueId(), ActionFlag.ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }
}
