package com.dungeonrift.command;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.DungeonInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collection;
import java.util.List;

/**
 * /dungeon — admin-only management commands.
 *
 * /dungeon set <template>   → swap active template (weekly rotation)
 * /dungeon list             → list available templates on disk
 * /dungeon status           → show all running instances
 * /dungeon reload           → reload config.yml
 * /dungeon close <id>       → force-close a specific instance
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

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            // ── /dungeon set <template> ────────────────────────────────────
            case "set" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /dungeon set <template_name>");
                    return true;
                }
                String name = args[1];
                boolean success = plugin.getTemplateManager().setActiveTemplate(name);
                if (success) {
                    sender.sendMessage("§aActive template changed to: §e" + name);
                    sender.sendMessage("§7New instances will use this template.");
                    sender.sendMessage("§7Running instances are unaffected.");
                } else {
                    sender.sendMessage("§cTemplate not found: " + name);
                    sender.sendMessage("§7Available: " + plugin.getTemplateManager()
                            .listTemplates());
                }
            }

            // ── /dungeon list ──────────────────────────────────────────────
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

            // ── /dungeon status ────────────────────────────────────────────
            case "status" -> {
                Collection<DungeonInstance> instances =
                        plugin.getInstanceManager().getAllInstances();

                sender.sendMessage("§6=== Running Instances (" + instances.size() + ") ===");
                sender.sendMessage("§7Active template: §e"
                        + plugin.getTemplateManager().getActiveTemplateName());

                if (instances.isEmpty()) {
                    sender.sendMessage("§7No instances running.");
                } else {
                    instances.forEach(di -> {
                        int mins = di.getSecondsLeft() / 60;
                        int secs = di.getSecondsLeft() % 60;
                        sender.sendMessage(String.format(
                                "§7[§e%s§7] template=§a%s §7players=§a%d §7time=§c%02d:%02d §7state=§f%s",
                                di.getId(), di.getTemplateName(),
                                di.getAlivePlayers().size(),
                                mins, secs, di.getState()
                        ));
                    });
                }
            }

            // ── /dungeon reload ────────────────────────────────────────────
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§aConfig reloaded.");
            }

            // ── /dungeon close <id> ────────────────────────────────────────
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

            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return List.of("set", "list", "status", "reload", "close");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return plugin.getTemplateManager().listTemplates();
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== DungeonRift Admin ===");
        sender.sendMessage("§e/dungeon set <template>  §7- Swap active dungeon");
        sender.sendMessage("§e/dungeon list            §7- List available templates");
        sender.sendMessage("§e/dungeon status          §7- Show running instances");
        sender.sendMessage("§e/dungeon reload          §7- Reload config");
        sender.sendMessage("§e/dungeon close <id>      §7- Force-close an instance");
    }
}
