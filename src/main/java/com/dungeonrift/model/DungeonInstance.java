package com.dungeonrift.model;

import com.dungeonrift.DungeonRift;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class DungeonInstance {

    public enum State { LOADING, ACTIVE, CLOSING, CLOSED }

    private State state = State.LOADING;

    private final String id;
    private final World  world;
    private final String templateName;

    // ── Players ───────────────────────────────────────────────────────────────

    private final Set<UUID>          alivePlayers       = new HashSet<>();
    /** Permanent record of everyone who entered — never cleared, used for party reset. */
    private final Set<UUID>          allInstancePlayers = new HashSet<>();
    private final Map<UUID, Integer> extractionProgress = new HashMap<>();

    /**
     * Tracks the last second at which we sent a "portal not yet active" message
     * per player, so we only send it every 5 seconds instead of every second.
     */
    private final Map<UUID, Integer> lastCooldownMessage = new HashMap<>();

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;
    private int        secondsElapsed = 0;

    private static final int EXTRACTION_COOLDOWN_SECONDS = 600; // 10 minutes

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private BossBar bossBar;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DungeonInstance(String id, World world, String templateName, List<Player> players) {
        this.id           = id;
        this.world        = world;
        this.templateName = templateName;
        players.forEach(p -> {
            alivePlayers.add(p.getUniqueId());
            allInstancePlayers.add(p.getUniqueId());
        });

        secondsRemaining = DungeonRift.get().getConfig()
                .getInt("instance.time-limit-minutes", 30) * 60;

        bossBar = Bukkit.createBossBar(buildBarTitle(), BarColor.GREEN, BarStyle.SOLID);
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    public void startTimer() {
        state = State.ACTIVE;
        DungeonRift plugin = DungeonRift.get();
        List<Integer> warnMinutes = plugin.getConfig()
                .getIntegerList("instance.warnings-at-minutes");

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {

            secondsRemaining--;
            secondsElapsed++;

            updateBossBar();

            // Minute warnings
            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast("§c⚠ " + minutesLeft + " minute(s) remaining!");
            }

            // Extraction unlock broadcast
            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active! You may leave with your loot.");
            }

            tickExtractionProgress();

            if (secondsRemaining <= 0) expire();

        }, 20L, 20L);
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private void updateBossBar() {
        int totalSeconds = DungeonRift.get().getConfig()
                .getInt("instance.time-limit-minutes", 30) * 60;

        double progress = Math.max(0, (double) secondsRemaining / totalSeconds);
        bossBar.setProgress(progress);
        bossBar.setTitle(buildBarTitle());

        if (secondsRemaining <= 120)      bossBar.setColor(BarColor.RED);
        else if (secondsRemaining <= 300) bossBar.setColor(BarColor.YELLOW);
        else                              bossBar.setColor(BarColor.GREEN);
    }

    private String buildBarTitle() {
        int mins = secondsRemaining / 60;
        int secs = secondsRemaining % 60;

        String extractStatus;
        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int cooldownLeft = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
            int clMins = cooldownLeft / 60;
            int clSecs = cooldownLeft % 60;
            extractStatus = String.format("§c⚑ Extract unlocks in %02d:%02d", clMins, clSecs);
        } else {
            extractStatus = "§a⚑ Extraction OPEN";
        }

        return String.format("§6⏱ %02d:%02d  §7|  %s", mins, secs, extractStatus);
    }

    // ── Extraction zone ───────────────────────────────────────────────────────

    private void tickExtractionProgress() {
        int holdSeconds = DungeonRift.get().getConfig()
                .getInt("instance.extraction-hold-seconds", 10);

        new HashSet<>(extractionProgress.keySet()).forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                extractionProgress.remove(uuid);
                return;
            }

            int secondsHeld = extractionProgress.merge(uuid, 1, Integer::sum);
            int remaining   = holdSeconds - secondsHeld;

            if (remaining >= 0) {
                // Show countdown title — no loot text
                player.sendTitle(
                        "§6Extracting...",
                        "§e" + remaining + "s",
                        0, 25, 5
                );
                // Bass note block sound on each tick
                player.playSound(player.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            }

            if (secondsHeld >= holdSeconds) {
                extractPlayer(player);
            }
        });
    }

    public void playerEnterExtractionZone(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;

        // Cooldown still active — throttle message to once every 5 seconds
        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            // If already tracking progress from before cooldown ended, clear it
            extractionProgress.remove(player.getUniqueId());

            int lastMsg = lastCooldownMessage.getOrDefault(player.getUniqueId(), -10);
            if (secondsElapsed - lastMsg >= 5) {
                int cooldownLeft = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
                int mins = cooldownLeft / 60;
                int secs = cooldownLeft % 60;
                player.sendMessage("§c[DungeonRift] §7The extraction portal is not yet active.");
                player.sendMessage(String.format(
                        "§7You must stay in the rift for §c%02d:%02d §7longer.", mins, secs));
                lastCooldownMessage.put(player.getUniqueId(), secondsElapsed);
            }
            return;
        }

        // Cooldown open — always (re)start countdown from 0 when entering zone
        extractionProgress.put(player.getUniqueId(), 0);
    }

    public void playerLeaveExtractionZone(Player player) {
        if (!extractionProgress.containsKey(player.getUniqueId())) return;
        extractionProgress.remove(player.getUniqueId());
        player.resetTitle();
        player.sendMessage("§c[DungeonRift] Extraction interrupted!");
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);

        player.resetTitle();
        player.sendMessage("§8[§6DungeonRift§8] §a§lEXTRACTED! §r§aYour loot has been kept.");
        player.sendTitle("§a§lEXTRACTED!", "", 10, 60, 20);

        DungeonRift.get().getInstanceManager().returnPlayerToHub(player, true);
        checkIfEmpty();
    }

    public void onPlayerDeath(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        checkIfEmpty();
    }

    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);

        if (player.isOnline()) {
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
        }

        player.getInventory().clear();
        player.sendMessage("§8[§6DungeonRift§8] §7You abandoned the rift. All loot has been lost.");

        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    private void checkIfEmpty() {
        if (alivePlayers.isEmpty()) close("All players eliminated or extracted.");
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    private void expire() {
        broadcast("§4§lThe rift collapses! Get out!");

        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) p.setHealth(0);
        });

        close("Timer expired.");
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    public void close(String reason) {
        if (state == State.CLOSING || state == State.CLOSED) return;
        state = State.CLOSING;

        DungeonRift.get().getLogger().info("Closing instance " + id + " — " + reason);

        if (countdownTask != null) countdownTask.cancel();

        bossBar.removeAll();

        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                DungeonRift.get().getInstanceManager().returnPlayerToHub(p);
            }
        });
        alivePlayers.clear();
        extractionProgress.clear();
        lastCooldownMessage.clear();

        state = State.CLOSED;
        DungeonRift.get().getInstanceManager().destroyInstance(this);
        // Use allInstancePlayers — never emptied, so always has everyone
        notifyPartyRiftComplete(allInstancePlayers);
    }

    /** Called by DungeonInstance when all players have left/died/extracted. */
    private void notifyPartyRiftComplete(java.util.Set<UUID> allPlayers) {
        com.dungeonrift.manager.PartyManager pm = DungeonRift.get().getPartyManager();
        // Find a party from any of the players who were in this instance
        for (UUID uuid : allPlayers) {
            com.dungeonrift.model.Party party = pm.getPartyByMember(uuid);
            if (party != null) {
                pm.onRiftComplete(party);
                return;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcast(String message) {
        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6DungeonRift§8] §r" + message);
        });
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String    getId()            { return id;            }
    public World     getWorld()         { return world;         }
    public String    getTemplateName()  { return templateName;  }
    public State     getState()         { return state;         }
    public int       getSecondsLeft()   { return secondsRemaining; }
    public boolean   isAlive(UUID u)    { return alivePlayers.contains(u); }
    public boolean   inExtractionZone(UUID u) { return extractionProgress.containsKey(u); }
    public Set<UUID> getAlivePlayers()  { return Collections.unmodifiableSet(alivePlayers); }
    public boolean   isExtractionUnlocked() { return secondsElapsed >= EXTRACTION_COOLDOWN_SECONDS; }
    public void      addPlayerToBar(Player p) { bossBar.addPlayer(p); }
}
