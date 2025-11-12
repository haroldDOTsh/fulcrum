package sh.harold.fulcrum.npc.adapter;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Citizens2-backed implementation of {@link NpcAdapter}.
 */
public final class CitizensNpcAdapter implements NpcAdapter {
    private final JavaPlugin plugin;
    private final NPCRegistry registry;

    public CitizensNpcAdapter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = CitizensAPI.getNPCRegistry();
    }

    @Override
    public CompletionStage<NpcHandle> spawn(NpcSpawnRequest request) {
        CompletableFuture<NpcHandle> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                NPC npc = registry.createNPC(EntityType.PLAYER, registryName(request.instanceId()));
                configureProfile(npc, request);
                boolean spawned = npc.spawn(request.location(), entity -> applyPostSpawn(npc, request));
                if (!spawned) {
                    throw new IllegalStateException("Failed to spawn NPC at " + request.location());
                }
                future.complete(new CitizensNpcHandle(request.instanceId(), request.definition(), npc));
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    @Override
    public void despawn(NpcHandle handle) {
        if (!(handle instanceof CitizensNpcHandle citizens)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            NPC npc = citizens.npc();
            if (npc.isSpawned()) {
                npc.despawn();
            }
            npc.destroy();
        });
    }

    private void configureProfile(NPC npc, NpcSpawnRequest request) {
        npc.setProtected(true);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setName(registryName(request.instanceId()));
        npc.setBukkitEntityType(request.definition().options().entityType());
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, request.definition().options().collidable());
        npc.data().setPersistent(NPC.Metadata.SILENT, request.definition().options().silent());
        npc.data().setPersistent(NPC.Metadata.USE_MINECRAFT_AI, request.definition().options().aiEnabled());
        npc.data().setPersistent(NPC.Metadata.GLOWING, request.definition().options().glowing());
        npc.getOrAddTrait(LookClose.class).lookClose(request.definition().options().lookClose());
        SkinTrait skinTrait = npc.getOrAddTrait(SkinTrait.class);
        skinTrait.setSkinPersistent(request.instanceId().toString(),
                request.skin().textureSignature(), request.skin().textureValue());
        ScoreboardTrait scoreboard = npc.getOrAddTrait(ScoreboardTrait.class);
        scoreboard.createTeam("npc_" + request.instanceId().toString().substring(0, 5));
        scoreboard.setColor(ChatColor.DARK_GRAY);
    }

    private void applyPostSpawn(NPC npc, NpcSpawnRequest request) {
        Entity entity = npc.getEntity();
        if (entity != null) {
            entity.setGravity(request.definition().options().gravity());
            entity.setGlowing(request.definition().options().glowing());
            entity.setSilent(request.definition().options().silent());
        }
        HologramTrait hologram = npc.getOrAddTrait(HologramTrait.class);
        hologram.clear();
        hologram.setLineHeight(0.3D);
        List<String> hologramLines = request.definition().profile().hologramLines();
        // Citizens stacks hologram lines from the bottom up, so write from prompt -> name to keep name at the top.
        for (int index = hologramLines.size() - 1; index >= 0; index--) {
            hologram.addLine(hologramLines.get(index));
        }
        applyEquipment(npc, request);
    }

    private void applyEquipment(NPC npc, NpcSpawnRequest request) {
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        if (request.definition().equipment().mainHand() != null) {
            equipment.set(EquipmentSlot.HAND, request.definition().equipment().mainHand());
        }
        if (request.definition().equipment().offHand() != null) {
            equipment.set(EquipmentSlot.OFF_HAND, request.definition().equipment().offHand());
        }
        if (request.definition().equipment().helmet() != null) {
            equipment.set(EquipmentSlot.HELMET, request.definition().equipment().helmet());
        }
        if (request.definition().equipment().chestplate() != null) {
            equipment.set(EquipmentSlot.CHESTPLATE, request.definition().equipment().chestplate());
        }
        if (request.definition().equipment().leggings() != null) {
            equipment.set(EquipmentSlot.LEGGINGS, request.definition().equipment().leggings());
        }
        if (request.definition().equipment().boots() != null) {
            equipment.set(EquipmentSlot.BOOTS, request.definition().equipment().boots());
        }
    }

    private String registryName(UUID instanceId) {
        String shortId = instanceId.toString().replace("-", "").substring(0, 8);
        return ChatColor.DARK_GRAY + "[NPC] " + shortId;
    }
}
