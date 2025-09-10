/*  Ricochet Instant Respawn (Any-Death, Ham-free)
 *
 *  Forces a near-instant respawn whenever a player dies in Ricochet.
 *  Uses DeathMsg (primary) and a PreThink safety net to catch all cases.
 *
 *  Requires: fakemeta
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN_NAME        "Ricochet Instant Respawn (Any-Death)"
#define PLUGIN_VERSION     "1.2.0"
#define PLUGIN_AUTHOR      "ChatGPT"

// HL SDK
#define DEAD_RESPAWNABLE   2

// Cvars
new g_pDelay;     // seconds (float)
new g_pDebug;     // 1 = debug prints

// Per-player state
new Float:g_nextRespawnAt[33]; // gametime when we’re allowed to respawn
new bool:g_pending[33];        // a respawn is scheduled/allowed

public plugin_init()
{
    register_plugin(PLUGIN_NAME, PLUGIN_VERSION, PLUGIN_AUTHOR);

    g_pDelay = register_cvar("ricoinstrespawn_delay", "0.20");
    g_pDebug = register_cvar("ricoinstrespawn_debug", "0"); // set 1 temporarily to verify

    // Fast indication of death
    register_event("DeathMsg", "OnDeathMsg", "a");

    // Safety net: if DeathMsg timing or other states block us, check every PreThink
    register_forward(FM_PlayerPreThink, "OnPlayerPreThink", false);
}

public client_connect(id)
{
    g_pending[id] = false;
    g_nextRespawnAt[id] = 0.0;
}

public client_disconnected(id)
{
    g_pending[id] = false;
    g_nextRespawnAt[id] = 0.0;
    if (task_exists(id)) remove_task(id);
}

// Primary path: DeathMsg arrives -> schedule the respawn slightly later
public OnDeathMsg()
{
    // 1: killer, 2: victim, 3: flag, 4: weapon (ignored)
    new victim = read_data(2);
    if (victim < 1 || victim > 32 || !is_user_connected(victim))
        return;

    // Mark when we are allowed to respawn
    new Float:now = get_gametime();
    new Float:delay = floatmax(0.0, get_pcvar_float(g_pDelay));

    g_pending[victim] = true;
    g_nextRespawnAt[victim] = now + delay;

    // Lightweight timer so we react quickly (also guarded by PreThink safety net)
    if (!task_exists(victim))
        set_task(delay, "ForceRespawnTask", victim);

    if (get_pcvar_num(g_pDebug))
        server_print("[ricorespawn] DeathMsg: victim=%d, respawn at %.3f (delay=%.2f)", victim, g_nextRespawnAt[victim], delay);
}

// Safety net runs every frame and respawns any dead player past their window
public OnPlayerPreThink(id)
{
    if (id < 1 || id > 32) return;
    if (!g_pending[id])    return;

    // If they’re already alive, clear pending
    if (is_user_alive(id))
    {
        g_pending[id] = false;
        return;
    }

    new Float:now = get_gametime();
    if (now >= g_nextRespawnAt[id])
    {
        // In case the task was blocked/removed, do it here
        ForceRespawnCore(id, true /*fromPreThink*/);
    }
}

// Task callback (primary path)
public ForceRespawnTask(id)
{
    if (!g_pending[id]) return;
    ForceRespawnCore(id, false /*fromPreThink*/);
}

// Core respawn logic
ForceRespawnCore(id, bool:fromPreThink)
{
    if (!is_user_connected(id))
    {
        g_pending[id] = false;
        return;
    }

    // If they came back alive via some other mechanism, stop
    if (is_user_alive(id))
    {
        g_pending[id] = false;
        return;
    }

    // Mark as respawnable and clear spectator-ish fields defensively
    set_pev(id, pev_deadflag, DEAD_RESPAWNABLE);
    set_pev(id, pev_iuser1, 0);
    set_pev(id, pev_iuser2, 0);
    set_pev(id, pev_iuser3, 0);
    set_pev(id, pev_fixangle, 0);

    // Zero velocity to avoid odd post-death motion
    static Float:zero[3]; zero[0]=zero[1]=zero[2]=0.0;
    set_pev(id, pev_velocity, zero);

    // Call the mod’s spawn for the player
    dllfunc(DLLFunc_Spawn, id);

    // Clear flags
    g_pending[id] = false;
    g_nextRespawnAt[id] = 0.0;

    if (get_pcvar_num(g_pDebug))
        server_print("[ricorespawn] Forced spawn for id=%d via %s", id, fromPreThink ? "PreThink" : "Task");
}
