package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Keeps inventory when players die in the hub world,
 * so they can build and keep loadouts safely.
 */
public class HubListener implements Listener {

    private final DungeonRift plugin;

    public HubListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // Only apply to hub world
        if (!isHubWorld(player.getWorld().getName())) return;

        // Skip if player is in a rift instance (handled by DungeonInstance)
        if (plugin.getInstanceManager().isInInstance(player.getUniqueId())) return;

        boolean keepInv = plugin.getConfig().getBoolean("loot.hub-keep-inventory", true);
        if (keepInv) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // If respawning in hub world, send to hub spawn point
        if (!plugin.getInstanceManager().isInInstance(player.getUniqueId())) {
            event.setRespawnLocation(
                    plugin.getInstanceManager().buildHubSpawnLocation());
        }
    }

    private boolean isHubWorld(String worldName) {
        return worldName.equals(plugin.getConfig().getString("hub-world", "world_hub"));
    }
}
