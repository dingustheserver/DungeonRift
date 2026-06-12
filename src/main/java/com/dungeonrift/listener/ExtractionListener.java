package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * ExtractionListener
 *
 * Detects when a player enters or leaves the extraction zone,
 * and cancels extraction if they take damage while in it.
 *
 * Zone detection uses a simple distance check against the portal location
 * defined in config.yml (instance.extraction-portal).
 * No command blocks or pressure plates needed — pure plugin logic.
 */
public class ExtractionListener implements Listener {

    private final DungeonRift plugin;

    public ExtractionListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    // ── Movement detection ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only fire when the player actually moved to a new block
        if (!movedBlock(event)) return;

        Player player = event.getPlayer();
        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance == null || instance.getState() != DungeonInstance.State.ACTIVE) return;

        boolean inZone    = isInExtractionZone(player, instance);
        boolean wasInZone = instance.inExtractionZone(player.getUniqueId());

        if (inZone && !wasInZone) {
            instance.playerEnterExtractionZone(player);
        } else if (!inZone && wasInZone) {
            instance.playerLeaveExtractionZone(player);
        }
    }

    // ── Damage cancels extraction ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(player.getUniqueId());

        if (instance == null) return;
        if (!instance.inExtractionZone(player.getUniqueId())) return;

        // Taking damage while in zone interrupts extraction
        instance.playerLeaveExtractionZone(player);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInExtractionZone(Player player, DungeonInstance instance) {
        FileConfiguration cfg = plugin.getConfig();
        double radius = cfg.getDouble("instance.extraction-zone-radius", 3.0);

        Location portal = new Location(
                instance.getWorld(),
                cfg.getDouble("instance.extraction-portal.x", 0),
                cfg.getDouble("instance.extraction-portal.y", 64),
                cfg.getDouble("instance.extraction-portal.z", 0)
        );

        return player.getLocation().distanceSquared(portal) <= radius * radius;
    }

    private boolean movedBlock(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return false;
        return from.getBlockX() != to.getBlockX()
            || from.getBlockY() != to.getBlockY()
            || from.getBlockZ() != to.getBlockZ();
    }
}
