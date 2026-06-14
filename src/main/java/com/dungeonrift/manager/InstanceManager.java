package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
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

        // Snapshot inventories NOW before teleport — Paper 1.21 cross-world
        // teleport clears inventory as part of world transfer, so we capture
        // each player's items here and restore them after the teleport lands.
        boolean clearOnEnter = plugin.getConfig().getBoolean("loot.clear-on-enter", false);
        Map<UUID, ItemStack[]> inventorySnapshots = new HashMap<>();
        Map<UUID, ItemStack[]> armorSnapshots     = new HashMap<>();
        Map<UUID, ItemStack>   offhandSnapshots   = new HashMap<>();

        if (!clearOnEnter) {
            players.forEach(p -> {
                inventorySnapshots.put(p.getUniqueId(), p.getInventory().getContents().clone());
                armorSnapshots.put(p.getUniqueId(),     p.getInventory().getArmorContents().clone());
                offhandSnapshots.put(p.getUniqueId(),   p.getInventory().getItemInOffHand().clone());
            });
        }

        String templateName = plugin.getTemplateManager().getActiveTemplateName();
        DungeonInstance di  = new DungeonInstance(instanceId, world, templateName, players);

        activeInstances.put(instanceId, di);
        players.forEach(p -> playerInstance.put(p.getUniqueId(), instanceId));

        Location spawnLoc = buildSpawnLocation(world);

        // Staggered teleport — 2 ticks between each player, then restore inventory
        List<Player> snapshot = new ArrayList<>(players);
        for (int i = 0; i < snapshot.size(); i++) {
            final Player p     = snapshot.get(i);
            final long   delay = (i * 2L) + 1L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.teleport(spawnLoc);

                // Restore inventory 2 ticks after teleport so the world transfer is complete
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    if (!clearOnEnter) {
                        ItemStack[] inv     = inventorySnapshots.get(p.getUniqueId());
                        ItemStack[] armor   = armorSnapshots.get(p.getUniqueId());
                        ItemStack   offhand = offhandSnapshots.get(p.getUniqueId());
                        if (inv     != null) p.getInventory().setContents(inv);
                        if (armor   != null) p.getInventory().setArmorContents(armor);
                        if (offhand != null) p.getInventory().setItemInOffHand(offhand);
                        p.updateInventory();
                    }
                    applyEntryEffects(p);
                    log.info("Teleported " + p.getName() + " into " + instanceId);
                }, 2L);
            }, delay);
        }

        long timerDelay = (snapshot.size() * 2L) + 5L;
        plugin.getServer().getScheduler().runTaskLater(plugin, di::startTimer, timerDelay);

        log.info("Instance started: " + instanceId + " | players: " + players.size());
    }

    private void applyEntryEffects(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,  60, 4, false, false));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    public void destroyInstance(DungeonInstance instance) {
        activeInstances.remove(instance.getId());
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> unloadAndDeleteWorld(instance.getId()), 40L);
    }

    public void shutdownAll() {
        new HashSet<>(activeInstances.values()).forEach(di -> di.close("Server shutdown"));
    }

    private void unloadAndDeleteWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            world.getPlayers().forEach(p -> p.teleport(buildHubReturnLocation()));
            Bukkit.unloadWorld(world, false);
        }
        deleteFolder(new File(Bukkit.getWorldContainer(), worldName));
        log.info("Instance world deleted: " + worldName);
    }

    // ── Hub return ────────────────────────────────────────────────────────────

    /**
     * Returns a player to the hub.
     * Always teleports to hub-return location.
     * @param playSuccessSound plays challenge complete sound on arrival (extraction only)
     */
    public void returnPlayerToHub(Player player, boolean playSuccessSound) {
        playerInstance.remove(player.getUniqueId());
        Location hub = buildHubReturnLocation();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.teleport(hub);

            if (playSuccessSound) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline())
                        player.playSound(player.getLocation(),
                                Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                }, 5L);
            }
        }, 1L);
    }

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

    // ── Location builders ─────────────────────────────────────────────────────

    private Location buildSpawnLocation(World world) {
        FileConfiguration cfg = plugin.getConfig();
        return new Location(world,
                cfg.getDouble("instance.instance-spawn.x",    0.5),
                cfg.getDouble("instance.instance-spawn.y",   65.0),
                cfg.getDouble("instance.instance-spawn.z",    0.5),
                (float) cfg.getDouble("instance.instance-spawn.yaw",   0),
                (float) cfg.getDouble("instance.instance-spawn.pitch", 0));
    }

    /** Hub world general spawn (first join) */
    public Location buildHubSpawnLocation() {
        FileConfiguration cfg = plugin.getConfig();
        String hubName = cfg.getString("hub-world", "world_hub");
        World hub = Bukkit.getWorld(hubName);
        if (hub == null) { hub = Bukkit.getWorlds().get(0); }
        return new Location(hub,
                cfg.getDouble("hub-spawn.x",   0.5),
                cfg.getDouble("hub-spawn.y",  64.0),
                cfg.getDouble("hub-spawn.z",   0.5),
                (float) cfg.getDouble("hub-spawn.yaw",   0),
                (float) cfg.getDouble("hub-spawn.pitch", 0));
    }

    /** Hub return point — where players arrive after a rift */
    public Location buildHubReturnLocation() {
        FileConfiguration cfg = plugin.getConfig();
        String hubName = cfg.getString("hub-world", "world_hub");
        World hub = Bukkit.getWorld(hubName);
        if (hub == null) { hub = Bukkit.getWorlds().get(0); }
        return new Location(hub,
                cfg.getDouble("hub-return.x",   0.5),
                cfg.getDouble("hub-return.y",  64.0),
                cfg.getDouble("hub-return.z",   0.5),
                (float) cfg.getDouble("hub-return.yaw",   0),
                (float) cfg.getDouble("hub-return.pitch", 0));
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
