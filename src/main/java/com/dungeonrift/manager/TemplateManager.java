package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * TemplateManager
 *
 * Keeps track of which dungeon template is currently active and exposes
 * a method to clone it into a fresh world folder for each new instance.
 *
 * Template worlds are stored as plain world folders inside:
 *   /plugins/DungeonRift/templates/<template_name>/
 *
 * Swapping the active dungeon (weekly rotation):
 *   /dungeon set <template_name>
 *   → updates config, next instance spawn uses new template automatically.
 *   In-flight instances are unaffected — they already have their own copy.
 */
public class TemplateManager {

    private final DungeonRift plugin;
    private final Logger      log;
    private final File        templatesDir;

    /** Name of the folder currently selected as the source template. */
    private String activeTemplateName;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TemplateManager(DungeonRift plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();

        FileConfiguration cfg = plugin.getConfig();

        String templatesPath = cfg.getString("templates-directory", "templates");
        templatesDir = new File(plugin.getDataFolder(), templatesPath);

        if (!templatesDir.exists()) {
            templatesDir.mkdirs();
            log.info("Created templates directory: " + templatesDir.getAbsolutePath());
        }

        activeTemplateName = cfg.getString("active-template", "dungeon_week1");
        log.info("Active template: " + activeTemplateName);
    }

    // ── Template resolution ───────────────────────────────────────────────────

    /**
     * Returns the File pointing to the currently active template folder.
     * Returns null if the folder does not exist (misconfigured / not uploaded).
     */
    public File getActiveTemplateFolder() {
        File folder = new File(templatesDir, activeTemplateName);
        if (!folder.exists() || !folder.isDirectory()) {
            log.warning("Active template folder not found: " + folder.getAbsolutePath());
            return null;
        }
        return folder;
    }

    /**
     * Copies the active template into a new world folder under the server root.
     *
     * This is a recursive file copy — fast for typical dungeon sizes (<500MB).
     * For very large worlds you should replace this with a FAWE schematic paste.
     *
     * @param destinationName  World folder name (e.g. "dungeon_abc123")
     * @return                 The destination folder, or null on failure
     */
    public File cloneTemplateTo(String destinationName) {
        File source = getActiveTemplateFolder();
        if (source == null) return null;

        // Destination is at the server root (where Multiverse loads worlds from)
        File destination = new File(plugin.getServer().getWorldContainer(), destinationName);
        if (destination.exists()) {
            log.warning("Destination world folder already exists: " + destinationName);
            return destination;
        }

        try {
            copyDirectory(source, destination);
            log.info("Cloned template '" + activeTemplateName + "' → '" + destinationName + "'");
            return destination;
        } catch (Exception e) {
            log.severe("Failed to clone template: " + e.getMessage());
            return null;
        }
    }

    // ── Weekly rotation ───────────────────────────────────────────────────────

    /**
     * Swap the active template. Persists to config.yml immediately.
     * New instances spawned after this call will use the new template.
     * Running instances are not affected.
     *
     * @return true if the template folder exists and the swap succeeded
     */
    public boolean setActiveTemplate(String templateName) {
        File candidate = new File(templatesDir, templateName);
        if (!candidate.exists() || !candidate.isDirectory()) {
            log.warning("Template not found: " + templateName);
            return false;
        }

        activeTemplateName = templateName;
        plugin.getConfig().set("active-template", templateName);
        plugin.saveConfig();

        log.info("Active template changed to: " + templateName);
        return true;
    }

    /**
     * Lists every template folder currently available on disk.
     */
    public List<String> listTemplates() {
        String[] names = templatesDir.list((dir, name) ->
                new File(dir, name).isDirectory());
        if (names == null) return Collections.emptyList();
        return Arrays.asList(names);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getActiveTemplateName() { return activeTemplateName; }

    // ── Internal file copy ────────────────────────────────────────────────────

    /**
     * Recursive directory copy.
     * Skips session.lock so Minecraft doesn't refuse to load the copy.
     */
    private void copyDirectory(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.getName().equals("session.lock")) continue;
            if (child.getName().equals("uid.dat")) continue; // skip UID so Bukkit treats this as a new world

            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target);
            } else {
                java.nio.file.Files.copy(child.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
