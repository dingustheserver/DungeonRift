package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * PlayerDeathListener
 *
 * Handles player death inside an instance:
 *   - Notifies the DungeonInstance (tracks alive players)
 *   - Applies the configured death penalty (drop / clear)
 *   - On respawn: immediately teleports the player to hub
 */
public class PlayerDeathListener implements Listener {

    private final DungeonRift plugin;

    public PlayerDeathListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance == null) return; // not in an instance

        String penalty = plugin.getConfig().getString("loot.death-penalty", "drop_in_world");

        if ("clear".equals(penalty)) {
            // Remove drops so nothing is left in the world
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
        // "drop_in_world" → vanilla behaviour, drops stay (default)
        // "keep"          → set keepInventory via gamerule on the instance world instead

        // Notify the instance (tracks alive count, may trigger close)
        instance.onPlayerDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // If this player just died inside an instance, send them to hub
        // We check the instance manager — onPlayerDeath already removed them from alivePlayers
        // but they're still logged in. returnPlayerToHub handles the teleport.
        if (!plugin.getInstanceManager().isInInstance(player.getUniqueId())) {
            // They're already deregistered — ensure hub spawn is set as respawn point
            event.setRespawnLocation(plugin.getServer().getWorld(
                    plugin.getConfig().getString("hub-world", "world_hub"))
                    .getSpawnLocation());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // If they disconnect mid-instance, treat as death / bail out
        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance != null) {
            // They logged off — remove from alive set
            instance.onPlayerDeath(player);
        }

        // Clean up queue if they were waiting
        plugin.getQueueManager().dequeue(player);
    }
}
