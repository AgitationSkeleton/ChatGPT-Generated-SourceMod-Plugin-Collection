/*
 * DMC Spectator Restore (minimal, compile-safe)
 * ---------------------------------------------
 * Restores practical spectator behavior in Deathmatch Classic:
 * - Enter/exit spectator
 * - Free-fly, chase (locked/free), in-eye
 * - Cycle next/prev targets
 * - Hooks DMC console commands: spectate, spec_mode, spec_menu, spec_help, spec_pos
 * - Extras: spec_next, spec_prev, unspectate
 *
 * Requirements: AMXX, fakemeta, hamsandwich
 * Notes:
 *  - This version avoids all user-message/FOV/HUD calls for maximum compiler compatibility.
 */

#include <amxmodx>
#include <amxmisc>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN   "DMC Spectator Restore"
#define VERSION  "1.5"
#define AUTHOR   "ChatGPT"

#define MAX_PLAYERS 32

// Observer modes (common HL/DMC semantics)
enum ObsMode
{
    OBS_NONE         = 0,
    OBS_CHASE_LOCKED = 1,
    OBS_CHASE_FREE   = 2,
    OBS_IN_EYE       = 4,
    OBS_MAP_FREE     = 5
};

// Buttons
#define IN_ATTACK   (1<<0)
#define IN_ATTACK2  (1<<11)
#define IN_RELOAD   (1<<13)

// Cvars
new gCvarAllowAlive;       // allow spectate while alive (0/1)
new gCvarDefaultMode;      // 1,2,4,5 (default mode when entering)
new gCvarKeyCycling;       // enable ATTACK/ATTACK2/RELOAD cycling while spectating (0/1)
new gCvarStartDeadToSpec;  // when killed, auto enter spectator (0/1)

// Per-player state
new bool:gIsSpec[MAX_PLAYERS+1];
new ObsMode:gSpecMode[MAX_PLAYERS+1];
new gSpecTarget[MAX_PLAYERS+1];
new bool:gWasAlive[MAX_PLAYERS+1];

// Utility
stock bool:is_plr(id) { return (id >= 1 && id <= MAX_PLAYERS); }

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    // Ham hooks
    RegisterHam(Ham_Spawn,  "player", "OnPlayerSpawnPost", 1);
    RegisterHam(Ham_Killed, "player", "OnPlayerKilledPost", 1);

    // FM for key handling (map buttons to spec actions)
    register_forward(FM_CmdStart, "OnCmdStart", 0);

    // Client console commands
    register_clcmd("spectate",    "CmdSpectate",   0, "- enter spectator mode");
    register_clcmd("unspectate",  "CmdUnSpectate", 0, "- leave spectator mode and respawn");

    register_clcmd("spec_mode",   "CmdSpecMode",   0, "[cycle|1|2|4|5] - set/cycle spectator mode");
    register_clcmd("spec_next",   "CmdSpecNext",   0, "- follow next player");
    register_clcmd("spec_prev",   "CmdSpecPrev",   0, "- follow previous player");

    register_clcmd("spec_menu",   "CmdSpecMenu",   0, "- show minimal spectator menu in console");
    register_clcmd("spec_help",   "CmdSpecHelp",   0, "- show spectator help in console");
    register_clcmd("spec_pos",    "CmdSpecPos",    0, "- print your current position/angles");

    // Cvars
    gCvarAllowAlive      = register_cvar("dmc_spec_allow_alive",      "1");
    gCvarDefaultMode     = register_cvar("dmc_spec_default_mode",     "2");   // 2 = chase free
    gCvarKeyCycling      = register_cvar("dmc_spec_key_cycling",      "1");
    gCvarStartDeadToSpec = register_cvar("dmc_spec_auto_on_death",    "0");
}

/* ---------- Lifecycle hooks ---------- */

public OnPlayerSpawnPost(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return HAM_IGNORED;

    gWasAlive[id] = true;

    if (gIsSpec[id])
        ExitSpectate(id, 0);

    return HAM_IGNORED;
}

public OnPlayerKilledPost(id, attacker, shouldgib)
{
    if (!is_plr(id) || !is_user_connected(id)) return HAM_IGNORED;

    gWasAlive[id] = false;

    if (get_pcvar_num(gCvarStartDeadToSpec))
    {
        new mode = clamp(get_pcvar_num(gCvarDefaultMode), 1, 5);
        EnterSpectate(id, ObsMode:mode);
    }
    return HAM_IGNORED;
}

/* ---------- Input mapping while spectating ---------- */

public OnCmdStart(id, uc, seed)
{
    if (!is_plr(id) || !gIsSpec[id] || !get_pcvar_num(gCvarKeyCycling))
        return FMRES_IGNORED;

    static buttons, oldbuttons;
    buttons = get_uc(uc, UC_Buttons);
    oldbuttons = pev(id, pev_oldbuttons);

    if ((buttons & IN_ATTACK) && !(oldbuttons & IN_ATTACK))
    {
        CmdSpecNext(id);
        buttons &= ~IN_ATTACK;
    }
    if ((buttons & IN_ATTACK2) && !(oldbuttons & IN_ATTACK2))
    {
        CmdSpecPrev(id);
        buttons &= ~IN_ATTACK2;
    }
    if ((buttons & IN_RELOAD) && !(oldbuttons & IN_RELOAD))
    {
        CmdSpecMode(id);
        buttons &= ~IN_RELOAD;
    }

    set_uc(uc, UC_Buttons, buttons);
    return FMRES_HANDLED;
}

