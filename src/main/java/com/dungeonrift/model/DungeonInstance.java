package com.dungeonrift.model;

import com.dungeonrift.DungeonRift;
import org.bukkit.*;
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

    private final Set<UUID>          alivePlayers        = new HashSet<>();
    private final Set<UUID>          allInstancePlayers  = new HashSet<>();
    private final Map<UUID, Integer> extractionProgress  = new HashMap<>();
    private final Map<UUID, Integer> lastCooldownMessage = new HashMap<>();

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;
    private int        secondsElapsed    = 0;
    private boolean    timerPaused       = false;
    /** UUID of the player currently holding the emergency pause */
    private UUID       pausedForPlayer   = null;
    /** Seconds left on the 5-second grace period after leaving zone while paused */
    private int        graceSecondsLeft  = 0;

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

        int collapseAtMinutes = plugin.getConfig()
                .getInt("instance.collapse.start-at-minutes-remaining", 1);

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {

            // ── Emergency pause handling ───────────────────────────────────
            if (timerPaused) {
                // Check if the paused player is still in the zone
                if (pausedForPlayer != null) {
                    Player pp = Bukkit.getPlayer(pausedForPlayer);
                    if (pp != null && extractionProgress.containsKey(pausedForPlayer)) {
                        // Still in zone — keep paused, update bar
                        updateBossBar();
                        return;
                    } else {
                        // Left the zone — start grace period
                        if (graceSecondsLeft <= 0) {
                            graceSecondsLeft = 5;
                            Player pp2 = pausedForPlayer != null ? Bukkit.getPlayer(pausedForPlayer) : null;
                            if (pp2 != null) {
                                pp2.sendMessage("§c[DungeonRift] §eYou left the extraction zone!");
                                pp2.sendMessage("§7Return within §c5 seconds §7or the timer resumes!");
                                pp2.sendTitle("§cReturn to portal!", "§e5 seconds!", 0, 25, 5);
                            }
                        }
                        graceSecondsLeft--;
                        if (graceSecondsLeft <= 0) {
                            // Grace expired — resume timer
                            timerPaused     = false;
                            pausedForPlayer = null;
                            broadcast("§c⚠ Emergency pause expired. Timer resumed!");
                        } else {
                            // Show grace countdown
                            Player pp2 = pausedForPlayer != null ? Bukkit.getPlayer(pausedForPlayer) : null;
                            if (pp2 != null) pp2.sendTitle("§cReturn to portal!", "§e" + graceSecondsLeft + "s", 0, 25, 5);
                            return;
                        }
                    }
                }
            }

            secondsRemaining--;
            secondsElapsed++;

            updateBossBar();

            // Minute warnings in chat
            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast("§c⚠ " + minutesLeft + " minute(s) remaining!");
            }

            // Extraction unlock
            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active!");
            }

            // ── Collapse sequence ──────────────────────────────────────────
            if (secondsRemaining <= collapseAtMinutes * 60) {
                tickCollapse();
            }

            // ── Emergency extraction safety net ────────────────────────────
            boolean safetyEnabled = plugin.getConfig()
                    .getBoolean("extraction-safety.enabled", true);
            int safetyThreshold   = plugin.getConfig()
                    .getInt("extraction-safety.pause-at-seconds-remaining", 10);

            if (safetyEnabled && !timerPaused && secondsRemaining <= safetyThreshold) {
                // Check if any alive player is in the extraction zone
                for (UUID uuid : alivePlayers) {
                    if (extractionProgress.containsKey(uuid)) {
                        timerPaused     = true;
                        pausedForPlayer = uuid;
                        graceSecondsLeft = 0;
                        Player pp = Bukkit.getPlayer(uuid);
                        if (pp != null) {
                            pp.sendMessage("§a[DungeonRift] §eTimer paused — finish extracting!");
                        }
                        updateBossBar();
                        return;
                    }
                }
            }

            tickExtractionProgress();

            if (secondsRemaining <= 0) expire();

        }, 20L, 20L);
    }

    // ── Collapse sequence ─────────────────────────────────────────────────────

    private int collapseTickCounter = 0;

    private void tickCollapse() {
        collapseTickCounter++;
        DungeonRift plugin = DungeonRift.get();
        boolean soundsEnabled   = plugin.getConfig().getBoolean("instance.collapse.sounds-enabled", true);
        boolean weatherEnabled  = plugin.getConfig().getBoolean("instance.collapse.weather-enabled", true);
        boolean lightningEnabled = plugin.getConfig().getBoolean("instance.collapse.lightning-enabled", true);

        // Start storm weather
        if (weatherEnabled) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(600);
        }

        // Every 3 seconds: crumbling / breaking sounds
        if (soundsEnabled && collapseTickCounter % 3 == 0) {
            // Cycle through collapse sound effects
            Sound[] collapseSounds = {
                Sound.BLOCK_STONE_BREAK,
                Sound.BLOCK_GRAVEL_BREAK,
                Sound.ENTITY_GENERIC_EXPLODE,
                Sound.BLOCK_ANCIENT_DEBRIS_BREAK,
                Sound.ENTITY_LIGHTNING_BOLT_THUNDER
            };
            Sound sound = collapseSounds[(collapseTickCounter / 3) % collapseSounds.length];

            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.playSound(p.getLocation(), sound, 0.8f, 0.6f);
            });
        }

        // Lightning strikes near players every 5 seconds
        if (lightningEnabled && collapseTickCounter % 5 == 0) {
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                // Strike within 15 blocks of player — close but not on them
                Random rng = new Random();
                double ox = (rng.nextDouble() - 0.5) * 30;
                double oz = (rng.nextDouble() - 0.5) * 30;
                Location strikeLoc = p.getLocation().add(ox, 0, oz);
                // Use strikeLightningEffect (no damage, visual only)
                p.getWorld().strikeLightningEffect(strikeLoc);
            });
        }

        // Darkness effect on players every 10 seconds
        if (collapseTickCounter % 10 == 0) {
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.DARKNESS, 60, 0, false, false));
                }
            });
        }

        // Broadcast escalating warning messages
        if (secondsRemaining == 60) broadcast("§4§l⚠ THE RIFT IS COLLAPSING! ⚠");
        if (secondsRemaining == 30) broadcast("§4§l☠ 30 SECONDS — GET TO THE PORTAL! ☠");
        if (secondsRemaining == 10) broadcast("§4§l☠ 10 SECONDS! ☠");
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private void updateBossBar() {
        int totalSeconds = DungeonRift.get().getConfig()
                .getInt("instance.time-limit-minutes", 30) * 60;

        double progress = Math.max(0, (double) secondsRemaining / totalSeconds);
        bossBar.setProgress(progress);

        String title = buildBarTitle();
        if (timerPaused) {
            bossBar.setTitle("§e§lTIMER PAUSED — EXTRACTING...  §7| §e" + buildTimeString());
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setTitle(title);
            if (secondsRemaining <= 120)      bossBar.setColor(BarColor.RED);
            else if (secondsRemaining <= 300) bossBar.setColor(BarColor.YELLOW);
            else                              bossBar.setColor(BarColor.GREEN);
        }
    }

    private String buildBarTitle() {
        String extractStatus;
        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int cooldownLeft = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
            int clMins = cooldownLeft / 60;
            int clSecs = cooldownLeft % 60;
            extractStatus = String.format("§c⚑ Extract unlocks in %02d:%02d", clMins, clSecs);
        } else {
            extractStatus = "§a⚑ Extraction OPEN";
        }
        return String.format("§6⏱ %s  §7|  %s", buildTimeString(), extractStatus);
    }

    private String buildTimeString() {
        int mins = secondsRemaining / 60;
        int secs = secondsRemaining % 60;
        return String.format("%02d:%02d", mins, secs);
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
                player.sendTitle("§6Extracting...", "§e" + remaining + "s", 0, 25, 5);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            }

            if (secondsHeld >= holdSeconds) extractPlayer(player);
        });
    }

    public void playerEnterExtractionZone(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;

        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int lastMsg = lastCooldownMessage.getOrDefault(player.getUniqueId(), -10);
            if (secondsElapsed - lastMsg >= 5) {
                int cooldownLeft = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
                int mins = cooldownLeft / 60;
                int secs = cooldownLeft % 60;
                player.sendMessage("§c[DungeonRift] §7The extraction portal is not yet active.");
                player.sendMessage(String.format("§7Stay in the rift for §c%02d:%02d §7longer.", mins, secs));
                lastCooldownMessage.put(player.getUniqueId(), secondsElapsed);
            }
            return;
        }

        // If this player was the paused player returning — clear grace
        if (player.getUniqueId().equals(pausedForPlayer)) {
            graceSecondsLeft = 0;
            player.resetTitle();
        }

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
        if (timerPaused && player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false;
            pausedForPlayer = null;
        }

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
        if (timerPaused && player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false;
            pausedForPlayer = null;
        }
        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (timerPaused && player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false;
            pausedForPlayer = null;
        }

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
        broadcast("§4§lThe rift collapses! Everyone is killed!");
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

        // Reset weather
        world.setStorm(false);
        world.setThundering(false);

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
        notifyPartyRiftComplete(allInstancePlayers);
    }

    private void notifyPartyRiftComplete(Set<UUID> players) {
        com.dungeonrift.manager.PartyManager pm = DungeonRift.get().getPartyManager();
        for (UUID uuid : players) {
            com.dungeonrift.model.Party party = pm.getPartyByMember(uuid);
            if (party != null) {
                pm.onRiftComplete(party);
                return;
            }
        }
    }

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
