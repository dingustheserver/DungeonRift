package com.dungeonrift.manager;

import com.dungeonrift.DungeonRift;
import com.dungeonrift.model.Party;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PartyManager
 *
 * Players form a party in the hub, then enter the queue together.
 * Party flow:
 *   /rift party create          → creates a party (player becomes leader)
 *   /rift party invite <name>   → sends an invite
 *   /rift party accept          → joins the party
 *   /rift party decline         → rejects the invite
 *   /rift party leave           → removes member (or disbands if leader)
 *   /rift party ready           → leader puts the party into the queue
 */
public class PartyManager {

    private final DungeonRift plugin;

    /** playerUUID → Party they lead or belong to */
    private final Map<UUID, Party> playerParties = new ConcurrentHashMap<>();

    /** Pending invite expiry tasks: inviteeUUID → task */
    private final Map<UUID, BukkitTask> inviteExpiry = new ConcurrentHashMap<>();

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
        leader.sendMessage("§aParty created. Invite players with /rift party invite <name>.");
        return true;
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    public boolean invitePlayer(Player leader, Player target) {
        Party party = getPartyOf(leader);
        if (party == null) {
            leader.sendMessage("§cYou don't have a party. Use /rift party create first.");
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

        // Store pending invite reference on the invitee
        playerParties.put(target.getUniqueId(), party);

        // Auto-expire invite
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

        broadcastToParty(party, "§a" + player.getName() + " joined the party! ("
                + party.getSize() + "/4)");
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

    // ── Leave / disband ───────────────────────────────────────────────────────

    public void leaveParty(Player player) {
        Party party = getPartyOf(player);
        if (party == null) {
            player.sendMessage("§cYou are not in a party.");
            return;
        }

        if (party.isLeader(player.getUniqueId())) {
            disbandParty(party, "§cParty disbanded by leader.");
        } else {
            party.removeMember(player.getUniqueId());
            playerParties.remove(player.getUniqueId());
            player.sendMessage("§7You left the party.");
            broadcastToParty(party, "§7" + player.getName() + " left the party.");
        }
    }

    public void disbandParty(Party party, String reason) {
        broadcastToParty(party, reason);
        party.getMembers().forEach(uuid -> playerParties.remove(uuid));
    }

    // ── Queue entry ───────────────────────────────────────────────────────────

    /**
     * Called by RiftCommand when the leader types /rift party ready.
     * Passes the full party member list to QueueManager.
     */
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
            leader.sendMessage("§cParty is already queued or in game.");
            return false;
        }

        List<Player> online = new ArrayList<>();
        for (UUID uuid : party.getMembers()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                leader.sendMessage("§c" + uuid + " is offline. Remove them first.");
                return false;
            }
            online.add(p);
        }

        party.setState(Party.State.READY);
        broadcastToParty(party, "§aParty queued for the dungeon! Entering now...");
        plugin.getQueueManager().enqueueParty(online, party);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public Party getPartyOf(Player player) {
        Party party = playerParties.get(player.getUniqueId());
        // Only return party if the player is a confirmed member (not just invited)
        if (party != null && party.isMember(player.getUniqueId())) return party;
        return null;
    }

    private void broadcastToParty(Party party, String message) {
        party.getMembers().forEach(uuid -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage("§8[§6Party§8] §r" + message);
        });
    }

    private void cancelExpiry(UUID uuid) {
        BukkitTask task = inviteExpiry.remove(uuid);
        if (task != null) task.cancel();
    }
}
