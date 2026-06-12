package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * InstanceManager
 *
 * Loads worlds via Bukkit WorldCreator (plain, no Multiverse needed).
 * Multiverse is used only for world cleanup on teardown.
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

        // Load via plain Bukkit — uid.dat was excluded from clone so no duplicate error
        World world = new WorldCreator(instanceId).createWorld();

        if (world == null) {
            log.severe("Could not load instance world: " + instanceId);
            deleteFolder(cloned);
            return;
        }

        log.info("Instance world loaded: " + instanceId);

        String templateName = plugin.getTemplateManager().getActiveTemplateName();
        DungeonInstance di  = new DungeonInstance(instanceId, world, templateName, players);

        activeInstances.put(instanceId, di);
        players.forEach(p -> playerInstance.put(p.getUniqueId(), instanceId));

        Location spawnLoc     = buildSpawnLocation(world);
        boolean  clearOnEnter = plugin.getConfig().getBoolean("loot.clear-on-enter", true);

        players.forEach(p -> {
            if (clearOnEnter) p.getInventory().clear();
            p.teleport(spawnLoc);
        });

        di.startTimer();
        log.info("Instance started: " + instanceId + " | players: " + players.size());
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

    private void unloadAndDeleteWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            // Move any lingering players out first
            Location hub = buildHubLocation();
            world.getPlayers().forEach(p -> p.teleport(hub));
            Bukkit.unloadWorld(world, false);
        }
        deleteFolder(new File(Bukkit.getWorldContainer(), worldName));
        log.info("Instance world deleted: " + worldName);
    }

    // ── Hub return ────────────────────────────────────────────────────────────

    public void returnPlayerToHub(Player player) {
        playerInstance.remove(player.getUniqueId());
        Location hub = buildHubLocation();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.teleport(hub);
        }, 1L);
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
        World hub = Bukkit.getWorld(hubName);
        if (hub == null) {
            log.warning("Hub world '" + hubName + "' not found! Using default world.");
            hub = Bukkit.getWorlds().get(0);
        }
        return new Location(hub,
                cfg.getDouble("hub-spawn.x",   0.5),
                cfg.getDouble("hub-spawn.y",  64.0),
                cfg.getDouble("hub-spawn.z",   0.5),
                (float) cfg.getDouble("hub-spawn.yaw",   0),
                (float) cfg.getDouble("hub-spawn.pitch", 0));
    }

    // ── File helpers ──────────────────────────────────────────────────────────

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