/* ---------- Console Commands ---------- */

public CmdSpectate(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    if (gIsSpec[id]) {
        client_print(id, print_console, "[SPEC] You are already spectating.");
        return PLUGIN_HANDLED;
    }

    if (is_user_alive(id) && !get_pcvar_num(gCvarAllowAlive)) {
        client_print(id, print_console, "[SPEC] You must be dead to spectate (server setting).");
        return PLUGIN_HANDLED;
    }

    new mode = clamp(get_pcvar_num(gCvarDefaultMode), 1, 5);
    EnterSpectate(id, ObsMode:mode);
    return PLUGIN_HANDLED;
}

public CmdUnSpectate(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    if (!gIsSpec[id]) {
        client_print(id, print_console, "[SPEC] You are not in spectator mode.");
        return PLUGIN_HANDLED;
    }

    ExitSpectate(id, 1);
    return PLUGIN_HANDLED;
}

public CmdSpecMode(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    if (!gIsSpec[id]) {
        client_print(id, print_console, "[SPEC] Enter spectator first (use 'spectate').");
        return PLUGIN_HANDLED;
    }

    new args[16];
    read_argv(1, args, charsmax(args));

    new ObsMode:newmode = gSpecMode[id];

    if (strlen(args) > 0)
    {
        if (equali(args, "cycle"))
        {
            newmode = ObsMode:CycleMode(gSpecMode[id]);
        }
        else
        {
            new m = str_to_num(args);
            if (m == 1 || m == 2 || m == 4 || m == 5)
                newmode = ObsMode:m;
            else {
                client_print(id, print_console, "[SPEC] Invalid mode. Use 1 (locked), 2 (free), 4 (in-eye), 5 (free-fly).");
                return PLUGIN_HANDLED;
            }
        }
    }
    else
    {
        newmode = ObsMode:CycleMode(gSpecMode[id]);
    }

    SetObserverMode(id, newmode);
    UpdateObserverView(id);
    return PLUGIN_HANDLED;
}

public CmdSpecNext(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;
    if (!gIsSpec[id]) {
        client_print(id, print_console, "[SPEC] Enter spectator first (use 'spectate').");
        return PLUGIN_HANDLED;
    }

    new target = FindNextLivePlayer(id, 1);
    if (target > 0) {
        gSpecTarget[id] = target;
        UpdateObserverView(id);
    } else {
        client_print(id, print_console, "[SPEC] No valid target.");
    }
    return PLUGIN_HANDLED;
}

public CmdSpecPrev(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;
    if (!gIsSpec[id]) {
        client_print(id, print_console, "[SPEC] Enter spectator first (use 'spectate').");
        return PLUGIN_HANDLED;
    }

    new target = FindNextLivePlayer(id, -1);
    if (target > 0) {
        gSpecTarget[id] = target;
        UpdateObserverView(id);
    } else {
        client_print(id, print_console, "[SPEC] No valid target.");
    }
    return PLUGIN_HANDLED;
}

public CmdSpecMenu(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    new mode = _:gSpecMode[id];
    new target = gSpecTarget[id];

    static tname[32];
    if (IsValidLiveTarget(target))
        get_user_name(target, tname, charsmax(tname));
    else
        copy(tname, charsmax(tname), "None");

    client_print(id, print_console, "=== Spectator Menu ===");
    client_print(id, print_console, "Mode: %d (1=locked,2=free,4=in-eye,5=free-fly)", mode);
    client_print(id, print_console, "Target: %s", tname);
    client_print(id, print_console, "Commands: spec_mode, spec_next, spec_prev, spec_help, spec_pos");
    return PLUGIN_HANDLED;
}

public CmdSpecHelp(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    client_print(id, print_console, "=== Spectator Help ===");
    client_print(id, print_console, "spectate           : enter spectator mode");
    client_print(id, print_console, "unspectate         : leave spectator and respawn");
    client_print(id, print_console, "spec_mode [cycle|1|2|4|5]");
    client_print(id, print_console, "                   : cycle or set mode (1=locked,2=free,4=in-eye,5=free-fly)");
    client_print(id, print_console, "spec_next/spec_prev: follow next/prev target");
    client_print(id, print_console, "spec_menu          : show quick summary");
    client_print(id, print_console, "spec_pos           : print current position/angles");
    client_print(id, print_console, "Tip: While spectating, Attack=next, Attack2=prev, Reload=mode (toggle).");
    return PLUGIN_HANDLED;
}

