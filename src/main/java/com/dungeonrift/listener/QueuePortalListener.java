package com.dungeonrift.listener;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.Party;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

/**
 * QueuePortalListener
 *
 * Solo:  stepping in queues the player; stepping out cancels the countdown.
 * Party: all members must be standing inside simultaneously before the
 *        countdown starts. If anyone leaves during the countdown, it cancels.
 */
public class QueuePortalListener implements Listener {

    private final DungeonRift plugin;

    /** Players currently inside the portal zone */
    private final Set<UUID> inPortalZone = new HashSet<>();

    public QueuePortalListener(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("queue-portal.enabled", false)) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        // Only fire on block change
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        String portalWorld = plugin.getConfig().getString("queue-portal.world", "world_hub");
        if (!player.getWorld().getName().equals(portalWorld)) return;

        boolean inZone = isInZone(to);
        boolean wasIn  = inPortalZone.contains(uuid);

        if (inZone && !wasIn) {
            onEnterPortal(player);
        } else if (!inZone && wasIn) {
            onLeavePortal(player);
        }
    }

    // ── Enter ─────────────────────────────────────────────────────────────────

    private void onEnterPortal(Player player) {
        UUID uuid = player.getUniqueId();
        inPortalZone.add(uuid);

        // Already in instance or queue — ignore
        if (plugin.getInstanceManager().isInInstance(uuid)) return;
        if (plugin.getQueueManager().isQueued(uuid)) return;

        // Check if this player is in a party
        Party party = plugin.getPartyManager().getPartyOf(player);

        if (party == null) {
            // Solo — queue immediately
            plugin.getQueueManager().enqueueSolo(player);
        } else {
            // Party — check if all members are now inside the portal
            tryStartPartyPortalQueue(party);
        }
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    private void onLeavePortal(Player player) {
        UUID uuid = player.getUniqueId();
        inPortalZone.remove(uuid);

        // If this player was counting down (solo or party), cancel it
        if (plugin.getQueueManager().isQueued(uuid)) {
            plugin.getQueueManager().dequeue(player);
            player.sendMessage("§c[DungeonRift] You left the portal — queue cancelled.");

            // Notify party members too
            Party party = plugin.getPartyManager().getPartyOf(player);
            if (party != null) {
                party.getMembers().forEach(memberUuid -> {
                    if (memberUuid.equals(uuid)) return;
                    Player member = plugin.getServer().getPlayer(memberUuid);
                    if (member != null && member.isOnline()) {
                        plugin.getQueueManager().dequeue(member);
                        member.sendMessage("§c[DungeonRift] " + player.getName()
                                + " left the portal — queue cancelled.");
                        member.resetTitle();
                    }
                });
            }
        }
    }

    // ── Party portal logic ────────────────────────────────────────────────────

    /**
     * Called whenever a party member enters the portal.
     * Starts the queue countdown only when ALL party members are inside.
     */
    private void tryStartPartyPortalQueue(Party party) {
        // Only start if party is in FORMING state (not already queued or in game)
        if (party.getState() != Party.State.FORMING) return;

        // Check every member is online, in the portal zone, and free
        List<Player> members = new ArrayList<>();
        for (UUID memberUuid : party.getMembers()) {
            Player member = plugin.getServer().getPlayer(memberUuid);
            if (member == null || !member.isOnline()) return; // someone offline
            if (!inPortalZone.contains(memberUuid))  return; // someone not in zone
            if (plugin.getInstanceManager().isInInstance(memberUuid)) return;
            if (plugin.getQueueManager().isQueued(memberUuid)) return;
            members.add(member);
        }

        // Everyone is inside — start the party countdown
        broadcastToParty(party, "§aAll party members are in the portal! Starting countdown...");
        plugin.getQueueManager().enqueueParty(members, party);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInZone(Location loc) {
        double px     = plugin.getConfig().getDouble("queue-portal.x",      0);
        double py     = plugin.getConfig().getDouble("queue-portal.y",     64);
        double pz     = plugin.getConfig().getDouble("queue-portal.z",      0);
        double radius = plugin.getConfig().getDouble("queue-portal.radius", 1.5);
        double dx = loc.getX() - px;
        double dy = loc.getY() - py;
        double dz = loc.getZ() - pz;
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }

    private void broadcastToParty(Party party, String message) {
        party.getMembers().forEach(uuid -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6Party§8] §r" + message);
        });
    }

    /** Called externally to check if a player is currently in the portal zone. */
    public boolean isInPortalZone(UUID uuid) {
        return inPortalZone.contains(uuid);
    }
}
