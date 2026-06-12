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
 * Supports Multiverse-Core v4.x AND v5.x via reflection.
 * Falls back to plain Bukkit WorldCreator if neither works.
 */
public class InstanceManager {

    private final DungeonRift plugin;
    private final Logger      log;

    private final Map<String, DungeonInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<UUID, String>            playerInstance  = new ConcurrentHashMap<>();

    public InstanceManager(DungeonRift plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────

    public void spawnInstance(List<Player> players) {
        String instanceId = "dungeon_" + UUID.randomUUID().toString().substring(0, 8);

        File cloned = plugin.getTemplateManager().cloneTemplateTo(instanceId);
        if (cloned == null) {
            players.forEach(p -> p.sendMessage("§c[DungeonRift] Failed to load dungeon. Please try again."));
            return;
        }

        World world = loadWorld(instanceId);
        if (world == null) {
            log.severe("Could not load instance world: " + instanceId);
            deleteFolder(cloned);
            return;
        }

        String templateName = plugin.getTemplateManager().getActiveTemplateName();
        DungeonInstance di  = new DungeonInstance(instanceId, world, templateName, players);

        activeInstances.put(instanceId, di);
        players.forEach(p -> playerInstance.put(p.getUniqueId(), instanceId));

        Location spawnLoc    = buildSpawnLocation(world);
        boolean  clearOnEnter = plugin.getConfig().getBoolean("loot.clear-on-enter", true);

        players.forEach(p -> {
            if (clearOnEnter) p.getInventory().clear();
            p.teleport(spawnLoc);
            p.sendMessage("§8[§6DungeonRift§8] §r"
                    + plugin.getConfig().getString("messages.instance-starting",
                        "§6The dungeon awaits. Prepare yourself."));
        });

        di.startTimer();
        log.info("Instance spawned: " + instanceId + " | players: " + players.size());
    }

    // ── World loading ─────────────────────────────────────────────────────────

    private World loadWorld(String worldName) {
        Plugin mv = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");

        if (mv != null) {
            String mvVersion = mv.getDescription().getVersion();
            log.info("Detected Multiverse-Core v" + mvVersion);

            // Try MV5 API first: getMVWorldManager() → loadWorld(String)
            try {
                Object api = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
                Method load = findMethod(api.getClass(), "loadWorld", String.class);
                if (load != null) {
                    Object result = load.invoke(api, worldName);
                    World w = plugin.getServer().getWorld(worldName);
                    if (w != null) { log.info("Loaded via MV5 API"); return w; }
                }
            } catch (Exception ignored) {}

            // Try MV5 API variant: getWorldManager()
            try {
                Object api = mv.getClass().getMethod("getWorldManager").invoke(mv);
                Method load = findMethod(api.getClass(), "loadWorld", String.class);
                if (load != null) {
                    load.invoke(api, worldName);
                    World w = plugin.getServer().getWorld(worldName);
                    if (w != null) { log.info("Loaded via MV5 getWorldManager API"); return w; }
                }
            } catch (Exception ignored) {}

            // Try MV4 API: getMVWorldManager() → addWorld(String, ...)
            try {
                Object wm = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
                // MV4 addWorld: (name, env, seed, type, gen, adjustSpawn)
                Method addWorld = findMethod(wm.getClass(), "addWorld",
                        String.class, World.Environment.class, String.class,
                        org.bukkit.WorldType.class, Boolean.class, String.class);
                if (addWorld != null) {
                    addWorld.invoke(wm, worldName,
                            World.Environment.NORMAL, null,
                            org.bukkit.WorldType.NORMAL, false, null);
                    World w = plugin.getServer().getWorld(worldName);
                    if (w != null) { log.info("Loaded via MV4 addWorld API"); return w; }
                }
            } catch (Exception ignored) {}

            log.warning("All Multiverse reflection attempts failed — falling back to Bukkit.");
        }

        // Plain Bukkit fallback — always works
        World w = new WorldCreator(worldName).createWorld();
        if (w != null) log.info("Loaded world via Bukkit WorldCreator.");
        return w;
    }

    private void unloadAndDeleteWorld(String worldName) {
        Plugin mv = plugin.getServer().getPluginManager().getPlugin("Multiverse-Core");

        if (mv != null) {
            // MV5: deleteWorld(String)
            try {
                Object api = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
                Method del = findMethod(api.getClass(), "deleteWorld", String.class);
                if (del == null) del = findMethod(api.getClass(), "removeWorld", String.class);
                if (del != null) {
                    del.invoke(api, worldName);
                    log.info("Deleted instance via Multiverse: " + worldName);
                    return;
                }
            } catch (Exception ignored) {}

            try {
                Object api = mv.getClass().getMethod("getWorldManager").invoke(mv);
                Method del = findMethod(api.getClass(), "deleteWorld", String.class);
                if (del == null) del = findMethod(api.getClass(), "removeWorld", String.class);
                if (del != null) {
                    del.invoke(api, worldName);
                    log.info("Deleted instance via MV5 getWorldManager: " + worldName);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // Bukkit fallback
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) plugin.getServer().unloadWorld(world, false);
        deleteFolder(new File(plugin.getServer().getWorldContainer(), worldName));
        log.info("Deleted instance via Bukkit: " + worldName);
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
        activeInstances.remove(instance.getId());
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> unloadAndDeleteWorld(instance.getId()), 40L);
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

    public DungeonInstance getInstanceById(String id) { return activeInstances.get(id); }

    public Collection<DungeonInstance> getAllInstances() {
        return Collections.unmodifiableCollection(activeInstances.values());
    }

    public boolean isInInstance(UUID uuid) { return playerInstance.containsKey(uuid); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Search a class hierarchy for a method by name and parameter types. */
    private Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getMethod(name, params); } catch (NoSuchMethodException ignored) {}
            try { return c.getDeclaredMethod(name, params); } catch (NoSuchMethodException ignored) {}
            // Also try with no params if parameterised version not found
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

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
            log.warning("Hub world '" + hubName + "' not found! Using default world.");
            hub = plugin.getServer().getWorlds().get(0);
        }
        return new Location(hub,
                cfg.getDouble("hub-spawn.x",   0.5),
                cfg.getDouble("hub-spawn.y",  64.0),
                cfg.getDouble("hub-spawn.z",   0.5),
                (float) cfg.getDouble("hub-spawn.yaw",   0),
                (float) cfg.getDouble("hub-spawn.pitch", 0));
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
