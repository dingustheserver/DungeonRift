package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * InstanceManager
 *
 * Spawns, tracks, and tears down dungeon instances.
 *
 * Multiverse-Core is called via reflection so we don't need it as a
 * compile-time dependency (it has no public Maven artifact).
 * If Multiverse is not installed the plugin falls back to plain Bukkit
 * world loading — fully functional either way.
 */
public class InstanceManager {

    private final DungeonRift plugin;
    private final Logger      log;

    /** instanceId → DungeonInstance */
    private final Map<String, DungeonInstance> activeInstances = new ConcurrentHashMap<>();

    /** playerUUID → instanceId */
    private final Map<UUID, String> playerInstance = new ConcurrentHashMap<>();

    public InstanceManager(DungeonRift plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────

    public void spawnInstance(List<Player> players) {
        String instanceId = "dungeon_" + UUID.randomUUID().toString().substring(0, 8);

        // Clone template files
        File cloned = plugin.getTemplateManager().cloneTemplateTo(instanceId);
        if (cloned == null) {
            players.forEach(p -> p.sendMessage("§c[DungeonRift] Failed to load dungeon. Please try again."));
            return;
        }

        // Load world (Multiverse if available, otherwise plain Bukkit)
        World world = loadWorld(instanceId);
        if (world == null) {
            log.severe("Could not load instance world: " + instanceId);
            deleteFolder(cloned);
            return;
        }

        // Build instance and register players
        String templateName = plugin.getTemplateManager().getActiveTemplateName();
        DungeonInstance di  = new DungeonInstance(instanceId, world, templateName, players);

        activeInstances.put(instanceId, di);
        players.forEach(p -> playerInstance.put(p.getUniqueId(), instanceId));

        // Teleport in and optionally clear inventories
        Location spawnLoc   = buildSpawnLocation(world);
        boolean clearOnEnter = plugin.getConfig().getBoolean("loot.clear-on-enter", true);

        players.forEach(p -> {
            if (clearOnEnter) p.getInventory().clear();
            p.teleport(spawnLoc);
            p.sendMessage("§8[§6DungeonRift§8] §r"
                    + plugin.getConfig().getString("messages.instance-starting",
                        "§6The dungeon awaits. Prepare yourself."));
        });

        di.startTimer();

        log.info("Instance spawned: " + instanceId
                + " | template: " + templateName
                + " | players: " + players.size());
    }

    // ── World loading (Multiverse-agnostic) ───────────────────────────────────

    /**
     * Loads a world by name.
     * Tries Multiverse first (via reflection); falls back to Bukkit WorldCreator.
     */
    private World loadWorld(String worldName) {
        // Try Multiverse via reflection — no compile-time import needed
        Plugin mv = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");
        if (mv != null) {
            try {
                Object mvWorldManager = mv.getClass()
                        .getMethod("getMVWorldManager")
                        .invoke(mv);

                Method loadWorld = mvWorldManager.getClass()
                        .getMethod("loadWorld", String.class);

                boolean ok = (boolean) loadWorld.invoke(mvWorldManager, worldName);
                if (ok) {
                    World w = plugin.getServer().getWorld(worldName);
                    if (w != null) return w;
                }
                log.warning("Multiverse failed to load world, falling back to Bukkit.");
            } catch (Exception e) {
                log.warning("Multiverse reflection failed (" + e.getMessage() + "), using Bukkit fallback.");
            }
        }

        // Plain Bukkit fallback
        return new WorldCreator(worldName).createWorld();
    }

    /**
     * Unloads and deletes a world.
     * Tries Multiverse first; falls back to Bukkit + manual folder delete.
     */
    private void unloadAndDeleteWorld(String worldName) {
        Plugin mv = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");
        if (mv != null) {
            try {
                Object mvWorldManager = mv.getClass()
                        .getMethod("getMVWorldManager")
                        .invoke(mv);

                Method deleteWorld = mvWorldManager.getClass()
                        .getMethod("deleteWorld", String.class);

                deleteWorld.invoke(mvWorldManager, worldName);
                log.info("Instance world deleted via Multiverse: " + worldName);
                return;
            } catch (Exception e) {
                log.warning("Multiverse delete failed, using Bukkit fallback: " + e.getMessage());
            }
        }

        // Bukkit fallback: unload then delete folder
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) plugin.getServer().unloadWorld(world, false);
        deleteFolder(new File(plugin.getServer().getWorldContainer(), worldName));
        log.info("Instance world deleted via Bukkit: " + worldName);
    }

    // ── Hub return ────────────────────────────────────────────────────────────

    public void returnPlayerToHub(Player player) {
        playerInstance.remove(player.getUniqueId());
        Location hub = buildHubLocation();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.teleport(hub);
        }, 1L);
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    public void destroyInstance(DungeonInstance instance) {
        String id = instance.getId();
        activeInstances.remove(id);

        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                unloadAndDeleteWorld(id), 40L);
    }

    public void shutdownAll() {
        new HashSet<>(activeInstances.values())
                .forEach(di -> di.close("Server shutdown"));
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public DungeonInstance getInstanceForPlayer(UUID uuid) {
        String id = playerInstance.get(uuid);
        return id == null ? null : activeInstances.get(id);
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

    // ── File deletion ─────────────────────────────────────────────────────────

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
