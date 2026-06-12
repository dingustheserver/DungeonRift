package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * InstanceManager
 *
 * Responsible for:
 *   1. Spawning a new dungeon instance (clone template → load world → teleport players)
 *   2. Tracking every live instance
 *   3. Tearing down finished instances (unload world → delete folder)
 *   4. Returning players to the hub after extraction or death
 */
public class InstanceManager {

    private final DungeonRift plugin;
    private final Logger      log;

    /** instanceId → DungeonInstance */
    private final Map<String, DungeonInstance> activeInstances = new ConcurrentHashMap<>();

    /** playerUUID → instanceId (fast reverse lookup) */
    private final Map<UUID, String> playerInstance = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public InstanceManager(DungeonRift plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Instance spawning ─────────────────────────────────────────────────────

    /**
     * Spawns a new instance for the given list of players.
     * Runs world creation on the main thread (Bukkit requirement),
     * but the file copy happens synchronously before that.
     *
     * Call from QueueManager when a solo player or full party is ready.
     */
    public void spawnInstance(List<Player> players) {
        // 1. Generate a unique world name
        String instanceId = "dungeon_" + UUID.randomUUID().toString().substring(0, 8);

        // 2. Clone the template on the current thread
        //    (brief freeze acceptable — FAWE async paste recommended for large worlds)
        File cloned = plugin.getTemplateManager().cloneTemplateTo(instanceId);
        if (cloned == null) {
            players.forEach(p -> p.sendMessage(
                    "§c[DungeonRift] Failed to load dungeon. Please try again."));
            return;
        }

        // 3. Load the world via Multiverse
        MultiverseCore mv = (MultiverseCore) plugin.getServer()
                .getPluginManager().getPlugin("Multiverse-Core");

        if (mv == null) {
            log.severe("Multiverse-Core not found! Cannot load instance world.");
            return;
        }

        MVWorldManager wm = mv.getMVWorldManager();
        boolean loaded = wm.loadWorld(instanceId);

        if (!loaded) {
            log.severe("Multiverse failed to load world: " + instanceId);
            return;
        }

        World world = plugin.getServer().getWorld(instanceId);
        if (world == null) {
            log.severe("World loaded by MV but Bukkit cannot find it: " + instanceId);
            return;
        }

        // 4. Build the instance object
        String templateName = plugin.getTemplateManager().getActiveTemplateName();
        DungeonInstance di  = new DungeonInstance(instanceId, world, templateName, players);

        activeInstances.put(instanceId, di);
        players.forEach(p -> playerInstance.put(p.getUniqueId(), instanceId));

        // 5. Teleport players in & optionally clear their inventories
        Location spawnLoc = buildSpawnLocation(world);
        FileConfiguration cfg = plugin.getConfig();
        boolean clearOnEnter = cfg.getBoolean("loot.clear-on-enter", true);

        players.forEach(p -> {
            if (clearOnEnter) p.getInventory().clear();
            p.teleport(spawnLoc);
            p.sendMessage("§8[§6DungeonRift§8] §r"
                    + plugin.getConfig().getString("messages.instance-starting",
                        "§6The dungeon awaits. Prepare yourself."));
        });

        // 6. Start the 30-minute countdown
        di.startTimer();

        log.info("Instance spawned: " + instanceId
                + " | template: " + templateName
                + " | players: " + players.size());
    }

    // ── Hub return ────────────────────────────────────────────────────────────

    /**
     * Teleports a player back to the hub world.
     * Called on successful extraction, death, and server shutdown.
     */
    public void returnPlayerToHub(Player player) {
        playerInstance.remove(player.getUniqueId());

        Location hub = buildHubLocation();
        // Schedule one tick later so death respawn doesn't conflict
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            player.teleport(hub);
        }, 1L);
    }

    // ── Instance teardown ─────────────────────────────────────────────────────

    /**
     * Called by DungeonInstance.close() once all players are out.
     * Unloads and deletes the world folder.
     */
    public void destroyInstance(DungeonInstance instance) {
        String id = instance.getId();
        activeInstances.remove(id);

        // Small delay so any final teleports complete
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            unloadAndDeleteWorld(id);
        }, 40L); // 2 seconds
    }

    /** Emergency shutdown — closes every running instance immediately. */
    public void shutdownAll() {
        new HashSet<>(activeInstances.values())
                .forEach(di -> di.close("Server shutdown"));
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public DungeonInstance getInstanceForPlayer(UUID uuid) {
        String id = playerInstance.get(uuid);
        if (id == null) return null;
        return activeInstances.get(id);
    }

    public DungeonInstance getInstanceById(String id) {
        return activeInstances.get(id);
    }

    public Collection<DungeonInstance> getAllInstances() {
        return Collections.unmodifiableCollection(activeInstances.values());
    }

    public boolean isInInstance(UUID uuid) {
        return playerInstance.containsKey(uuid);
    }

    // ── Location helpers ──────────────────────────────────────────────────────

    private Location buildSpawnLocation(World world) {
        FileConfiguration cfg = plugin.getConfig();
        return new Location(world,
                cfg.getDouble("instance.instance-spawn.x",   0.5),
                cfg.getDouble("instance.instance-spawn.y",  65.0),
                cfg.getDouble("instance.instance-spawn.z",   0.5),
                (float) cfg.getDouble("instance.instance-spawn.yaw",   0),
                (float) cfg.getDouble("instance.instance-spawn.pitch", 0));
    }

    private Location buildHubLocation() {
        FileConfiguration cfg = plugin.getConfig();
        String hubName = cfg.getString("hub-world", "world_hub");
        World hub = plugin.getServer().getWorld(hubName);

        if (hub == null) {
            log.warning("Hub world '" + hubName + "' not found! Falling back to default world.");
            hub = plugin.getServer().getWorlds().get(0);
        }

        return new Location(hub,
                cfg.getDouble("hub-spawn.x",   0.5),
                cfg.getDouble("hub-spawn.y",  64.0),
                cfg.getDouble("hub-spawn.z",   0.5),
                (float) cfg.getDouble("hub-spawn.yaw",   0),
                (float) cfg.getDouble("hub-spawn.pitch", 0));
    }

    // ── World deletion ────────────────────────────────────────────────────────

    private void unloadAndDeleteWorld(String worldName) {
        MultiverseCore mv = (MultiverseCore) plugin.getServer()
                .getPluginManager().getPlugin("Multiverse-Core");

        if (mv != null) {
            // removeWorld unloads AND removes from MV's world list
            mv.getMVWorldManager().deleteWorld(worldName);
        } else {
            // Fallback: unload via Bukkit then delete folder manually
            World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                plugin.getServer().unloadWorld(world, false);
            }
            deleteFolder(new File(plugin.getServer().getWorldContainer(), worldName));
        }

        log.info("Instance world deleted: " + worldName);
    }

    private void deleteFolder(File folder) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) Arrays.stream(files).forEach(f -> {
            if (f.isDirectory()) deleteFolder(f);
            else f.delete();
        });
        folder.delete();
    }
}
