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
    private int        secondsElapsed = 0;

    // ── Extraction safety ─────────────────────────────────────────────────────
    //
    // Simple, abuse-proof design:
    //   - Once the timer drops to <= safetyThreshold seconds, the "safety window" opens.
    //   - The first time a player enters the zone in this window, the timer pauses.
    //   - If they leave, the timer resumes immediately (no grace, no reset).
    //   - Each re-entry into the zone while <= safetyThreshold re-pauses the timer.
    //   - After MAX_ZONE_EXITS zone exits within the safety window, no more pauses.
    //   - Players are warned when they have 1 exit left.
    //
    private static final int MAX_ZONE_EXITS = 3;

    /** How many times the player has LEFT the zone during the safety window. */
    private final Map<UUID, Integer> safetyZoneExits = new HashMap<>();

    /** Whether the timer is currently paused for a player in the zone. */
    private boolean timerPaused     = false;
    private UUID    pausedForPlayer = null;

    // ── Collapse ──────────────────────────────────────────────────────────────

    private static final int EXTRACTION_COOLDOWN_SECONDS = 600;

    private final int collapseStartSeconds;
    private int       collapseTickCounter = 0;
    private boolean   pigStepPlaying      = false;

    // ── Vein generation ───────────────────────────────────────────────────────

    /** Tracks seconds at which veins have already been spawned to avoid duplicates. */
    private final Set<Integer> veinSpawnedAt = new HashSet<>();

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
        int collapseMinutes = DungeonRift.get().getConfig()
                .getInt("instance.collapse.start-at-minutes-remaining", 2);
        collapseStartSeconds = collapseMinutes * 60 + 30;
        bossBar = Bukkit.createBossBar(buildBarTitle(), BarColor.GREEN, BarStyle.SOLID);
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    public void startTimer() {
        state = State.ACTIVE;
        DungeonRift plugin  = DungeonRift.get();
        List<Integer> warns = plugin.getConfig().getIntegerList("instance.warnings-at-minutes");
        boolean safetyOn    = plugin.getConfig().getBoolean("extraction-safety.enabled", true);
        int safetyThreshold = plugin.getConfig().getInt("extraction-safety.pause-at-seconds-remaining", 10);

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            // ── Collapse always fires first — never blocked by pause ────────
            if (secondsRemaining <= collapseStartSeconds) tickCollapse();

            // ── Safety pause ───────────────────────────────────────────────
            if (timerPaused && safetyOn) {
                Player pp      = pausedForPlayer != null ? Bukkit.getPlayer(pausedForPlayer) : null;
                boolean inZone = pp != null && extractionProgress.containsKey(pausedForPlayer);

                if (inZone) {
                    updateBossBar();
                    tickExtractionProgress();
                    return; // hold the pause
                }

                // Player left zone — immediately resume timer (no grace)
                timerPaused     = false;
                pausedForPlayer = null;
            }

            // ── Normal timer tick ──────────────────────────────────────────
            secondsRemaining--;
            secondsElapsed++;
            updateBossBar();

            // Minute warnings + XP ping
            int minsLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warns.contains(minsLeft)) {
                broadcast("§c⚠ " + minsLeft + " minute(s) remaining!");
                alivePlayers.forEach(uuid -> {
                    Player pp = Bukkit.getPlayer(uuid);
                    if (pp != null) pp.playSound(pp.getLocation(),
                            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                });
            }

            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active!");
            }

            // ── Safety re-pause check ──────────────────────────────────────
            // Each second the timer is <= threshold and a player is in the zone,
            // re-pause — unless they've already used all their exits.
            if (safetyOn && secondsRemaining > 0 && secondsRemaining <= safetyThreshold) {
                for (UUID uuid : alivePlayers) {
                    if (!extractionProgress.containsKey(uuid)) continue;
                    int exits = safetyZoneExits.getOrDefault(uuid, 0);
                    if (exits >= MAX_ZONE_EXITS) continue; // no more pauses for them
                    timerPaused     = true;
                    pausedForPlayer = uuid;
                    updateBossBar();
                    tickExtractionProgress();
                    return;
                }
            }

            tickExtractionProgress();
            if (secondsRemaining <= 0) expire();

        }, 20L, 20L);
    }

    // ── Collapse ──────────────────────────────────────────────────────────────

    private void tickCollapse() {
        collapseTickCounter++;
        DungeonRift plugin     = DungeonRift.get();
        boolean soundsOn       = plugin.getConfig().getBoolean("instance.collapse.sounds-enabled",    true);
        boolean weatherOn      = plugin.getConfig().getBoolean("instance.collapse.weather-enabled",   true);
        boolean lightningOn    = plugin.getConfig().getBoolean("instance.collapse.lightning-enabled", true);

        double intensity = collapseStartSeconds > 0
                ? Math.min(1.0, 1.0 - ((double) secondsRemaining / collapseStartSeconds))
                : 1.0;

        // ── Pig Step — plays in the player's head, follows them ────────────
        if (!pigStepPlaying && soundsOn) {
            pigStepPlaying = true;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                // MASTER category plays client-side at the player's ear position
                // regardless of where they move — like a music disc to the client.
                p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.MASTER);
                p.playSound(p.getLocation(), Sound.MUSIC_DISC_PIGSTEP,
                        SoundCategory.MASTER, 2.0f, 1.0f);
            });
        }

        // ── Storm ─────────────────────────────────────────────────────────
        if (weatherOn) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(600);
        }

        // ── Crumbling sounds ──────────────────────────────────────────────
        int soundInterval = Math.max(1, (int) (5 - intensity * 4));
        if (soundsOn && collapseTickCounter % soundInterval == 0) {
            Sound[] sounds = { Sound.BLOCK_STONE_BREAK, Sound.BLOCK_GRAVEL_BREAK,
                               Sound.BLOCK_ANCIENT_DEBRIS_BREAK, Sound.BLOCK_DEEPSLATE_BREAK,
                               Sound.ENTITY_GENERIC_EXPLODE };
            Sound s = sounds[collapseTickCounter % sounds.length];
            float vol = (float) (0.5 + intensity * 0.5);
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.playSound(p.getLocation(), s, vol, 0.7f);
            });
        }

        // ── Explosions ────────────────────────────────────────────────────
        int explodeInterval = Math.max(2, (int) (20 - intensity * 18));
        if (soundsOn && collapseTickCounter % explodeInterval == 0) {
            Random rng = new Random();
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                double ox = (rng.nextDouble() - 0.5) * 40;
                double oz = (rng.nextDouble() - 0.5) * 40;
                world.createExplosion(p.getLocation().add(ox, 0, oz), 0f, false, false);
            });
        }

        // ── Lightning + ground cracking ───────────────────────────────────
        int lightningInterval = Math.max(2, (int) (8 - intensity * 6));
        if (lightningOn && collapseTickCounter % lightningInterval == 0) {
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

        // ── Darkness ──────────────────────────────────────────────────────
        int darkInterval = Math.max(3, (int) (15 - intensity * 12));
        if (collapseTickCounter % darkInterval == 0) {
            int amp = intensity > 0.7 ? 1 : 0;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.DARKNESS, 80, amp, false, false));
            });
        }

        // ── Magma + skulk veins — appear at 90s, 60s, 30s once each ───────
        spawnVeinAtThreshold(150, intensity);
        spawnVeinAtThreshold(90,  intensity);
        spawnVeinAtThreshold(60,  intensity);
        spawnVeinAtThreshold(30,  intensity);

        // ── Milestone broadcasts ──────────────────────────────────────────
        if (secondsRemaining == 60) broadcast("§4§l⚠ THE RIFT IS COLLAPSING! ⚠");
        if (secondsRemaining == 30) broadcast("§4§l☠ 30 SECONDS — GET OUT OF THE RIFT! ☠");
        if (secondsRemaining == 10) broadcast("§4§l☠ 10 SECONDS! ☠");
    }

    // ── Vein generation ───────────────────────────────────────────────────────

    private void spawnVeinAtThreshold(int threshold, double intensity) {
        if (secondsRemaining > threshold) return;
        if (veinSpawnedAt.contains(threshold)) return;
        veinSpawnedAt.add(threshold);

        // Spawn 2–4 veins spread around alive players
        Random rng       = new Random();
        int    veinCount = 2 + (int) (intensity * 2);

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            for (int v = 0; v < veinCount; v++) {
                double ox = (rng.nextDouble() - 0.5) * 80;
                double oz = (rng.nextDouble() - 0.5) * 80;
                Location origin = p.getLocation().add(ox, 0, oz);
                // Async world gen to avoid server tick spike
                DungeonRift.get().getServer().getScheduler().runTaskLater(
                        DungeonRift.get(),
                        () -> generateVein(origin, threshold, rng),
                        (long) (v * 4)); // stagger 4 ticks apart
            }
        });
    }

    /**
     * Generates a branching vein of magma and skulk blocks along the surface.
     * The vein walks randomly in a direction, placing blocks at surface level,
     * making the terrain look like it's splitting apart.
     *
     * @param origin     Starting location
     * @param threshold  The time threshold (controls material mix)
     * @param rng        Shared Random instance
     */
    private void generateVein(Location origin, int threshold, Random rng) {
        if (origin.getWorld() == null) return;

        // Material weighting: earlier (150s) = mostly skulk; later (30s) = mostly magma
        boolean moreMagma = threshold <= 60;

        // Walk the vein: 8–20 steps in a semi-random direction
        int steps = 8 + rng.nextInt(13);
        double dirX = (rng.nextDouble() - 0.5) * 2;
        double dirZ = (rng.nextDouble() - 0.5) * 2;

        Location current = origin.clone();
        for (int step = 0; step < steps; step++) {
            // Drift the direction slightly each step for organic feel
            dirX += (rng.nextDouble() - 0.5) * 0.4;
            dirZ += (rng.nextDouble() - 0.5) * 0.4;

            current = current.add(dirX, 0, dirZ);

            // Find surface
            Location surface = world.getHighestBlockAt(current).getLocation();

            // Place primary block
            placeVeinBlock(surface, moreMagma, rng);

            // Widen vein — place 1–2 blocks on each side perpendicular to travel
            int width = 1 + (step % 3 == 0 ? 1 : 0); // occasionally wider
            for (int w = 1; w <= width; w++) {
                placeVeinBlock(surface.clone().add(dirZ, 0, -dirX * w), moreMagma, rng);
                placeVeinBlock(surface.clone().add(-dirZ, 0, dirX * w), moreMagma, rng);
            }

            // Occasional branch — 20% chance past step 4
            if (step > 4 && rng.nextDouble() < 0.2) {
                double bx = (rng.nextDouble() - 0.5) * 2;
                double bz = (rng.nextDouble() - 0.5) * 2;
                Location branch = surface.clone();
                int branchLen   = 3 + rng.nextInt(5);
                for (int b = 0; b < branchLen; b++) {
                    branch = branch.add(bx, 0, bz);
                    placeVeinBlock(world.getHighestBlockAt(branch).getLocation(), moreMagma, rng);
                }
            }
        }
    }

    private void placeVeinBlock(Location loc, boolean moreMagma, Random rng) {
        if (loc.getWorld() == null) return;
        Material m = loc.getBlock().getType();
        if (!m.isSolid() || m == Material.BEDROCK
                || m.name().contains("LOG")   || m.name().contains("LEAVES")
                || m.name().contains("CHEST") || m.name().contains("SIGN")
                || m.name().contains("SKULL") || m == Material.MAGMA_BLOCK
                || m == Material.SCULK) return;

        double roll = rng.nextDouble();
        Material place;
        if (moreMagma) {
            // 60% magma, 25% skulk, 15% cracked stone
            place = roll < 0.60 ? Material.MAGMA_BLOCK
                  : roll < 0.85 ? Material.SCULK
                  : Material.CRACKED_STONE_BRICKS;
        } else {
            // 30% magma, 55% skulk, 15% cracked stone (earlier veins = more skulk)
            place = roll < 0.30 ? Material.MAGMA_BLOCK
                  : roll < 0.85 ? Material.SCULK
                  : Material.CRACKED_STONE_BRICKS;
        }
        loc.getBlock().setType(place, false);
    }

    // ── Ground cracking ───────────────────────────────────────────────────────

    private void crackGround(Location centre) {
        if (centre.getWorld() == null) return;
        Random rng = new Random();

        // Inner hole
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (rng.nextDouble() > 0.6) continue;
                Location surface = world.getHighestBlockAt(
                        centre.clone().add(dx, 0, dz)).getLocation();
                Material m = surface.getBlock().getType();
                if (m.isSolid() && m != Material.BEDROCK
                        && !m.name().contains("LOG") && !m.name().contains("CHEST")) {
                    surface.getBlock().setType(Material.AIR, false);
                }
            }
        }

        // Outer magma ring (radius 2–4)
        int magmaRadius = 2 + rng.nextInt(3);
        for (int dx = -magmaRadius; dx <= magmaRadius; dx++) {
            for (int dz = -magmaRadius; dz <= magmaRadius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 1.5 || dist > magmaRadius) continue;
                if (rng.nextDouble() > 0.35) continue;
                Location surface = world.getHighestBlockAt(
                        centre.clone().add(dx, 0, dz)).getLocation();
                Material m = surface.getBlock().getType();
                if (m.isSolid() && m != Material.BEDROCK
                        && !m.name().contains("LOG") && !m.name().contains("CHEST")) {
                    surface.getBlock().setType(
                            rng.nextDouble() < 0.7 ? Material.MAGMA_BLOCK
                                                   : Material.CRACKED_STONE_BRICKS,
                            false);
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
            bossBar.setTitle("§e§lTIMER PAUSED — Extracting...");
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

        // 10-minute cooldown
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

        // Start extraction countdown from 0 on each entry
        extractionProgress.put(player.getUniqueId(), 0);

        // Show exit-limit warning if in safety window
        int safetyThreshold = DungeonRift.get().getConfig()
                .getInt("extraction-safety.pause-at-seconds-remaining", 10);
        if (secondsRemaining <= safetyThreshold) {
            int exits = safetyZoneExits.getOrDefault(player.getUniqueId(), 0);
            int remaining = MAX_ZONE_EXITS - exits;
            if (remaining <= 1 && exits < MAX_ZONE_EXITS) {
                player.sendMessage("§c[DungeonRift] §eWarning: §7You have §c1 §7zone exit left before the timer pause is disabled!");
            } else if (exits >= MAX_ZONE_EXITS) {
                player.sendMessage("§c[DungeonRift] §7Zone exits exhausted — timer will NOT pause if you leave again.");
            }
        }
    }

    public void playerLeaveExtractionZone(Player player) {
        if (!extractionProgress.containsKey(player.getUniqueId())) return;
        extractionProgress.remove(player.getUniqueId());
        player.resetTitle();
        player.sendMessage("§c[DungeonRift] Extraction interrupted!");

        // Count exits inside safety window
        int safetyThreshold = DungeonRift.get().getConfig()
                .getInt("extraction-safety.pause-at-seconds-remaining", 10);
        if (secondsRemaining <= safetyThreshold) {
            int exits = safetyZoneExits.merge(player.getUniqueId(), 1, Integer::sum);
            int remaining = MAX_ZONE_EXITS - exits;
            if (remaining > 0) {
                player.sendMessage("§c[DungeonRift] §7Zone exit §c" + exits + "/" + MAX_ZONE_EXITS
                        + "§7 — §e" + remaining + " §7pause(s) remaining.");
            } else {
                player.sendMessage("§c[DungeonRift] §4Zone exits exhausted! §7The timer will no longer pause.");
            }
        }

        // Release pause immediately when they leave
        if (player.getUniqueId().equals(pausedForPlayer)) {
            timerPaused     = false;
            pausedForPlayer = null;
        }
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    private void stopPigStep(Player player) {
        player.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.MASTER);
    }

    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; }
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
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; }
        stopPigStep(player);
        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; }
        stopPigStep(player);
        if (player.isOnline()) player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
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
        safetyZoneExits.clear();
        veinSpawnedAt.clear();
        pigStepPlaying = false;
        timerPaused    = false;
        pausedForPlayer = null;

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
