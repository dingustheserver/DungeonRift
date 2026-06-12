package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.Party;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PartyManager
 *
 * Handles party lifecycle, sidebar scoreboard showing member HP,
 * and resetting the party back to FORMING after a rift ends.
 */
public class PartyManager {

    private final DungeonRift plugin;

    /** playerUUID → Party they belong to */
    private final Map<UUID, Party>      playerParties  = new ConcurrentHashMap<>();
    /** Pending invite expiry tasks */
    private final Map<UUID, BukkitTask> inviteExpiry   = new ConcurrentHashMap<>();
    /** Sidebar update task per party leader */
    private final Map<UUID, BukkitTask> sidebarTasks   = new ConcurrentHashMap<>();
    /** Scoreboards per player */
    private final Map<UUID, Scoreboard> scoreboards    = new ConcurrentHashMap<>();

    public PartyManager(DungeonRift plugin) {
        this.plugin = plugin;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public boolean createParty(Player leader) {
        if (playerParties.containsKey(leader.getUniqueId())) {
            leader.sendMessage("§cYou are already in a party.");
            return false;
        }
        Party party = new Party(leader);
        playerParties.put(leader.getUniqueId(), party);
        leader.sendMessage("§aParty created! Invite players with §e/rift party invite <name>§a.");
        updateSidebar(party);
        startSidebarTask(party);
        return true;
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    public boolean invitePlayer(Player leader, Player target) {
        Party party = getPartyOf(leader);
        if (party == null) {
            leader.sendMessage("§cYou don't have a party. Use §e/rift party create §cfirst.");
            return false;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            leader.sendMessage("§cOnly the party leader can invite players.");
            return false;
        }
        if (playerParties.containsKey(target.getUniqueId())) {
            leader.sendMessage("§c" + target.getName() + " is already in a party.");
            return false;
        }
        if (!party.invite(target)) {
            leader.sendMessage("§cParty is full (max 4 players).");
            return false;
        }

        playerParties.put(target.getUniqueId(), party);

        int expirySecs = plugin.getConfig().getInt("party.invite-expiry-seconds", 60);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (party.hasPendingInvite(target.getUniqueId())) {
                party.declineInvite(target);
                playerParties.remove(target.getUniqueId());
                target.sendMessage("§7Your party invite expired.");
                leader.sendMessage("§7" + target.getName() + "'s invite expired.");
            }
        }, expirySecs * 20L);

        inviteExpiry.put(target.getUniqueId(), task);

        target.sendMessage("§6" + leader.getName()
                + " §ehas invited you to a party! "
                + "§a/rift party accept §7or §c/rift party decline");
        leader.sendMessage("§aInvite sent to " + target.getName() + ".");
        return true;
    }

    // ── Accept / Decline ──────────────────────────────────────────────────────

    public boolean acceptInvite(Player player) {
        Party party = playerParties.get(player.getUniqueId());
        if (party == null || !party.hasPendingInvite(player.getUniqueId())) {
            player.sendMessage("§cYou have no pending party invite.");
            return false;
        }
        cancelExpiry(player.getUniqueId());
        party.acceptInvite(player);
        broadcastToParty(party, "§a" + player.getName() + " joined the party! (" + party.getSize() + "/4)");
        updateSidebar(party);
        return true;
    }

    public boolean declineInvite(Player player) {
        Party party = playerParties.get(player.getUniqueId());
        if (party == null || !party.hasPendingInvite(player.getUniqueId())) {
            player.sendMessage("§cYou have no pending party invite.");
            return false;
        }
        cancelExpiry(player.getUniqueId());
        party.declineInvite(player);
        playerParties.remove(player.getUniqueId());
        player.sendMessage("§7Party invite declined.");
        broadcastToParty(party, "§7" + player.getName() + " declined the invite.");
        return true;
    }

    // ── Leave (anyone can call this) ──────────────────────────────────────────

    public void leaveParty(Player player) {
        Party party = getPartyOf(player);
        if (party == null) {
            player.sendMessage("§cYou are not in a party.");
            return;
        }

        if (party.isLeader(player.getUniqueId())) {
            disbandParty(party, "§cParty disbanded — leader left.");
        } else {
            party.removeMember(player.getUniqueId());
            playerParties.remove(player.getUniqueId());
            removeSidebar(player);
            player.sendMessage("§7You left the party.");
            broadcastToParty(party, "§7" + player.getName() + " left the party.");
            updateSidebar(party);
        }
    }

    public void disbandParty(Party party, String reason) {
        stopSidebarTask(party);
        broadcastToParty(party, reason);
        party.getMembers().forEach(uuid -> {
            playerParties.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) removeSidebar(p);
        });
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public void listParty(Player player) {
        Party party = getPartyOf(player);
        if (party == null) {
            player.sendMessage("§cYou are not in a party.");
            return;
        }
        player.sendMessage("§6=== Party Members (" + party.getSize() + "/4) ===");
        party.getMembers().forEach(uuid -> {
            Player member = Bukkit.getPlayer(uuid);
            String name   = member != null ? member.getName() : uuid.toString();
            String role   = party.isLeader(uuid) ? " §6[Leader]" : "";
            String hp     = member != null && member.isOnline()
                    ? " §c" + (int) member.getHealth() + "❤" : " §7offline";
            String state  = party.getState() == Party.State.IN_GAME ? " §a[In Rift]" : "";
            player.sendMessage("§7- §f" + name + role + hp + state);
        });
    }

