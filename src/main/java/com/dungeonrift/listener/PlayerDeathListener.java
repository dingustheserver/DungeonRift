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
 * Death inside an instance = lose everything.
 * Disconnecting mid-instance = treated as forfeit (loot lost).
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

        if (instance == null) return;

        // Drop everything in the world — death means lose all loot
        // Drops stay (vanilla behaviour) so other players could loot the corpse
        // If you want no drops at all, uncomment the next two lines:
        // event.getDrops().clear();
        // event.setDroppedExp(0);

        instance.onPlayerDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // If they died in an instance, respawn in hub
        if (!plugin.getInstanceManager().isInInstance(player.getUniqueId())) {
            String hubName = plugin.getConfig().getString("hub-world", "world_hub");
            org.bukkit.World hub = plugin.getServer().getWorld(hubName);
            if (hub != null) {
                event.setRespawnLocation(hub.getSpawnLocation());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance != null) {
            // Disconnecting mid-run = forfeit, loot lost
            instance.onPlayerForfeit(player);
        }

        plugin.getQueueManager().dequeue(player);
    }
}
