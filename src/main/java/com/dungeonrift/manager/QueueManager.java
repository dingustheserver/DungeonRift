package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
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
        if (soloQueue.contains(player.getUniqueId())) {
            player.sendMessage("§c[DungeonRift] You are already in the queue.");
            return;
        }
        if (countdownTasks.containsKey(player.getUniqueId())) {
            player.sendMessage("§c[DungeonRift] You are already counting down.");
            return;
        }

        soloQueue.add(player.getUniqueId());
        player.sendMessage("§8[§6DungeonRift§8] §ePreparing your rift... §7(use §c/rift leave §7to cancel)");
        startCountdown(player);
    }

    private void startCountdown(Player player) {
        // Show "10" immediately
        player.sendTitle("§6Entering Rift", "§e10s", 0, 25, 5);

        // Mutable counter — array so lambda can write to it
        final int[] secondsLeft = {10};
        // Holder so the lambda can cancel itself
        final BukkitTask[] holder = {null};

        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            // Guard: cancelled externally (dequeue / disconnect)
            if (!player.isOnline() || !soloQueue.contains(player.getUniqueId())) {
                holder[0].cancel();
                countdownTasks.remove(player.getUniqueId());
                return;
            }

            secondsLeft[0]--;

            if (secondsLeft[0] > 0) {
                player.sendTitle("§6Entering Rift", "§e" + secondsLeft[0] + "s", 0, 25, 5);
            } else {
                // Reached 0 — cancel self, clean up, spawn
                holder[0].cancel();
                countdownTasks.remove(player.getUniqueId());
                soloQueue.remove(player.getUniqueId());
                player.resetTitle();
                plugin.getInstanceManager().spawnInstance(List.of(player));
            }

        }, 20L, 20L); // first tick at 1 second, every second after

        countdownTasks.put(player.getUniqueId(), holder[0]);
    }

    public void dequeue(Player player) {
        boolean wasQueued = soloQueue.remove(player.getUniqueId());
        BukkitTask task   = countdownTasks.remove(player.getUniqueId());

        if (task != null) task.cancel();

        if (wasQueued || task != null) {
            player.resetTitle();
            player.sendMessage("§7[DungeonRift] Queue cancelled.");
        }
    }

    // ── Party queue ───────────────────────────────────────────────────────────

    public void enqueueParty(List<Player> members, Party party) {
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

    public boolean isQueued(UUID uuid) { return soloQueue.contains(uuid); }
    public int getSoloQueueSize()      { return soloQueue.size(); }
}
