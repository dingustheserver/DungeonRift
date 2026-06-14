package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Detects players stepping into the in-world queue portal block
 * and automatically adds them to the solo queue.
 *
 * Set up with: /dungeon setqueueportal
 * Enable/disable in config: queue-portal.enabled
 */
public class QueuePortalListener implements Listener {

    private final DungeonRift plugin;
    /** Debounce — prevents re-queuing until they leave the zone */
    private final Set<UUID> inPortalZone = new HashSet<>();

    public QueuePortalListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("queue-portal.enabled", false)) return;

        // Only check when player moves to a new block
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        // Check portal is in the right world
        String portalWorld = plugin.getConfig().getString("queue-portal.world", "world_hub");
        if (!player.getWorld().getName().equals(portalWorld)) return;

        double px     = plugin.getConfig().getDouble("queue-portal.x", 0);
        double py     = plugin.getConfig().getDouble("queue-portal.y", 64);
        double pz     = plugin.getConfig().getDouble("queue-portal.z", 0);
        double radius = plugin.getConfig().getDouble("queue-portal.radius", 1.5);

        double dx = to.getX() - px;
        double dy = to.getY() - py;
        double dz = to.getZ() - pz;
        boolean inZone = (dx * dx + dy * dy + dz * dz) <= (radius * radius);

        if (inZone && !inPortalZone.contains(uuid)) {
            inPortalZone.add(uuid);

            // Don't re-queue if already in a queue or instance
            if (plugin.getInstanceManager().isInInstance(uuid)) return;
            if (plugin.getQueueManager().isQueued(uuid)) return;

            plugin.getQueueManager().enqueueSolo(player);

        } else if (!inZone && inPortalZone.contains(uuid)) {
            inPortalZone.remove(uuid);
        }
    }
}
