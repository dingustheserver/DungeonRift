package com.dungeonrift.command;

import com.dungeonrift.DungeonRift;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /rift — player-facing commands.
 *
 * /rift queue          → join solo queue
 * /rift leave          → leave queue or current instance (bail out to hub)
 * /rift party create   → create a party
 * /rift party invite <player>  → invite someone
 * /rift party accept   → accept an invite
 * /rift party decline  → decline an invite
 * /rift party leave    → leave / disband party
 * /rift party ready    → leader queues the party
 */
public class RiftCommand implements CommandExecutor, TabCompleter {

    private final DungeonRift plugin;

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

            // ── /rift queue ────────────────────────────────────────────────
            case "queue" -> plugin.getQueueManager().enqueueSolo(player);

            // ── /rift leave ────────────────────────────────────────────────
            case "leave" -> {
                // If in queue, remove them
                plugin.getQueueManager().dequeue(player);

                // If in an instance, bail them out (counts as death / no loot)
                var instance = plugin.getInstanceManager()
                        .getInstanceForPlayer(player.getUniqueId());
                if (instance != null) {
                    player.sendMessage("§7You bailed out. Your loot is lost.");
                    instance.onPlayerDeath(player);
                } else {
                    player.sendMessage("§7You are not in a queue or dungeon.");
                }
            }

            // ── /rift party <sub> ──────────────────────────────────────────
            case "party" -> {
                if (args.length < 2) {
                    sendPartyHelp(player);
                    return true;
                }
                handleParty(player, args);
            }

            default -> sendHelp(player);
        }
        return true;
    }

    private void handleParty(Player player, String[] args) {
        var pm = plugin.getPartyManager();

        switch (args[1].toLowerCase()) {

            case "create" -> pm.createParty(player);

            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /rift party invite <player>");
                    return;
                }
                Player target = plugin.getServer().getPlayer(args[2]);
                if (target == null) {
                    player.sendMessage("§cPlayer not found: " + args[2]);
                    return;
                }
                pm.invitePlayer(player, target);
            }

            case "accept"  -> pm.acceptInvite(player);
            case "decline" -> pm.declineInvite(player);
            case "leave"   -> pm.leaveParty(player);
            case "ready"   -> pm.queueParty(player);

            default -> sendPartyHelp(player);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (args.length == 1) return List.of("queue", "leave", "party");
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            return List.of("create", "invite", "accept", "decline", "leave", "ready");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("invite")) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).toList();
        }
        return List.of();
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== DungeonRift ===");
        player.sendMessage("§e/rift queue        §7- Enter solo");
        player.sendMessage("§e/rift leave        §7- Bail out");
        player.sendMessage("§e/rift party        §7- Party options");
    }

    private void sendPartyHelp(Player player) {
        player.sendMessage("§6=== Party Commands ===");
        player.sendMessage("§e/rift party create          §7- Create a party");
        player.sendMessage("§e/rift party invite <name>   §7- Invite a player");
        player.sendMessage("§e/rift party accept          §7- Accept invite");
        player.sendMessage("§e/rift party decline         §7- Decline invite");
        player.sendMessage("§e/rift party leave           §7- Leave / disband");
        player.sendMessage("§e/rift party ready           §7- Queue for dungeon");
    }
}
