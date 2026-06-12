package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.Party;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * QueueManager
 *
 * Solo: 10-second countdown displayed via title before the instance loads.
 *       Player can /rift leave during the countdown to cancel.
 * Party: immediate spawn when leader marks ready.
 */
public class QueueManager {

    private final DungeonRift plugin;

    private final Queue<UUID>            soloQueue      = new ConcurrentLinkedQueue<>();
    /** Players currently in the 10-second pre-game countdown. */
    private final Map<UUID, BukkitTask>  countdownTasks = new ConcurrentHashMap<>();

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

        soloQueue.add(player.getUniqueId());
        player.sendMessage("§8[§6DungeonRift§8] §ePreparing your rift... §7(use §c/rift leave §7to cancel)");

        startCountdown(player);
    }

    /**
     * 10-second countdown shown as a title. On 0 the instance spawns.
     */
    private void startCountdown(Player player) {
        final int[] secondsLeft = {10};

        // Show initial title immediately
        player.sendTitle("§6Entering Rift", "§e" + secondsLeft[0] + "s", 0, 25, 5);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            secondsLeft[0]--;

            if (!player.isOnline() || !soloQueue.contains(player.getUniqueId())) {
                // Cancelled — task will be stopped by dequeue()
                return;
            }

            if (secondsLeft[0] > 0) {
                player.sendTitle("§6Entering Rift", "§e" + secondsLeft[0] + "s", 0, 25, 5);
            } else {
                // Countdown finished — launch
                player.resetTitle();
                soloQueue.remove(player.getUniqueId());
                countdownTasks.remove(player.getUniqueId());
                plugin.getInstanceManager().spawnInstance(List.of(player));
            }

        }, 20L, 20L); // starts after 1 second, fires every second

        countdownTasks.put(player.getUniqueId(), task);
    }

    public void dequeue(Player player) {
        if (soloQueue.remove(player.getUniqueId())) {
            // Cancel countdown task if running
            BukkitTask task = countdownTasks.remove(player.getUniqueId());
            if (task != null) task.cancel();

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

    public boolean isQueued(UUID uuid) {
        return soloQueue.contains(uuid);
    }

    public int getSoloQueueSize() {
        return soloQueue.size();
    }
}
