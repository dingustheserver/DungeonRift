package com.dungeonrift.model;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.util.MessageUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Represents a single running dungeon instance.
 *
 * Lifecycle:
 *   LOADING → ACTIVE → EXTRACTING (per player) → CLOSED
 *
 * One instance may host 1–4 players (solo or party).
 * The instance closes when:
 *   a) all players have extracted or died, or
 *   b) the 30-minute timer expires.
 */
public class DungeonInstance {

    // ── State ─────────────────────────────────────────────────────────────────

    public enum State { LOADING, ACTIVE, CLOSING, CLOSED }

    private State state = State.LOADING;

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Unique ID, used as the world folder name: dungeon_<id> */
    private final String id;
    private final World  world;
    private final String templateName;

    // ── Players ───────────────────────────────────────────────────────────────

    /** All players currently alive inside this instance. */
    private final Set<UUID> alivePlayers = new HashSet<>();

    /**
     * Players currently standing in the extraction zone.
     * Maps UUID → ticks spent in zone (200 ticks = 10 s).
     */
    private final Map<UUID, Integer> extractionProgress = new HashMap<>();

    // ── Timer ─────────────────────────────────────────────────────────────────

    private BukkitTask countdownTask;
    private int        secondsRemaining;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DungeonInstance(String id, World world, String templateName, List<Player> players) {
        this.id           = id;
        this.world        = world;
        this.templateName = templateName;

        players.forEach(p -> alivePlayers.add(p.getUniqueId()));

        secondsRemaining = DungeonRift.get().getConfig()
                .getInt("instance.time-limit-minutes", 30) * 60;
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    /** Called by InstanceManager after the world is ready and players are in. */
    public void startTimer() {
        state = State.ACTIVE;

        DungeonRift plugin = DungeonRift.get();
        List<Integer> warnMinutes = plugin.getConfig()
                .getIntegerList("instance.warnings-at-minutes");

        countdownTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {

            secondsRemaining--;

            // Broadcast warnings
            int minutesLeft = secondsRemaining / 60;
            if (secondsRemaining % 60 == 0 && warnMinutes.contains(minutesLeft)) {
                broadcast(MessageUtil.format("messages.timer-warning",
                        "minutes", String.valueOf(minutesLeft)));
            }

            // Advance extraction countdowns for players in zone
            tickExtractionProgress();

            // Timer expired
            if (secondsRemaining <= 0) {
                expire();
            }

        }, 20L, 20L); // every second
    }

    // ── Extraction zone ───────────────────────────────────────────────────────