    // ── Queue entry ───────────────────────────────────────────────────────────

    public boolean queueParty(Player leader) {
        Party party = getPartyOf(leader);
        if (party == null) {
            leader.sendMessage("§cYou are not in a party.");
            return false;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            leader.sendMessage("§cOnly the party leader can queue the party.");
            return false;
        }
        if (party.getState() != Party.State.FORMING) {
            leader.sendMessage("§cParty is already queued or in a rift.");
            return false;
        }

        List<Player> online = new ArrayList<>();
        for (UUID uuid : party.getMembers()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                leader.sendMessage("§cA party member is offline. Ask them to reconnect first.");
                return false;
            }
            online.add(p);
        }

        party.setState(Party.State.READY);
        broadcastToParty(party, "§aEntering the rift...");
        plugin.getQueueManager().enqueueParty(online, party);
        return true;
    }

    // ── Called when the rift closes — resets party to FORMING ────────────────

    /**
     * Called by DungeonInstance.close() so the party can queue again.
     * Resets state to FORMING without disbanding — members stay in the party.
     */
    public void onRiftComplete(Party party) {
        if (party == null) return;
        party.setState(Party.State.FORMING);
        broadcastToParty(party, "§eThe rift has ended. Use §a/rift party ready §eto queue again.");
        updateSidebar(party);
    }

    // ── Sidebar scoreboard ────────────────────────────────────────────────────

    private void startSidebarTask(Party party) {
        UUID leaderId = party.getLeaderId();
        // Cancel any existing task for this leader
        stopSidebarTask(party);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (party.getSize() > 0) updateSidebar(party);
        }, 20L, 20L); // update every second

        sidebarTasks.put(leaderId, task);
    }

    private void stopSidebarTask(Party party) {
        BukkitTask old = sidebarTasks.remove(party.getLeaderId());
        if (old != null) old.cancel();
    }

    public void updateSidebar(Party party) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();

        party.getMembers().forEach(uuid -> {
            Player viewer = Bukkit.getPlayer(uuid);
            if (viewer == null || !viewer.isOnline()) return;

            // Get or create a scoreboard for this player
            Scoreboard board = scoreboards.computeIfAbsent(uuid, k -> sm.getNewScoreboard());

            // Remove old objective and recreate to clear stale entries
            Objective old = board.getObjective("party");
            if (old != null) old.unregister();

            Objective obj = board.registerNewObjective("party", Criteria.DUMMY, "§6§lParty");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            // Show each member with HP
            int score = party.getSize();
            for (UUID memberUuid : party.getMembers()) {
                Player member = Bukkit.getPlayer(memberUuid);
                String name   = member != null ? member.getName() : "?";
                String leader = party.isLeader(memberUuid) ? "§6★ " : "§7  ";

                String hp;
                if (member != null && member.isOnline()) {
                    int current = (int) Math.ceil(member.getHealth());
                    double maxHp = member.getAttribute(Attribute.MAX_HEALTH) != null
                            ? member.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
                    int max = (int) maxHp;
                    // Colour shifts with HP
                    String colour = current > max * 0.5 ? "§a" : current > max * 0.25 ? "§e" : "§c";
                    hp = colour + current + "§7/" + max + " §c❤";
                } else {
                    hp = "§8offline";
                }

                String line = leader + "§f" + name + " " + hp;
                obj.getScore(line).setScore(score--);
            }

            // Status line at bottom
            String statusLine = switch (party.getState()) {
                case FORMING  -> "§7Forming...";
                case READY    -> "§eEntering rift...";
                case IN_GAME  -> "§a§lIn Rift";
            };
            obj.getScore(statusLine).setScore(0);

            viewer.setScoreboard(board);
        });
    }

    private void removeSidebar(Player player) {
        scoreboards.remove(player.getUniqueId());
        // Reset to blank scoreboard
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public Party getPartyOf(Player player) {
        Party party = playerParties.get(player.getUniqueId());
        if (party != null && party.isMember(player.getUniqueId())) return party;
        return null;
    }

    /** Get party by any member UUID — used by DungeonInstance on close. */
    public Party getPartyByMember(UUID uuid) {
        Party party = playerParties.get(uuid);
        if (party != null && party.isMember(uuid)) return party;
        return null;
    }

    private void broadcastToParty(Party party, String message) {
        party.getMembers().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6Party§8] §r" + message);
        });
    }

    private void cancelExpiry(UUID uuid) {
        BukkitTask task = inviteExpiry.remove(uuid);
        if (task != null) task.cancel();
    }
}
