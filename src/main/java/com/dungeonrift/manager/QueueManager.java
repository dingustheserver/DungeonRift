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
        startSoloCountdown(player);
    }

    private void startSoloCountdown(Player player) {
        player.sendTitle("§6Entering Rift", "§e10s", 0, 25, 5);

        final int[]       secondsLeft = {10};
        final BukkitTask[] holder     = {null};

        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            if (!player.isOnline() || !soloQueue.contains(player.getUniqueId())) {
                holder[0].cancel();
                countdownTasks.remove(player.getUniqueId());
                return;
            }

            secondsLeft[0]--;

            if (secondsLeft[0] > 0) {
                player.sendTitle("§6Entering Rift", "§e" + secondsLeft[0] + "s", 0, 25, 5);
            } else {
                holder[0].cancel();
                countdownTasks.remove(player.getUniqueId());
                soloQueue.remove(player.getUniqueId());
                player.resetTitle();
                plugin.getInstanceManager().spawnInstance(List.of(player));
            }

        }, 20L, 20L);

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

    /**
     * Party spawn — validates all members are online and not already in an
     * instance, then spawns them all into one shared instance together.
     * Uses a 2-tick delay after world creation to ensure the world is fully
     * ready before teleporting all players simultaneously.
     */
    public void enqueueParty(List<Player> members, Party party) {
        // Re-validate everyone is still online and free
        for (Player p : members) {
            if (!p.isOnline()) {
                members.forEach(m -> m.sendMessage(
                        "§c" + p.getName() + " went offline. Party queue cancelled."));
                party.setState(Party.State.FORMING);
                return;
            }
            if (plugin.getInstanceManager().isInInstance(p.getUniqueId())) {
                members.forEach(m -> m.sendMessage(
                        "§c" + p.getName() + " is already in a dungeon. Party queue cancelled."));
                party.setState(Party.State.FORMING);
                return;
            }
        }

        // Snapshot the list so it can't change under us
        List<Player> snapshot = new ArrayList<>(members);

        // Show countdown to all party members
        snapshot.forEach(p -> p.sendTitle("§6Entering Rift", "§eparty", 5, 60, 10));

        // Schedule spawn on next tick — world creation blocks briefly so we
        // do it on the main thread then teleport everyone in the same tick
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            party.setState(Party.State.IN_GAME);
            plugin.getInstanceManager().spawnInstance(snapshot);
        });
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public boolean isQueued(UUID uuid) { return soloQueue.contains(uuid); }
    public int getSoloQueueSize()      { return soloQueue.size(); }
}