    /**
     * Called every second by the main countdown task.
     * If a player has been in the zone long enough, extracts them.
     */
    private void tickExtractionProgress() {
        int holdSeconds = DungeonRift.get().getConfig()
                .getInt("instance.extraction-hold-seconds", 10);

        new HashSet<>(extractionProgress.keySet()).forEach(uuid -> {
            Player player = DungeonRift.get().getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                extractionProgress.remove(uuid);
                return;
            }

            int ticks = extractionProgress.merge(uuid, 1, Integer::sum);
            int secondsHeld = ticks;

            // Show countdown title
            int remaining = holdSeconds - secondsHeld;
            if (remaining >= 0) {
                player.sendTitle(
                        "§6Extracting...",
                        "§e" + remaining + "s",
                        0, 25, 5
                );
            }

            // Extraction complete
            if (secondsHeld >= holdSeconds) {
                extractPlayer(player);
            }
        });
    }

    /**
     * Called when a player steps into the extraction zone.
     * Idempotent — safe to call every move event.
     */
    public void playerEnterExtractionZone(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;
        if (extractionProgress.containsKey(player.getUniqueId())) return;

        extractionProgress.put(player.getUniqueId(), 0);

        String msg = MessageUtil.format("messages.extraction-start",
                "seconds", String.valueOf(
                        DungeonRift.get().getConfig()
                                .getInt("instance.extraction-hold-seconds", 10)));
        player.sendMessage(MessageUtil.prefix() + msg);
    }

    /**
     * Called when a player leaves the extraction zone or takes damage.
     */
    public void playerLeaveExtractionZone(Player player) {
        if (!extractionProgress.containsKey(player.getUniqueId())) return;

        extractionProgress.remove(player.getUniqueId());
        player.resetTitle();
        player.sendMessage(MessageUtil.prefix()
                + MessageUtil.format("messages.extraction-interrupted"));
    }

    // ── Player outcomes ───────────────────────────────────────────────────────

    /** Player successfully stood in zone long enough — send them home with loot. */
    private void extractPlayer(Player player) {
        extractionProgress.remove(player.getUniqueId());
        alivePlayers.remove(player.getUniqueId());

        player.resetTitle();
        player.sendMessage(MessageUtil.prefix()
                + MessageUtil.format("messages.extracted"));

        // Loot is kept — teleport directly to hub
        DungeonRift.get().getInstanceManager().returnPlayerToHub(player);

        checkIfEmpty();
    }

    /** Player died inside the instance. */
    public void onPlayerDeath(Player player) {
        alivePlayers.remove(player.getUniqueId());
        extractionProgress.remove(player.getUniqueId());

        player.sendMessage(MessageUtil.prefix()
                + MessageUtil.format("messages.death-message"));

        // Death penalty (drop_in_world already handled by vanilla on death)
        String penalty = DungeonRift.get().getConfig()
                .getString("loot.death-penalty", "drop_in_world");

        if ("clear".equals(penalty)) {
            // Inventory cleared on respawn via InstanceLifecycleListener
        }
        // "keep" — do nothing extra; "drop_in_world" is Minecraft default

        // Respawn handled by InstanceLifecycleListener → immediately send to hub
        checkIfEmpty();
    }

    /** No alive players left — close the instance. */
    private void checkIfEmpty() {
        if (alivePlayers.isEmpty()) {
            close("All players eliminated or extracted.");
        }
    }

    // ── Timer expiry ──────────────────────────────────────────────────────────

    private void expire() {
        broadcast(MessageUtil.format("messages.timer-expired"));

        // Kill everyone still inside
        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = DungeonRift.get().getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.setHealth(0); // triggers PlayerDeathEvent → onPlayerDeath
            }
        });

        close("Timer expired.");
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Cleanly close this instance.
     * Cancels the timer, returns any remaining players to hub,
     * then signals InstanceManager to unload and delete the world.
     */
    public void close(String reason) {
        if (state == State.CLOSING || state == State.CLOSED) return;
        state = State.CLOSING;

        DungeonRift.get().getLogger().info(
                "Closing instance " + id + " — " + reason);

        if (countdownTask != null) countdownTask.cancel();

        // Return any survivors who haven't extracted yet
        new HashSet<>(alivePlayers).forEach(uuid -> {
            Player p = DungeonRift.get().getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                DungeonRift.get().getInstanceManager().returnPlayerToHub(p);
            }
        });
        alivePlayers.clear();
        extractionProgress.clear();

        state = State.CLOSED;

        // Hand off world teardown to InstanceManager
        DungeonRift.get().getInstanceManager().destroyInstance(this);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void broadcast(String message) {
        String prefix = MessageUtil.prefix();
        alivePlayers.forEach(uuid -> {
            Player p = DungeonRift.get().getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(prefix + message);
        });
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getId()            { return id;            }
    public World   getWorld()         { return world;         }
    public String  getTemplateName()  { return templateName;  }
    public State   getState()         { return state;         }
    public int     getSecondsLeft()   { return secondsRemaining; }
    public boolean isAlive(UUID uuid) { return alivePlayers.contains(uuid); }
    public boolean inExtractionZone(UUID uuid) {
        return extractionProgress.containsKey(uuid);
    }
    public Set<UUID> getAlivePlayers() {
        return Collections.unmodifiableSet(alivePlayers);
    }
}
