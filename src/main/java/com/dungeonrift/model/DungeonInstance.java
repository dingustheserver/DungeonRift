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

    /** How many times each player has entered the extraction zone */
    private final Map<UUID, Integer> extractionEntries   = new HashMap<>();
    private static final int MAX_EXTRACTION_ENTRIES = 3;

    /** How many times the emergency pause has been released due to player leaving zone */
    private int pauseExitCount = 0;
    private static final int MAX_PAUSE_EXITS = 3;

    /** Whether pig step music disc has been started for the collapse */
    private boolean pigStepPlaying = false;

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;
    private int        secondsElapsed    = 0;

    /**
     * Whether the emergency safety net has paused the main timer.
     * When paused the main timer does not decrement.
     */
    private boolean    timerPaused       = false;

    /**
     * When the player leaves the zone during a pause, this counts down
     * from 5 to 0. While > 0 the pause is maintained (grace period).
     * When it hits 0 the pause is released and timer resumes.
     * -1 means grace is not active.
     */
    private int        graceCountdown    = -1;

    /** The player the current pause is protecting */
    private UUID       pausedForPlayer   = null;

    private static final int EXTRACTION_COOLDOWN_SECONDS = 600;

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
        List<Integer> warnMinutes       = plugin.getConfig().getIntegerList("instance.warnings-at-minutes");
        int collapseAtMinutes           = plugin.getConfig().getInt("instance.collapse.start-at-minutes-remaining", 1);
        boolean safetyEnabled           = plugin.getConfig().getBoolean("extraction-safety.enabled", true);
        int safetyThreshold             = plugin.getConfig().getInt("extraction-safety.pause-at-seconds-remaining", 10);

        alivePlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        });

        countdownTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            // ── Safety pause state machine ─────────────────────────────────
            if (timerPaused) {
                Player pp = pausedForPlayer != null ? Bukkit.getPlayer(pausedForPlayer) : null;
                boolean stillInZone = pp != null && extractionProgress.containsKey(pausedForPlayer);

                if (stillInZone) {
                    // Player is in zone — stay paused, tick their extraction progress
                    updateBossBar();
                    tickExtractionProgress();
                    return;
                }

                // Player left zone — grace period
                if (graceCountdown < 0) {
                    // First tick after leaving — start grace
                    graceCountdown = 5;
                    if (pp != null) {
                        pp.sendMessage("§c[DungeonRift] You left the extraction zone!");
                        pp.sendMessage("§7Return within §c5 seconds §7or the timer resumes!");
                    }
                }

                // Tick grace
                if (graceCountdown > 0) {
                    if (pp != null) pp.sendTitle("§cReturn to portal!", "§e" + graceCountdown + "s", 0, 25, 5);
                    graceCountdown--;
                    updateBossBar();
                    return; // timer still paused during grace
                }

                // Grace elapsed — release pause and fall through to normal tick
                timerPaused     = false;
                pausedForPlayer = null;
                graceCountdown  = -1;
                pauseExitCount++;
                if (pauseExitCount >= MAX_PAUSE_EXITS) {
                    broadcast("§c⚠ Emergency pauses exhausted. Timer will no longer pause!");
                } else {
                    broadcast("§c⚠ Emergency pause expired. Timer resumed!");
                }
            }

            // ── Normal tick ────────────────────────────────────────────────
            secondsRemaining--;
            secondsElapsed++;
            updateBossBar();

            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast("§c⚠ " + minutesLeft + " minute(s) remaining!");
                // XP pickup ping so the notification is audible
                alivePlayers.forEach(uuid -> {
                    Player pp = Bukkit.getPlayer(uuid);
                    if (pp != null) pp.playSound(pp.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                });
            }
            if (secondsElapsed == EXTRACTION_COOLDOWN_SECONDS) {
                broadcast("§a✔ The extraction portal is now active!");
            }
            if (secondsRemaining <= collapseAtMinutes * 60) {
                tickCollapse();
            }

            // ── Safety net check ───────────────────────────────────────────
            // Only activates if enabled, timer is low, and a player is in zone
            if (safetyEnabled && pauseExitCount < MAX_PAUSE_EXITS
                    && secondsRemaining > 0 && secondsRemaining <= safetyThreshold) {
                for (UUID uuid : alivePlayers) {
                    if (extractionProgress.containsKey(uuid)) {
                        timerPaused     = true;
                        pausedForPlayer = uuid;
                        graceCountdown  = -1;
                        Player pp = Bukkit.getPlayer(uuid);
                        if (pp != null) pp.sendMessage("§a[DungeonRift] §eTimer paused — finish extracting! §7(" + (MAX_PAUSE_EXITS - pauseExitCount) + " pauses left)");
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

    private int collapseTickCounter = 0;

    private void tickCollapse() {
        collapseTickCounter++;
        DungeonRift plugin = DungeonRift.get();
        boolean soundsEnabled    = plugin.getConfig().getBoolean("instance.collapse.sounds-enabled",   true);
        boolean weatherEnabled   = plugin.getConfig().getBoolean("instance.collapse.weather-enabled",  true);
        boolean lightningEnabled = plugin.getConfig().getBoolean("instance.collapse.lightning-enabled", true);

        // Intensity: 0.0 at collapse start, 1.0 at 0 seconds remaining
        int collapseStart = plugin.getConfig().getInt("instance.collapse.start-at-minutes-remaining", 2) * 60 + 30;
        double intensity  = collapseStart > 0
                ? Math.min(1.0, 1.0 - ((double) secondsRemaining / collapseStart))
                : 1.0;

        // ── Pig Step music — play once at collapse start ───────────────────
        if (!pigStepPlaying && soundsEnabled) {
            pigStepPlaying = true;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.playSound(p.getLocation(), Sound.MUSIC_DISC_PIGSTEP, 1.0f, 1.0f);
            });
        }

        // ── Storm ─────────────────────────────────────────────────────────
        if (weatherEnabled) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(600);
        }

        // ── Crumbling sounds — frequency increases with intensity ─────────
        // At low intensity fire every ~5s; at high intensity every ~1s
        int soundInterval = Math.max(1, (int) (5 - intensity * 4));
        if (soundsEnabled && collapseTickCounter % soundInterval == 0) {
            Sound[] sounds = {
                Sound.BLOCK_STONE_BREAK,         Sound.BLOCK_GRAVEL_BREAK,
                Sound.BLOCK_ANCIENT_DEBRIS_BREAK, Sound.BLOCK_DEEPSLATE_BREAK,
                Sound.ENTITY_GENERIC_EXPLODE
            };
            Sound s = sounds[collapseTickCounter % sounds.length];
            float vol = (float) (0.5 + intensity * 0.5);
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.playSound(p.getLocation(), s, vol, 0.7f);
            });
        }

        // ── Explosions — rare early, frequent late ────────────────────────
        int explosionInterval = Math.max(2, (int) (20 - intensity * 18));
        if (soundsEnabled && collapseTickCounter % explosionInterval == 0) {
            Random rng = new Random();
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                double ox = (rng.nextDouble() - 0.5) * 40;
                double oz = (rng.nextDouble() - 0.5) * 40;
                Location expLoc = p.getLocation().add(ox, 0, oz);
                // Visual explosion (no block damage)
                world.createExplosion(expLoc, 0f, false, false);
            });
        }

        // ── Lightning — damages ground, sets fires, leaves magma ─────────
        int lightningInterval = Math.max(2, (int) (8 - intensity * 6));
        if (lightningEnabled && collapseTickCounter % lightningInterval == 0) {
            Random rng = new Random();
            int strikes = 1 + (int) (intensity * 2); // 1 at start, up to 3 at end
            for (int i = 0; i < strikes; i++) {
                alivePlayers.forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) return;
                    double ox = (rng.nextDouble() - 0.5) * 50;
                    double oz = (rng.nextDouble() - 0.5) * 50;
                    Location strikeLoc = p.getLocation().add(ox, 0, oz);

                    // Real lightning strike — sets fire
                    world.strikeLightning(strikeLoc);

                    // Crack the ground: place magma blocks near strike
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        crackGround(strikeLoc);
                    }, 2L);
                });
            }
        }

        // ── Darkness — stronger as intensity rises ────────────────────────
        int darkInterval = Math.max(3, (int) (15 - intensity * 12));
        if (collapseTickCounter % darkInterval == 0) {
            int darkAmplifier = intensity > 0.7 ? 1 : 0;
            alivePlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.DARKNESS, 80, darkAmplifier, false, false));
            });
        }

        // ── Milestone broadcasts ──────────────────────────────────────────
        if (secondsRemaining == 60)  broadcast("§4§l⚠ THE RIFT IS COLLAPSING! ⚠");
        if (secondsRemaining == 30)  broadcast("§4§l☠ 30 SECONDS — GET OUT OF THE RIFT! ☠");
        if (secondsRemaining == 10)  broadcast("§4§l☠ 10 SECONDS! ☠");
    }

    /**
     * Places cracks (cobblestone, gravel, magma) around a lightning strike location
     * on the surface blocks to simulate ground cracking.
     */
    private void crackGround(Location centre) {
        Random rng = new Random();
        Material[] crackMaterials = {
            Material.MAGMA_BLOCK, Material.CRACKED_STONE_BRICKS,
            Material.COBBLESTONE,  Material.GRAVEL
        };
        int radius = 2 + (int)(rng.nextDouble() * 2); // 2–3 block radius
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                if (rng.nextDouble() > 0.4) continue; // only affect ~40% of blocks

                Location check = centre.clone().add(dx, 0, dz);
                // Find the surface block
                check = check.getWorld().getHighestBlockAt(check).getLocation();
                Material existing = check.getBlock().getType();

                // Only crack solid non-air, non-fluid surface blocks
                if (existing.isSolid() && !existing.name().contains("LOG")
                        && !existing.name().contains("LEAVES")
                        && !existing.name().contains("CHEST")
                        && existing != Material.BEDROCK) {
                    Material crack = crackMaterials[rng.nextInt(crackMaterials.length)];
                    check.getBlock().setType(crack, false);
                }
            }
        }
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    private void updateBossBar() {
        int total    = DungeonRift.get().getConfig().getInt("instance.time-limit-minutes", 30) * 60;
        double prog  = Math.max(0, (double) secondsRemaining / total);
        bossBar.setProgress(prog);

        if (timerPaused) {
            int grace = graceCountdown > 0 ? graceCountdown : 0;
            bossBar.setTitle("§e§lTIMER PAUSED" + (graceCountdown > 0 ? " — return in §c" + grace + "s" : " — extracting..."));
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
        int holdSeconds = DungeonRift.get().getConfig().getInt("instance.extraction-hold-seconds", 10);

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

        // Cooldown check
        if (secondsElapsed < EXTRACTION_COOLDOWN_SECONDS) {
            int lastMsg = lastCooldownMessage.getOrDefault(player.getUniqueId(), -10);
            if (secondsElapsed - lastMsg >= 5) {
                int left = EXTRACTION_COOLDOWN_SECONDS - secondsElapsed;
                player.sendMessage(String.format("§c[DungeonRift] §7Portal not active — §c%02d:%02d §7remaining.", left / 60, left % 60));
                lastCooldownMessage.put(player.getUniqueId(), secondsElapsed);
            }
            return;
        }

        // Zone entry limit — max 3 entries per player
        int entries = extractionEntries.getOrDefault(player.getUniqueId(), 0);
        if (entries >= MAX_EXTRACTION_ENTRIES) {
            // Already used all entries — if timer is in safety zone we still let them try
            // but no new pause will be granted
            if (!extractionProgress.containsKey(player.getUniqueId())) {
                player.sendMessage("§c[DungeonRift] You have used all your extraction attempts. No timer pause will be granted.");
                extractionProgress.put(player.getUniqueId(), 0);
            }
            return;
        }

        // Normal entry
        extractionEntries.put(player.getUniqueId(), entries + 1);
        extractionProgress.put(player.getUniqueId(), 0);

        // Restore pause if they returned during grace
        if (player.getUniqueId().equals(pausedForPlayer) && graceCountdown >= 0) {
            graceCountdown = -1; // back in zone — cancel grace countdown
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

    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; graceCountdown = -1; }

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
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; graceCountdown = -1; }
        player.sendMessage("§c[DungeonRift] §4§lYou died in the rift. All loot is lost.");
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);
        checkIfEmpty();
    }

    public void onPlayerForfeit(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        if (player.getUniqueId().equals(pausedForPlayer)) { timerPaused = false; pausedForPlayer = null; graceCountdown = -1; }
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
