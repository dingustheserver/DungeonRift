package com.dungeonrift.command;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/**
 * /dungeon — admin commands.
 *
 * /dungeon set <template>       → swap active template
 * /dungeon list                 → list templates on disk
 * /dungeon status               → show running instances
 * /dungeon reload               → reload config
 * /dungeon close <id>           → force-close an instance
 * /dungeon setspawn             → set instance spawn to your current position
 * /dungeon setextraction        → set extraction portal to your current position
 */
public class DungeonCommand implements CommandExecutor, TabCompleter {

    private final DungeonRift plugin;

    public DungeonCommand(DungeonRift plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {

        if (!sender.hasPermission("dungeonrift.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {

            // ── /dungeon set <template> ────────────────────────────────────────
            case "set" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /dungeon set <template_name>");
                    return true;
                }
                boolean ok = plugin.getTemplateManager().setActiveTemplate(args[1]);
                if (ok) {
                    sender.sendMessage("§aActive template changed to: §e" + args[1]);
                    sender.sendMessage("§7New instances will use this template.");
                } else {
                    sender.sendMessage("§cTemplate not found: " + args[1]);
                    sender.sendMessage("§7Available: " + plugin.getTemplateManager().listTemplates());
                }
            }

            // ── /dungeon list ──────────────────────────────────────────────────
            case "list" -> {
                List<String> templates = plugin.getTemplateManager().listTemplates();
                String active = plugin.getTemplateManager().getActiveTemplateName();
                sender.sendMessage("§6=== Dungeon Templates ===");
                if (templates.isEmpty()) {
                    sender.sendMessage("§7No templates found in templates/ folder.");
                } else {
                    templates.forEach(t -> {
                        String marker = t.equals(active) ? " §a◀ active" : "";
                        sender.sendMessage("§7- §e" + t + marker);
                    });
                }
            }

            // ── /dungeon status ────────────────────────────────────────────────
            case "status" -> {
                Collection<DungeonInstance> instances = plugin.getInstanceManager().getAllInstances();
                sender.sendMessage("§6=== Running Instances (" + instances.size() + ") ===");
                sender.sendMessage("§7Active template: §e" + plugin.getTemplateManager().getActiveTemplateName());
                if (instances.isEmpty()) {
                    sender.sendMessage("§7No instances running.");
                } else {
                    instances.forEach(di -> {
                        int mins = di.getSecondsLeft() / 60;
                        int secs = di.getSecondsLeft() % 60;
                        sender.sendMessage(String.format(
                                "§7[§e%s§7] players=§a%d §7time=§c%02d:%02d §7state=§f%s",
                                di.getId(), di.getAlivePlayers().size(), mins, secs, di.getState()));
                    });
                }
            }

            // ── /dungeon reload ────────────────────────────────────────────────
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§aConfig reloaded.");
            }

            // ── /dungeon close <id> ────────────────────────────────────────────
            case "close" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /dungeon close <instance_id>");
                    return true;
                }
                DungeonInstance di = plugin.getInstanceManager().getInstanceById(args[1]);
                if (di == null) {
                    sender.sendMessage("§cInstance not found: " + args[1]);
                } else {
                    di.close("Closed by admin: " + sender.getName());
                    sender.sendMessage("§aInstance closed: " + args[1]);
                }
            }

            // ── /dungeon setspawn ──────────────────────────────────────────────
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cMust be run by a player.");
                    return true;
                }
                Location loc = player.getLocation();
                plugin.getConfig().set("instance.instance-spawn.x",     loc.getX());
                plugin.getConfig().set("instance.instance-spawn.y",     loc.getY());
                plugin.getConfig().set("instance.instance-spawn.z",     loc.getZ());
                plugin.getConfig().set("instance.instance-spawn.yaw",   (double) loc.getYaw());
                plugin.getConfig().set("instance.instance-spawn.pitch", (double) loc.getPitch());
                plugin.saveConfig();

                sender.sendMessage("§aInstance spawn point set to:");
                sender.sendMessage(formatLocation(loc));
                sender.sendMessage("§7Players will spawn here when they enter a dungeon instance.");
            }

            // ── /dungeon setextraction ─────────────────────────────────────────
            case "setextraction" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cMust be run by a player.");
                    return true;
                }
                Location loc = player.getLocation();
                plugin.getConfig().set("instance.extraction-portal.x", loc.getX());
                plugin.getConfig().set("instance.extraction-portal.y", loc.getY());
                plugin.getConfig().set("instance.extraction-portal.z", loc.getZ());
                plugin.saveConfig();

                sender.sendMessage("§aExtraction portal centre set to:");
                sender.sendMessage(formatLocation(loc));
                sender.sendMessage("§7Radius: §e"
                        + plugin.getConfig().getDouble("instance.extraction-zone-radius", 3.0)
                        + " blocks");
                sender.sendMessage("§7Tip: adjust §einstance.extraction-zone-radius §7in config.yml to change the trigger area.");
            }

            // ── /dungeon setextractionradius <n> ──────────────────────────────
            case "setextractionradius" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /dungeon setextractionradius <blocks>");
                    return true;
                }
                try {
                    double radius = Double.parseDouble(args[1]);
                    if (radius < 1 || radius > 20) {
                        sender.sendMessage("§cRadius must be between 1 and 20.");
                        return true;
                    }
                    plugin.getConfig().set("instance.extraction-zone-radius", radius);
                    plugin.saveConfig();
                    sender.sendMessage("§aExtraction zone radius set to §e" + radius + " blocks§a.");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid number: " + args[1]);
                }
            }

            // ── /dungeon locations ─────────────────────────────────────────────
            case "locations" -> {
                double sx = plugin.getConfig().getDouble("instance.instance-spawn.x");
                double sy = plugin.getConfig().getDouble("instance.instance-spawn.y");
                double sz = plugin.getConfig().getDouble("instance.instance-spawn.z");
                double ex = plugin.getConfig().getDouble("instance.extraction-portal.x");
                double ey = plugin.getConfig().getDouble("instance.extraction-portal.y");
                double ez = plugin.getConfig().getDouble("instance.extraction-portal.z");
                double er = plugin.getConfig().getDouble("instance.extraction-zone-radius", 3.0);

                sender.sendMessage("§6=== DungeonRift Locations ===");
                sender.sendMessage("§eInstance spawn:   §7" + fmt(sx) + ", " + fmt(sy) + ", " + fmt(sz));
                sender.sendMessage("§eExtraction portal: §7" + fmt(ex) + ", " + fmt(ey) + ", " + fmt(ez)
                        + " §8(radius: " + er + ")");
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    // ── Tab complete ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return List.of("set", "list", "status", "reload", "close",
                    "setspawn", "setextraction", "setextractionradius", "locations");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return plugin.getTemplateManager().listTemplates();
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== DungeonRift Admin ===");
        sender.sendMessage("§e/dungeon set <template>          §7- Swap active dungeon");
        sender.sendMessage("§e/dungeon list                    §7- List templates");
        sender.sendMessage("§e/dungeon status                  §7- Running instances");
        sender.sendMessage("§e/dungeon reload                  §7- Reload config");
        sender.sendMessage("§e/dungeon close <id>              §7- Force-close instance");
        sender.sendMessage("§e/dungeon setspawn                §7- Set player spawn point §8(stand there first)");
        sender.sendMessage("§e/dungeon setextraction           §7- Set extraction portal §8(stand there first)");
        sender.sendMessage("§e/dungeon setextractionradius <n> §7- Set portal trigger radius");
        sender.sendMessage("§e/dungeon locations               §7- Show current locations");
    }

    private String formatLocation(Location loc) {
        return "§7X: §f" + fmt(loc.getX())
             + " §7Y: §f" + fmt(loc.getY())
             + " §7Z: §f" + fmt(loc.getZ())
             + " §7Yaw: §f" + fmt(loc.getYaw());
    }

    private String fmt(double v) { return String.format("%.2f", v); }
    private String fmt(float v)  { return String.format("%.1f", v); }
}
