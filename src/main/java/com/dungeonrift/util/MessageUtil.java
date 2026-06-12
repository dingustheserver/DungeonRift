package com.dungeonrift.util;

import com.dungeonrift.DungeonRift;
import org.bukkit.ChatColor;

/**
 * MessageUtil
 *
 * Fetches messages from config.yml, applies colour codes, and
 * substitutes simple {key} placeholders.
 */
public final class MessageUtil {

    private MessageUtil() {}

    /**
     * Fetch and colour a message from config.yml by key.
     * Example: MessageUtil.format("messages.extracted")
     */
    public static String format(String configKey, String... placeholders) {
        String raw = DungeonRift.get().getConfig().getString(configKey, "§c[missing: " + configKey + "]");

        // Apply {key} → value substitutions (pairs: key, value, key, value, ...)
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }

        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Returns the coloured prefix string. */
    public static String prefix() {
        return format("messages.prefix");
    }
}
