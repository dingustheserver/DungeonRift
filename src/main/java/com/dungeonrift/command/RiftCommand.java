package com.dungeonrift.command;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /rift — player commands.
 *
 * /rift queue              → join solo queue
 * /rift leave              → shows warning, asks for confirmation
 * /rift leave confirm      → confirms leave (loses all loot)
 * /rift party <sub>        → party management
 */
public class RiftCommand implements CommandExecutor, TabCompleter {

    private final DungeonRift plugin;

    /**
     * Players who have typed /rift leave and are waiting to confirm.
     * Maps UUID → expiry task (auto-cancels after 15 seconds).
     */
    private final Map<UUID, BukkitTask> pendingLeave = new HashMap<>();

    public RiftCommand(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "queue" -> handleQueue(player);
            case "leave" -> handleLeave(player, args);
            case "party" -> {
                if (args.length < 2) { sendPartyHelp(player); return true; }
                handleParty(player, args);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    private void handleQueue(Player player) {
        plugin.getQueueManager().enqueueSolo(player);
    }

    // ── Leave (with confirmation) ─────────────────────────────────────────────

    private void handleLeave(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        // Not in an instance or queue — nothing to leave
        DungeonInstance instance = plugin.getInstanceManager()
                .getInstanceForPlayer(uuid);
        boolean inQueue = plugin.getQueueManager().isQueued(uuid);

        if (instance == null && !inQueue) {
            player.sendMessage("§c[DungeonRift] You are not in a dungeon or queue.");
            return;
        }

        // Just in queue — remove silently, no loot risk
        if (instance == null) {
            plugin.getQueueManager().dequeue(player);
            player.sendMessage("§7[DungeonRift] Removed from queue.");
            return;
        }

        // Check if they typed "confirm" as second arg
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            if (pendingLeave.containsKey(uuid)) {
                // Cancel the expiry task
                pendingLeave.remove(uuid).cancel();
                // Forfeit — lose all loot
                instance.onPlayerForfeit(player);
            } else {
                player.sendMessage("§c[DungeonRift] No pending leave request. Type §e/rift leave §cfirst.");
            }
            return;
        }

        // First /rift leave — show warning and start 15-second confirmation window
        if (pendingLeave.containsKey(uuid)) {
            // Already waiting — remind them
            player.sendMessage("§c[DungeonRift] Type §e/rift leave confirm §cto abandon the rift.");
            player.sendMessage("§7Warning: §cAll loot will be lost!");
            return;
        }

        // Show big warning
        player.sendTitle("§c§lABANDON RIFT?", "§7All loot will be lost forever!", 10, 80, 20);
        player.sendMessage("");
        player.sendMessage("§c§l⚠ WARNING ⚠");
        player.sendMessage("§7You are about to abandon the rift.");
        player.sendMessage("§c§lAll loot collected will be permanently lost!");
        player.sendMessage("§7Type §e/rift leave confirm §7within §c15 seconds §7to proceed.");
        player.sendMessage("§7Or ignore this message to stay in the rift.");
        player.sendMessage("");

        // Schedule auto-expiry after 15 seconds
        BukkitTask expiry = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingLeave.remove(uuid) != null) {
                player.sendMessage("§7[DungeonRift] Leave request expired. You remain in the rift.");
                player.resetTitle();
            }
        }, 15 * 20L);

        pendingLeave.put(uuid, expiry);
    }

    // ── Party ─────────────────────────────────────────────────────────────────

    private void handleParty(Player player, String[] args) {
        var pm = plugin.getPartyManager();
        switch (args[1].toLowerCase()) {
            case "create"  -> pm.createParty(player);
            case "invite"  -> {
                if (args.length < 3) { player.sendMessage("§cUsage: /rift party invite <player>"); return; }
                Player target = plugin.getServer().getPlayer(args[2]);
                if (target == null) { player.sendMessage("§cPlayer not found: " + args[2]); return; }
                pm.invitePlayer(player, target);
            }
            case "accept"  -> pm.acceptInvite(player);
            case "decline" -> pm.declineInvite(player);
            case "leave"   -> pm.leaveParty(player);
            case "ready"   -> pm.queueParty(player);
            case "list"    -> pm.listParty(player);
            default        -> sendPartyHelp(player);
        }
    }

    // ── Tab complete ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (args.length == 1) return List.of("queue", "leave", "party");
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("leave"))  return List.of("confirm");
            if (args[0].equalsIgnoreCase("party"))
                return List.of("create", "invite", "accept", "decline", "leave", "ready", "list");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("invite")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage("§6=== DungeonRift ===");
        player.sendMessage("§e/rift queue          §7- Enter solo");
        player.sendMessage("§e/rift leave          §7- Abandon rift (loot lost)");
        player.sendMessage("§e/rift leave confirm  §7- Confirm abandonment");
        player.sendMessage("§e/rift party          §7- Party options");
    }

    private void sendPartyHelp(Player player) {
        player.sendMessage("§6=== Party Commands ===");
        player.sendMessage("§e/rift party create          §7- Create a party");
        player.sendMessage("§e/rift party invite <name>   §7- Invite a player");
        player.sendMessage("§e/rift party accept          §7- Accept invite");
        player.sendMessage("§e/rift party decline         §7- Decline invite");
        player.sendMessage("§e/rift party leave           §7- Leave / disband");
        player.sendMessage("§e/rift party ready           §7- Queue for dungeon");
        player.sendMessage("§e/rift party list            §7- Show party members");
    }
}
