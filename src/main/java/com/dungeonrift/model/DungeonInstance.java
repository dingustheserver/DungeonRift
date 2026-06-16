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

    /** Cached extraction portal location — set at construction, used for safe-zone checks. */
    private Location extractionPortalLocation;
    private double   extractionSafeRadius;

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
        // Cache extraction portal location so we always have the right coords
        // even if the config changes mid-run
        double epx = DungeonRift.get().getConfig().getDouble("instance.extraction-portal.x", 0);
        double epy = DungeonRift.get().getConfig().getDouble("instance.extraction-portal.y", 64);
        double epz = DungeonRift.get().getConfig().getDouble("instance.extraction-portal.z", 0);
        extractionPortalLocation = new Location(world, epx, epy, epz);
        extractionSafeRadius = DungeonRift.get().getConfig()
                .getDouble("instance.extraction-zone-radius", 3.0) + 8.0; // generous buffer
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

        // ── Pig Step — executed at the player's position so it follows them ──
        // Uses the same approach as:
        //   /execute at <player> run playsound minecraft:music_disc.pigstep record @a ~ ~ ~ 100 1
        // Playing at ~ ~ ~ (player's own coords) with volume 100 means the sound
        // originates right on top of them and never attenuates as they move.
        if (!pigStepPlaying && soundsOn) {
            pigStepPlaying = true;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                // Stop any previous instance first
                p.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
                // Play at the player's exact location with very high volume (100)
                // so it sounds identical regardless of position in the world
                p.playSound(p.getLocation(), Sound.MUSIC_DISC_PIGSTEP,
                        SoundCategory.RECORDS, 100.0f, 1.0f);
            });
        }

        // ── Thunder without rain — dramatic sky without wet ground ──────────
        if (weatherOn) {
            // setStorm(false) = clear weather (no rain)
            // setThundering(true) = thunder still rumbles, sky darkens
            world.setStorm(false);
            world.setThundering(true);
            world.setThunderDuration(600);
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

        // ── Lightning hits the ground, then spawns tentacles ─────────────
        int lightningInterval = Math.max(2, (int) (8 - intensity * 6));
        if (lightningOn && collapseTickCounter % lightningInterval == 0) {
            Random rng     = new Random();
            int    strikes = 1 + (int) (intensity * 2);
            for (int i = 0; i < strikes; i++) {
                alivePlayers.forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) return;
                    double ox = (rng.nextDouble() - 0.5) * 50;
                    double oz = (rng.nextDouble() - 0.5) * 50;
                    // ── Find ground level so lightning actually hits the terrain ──
                    Location approx = p.getLocation().add(ox, 0, oz);
                    // Check both the approx AND the actual ground point
                    if (isNearExtractionPortal(approx)) return;
                    Location groundLoc = world.getHighestBlockAt(approx)
                            .getLocation().add(0, 1, 0);
                    // Double-check after resolving actual ground height
                    if (isNearExtractionPortal(groundLoc)) return;
                    world.strikeLightning(groundLoc);
                    Location surface = groundLoc.clone().subtract(0, 1, 0);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> spawnTentacles(surface, rng), 2L);
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

    // ── Spreading infection veins ──────────────────────────────────────────────

    private void spawnVeinAtThreshold(int threshold, double intensity) {
        if (secondsRemaining > threshold) return;
        if (veinSpawnedAt.contains(threshold)) return;
        veinSpawnedAt.add(threshold);

        boolean moreSkulk = threshold >= 90; // early = skulk-heavy, late = magma-heavy
        Random rng        = new Random();
        int    veinCount  = 2 + (int) (intensity * 2); // 2–4 veins

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            for (int v = 0; v < veinCount; v++) {
                double ox = (rng.nextDouble() - 0.5) * 80;
                double oz = (rng.nextDouble() - 0.5) * 80;
                Location origin = p.getLocation().add(ox, 0, oz);
                // Skip if origin is too close to extraction portal
                if (isNearExtractionPortal(origin)) continue;
                long startDelay = v * 5L;
                DungeonRift.get().getServer().getScheduler().runTaskLater(
                        DungeonRift.get(),
                        () -> startInfectionVein(origin, moreSkulk, rng),
                        startDelay);
            }
        });
    }

    /**
     * Starts an infection vein at a point and spreads it outward over 6 waves,
     * one wave per second. Each wave adds a new ring of infection with a chance
     * of the infection also deleting blocks (leaving holes/craters).
     *
     * Early veins (moreSkulk=true): skulk-heavy — looks like dark corruption spreading
     * Late veins  (moreSkulk=false): magma-heavy — looks like molten rock spreading
     */
    private void startInfectionVein(Location origin, boolean moreSkulk, Random rng) {
        if (origin.getWorld() == null) return;

        // Plant the seed — place a small cluster at the origin
        Location surface = world.getHighestBlockAt(origin).getLocation();
        if (canInfect(surface.getBlock().getType())) {
            surface.getBlock().setType(moreSkulk ? Material.SCULK : Material.MAGMA_BLOCK, false);
        }

        // Spread outward: 10–14 waves for much bigger veins, fired 1s apart
        int waves = 10 + rng.nextInt(5); // 10–14 waves — much larger than before
        for (int wave = 1; wave <= waves; wave++) {
            final int   radius     = wave;
            final long  delay      = wave * 20L; // one wave per second
            final boolean skulkWave = moreSkulk;
            DungeonRift.get().getServer().getScheduler().runTaskLater(
                    DungeonRift.get(),
                    () -> infectRing(origin, radius, skulkWave, rng),
                    delay);
        }
    }

    /**
     * Infects a single ring at the given radius around the origin.
     * Blocks in the ring have a chance to become magma, skulk, or air (hole).
     */
    private void infectRing(Location origin, int radius, boolean moreSkulk, Random rng) {
        if (origin.getWorld() == null) return;

        // Angular sweep around the ring — gives organic uneven spread
        int steps = (int) (Math.PI * 2 * radius * 4); // ~4 samples per block
        for (int i = 0; i < steps; i++) {
            double angle = (2 * Math.PI * i) / steps;
            double ox    = Math.cos(angle) * radius;
            double oz    = Math.sin(angle) * radius;

            // Fuzzy radius — jitter ±1 for organic edges
            double jitter = (rng.nextDouble() - 0.5) * 2;
            double jx = Math.cos(angle) * jitter;
            double jz = Math.sin(angle) * jitter;

            Location checkLoc = origin.clone().add(ox + jx, 0, oz + jz);
            if (isNearExtractionPortal(checkLoc)) continue;
            Location check = world.getHighestBlockAt(checkLoc).getLocation();
            Material m = check.getBlock().getType();
            if (!canInfect(m)) continue;

            double roll = rng.nextDouble();

            if (roll < 0.15) {
                // 15% — remove block (infection eats through)
                check.getBlock().setType(Material.AIR, false);
            } else if (roll < 0.55) {
                // 40% — place infection block
                check.getBlock().setType(
                        moreSkulk ? (rng.nextDouble() < 0.7 ? Material.SCULK : Material.MAGMA_BLOCK)
                                  : (rng.nextDouble() < 0.65 ? Material.MAGMA_BLOCK : Material.SCULK),
                        false);
            }
            // Remaining 45% — untouched, gives patchy organic look
        }
    }

    // ── Tentacle spread from lightning strike ────────────────────────────────

    /**
     * Spawns a crater, fire, and 4–6 thick tentacles from a lightning strike.
     * Tentacles are 3 blocks wide with a solid core plus two flanking lanes.
     */
    private void spawnTentacles(Location origin, Random rng) {
        if (origin.getWorld() == null) return;

        // ── Crater at impact ──────────────────────────────────────────────
        // Remove a 3x3 area around impact and fill with magma + fire above
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Location s = world.getHighestBlockAt(origin.clone().add(dx, 0, dz)).getLocation();
                Material m = s.getBlock().getType();
                if (!canInfect(m)) continue;

                if (dx == 0 && dz == 0) {
                    // Dead centre — always air
                    s.getBlock().setType(Material.AIR, false);
                } else {
                    s.getBlock().setType(Material.MAGMA_BLOCK, false);
                    // Fire on top of magma at the crater rim
                    Location above = s.clone().add(0, 1, 0);
                    if (above.getBlock().getType() == Material.AIR) {
                        above.getBlock().setType(Material.FIRE, false);
                    }
                }
            }
        }

        // ── Fire burst — sparse fire only at the crater centre ──────────
        // Reduced significantly — just a small burst at impact, not a wide field
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (rng.nextDouble() > 0.25) continue; // 25% chance, was 45%
                Location s = world.getHighestBlockAt(origin.clone().add(dx, 0, dz)).getLocation();
                Location above = s.clone().add(0, 1, 0);
                if (s.getBlock().getType().isSolid()
                        && above.getBlock().getType() == Material.AIR) {
                    above.getBlock().setType(Material.FIRE, false);
                }
            }
        }

        // ── Thick tentacles ───────────────────────────────────────────────
        // 6–9 arms, each 3–5 blocks wide, 12–22 blocks long
        int tentacleCount = 6 + rng.nextInt(4);
        for (int t = 0; t < tentacleCount; t++) {
            double angle   = (2 * Math.PI * t) / tentacleCount + (rng.nextDouble() - 0.5) * 0.7;
            double speedX  = Math.cos(angle);
            double speedZ  = Math.sin(angle);
            int    length  = 12 + rng.nextInt(11); // 12–22 blocks
            boolean magma  = rng.nextBoolean();
            growTentacleStep(origin.clone(), speedX, speedZ, 0, length, magma, rng);
        }
    }

    /**
     * Grows one thick tentacle step-by-step via the scheduler.
     * Each step places a 3-wide cross-section: core block + two side flanks.
     * Fire spawns above magma blocks along the arm.
     * Additional fire is scattered at each step for a burning trail effect.
     */
    private void growTentacleStep(Location current, double dx, double dz,
                                   int step, int maxSteps, boolean placeMagma, Random rng) {
        if (step >= maxSteps || current.getWorld() == null) return;
        if (DungeonRift.get() == null) return;

        DungeonRift.get().getServer().getScheduler().runTaskLater(DungeonRift.get(), () -> {
            if (world == null) return;

            // Drift direction for organic curvature
            double newDx = dx + (rng.nextDouble() - 0.5) * 0.45;
            double newDz = dz + (rng.nextDouble() - 0.5) * 0.45;
            double len   = Math.sqrt(newDx * newDx + newDz * newDz);
            if (len > 0) { newDx /= len; newDz /= len; }

            Location next = current.clone().add(newDx, 0, newDz);

            // Near tip = bias toward skulk; near root = bias toward magma
            boolean nearTip = step > maxSteps * 0.65;
            Material coreMat = nearTip
                    ? (rng.nextDouble() < 0.75 ? Material.SCULK   : Material.MAGMA_BLOCK)
                    : (placeMagma
                            ? (rng.nextDouble() < 0.6 ? Material.MAGMA_BLOCK : Material.SCULK)
                            : (rng.nextDouble() < 0.6 ? Material.SCULK       : Material.MAGMA_BLOCK));

            // ── Core block ─────────────────────────────────────────────────
            placeInfectionBlock(next, coreMat, rng);

            // ── Flanking blocks — makes the tentacle 3 wide ────────────────
            // Perpendicular direction to the travel direction
            double perpX = -newDz;
            double perpZ =  newDx;

            // Both sides
            for (int side = -1; side <= 1; side += 2) {
                Location flank = next.clone().add(perpX * side, 0, perpZ * side);
                // Flanks are slightly biased to skulk for a layered look
                Material flankMat = rng.nextDouble() < 0.55 ? Material.SCULK : Material.MAGMA_BLOCK;
                placeInfectionBlock(flank, flankMat, rng);

                // Outer flanks more frequent — 55% chance, skulk-heavy for thickness
                if (rng.nextDouble() < 0.55) {
                    Location outerFlank = next.clone().add(perpX * side * 2, 0, perpZ * side * 2);
                    placeInfectionBlock(outerFlank, Material.SCULK, rng);
                }
                // Occasional third layer — makes it 7 wide at key spots
                if (rng.nextDouble() < 0.2) {
                    Location thirdFlank = next.clone().add(perpX * side * 3, 0, perpZ * side * 3);
                    placeInfectionBlock(thirdFlank, Material.SCULK, rng);
                }
            }

            // Continue arm
            growTentacleStep(next, newDx, newDz, step + 1, maxSteps, !placeMagma, rng);

        }, 2L);
    }

    /**
     * Places a single infection block at the surface of a location.
     * If the block placed is magma, fire is spawned above it.
     * 8% chance to leave an air hole instead.
     */
    private void placeInfectionBlock(Location loc, Material mat, Random rng) {
        // Never touch the extraction zone
        if (isNearExtractionPortal(loc)) return;
        Location surface = world.getHighestBlockAt(loc).getLocation();
        Material existing = surface.getBlock().getType();
        if (!canInfect(existing)) return;

        if (rng.nextDouble() < 0.08) {
            // Rare air gap — infection eats a hole
            surface.getBlock().setType(Material.AIR, false);
            return;
        }

        surface.getBlock().setType(mat, false);

        // Fire above magma blocks — sparse, only 20% chance per block
        if (mat == Material.MAGMA_BLOCK && rng.nextDouble() < 0.20) {
            Location above = surface.clone().add(0, 1, 0);
            if (above.getBlock().getType() == Material.AIR) {
                above.getBlock().setType(Material.FIRE, false);
            }
        }
    }

    private boolean canInfect(Material m) {
        if (!m.isSolid() || m == Material.BEDROCK) return false;
        if (m == Material.MAGMA_BLOCK || m == Material.SCULK) return false;
        String n = m.name();
        return !n.contains("LOG") && !n.contains("LEAVES") && !n.contains("CHEST")
            && !n.contains("SIGN") && !n.contains("SKULL") && !n.contains("SHULKER");
    }

    /**
     * Returns true if a location is within the extraction zone safe radius.
     * Uses a flat 2D XZ check so height differences cannot bypass it.
     * No skulk, magma, lightning, or fire should touch this area.
     */
    private boolean isNearExtractionPortal(Location loc) {
        if (extractionPortalLocation == null) return false;
        // 2D XZ only — ignoring Y so blocks at different heights are still protected
        double dx = loc.getX() - extractionPortalLocation.getX();
        double dz = loc.getZ() - extractionPortalLocation.getZ();
        return (dx * dx + dz * dz) <= (extractionSafeRadius * extractionSafeRadius);
    }

    /**
     * Called by InstanceLifecycleListener to check if a lightning strike
     * is inside the extraction safe zone and should be cancelled.
     */
    public boolean isExtractionSafeZone(Location loc) {
        return isNearExtractionPortal(loc);
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
        player.stopSound(Sound.MUSIC_DISC_PIGSTEP, SoundCategory.RECORDS);
    }

    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; }
        stopPigStep(player);
        player.resetTitle();
        // 2 seconds of blindness on extraction — cinematic transition feel
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.BLINDNESS, 40, 0, false, false));
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
        world.setWeatherDuration(0);
        world.setThunderDuration(0);

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
