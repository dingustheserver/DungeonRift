package com.dungeonrift.model;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * A player-formed group of 1–4 people waiting to enter a dungeon together.
 *
 * States:
 *   FORMING  – leader created the party, invites pending
 *   READY    – all members accepted, waiting in queue
 *   IN_GAME  – currently inside an instance
 */
public class Party {

    public enum State { FORMING, READY, IN_GAME }

    private final UUID         leaderId;
    private final List<UUID>   members    = new ArrayList<>();
    private final Set<UUID>    pendingInvites = new HashSet<>();
    private State              state      = State.FORMING;
    private String             instanceId = null;

    public Party(Player leader) {
        this.leaderId = leader.getUniqueId();
        this.members.add(leader.getUniqueId());
    }

    // ── Invite management ─────────────────────────────────────────────────────

    public boolean invite(Player target) {
        if (members.size() + pendingInvites.size() >= 4) return false;
        return pendingInvites.add(target.getUniqueId());
    }

    public boolean acceptInvite(Player player) {
        if (!pendingInvites.remove(player.getUniqueId())) return false;
        members.add(player.getUniqueId());
        return true;
    }

    public void declineInvite(Player player) {
        pendingInvites.remove(player.getUniqueId());
    }

    // ── Membership ────────────────────────────────────────────────────────────

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public boolean isLeader(UUID uuid) {
        return leaderId.equals(uuid);
    }

    // ── Getters / setters ─────────────────────────────────────────────────────

    public UUID         getLeaderId()      { return leaderId;      }
    public List<UUID>   getMembers()       { return Collections.unmodifiableList(members); }
    public int          getSize()          { return members.size(); }
    public State        getState()         { return state;         }
    public void         setState(State s)  { this.state = s;       }
    public String       getInstanceId()    { return instanceId;    }
    public void         setInstanceId(String id) { this.instanceId = id; }
    public boolean      hasPendingInvite(UUID uuid) {
        return pendingInvites.contains(uuid);
    }
}
