package com.dungeonrift;

import com.dungeonrift.command.DungeonCommand;
import com.dungeonrift.command.RiftCommand;
import com.dungeonrift.listener.ExtractionListener;
import com.dungeonrift.listener.InstanceLifecycleListener;
import com.dungeonrift.listener.PlayerDeathListener;
import com.dungeonrift.manager.InstanceManager;
import com.dungeonrift.manager.PartyManager;
import com.dungeonrift.manager.QueueManager;
import com.dungeonrift.manager.TemplateManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * DungeonRift – entry point.
 *
 * Manages the full extraction gameplay loop:
 *   Hub → Queue → Instance spawn → Loot → Extract/Die → Hub
 *
 * Depends on: Multiverse-Core, FastAsyncWorldEdit
 */
public class DungeonRift extends JavaPlugin {

    // ── Singletons ────────────────────────────────────────────────────────────
    private static DungeonRift instance;

    private TemplateManager templateManager;
    private InstanceManager instanceManager;
    private PartyManager    partyManager;
    private QueueManager    queueManager;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        // Boot managers in dependency order
        templateManager = new TemplateManager(this);
        instanceManager = new InstanceManager(this);
        partyManager    = new PartyManager(this);
        queueManager    = new QueueManager(this);

        // Register event listeners
        registerListeners();

        // Register commands
        registerCommands();

        getLogger().info("DungeonRift enabled. Active template: "
                + templateManager.getActiveTemplateName());
    }

    @Override
    public void onDisable() {
        // Safely shut down every running instance — teleports survivors to hub
        instanceManager.shutdownAll();
        getLogger().info("DungeonRift disabled. All instances closed.");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new ExtractionListener(this),        this);
        pm.registerEvents(new InstanceLifecycleListener(this), this);
        pm.registerEvents(new PlayerDeathListener(this),       this);
    }

    private void registerCommands() {
        getCommand("dungeon").setExecutor(new DungeonCommand(this));
        getCommand("rift").setExecutor(new RiftCommand(this));
    }

    // ── Static accessor ───────────────────────────────────────────────────────

    public static DungeonRift get() { return instance; }

    // ── Manager accessors ─────────────────────────────────────────────────────

    public TemplateManager getTemplateManager() { return templateManager; }
    public InstanceManager getInstanceManager() { return instanceManager; }
    public PartyManager    getPartyManager()    { return partyManager;    }
    public QueueManager    getQueueManager()    { return queueManager;    }
}
