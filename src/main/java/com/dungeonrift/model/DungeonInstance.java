package com.dungeonrift.model;

import com.dungeonrift.DungeonRift;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
    private final Map<UUID, Integer> extractionEntries   = new HashMap<>();
    private static final int         MAX_EXTRACTION_ENTRIES = 3;

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;
    private int        secondsElapsed = 0;

    // ── Safety pause ──────────────────────────────────────────────────────────

    private boolean timerPaused     = false;
    private UUID    pausedForPlayer = null;

    /**
     * Counts down during the grace period (player left zone while paused).
     * -1 = grace not active. 0 = grace just expired.
     */
    private int graceCountdown = -1;

    /** Total times grace has been spent (counts a full grace expiry as one use). */
    private int pauseExitCount  = 0;
    private static final int MAX_PAUSE_EXITS = 3;

    // ── Collapse ──────────────────────────────────────────────────────────────

    private static final int EXTRACTION_COOLDOWN_SECONDS = 600;

    /** Seconds remaining when collapse sequence starts — set at construction. */
    private final int collapseStartSeconds;
    private int       collapseTickCounter = 0;
    private boolean   pigStepPlaying      = false;

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

        // Read collapse start once — used both as trigger and for intensity calc
        int collapseMinutes = DungeonRift.get().getConfig()
                .getInt("instance.collapse.start-at-minutes-remaining", 2);
        collapseStartSeconds = collapseMinutes * 60 + 30; // e.g. 2m → 2:30

        bossBar = Bukkit.createBossBar(buildBarTitle(), BarColor.GREEN, BarStyle.SOLID);
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    public void startTimer() {
        state = State.ACTIVE;
        DungeonRift plugin = DungeonRift.get();

        List<Integer> warnMinutes   = plugin.getConfig().getIntegerList("instance.warnings-at-minutes");
        boolean safetyEnabled       = plugin.getConfig().getBoolean("extraction-safety.enabled", true);
        int     safetyThreshold     = plugin.getConfig().getInt("extraction-safety.pause-at-seconds-remaining", 10);

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            // ── Collapse always runs — even when timer is paused ───────────
            // This ensures the collapse sequence never freezes during extraction.
            if (secondsRemaining <= collapseStartSeconds) {
                tickCollapse();
            }

            // ── Safety pause state machine ─────────────────────────────────
            if (timerPaused) {
                Player pp      = pausedForPlayer != null ? Bukkit.getPlayer(pausedForPlayer) : null;
                boolean inZone = pp != null && extractionProgress.containsKey(pausedForPlayer);

                if (inZone) {
                    // In zone — keep timer paused, progress extraction
                    updateBossBar();
                    tickExtractionProgress();
                    return;
                }

                // ── Grace period ───────────────────────────────────────────
                if (graceCountdown < 0) {
                    // First tick after leaving — initialise grace
                    graceCountdown = 5;
                    if (pp != null) {
                        pp.sendMessage("§c[DungeonRift] You left the extraction zone!");
                        pp.sendMessage("§7Return within §c5 seconds §7or the timer resumes!");
                    }
                }

                if (graceCountdown > 0) {
                    if (pp != null) pp.sendTitle("§cReturn to portal!", "§e" + graceCountdown + "s", 0, 25, 5);
                    graceCountdown--;
                    updateBossBar();
                    return; // still paused
                }

                // Grace elapsed — release pause
                timerPaused     = false;
                pausedForPlayer = null;
                graceCountdown  = -1;
                pauseExitCount++;
                if (pauseExitCount >= MAX_PAUSE_EXITS) {
                    broadcast("§c⚠ Emergency pauses exhausted. Timer will no longer pause!");
                } else {
                    broadcast("§c⚠ Emergency pause expired. Timer resumed! §7(" + (MAX_PAUSE_EXITS - pauseExitCount) + " remaining)");
                }
            }

            // ── Normal tick ────────────────────────────────────────────────
            secondsRemaining--;
            secondsElapsed++;
            updateBossBar();

            // Minute warnings + XP sound
            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast("§c⚠ " + minutesLeft + " minute(s) remaining!");
                alivePlayers.forEach(uuid -> {
                    Player pp = Bukkit.getPlayer(uuid);
                    if (pp != null) pp.playSound(pp.getLocation(),
                            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                });
            }

            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active!");
            }

            // ── Safety net ─────────────────────────────────────────────────
            if (safetyEnabled && pauseExitCount < MAX_PAUSE_EXITS
                    && secondsRemaining > 0 && secondsRemaining <= safetyThreshold) {
                for (UUID uuid : alivePlayers) {
                    if (extractionProgress.containsKey(uuid)) {
                        timerPaused     = true;
                        pausedForPlayer = uuid;
                        graceCountdown  = -1;
                        Player pp = Bukkit.getPlayer(uuid);
                        if (pp != null) pp.sendMessage("§a[DungeonRift] §eTimer paused! §7("
                                + (MAX_PAUSE_EXITS - pauseExitCount) + " pause(s) remaining)");
                        updateBossBar();
                        tickExtractionProgress();
                        return;
                    }
                }
            }

            tickExtractionProgress();
            if (secondsRemaining <= 0) expire();

        }, 20L, 20L);
    }

    // ── Collapse ──────────────────────────────────────────────────────────────

    private void tickCollapse() {
        collapseTickCounter++;
        DungeonRift plugin    = DungeonRift.get();
        boolean soundsEnabled    = plugin.getConfig().getBoolean("instance.collapse.sounds-enabled",    true);
        boolean weatherEnabled   = plugin.getConfig().getBoolean("instance.collapse.weather-enabled",   true);
        boolean lightningEnabled = plugin.getConfig().getBoolean("instance.collapse.lightning-enabled", true);

        // intensity: 0.0 at collapse start → 1.0 at 0 seconds
        double intensity = collapseStartSeconds > 0
                ? Math.min(1.0, 1.0 - ((double) secondsRemaining / collapseStartSeconds))
                : 1.0;

        // ── Pig Step — play to each player client-side once ────────────────
        if (!pigStepPlaying && soundsEnabled) {
            pigStepPlaying = true;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    // Stop any existing music first, then play at the player's
                    // own ears (category RECORDS so it sounds like a disc, not ambient)
                    p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
                    p.playSound(p.getLocation(), Sound.MUSIC_DISC_PIGSTEP,
                            SoundCategory.RECORDS, 1.0f, 1.0f);
                }
            });
        }

        // ── Storm ─────────────────────────────────────────────────────────
        if (weatherEnabled) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(600);
        }

        // ── Crumbling sounds ─────────────────────────────────────────────
        int soundInterval = Math.max(1, (int) (5 - intensity * 4));
        if (soundsEnabled && collapseTickCounter % soundInterval == 0) {
            Sound[] sounds = {
                Sound.BLOCK_STONE_BREAK,          Sound.BLOCK_GRAVEL_BREAK,
                Sound.BLOCK_ANCIENT_DEBRIS_BREAK,  Sound.BLOCK_DEEPSLATE_BREAK,
                Sound.ENTITY_GENERIC_EXPLODE
            };
            Sound s   = sounds[collapseTickCounter % sounds.length];
            float vol = (float) (0.5 + intensity * 0.5);
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.playSound(p.getLocation(), s, vol, 0.7f);
            });
        }

        // ── Explosions ───────────────────────────────────────────────────
        int explosionInterval = Math.max(2, (int) (20 - intensity * 18));
        if (soundsEnabled && collapseTickCounter % explosionInterval == 0) {
            Random rng = new Random();
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                double ox = (rng.nextDouble() - 0.5) * 40;
                double oz = (rng.nextDouble() - 0.5) * 40;
                world.createExplosion(p.getLocation().add(ox, 0, oz), 0f, false, false);
            });
        }

        // ── Lightning — creates small holes + magma scatter ───────────────
        int lightningInterval = Math.max(2, (int) (8 - intensity * 6));
        if (lightningEnabled && collapseTickCounter % lightningInterval == 0) {
            Random rng    = new Random();
            int    strikes = 1 + (int) (intensity * 2);
            for (int i = 0; i < strikes; i++) {
                alivePlayers.forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) return;
                    double ox = (rng.nextDouble() - 0.5) * 50;
                    double oz = (rng.nextDouble() - 0.5) * 50;
                    Location strikeLoc = p.getLocation().add(ox, 0, oz);
                    world.strikeLightning(strikeLoc);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> crackGround(strikeLoc), 2L);
                });
            }
        }

        // ── Darkness ─────────────────────────────────────────────────────
        int darkInterval = Math.max(3, (int) (15 - intensity * 12));
        if (collapseTickCounter % darkInterval == 0) {
            int amp = intensity > 0.7 ? 1 : 0;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.DARKNESS, 80, amp, false, false));
            });
        }

        // ── Milestone broadcasts ──────────────────────────────────────────
        if (secondsRemaining == 60) broadcast("§4§l⚠ THE RIFT IS COLLAPSING! ⚠");
        if (secondsRemaining == 30) broadcast("§4§l☠ 30 SECONDS — GET OUT OF THE RIFT! ☠");
        if (secondsRemaining == 10) broadcast("§4§l☠ 10 SECONDS! ☠");
    }

    /**
     * Creates a small crater + magma scatter around a lightning impact point.
     * Removes 1–3 surface blocks in a tiny radius to make a hole,
     * then scatters magma in a wider ring.
     */
    private void crackGround(Location centre) {
        if (centre.getWorld() == null) return;
        Random rng = new Random();

        // Inner hole — remove blocks (air) in radius 0–1
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (rng.nextDouble() > 0.6) continue;
                Location check = centre.clone().add(dx, 0, dz);
                Location surface = check.getWorld()
                        .getHighestBlockAt(check).getLocation();
                Material m = surface.getBlock().getType();
                if (m.isSolid() && m != Material.BEDROCK
                        && !m.name().contains("LOG")
                        && !m.name().contains("CHEST")) {
                    surface.getBlock().setType(Material.AIR, false);
                }
            }
        }

        // Outer magma scatter — radius 2–4
        int magmaRadius = 2 + rng.nextInt(3);
        for (int dx = -magmaRadius; dx <= magmaRadius; dx++) {
            for (int dz = -magmaRadius; dz <= magmaRadius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 1.5 || dist > magmaRadius) continue; // ring only
                if (rng.nextDouble() > 0.35) continue;           // sparse

                Location check   = centre.clone().add(dx, 0, dz);
                Location surface = check.getWorld()
                        .getHighestBlockAt(check).getLocation();
                Material m = surface.getBlock().getType();
                if (m.isSolid() && m != Material.BEDROCK
                        && !m.name().contains("LOG")
                        && !m.name().contains("CHEST")) {
                    // Mostly magma, occasionally cracked stone for variety
                    Material place = rng.nextDouble() < 0.7
                            ? Material.MAGMA_BLOCK
                            : Material.CRACKED_STONE_BRICKS;
                    surface.getBlock().setType(place, false);
                }
            }
        }
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private void updateBossBar() {
        int total   = DungeonRift.get().getConfig().getInt("instance.time-limit-minutes", 30) * 60;
        double prog = Math.max(0, (double) secondsRemaining / total);
        bossBar.setProgress(prog);

        if (timerPaused) {
            String graceStr = graceCountdown > 0 ? " — return in §c" + graceCountdown + "s" : " — extracting...";
            bossBar.setTitle("§e§lTIMER PAUSED" + graceStr);
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setTitle(buildBarTitle());
            bossBar.setColor(secondsRemaining <= 120 ? BarColor.RED
                           : secondsRemaining <= 300 ? BarColor.YELLOW : BarColor.GREEN);
        }
    }

    private String buildBarTitle() {
        String extractStatus = secondsElapsed < EXTRACTION_COOLDOWN_SECONDS
                ? String.format("§c⚑ Extract unlocks in %02d:%02d",
                    (EXTRACTION_COOLDOWN_SECONDS - secondsElapsed) / 60,
                    (EXTRACTION_COOLDOWN_SECONDS - secondsElapsed) % 60)
                : "§a⚑ Extraction OPEN";
        return String.format("§6⏱ %02d:%02d  §7|  %s",
                secondsRemaining / 60, secondsRemaining % 60, extractStatus);
    }

    // ── Extraction zone ───────────────────────────────────────────────────────

    private void tickExtractionProgress() {
        int holdSeconds = DungeonRift.get().getConfig()
                .getInt("instance.extraction-hold-seconds", 10);

        new HashSet<>(extractionProgress.keySet()).forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) { extractionProgress.remove(uuid); return; }

            int held      = extractionProgress.merge(uuid, 1, Integer::sum);
            int remaining = holdSeconds - held;

            if (remaining >= 0) {
                player.sendTitle("§6Extracting...", "§e" + remaining + "s", 0, 25, 5);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            }
            if (held >= holdSeconds) extractPlayer(player);
        });
    }

    public void playerEnterExtractionZone(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;

        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int lastMsg = lastCooldownMessage.getOrDefault(player.getUniqueId(), -10);
            if (secondsElapsed - lastMsg >= 5) {
                int left = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
                player.sendMessage(String.format(
                        "§c[DungeonRift] §7Portal not active — §c%02d:%02d §7remaining.",
                        left / 60, left % 60));
                lastCooldownMessage.put(player.getUniqueId(), secondsElapsed);
            }
            return;
        }

        int entries = extractionEntries.getOrDefault(player.getUniqueId(), 0);
        if (entries >= MAX_EXTRACTION_ENTRIES) {
            if (!extractionProgress.containsKey(player.getUniqueId())) {
                player.sendMessage("§c[DungeonRift] Zone entries exhausted — no further pauses.");
                extractionProgress.put(player.getUniqueId(), 0);
            }
            return;
        }

        extractionEntries.put(player.getUniqueId(), entries + 1);
        extractionProgress.put(player.getUniqueId(), 0);

        // If returning during grace — cancel grace (they're back, pause resumes)
        // but do NOT reset graceCountdown to 5; grace is spent when it started.
        if (player.getUniqueId().equals(pausedForPlayer) && graceCountdown >= 0) {
            graceCountdown = -1;
            player.resetTitle();
            player.sendMessage("§a[DungeonRift] Back in zone — timer still paused.");
        }
    }

    public void playerLeaveExtractionZone(Player player) {
        if (!extractionProgress.containsKey(player.getUniqueId())) return;
        extractionProgress.remove(player.getUniqueId());
        player.resetTitle();
        player.sendMessage("§c[DungeonRift] Extraction interrupted!");
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    private void stopPigStep(Player player) {
        player.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
    }

    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false; pausedForPlayer = null; graceCountdown = -1;
        }
        stopPigStep(player);
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
        if (player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false; pausedForPlayer = null; graceCountdown = -1;
        }
        stopPigStep(player);
        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused = false; pausedForPlayer = null; graceCountdown = -1;
        }
        stopPigStep(player);
        if (player.isOnline()) player.playSound(player.getLocation(),
                Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
        player.getInventory().clear();
        player.sendMessage("§8[§6DungeonRift§8] §7You abandoned the rift. All loot has been lost.");
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    private void checkIfEmpty() {
        if (alivePlayers.isEmpty()) close("All players eliminated or extracted.");
    }

    private void expire() {
        broadcast("§4§lThe rift collapses!");
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
        world.setStorm(false);
        world.setThundering(false);

        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                stopPigStep(p);
                p.getInventory().clear();
                DungeonRift.get().getInstanceManager().returnPlayerToHub(p);
            }
        });

        alivePlayers.clear();
        extractionProgress.clear();
        lastCooldownMessage.clear();
        extractionEntries.clear();
        pigStepPlaying = false;

        state = State.CLOSED;
        DungeonRift.get().getInstanceManager().destroyInstance(this);
        notifyPartyRiftComplete(allInstancePlayers);
    }

    private void notifyPartyRiftComplete(Set<UUID> players) {
        com.dungeonrift.manager.PartyManager pm = DungeonRift.get().getPartyManager();
        for (UUID uuid : players) {
            com.dungeonrift.model.Party party = pm.getPartyByMember(uuid);
            if (party != null) { pm.onRiftComplete(party); return; }
        }
    }

    private void broadcast(String msg) {
        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6DungeonRift§8] §r" + msg);
        });
    }

    public String    getId()            { return id; }
    public World     getWorld()         { return world; }
    public String    getTemplateName()  { return templateName; }
    public State     getState()         { return state; }
    public int       getSecondsLeft()   { return secondsRemaining; }
    public boolean   isAlive(UUID u)    { return alivePlayers.contains(u); }
    public boolean   inExtractionZone(UUID u) { return extractionProgress.containsKey(u); }
    public Set<UUID> getAlivePlayers()  { return Collections.unmodifiableSet(alivePlayers); }
    public boolean   isExtractionUnlocked() { return secondsElapsed >= EXTRACTION_COOLDOWN_SECONDS; }
    public void      addPlayerToBar(Player p) { bossBar.addPlayer(p); }
}
