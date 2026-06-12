package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import org.bukkit.Sound;
import com.dungeonrift.model.Party;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class QueueManager {

    private final DungeonRift plugin;

    private final Queue<UUID>           soloQueue      = new ConcurrentLinkedQueue<>();
    private final Map<UUID, BukkitTask> countdownTasks = new ConcurrentHashMap<>();

    public QueueManager(DungeonRift plugin) {
        this.plugin = plugin;
    }

    // ── Solo queue ────────────────────────────────────────────────────────────

    public void enqueueSolo(Player player) {
        if (plugin.getInstanceManager().isInInstance(player.getUniqueId())) {
            player.sendMessage("§c[DungeonRift] You are already in a dungeon.");
            return;
        }
        if (soloQueue.contains(player.getUniqueId()) || countdownTasks.containsKey(player.getUniqueId())) {
            player.sendMessage("§c[DungeonRift] You are already in the queue.");
            return;
        }

        soloQueue.add(player.getUniqueId());
        player.sendMessage("§8[§6DungeonRift§8] §ePreparing your rift... §7(use §c/rift leave §7to cancel)");
        startCountdown(List.of(player), null);
    }

    // ── Party queue ───────────────────────────────────────────────────────────

    public void enqueueParty(List<Player> members, Party party) {
        for (Player p : members) {
            if (!p.isOnline()) {
                members.forEach(m -> m.sendMessage("§c" + p.getName() + " went offline. Queue cancelled."));
                party.setState(Party.State.FORMING);
                return;
            }
            if (plugin.getInstanceManager().isInInstance(p.getUniqueId())) {
                members.forEach(m -> m.sendMessage("§c" + p.getName() + " is already in a dungeon. Queue cancelled."));
                party.setState(Party.State.FORMING);
                return;
            }
        }

        List<Player> snapshot = new ArrayList<>(members);
        // Track all party members in soloQueue so /rift leave works for them
        snapshot.forEach(p -> soloQueue.add(p.getUniqueId()));

        startCountdown(snapshot, party);
    }

    // ── Shared countdown (solo and party) ────────────────────────────────────

    /**
     * Runs a 10-second countdown title for all players, then spawns the instance.
     * @param players the players to count down and spawn
     * @param party   the party if this is a party queue, or null for solo
     */
    private void startCountdown(List<Player> players, Party party) {
        final int[]       secondsLeft = {10};
        final BukkitTask[] holder     = {null};

        // Show initial title to all
        players.forEach(p -> p.sendTitle("§6Entering Rift", "§e10s", 0, 25, 5));

        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            // Check if any player cancelled (dequeued)
            boolean anyCancelled = players.stream()
                    .anyMatch(p -> !p.isOnline() || !soloQueue.contains(p.getUniqueId()));

            if (anyCancelled) {
                holder[0].cancel();
                players.forEach(p -> {
                    countdownTasks.remove(p.getUniqueId());
                    soloQueue.remove(p.getUniqueId());
                    if (p.isOnline()) p.resetTitle();
                });
                if (party != null) party.setState(Party.State.FORMING);
                return;
            }

            secondsLeft[0]--;

            if (secondsLeft[0] > 0) {
                players.forEach(p -> {
                    p.sendTitle("§6Entering Rift", "§e" + secondsLeft[0] + "s", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                });
            } else {
                // Done — clean up and spawn
                holder[0].cancel();
                players.forEach(p -> {
                    countdownTasks.remove(p.getUniqueId());
                    soloQueue.remove(p.getUniqueId());
                    p.resetTitle();
                });
                if (party != null) party.setState(Party.State.IN_GAME);
                plugin.getInstanceManager().spawnInstance(players);
            }

        }, 20L, 20L);

        // Register task for every player so /rift leave can cancel it
        players.forEach(p -> countdownTasks.put(p.getUniqueId(), holder[0]));
    }

    // ── Dequeue ───────────────────────────────────────────────────────────────

    public void dequeue(Player player) {
        boolean wasQueued = soloQueue.remove(player.getUniqueId());
        BukkitTask task   = countdownTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        if (wasQueued || task != null) {
            player.resetTitle();
            player.sendMessage("§7[DungeonRift] Queue cancelled.");
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public boolean isQueued(UUID uuid) { return soloQueue.contains(uuid); }
    public int getSoloQueueSize()      { return soloQueue.size(); }
}