public CmdSpecPos(id)
{
    if (!is_plr(id) || !is_user_connected(id)) return PLUGIN_HANDLED;

    new Float:origin[3], Float:angles[3];
    pev(id, pev_origin, origin);
    pev(id, pev_v_angle, angles);

    client_print(id, print_console, "[SPEC] Origin: %.1f %.1f %.1f | Angles: %.1f %.1f %.1f",
        origin[0], origin[1], origin[2], angles[0], angles[1], angles[2]);
    return PLUGIN_HANDLED;
}

/* ---------- Core Spectator Logic ---------- */

stock EnterSpectate(id, ObsMode:mode)
{
    if (!is_user_connected(id)) return;

    gIsSpec[id] = true;
    gSpecMode[id] = mode;

    // Hide body / disable interaction
    set_pev(id, pev_movetype, MOVETYPE_NOCLIP);
    set_pev(id, pev_solid, SOLID_NOT);
    set_pev(id, pev_effects, pev(id, pev_effects) | EF_NODRAW);
    set_pev(id, pev_takedamage, DAMAGE_NO);
    set_pev(id, pev_flags, pev(id, pev_flags) | FL_SPECTATOR);

    // initial target (if mode follows someone)
    if (mode == OBS_IN_EYE || mode == OBS_CHASE_FREE || mode == OBS_CHASE_LOCKED)
        gSpecTarget[id] = FindNextLivePlayer(id, 1);
    else
        gSpecTarget[id] = 0;

    UpdateObserverView(id);
    client_print(id, print_console, "[SPEC] You are now spectating. Use spec_mode/spec_next/spec_prev.");
}

stock ExitSpectate(id, respawn)
{
    gIsSpec[id] = false;
    gSpecTarget[id] = 0;
    gSpecMode[id] = OBS_NONE;

    // Restore body
    set_pev(id, pev_movetype, MOVETYPE_WALK);
    set_pev(id, pev_solid, SOLID_SLIDEBOX);
    set_pev(id, pev_effects, pev(id, pev_effects) & ~EF_NODRAW);
    set_pev(id, pev_takedamage, DAMAGE_AIM);
    set_pev(id, pev_flags, pev(id, pev_flags) & ~FL_SPECTATOR);

    // Restore view to self
    engfunc(EngFunc_SetView, id, id);

    if (respawn)
        ExecuteHamB(Ham_Spawn, id);

    client_print(id, print_console, "[SPEC] Spectator mode ended.");
}

stock SetObserverMode(id, ObsMode:mode)
{
    gSpecMode[id] = mode;
    set_pev(id, pev_iuser1, _:mode);
}

stock UpdateObserverView(id)
{
    new ObsMode:mode = gSpecMode[id];

    if (mode == OBS_IN_EYE || mode == OBS_CHASE_FREE || mode == OBS_CHASE_LOCKED)
    {
        new target = gSpecTarget[id];
        if (!IsValidLiveTarget(target))
        {
            target = FindNextLivePlayer(id, 1);
            gSpecTarget[id] = target;
        }

        set_pev(id, pev_iuser1, _:mode);
        set_pev(id, pev_iuser2, target);

        if (IsValidLiveTarget(target))
        {
            engfunc(EngFunc_SetView, id, target);

            static name[32];
            get_user_name(target, name, charsmax(name));
            client_print(id, print_console, "[SPEC] Now watching: %s (mode %d)", name, _:mode);
        }
        else
        {
            // No targets; fall back to free-fly
            set_pev(id, pev_iuser1, _:OBS_MAP_FREE);
            set_pev(id, pev_iuser2, 0);
            engfunc(EngFunc_SetView, id, id);
            client_print(id, print_console, "[SPEC] Free-Fly");
        }
    }
    else
    {
        // Free-fly
        set_pev(id, pev_iuser1, _:OBS_MAP_FREE);
        set_pev(id, pev_iuser2, 0);
        engfunc(EngFunc_SetView, id, id);
        client_print(id, print_console, "[SPEC] Free-Fly");
    }
}

/* ---------- Helpers ---------- */

stock FindNextLivePlayer(id, dir)
{
    // dir = +1 for next, -1 for prev
    new start = id;
    new idx = id;

    for (new i = 0; i < MAX_PLAYERS; i++)
    {
        idx += dir;
        if (idx < 1) idx = MAX_PLAYERS;
        if (idx > MAX_PLAYERS) idx = 1;

        if (idx == start) break;

        if (IsValidLiveTarget(idx))
            return idx;
    }
    return 0;
}

stock IsValidLiveTarget(ent)
{
    if (!is_plr(ent) || !is_user_connected(ent)) return 0;
    if (!is_user_alive(ent)) return 0;
    return 1;
}

// Cycle spectator mode in a stable order: 2 -> 4 -> 1 -> 5 -> 2
stock CycleMode(ObsMode:mode)
{
    if (mode == OBS_CHASE_FREE)    return _:OBS_IN_EYE;
    if (mode == OBS_IN_EYE)        return _:OBS_CHASE_LOCKED;
    if (mode == OBS_CHASE_LOCKED)  return _:OBS_MAP_FREE;
    return _:OBS_CHASE_FREE; // from free-fly or anything else
}
