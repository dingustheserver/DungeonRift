package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * InstanceLifecycleListener
 *
 * Guards against players accidentally leaving an instance world via
 * nether/end portals, /tp, or other world-change events.
 *
 * Also cleans up player state if they somehow exit the instance world
 * without going through the extraction or death flow.
 */
public class InstanceLifecycleListener implements Listener {

    private final DungeonRift plugin;

    public InstanceLifecycleListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    /**
     * Block portal travel inside instance worlds.
     * Players must use the extraction zone — they cannot nether/end portal out.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalUse(PlayerPortalEvent event) {
        Player player = event.getPlayer();

        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance == null) return; // not in an instance, allow normally

        // Cancel all portal travel inside instances
        event.setCancelled(true);
        player.sendMessage("§cYou cannot use portals here. Find the extraction point.");
    }

    /**
     * Safety net: if a player somehow ends up in a different world than their
     * registered instance (e.g. an admin /tp), clean up their instance state.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance == null) return;

        // If they moved out of their instance world without going through extraction/death
        if (!player.getWorld().equals(instance.getWorld())) {
            plugin.getLogger().warning(player.getName()
                    + " left instance world without extracting. Cleaning up.");
            instance.onPlayerDeath(player); // treat as a bail-out / death
        }
    }
}
