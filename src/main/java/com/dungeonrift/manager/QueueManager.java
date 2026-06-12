package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.Party;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * QueueManager
 *
 * For solo play: player joins queue → immediately spawns a solo instance.
 * For parties:   leader calls /rift party ready → party spawns an instance together.
 *
 * This is intentionally simple — one instance per group, no matchmaking.
 * If you want matchmaking (e.g. fill a party slot with a solo player),
 * expand the solo queue logic here.
 */
public class QueueManager {

    private final DungeonRift plugin;

    /** Solo players waiting (currently instant — one instance per player). */
    private final Queue<UUID> soloQueue = new ConcurrentLinkedQueue<>();

    public QueueManager(DungeonRift plugin) {
        this.plugin = plugin;
    }

    // ── Solo queue ────────────────────────────────────────────────────────────

    /**
     * Queue a solo player.
     * Current behaviour: immediately spawns a private instance.
     * Change to a timed wait here if you ever want solo matchmaking.
     */
    public void enqueueSolo(Player player) {
        if (plugin.getInstanceManager().isInInstance(player.getUniqueId())) {
            player.sendMessage("§cYou are already in a dungeon instance.");
            return;
        }
        if (soloQueue.contains(player.getUniqueId())) {
            player.sendMessage("§cYou are already in the queue.");
            return;
        }

        player.sendMessage("§8[§6DungeonRift§8] §aQueued solo. Loading your dungeon...");
        soloQueue.add(player.getUniqueId());

        // Spawn immediately
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            soloQueue.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            plugin.getInstanceManager().spawnInstance(List.of(player));
        });
    }

    /**
     * Remove a player from the solo queue (they left hub or disconnected).
     */
    public void dequeue(Player player) {
        if (soloQueue.remove(player.getUniqueId())) {
            player.sendMessage("§7Removed from queue.");
        }
    }

    // ── Party queue ───────────────────────────────────────────────────────────

    /**
     * Called by PartyManager when the leader marks the party ready.
     * Spawns one shared instance for all members immediately.
     */
    public void enqueueParty(List<Player> members, Party party) {
        // Validate all members are online and not in another instance
        for (Player p : members) {
            if (plugin.getInstanceManager().isInInstance(p.getUniqueId())) {
                members.forEach(m -> m.sendMessage(
                        "§c" + p.getName() + " is already in a dungeon. Party queue cancelled."));
                party.setState(Party.State.FORMING);
                return;
            }
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            party.setState(Party.State.IN_GAME);
            plugin.getInstanceManager().spawnInstance(members);
        });
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public boolean isQueued(UUID uuid) {
        return soloQueue.contains(uuid);
    }

    public int getSoloQueueSize() {
        return soloQueue.size();
    }
}
