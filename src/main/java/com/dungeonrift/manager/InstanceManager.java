package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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

        if (clearOnEnter) players.forEach(p -> p.getInventory().clear());

        // teleportAsync handles cross-world moves correctly in Paper 1.21.
        // We fire all teleports simultaneously then apply effects once each
        // individual future completes, so no player is left behind.
        int[] remaining = {players.size()};
        for (Player p : players) {
            p.teleportAsync(spawnLoc).thenAccept(success -> {
                if (success) {
                    // Back on main thread for Bukkit API calls
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        applyEntryEffects(p);
                    });
                } else {
                    log.warning("Failed to teleport " + p.getName() + " into instance " + instanceId);
                }
                // Start timer once ALL players have been processed
                synchronized (remaining) {
                    remaining[0]--;
                    if (remaining[0] <= 0) {
                        plugin.getServer().getScheduler().runTask(plugin, di::startTimer);
                    }
                }
            });
        }

        log.info("Instance started: " + instanceId + " | players: " + players.size());
    }

    /**
     * Applied the moment a player loads into the dungeon:
     *  - Blindness for 3 seconds
     *  - Slowness V for 3 seconds
     *  - Beacon creation sound
     */
    private void applyEntryEffects(Player player) {
        int durationTicks = 60; // 3 seconds

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, durationTicks, 0, false, false));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS, durationTicks, 4, false, false)); // amplifier 4 = Slowness V

        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
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
            Location hub = buildHubLocation();
            world.getPlayers().forEach(p -> p.teleport(hub));
            Bukkit.unloadWorld(world, false);
        }
        deleteFolder(new File(Bukkit.getWorldContainer(), worldName));
        log.info("Instance world deleted: " + worldName);
    }

    // ── Hub return ────────────────────────────────────────────────────────────

    /**
     * Returns a player to the hub.
     * @param playSuccessSound if true, plays UI_TOAST_CHALLENGE_COMPLETE after
     *                         they arrive — used for successful extraction only.
     */
    public void returnPlayerToHub(Player player, boolean playSuccessSound) {
        playerInstance.remove(player.getUniqueId());
        Location hub = buildHubLocation();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.teleport(hub);

            if (playSuccessSound) {
                // Small extra delay so the teleport fully completes first
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(),
                                Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    }
                }, 5L);
            }
        }, 1L);
    }

    /** Convenience overload — no success sound (death, forfeit, shutdown). */
    public void returnPlayerToHub(Player player) {
        returnPlayerToHub(player, false);
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
