package com.dungeonrift.model;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * DungeonInstance — one live dungeon run.
 *
 * Changes:
 *  - Boss bar timer visible to all players in the instance
 *  - 10-minute cooldown before extraction is allowed
 *  - Extraction = keep loot, death = lose everything
 *  - /rift leave requires confirmation (handled via RiftCommand)
 */
public class DungeonInstance {

    public enum State { LOADING, ACTIVE, CLOSING, CLOSED }

    private State state = State.LOADING;

    private final String id;
    private final World  world;
    private final String templateName;

    // ── Players ───────────────────────────────────────────────────────────────

    private final Set<UUID>         alivePlayers       = new HashSet<>();
    private final Map<UUID, Integer> extractionProgress = new HashMap<>();

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;
    private int        secondsElapsed = 0;

    /** Seconds a player must be in the instance before extraction unlocks. */
    private static final int EXTRACTION_COOLDOWN_SECONDS = 600; // 10 minutes

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private BossBar bossBar;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DungeonInstance(String id, World world, String templateName, List<Player> players) {
        this.id           = id;
        this.world        = world;
        this.templateName = templateName;
        players.forEach(p -> alivePlayers.add(p.getUniqueId()));

        secondsRemaining = DungeonRift.get().getConfig()
                .getInt("instance.time-limit-minutes", 30) * 60;

        // Create boss bar — shown to all players in this instance
        bossBar = Bukkit.createBossBar(
                buildBarTitle(),
                BarColor.GREEN,
                BarStyle.SOLID
        );
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    public void startTimer() {
        state = State.ACTIVE;
        DungeonRift plugin = DungeonRift.get();
        List<Integer> warnMinutes = plugin.getConfig()
                .getIntegerList("instance.warnings-at-minutes");

        // Add all players to boss bar
        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {

            secondsRemaining--;
            secondsElapsed++;

            // Update boss bar every second
            updateBossBar();

            // Broadcast minute warnings
            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast("§c⚠ " + minutesLeft + " minute(s) remaining!");
            }

            // Show extraction unlock message at exactly 10 minutes elapsed
            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active! You may leave with your loot.");
            }

            // Advance extraction countdowns
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

        // Colour shifts as time runs out
        if (secondsRemaining <= 120) {
            bossBar.setColor(BarColor.RED);
        } else if (secondsRemaining <= 300) {
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setColor(BarColor.GREEN);
        }
    }

    private String buildBarTitle() {
        int mins = secondsRemaining / 60;
        int secs = secondsRemaining % 60;

        // Show extraction lock status
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
                player.sendTitle(
                        "§6Extracting...",
                        "§e" + remaining + "s  §7|  §aLoot: KEPT",
                        0, 25, 5
                );
            }

            if (secondsHeld >= holdSeconds) {
                extractPlayer(player);
            }
        });
    }

    public void playerEnterExtractionZone(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;
        if (extractionProgress.containsKey(player.getUniqueId())) return;

        // Check 10-minute cooldown
        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int cooldownLeft = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
            int mins = cooldownLeft / 60;
            int secs = cooldownLeft % 60;
            player.sendMessage("§c[DungeonRift] §7The extraction portal is not yet active.");
            player.sendMessage(String.format("§7You must stay in the rift for §c%02d:%02d §7before you can extract.", mins, secs));
            return;
        }

        extractionProgress.put(player.getUniqueId(), 0);

        int holdSecs = DungeonRift.get().getConfig().getInt("instance.extraction-hold-seconds", 10);
        player.sendMessage("§8[§6DungeonRift§8] §eStep into the light... §7(" + holdSecs + "s) — §aYour loot will be kept!");
    }

    public void playerLeaveExtractionZone(Player player) {
        if (!extractionProgress.containsKey(player.getUniqueId())) return;
        extractionProgress.remove(player.getUniqueId());
        player.resetTitle();
        player.sendMessage("§c[DungeonRift] Extraction interrupted!");
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    /** Successful extraction — player keeps their loot. */
    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);

        player.resetTitle();
        // Loot is NOT cleared — player keeps everything
        player.sendMessage("§8[§6DungeonRift§8] §a§lEXTRACTED! §r§aYour loot has been kept.");
        player.sendTitle("§a§lEXTRACTED!", "§7Your loot is safe.", 10, 60, 20);

        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    /**
     * Player died — drop everything.
     * "drop_in_world" is handled by vanilla; we also clear on respawn
     * so nothing is kept if the penalty is "clear".
     */
    public void onPlayerDeath(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);

        // Always clear inventory on death — lose everything
        // (actual drop in world happens via vanilla before this is called)
        // PlayerDeathListener clears the drops based on config

        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        checkIfEmpty();
    }

    /**
     * Player used /rift leave (confirmed). They get nothing.
     */
    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);

        // Clear inventory — forfeiting means losing loot
        player.getInventory().clear();
        player.sendMessage("§8[§6DungeonRift§8] §7You abandoned the rift. All loot has been lost.");

        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    private void checkIfEmpty() {
        if (alivePlayers.isEmpty()) close("All players eliminated or extracted.");
    }

    // ── Timer expiry ──────────────────────────────────────────────────────────

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

        // Remove boss bar from everyone
        bossBar.removeAll();

        // Return any remaining alive players (edge case)
        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                DungeonRift.get().getInstanceManager().returnPlayerToHub(p);
            }
        });
        alivePlayers.clear();
        extractionProgress.clear();

        state = State.CLOSED;
        DungeonRift.get().getInstanceManager().destroyInstance(this);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcast(String message) {
        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6DungeonRift§8] §r" + message);
        });
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String   getId()           { return id;            }
    public World    getWorld()        { return world;         }
    public String   getTemplateName() { return templateName;  }
    public State    getState()        { return state;         }
    public int      getSecondsLeft()  { return secondsRemaining; }
    public boolean  isAlive(UUID u)   { return alivePlayers.contains(u); }
    public boolean  inExtractionZone(UUID u) { return extractionProgress.containsKey(u); }
    public Set<UUID> getAlivePlayers() { return Collections.unmodifiableSet(alivePlayers); }
    public boolean  isExtractionUnlocked() { return secondsElapsed >= EXTRACTION_COOLDOWN_SECONDS; }
    public void     addPlayerToBar(Player p) { bossBar.addPlayer(p); }
}
