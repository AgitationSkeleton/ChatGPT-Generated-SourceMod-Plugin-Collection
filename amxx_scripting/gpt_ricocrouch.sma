/*  Ricochet Restore Crouch (No Ham)
 *  Restores server-side crouching (hull + view) with safe headroom checks.
 *  Uses only amxmodx + fakemeta (no hamsandwich).
 *
 *  CVARs:
 *    ric_duck_speed_mult   0.55   // movement speed multiplier while crouched (0.1..1.0)
 *    ric_duck_view_z       12.0   // eye Z offset when crouched (visual)
 *    ric_duck_mins_x      -16.0   // crouch hull mins X
 *    ric_duck_mins_y      -16.0   // crouch hull mins Y
 *    ric_duck_mins_z      -18.0   // crouch hull mins Z
 *    ric_duck_maxs_x       16.0   // crouch hull maxs X
 *    ric_duck_maxs_y       16.0   // crouch hull maxs Y
 *    ric_duck_maxs_z       18.0   // crouch hull maxs Z
 *    ric_duck_headroom       1    // 1=enforce clear headroom to stand, 0=stand immediately
 *    ric_duck_anim           0    // (optional) force crouch gait sequence; off by default
 *
 *  Notes:
 *    - Hooks FM_PlayerPreThink (per-frame) to read IN_DUCK and apply/restore hull+view.
 *    - Uses ResetHUD/DeathMsg to clear state on spawn/death (no Ham needed).
 *    - Caches each player’s standing hull/view exactly for precise restore.
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN  "Ricochet Restore Crouch (No Ham)"
#define VERSION "0.4"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32

// Button/flag bits
#if !defined IN_DUCK
    #define IN_DUCK (1<<2)
#endif
#if !defined FL_DUCKING
    #define FL_DUCKING (1<<14)
#endif

// Cvars
new c_duck_speed_mult;
new c_duck_viewofs_z;
new c_duck_mins_x, c_duck_mins_y, c_duck_mins_z;
new c_duck_maxs_x, c_duck_maxs_y, c_duck_maxs_z;
new c_headroom_trace;
new c_anim_enable;

// Per-player state
new bool:g_isDucking[33];

// Cached standing hull & view for exact restore
new bool:g_haveStandHull[33];
new Float:g_stand_mins[33][3];
new Float:g_stand_maxs[33][3];

new bool:g_haveStandView[33];
new Float:g_stand_viewofs[33][3];

// Cached base maxspeed to avoid compounding
new Float:g_baseMax[33];

// Fallback standing hull (used only if we somehow lack a cache)
new const Float:DEF_STAND_MINS[3] = { -16.0, -16.0, -36.0 };
new const Float:DEF_STAND_MAXS[3] = {  16.0,  16.0,  36.0 };

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    // CVARs
    c_duck_speed_mult  = register_cvar("ric_duck_speed_mult", "0.55");
    c_duck_viewofs_z   = register_cvar("ric_duck_view_z",     "12.0");

    c_duck_mins_x = register_cvar("ric_duck_mins_x", "-16.0");
    c_duck_mins_y = register_cvar("ric_duck_mins_y", "-16.0");
    c_duck_mins_z = register_cvar("ric_duck_mins_z", "-18.0");
    c_duck_maxs_x = register_cvar("ric_duck_maxs_x",  "16.0");
    c_duck_maxs_y = register_cvar("ric_duck_maxs_y",  "16.0");
    c_duck_maxs_z = register_cvar("ric_duck_maxs_z",  "18.0");

    c_headroom_trace   = register_cvar("ric_duck_headroom", "1");
    c_anim_enable      = register_cvar("ric_duck_anim",     "0");

    // Per-frame player think (no Ham)
    register_forward(FM_PlayerPreThink, "OnPlayerPreThink");

    // Spawn/death state housekeeping without Ham:
    // ResetHUD is sent to a player after spawn
    register_event("ResetHUD", "OnResetHUD", "be");
    // DeathMsg gives victim by userid
    register_message(get_user_msgid("DeathMsg"), "msg_DeathMsg");
}

public client_putinserver(id)  { reset_player_state(id); }
public client_disconnect(id)   { reset_player_state(id); }

public OnResetHUD(id)
{
    if (!is_user_connected(id)) return;
    // Don’t touch hull/view here; just clear our state so next crouch caches fresh stand values
    reset_player_state(id);
}

public msg_DeathMsg(msg_id, msg_dest, id)
{
    // Victim is arg2 (userid)
    if (get_msg_args() < 2) return PLUGIN_CONTINUE;
    new victim_uid = get_msg_arg_int(2);
    new victim = find_player("k", victim_uid);
    if (1 <= victim <= MAX_PLAYERS && is_user_connected(victim))
    {
        g_isDucking[victim] = false;
    }
    return PLUGIN_CONTINUE;
}

// ---------------- Core per-frame logic (no Ham) ----------------

public OnPlayerPreThink(id)
{
    if (!IsSpawnedAlive(id)) return FMRES_IGNORED;

    // Read intended buttons for this frame
    new buttons = pev(id, pev_button);
    new bool:wantsDuck = (buttons & IN_DUCK) != 0;

    if (wantsDuck)
    {
        if (!g_isDucking[id])
        {
            // Cache exact standing hull/view just before we change them
            if (!g_haveStandHull[id])
            {
                pev(id, pev_mins, g_stand_mins[id]);
                pev(id, pev_maxs, g_stand_maxs[id]);
                g_haveStandHull[id] = true;
            }
            if (!g_haveStandView[id])
            {
                pev(id, pev_view_ofs, g_stand_viewofs[id]);
                g_haveStandView[id] = true;
            }
            g_baseMax[id] = SafeReadBaseMax(id);

            if (EnterDuck(id))
                g_isDucking[id] = true;
        }
        else
        {
            if (get_pcvar_num(c_anim_enable)) ApplyDuckAnim(id);
        }
    }
    else if (g_isDucking[id])
    {
        // Try to stand up if there’s headroom (or headroom check is disabled)
        if (CanStandHere(id) || !get_pcvar_num(c_headroom_trace))
        {
            ExitDuck(id);
            g_isDucking[id] = false;
        }
        // else remain crouched until clear
    }

    return FMRES_IGNORED;
}

// ---------------- Helpers ----------------

stock bool:IsSpawnedAlive(id)
{
    if (!is_user_connected(id) || !is_user_alive(id)) return false;

    // Observer modes use iuser1 != 0
    if (pev(id, pev_iuser1) != 0) return false;

    // Must be in the world (not SOLID_NOT)
    if (pev(id, pev_solid) == SOLID_NOT) return false;

    return true;
}

stock bool:EnterDuck(id)
{
    if (!IsSpawnedAlive(id)) return false;

    new Float:mins[3], Float:maxs[3];

    // Keep live standing XY (cached), but apply crouch Z from cvars
    if (g_haveStandHull[id])
    {
        mins[0] = g_stand_mins[id][0];
        mins[1] = g_stand_mins[id][1];
        maxs[0] = g_stand_maxs[id][0];
        maxs[1] = g_stand_maxs[id][1];
    }
    else
    {
        pev(id, pev_mins, mins);
        pev(id, pev_maxs, maxs);
    }

    mins[2] = get_pcvar_float(c_duck_mins_z);
    maxs[2] = get_pcvar_float(c_duck_maxs_z);

    engfunc(EngFunc_SetSize, id, mins, maxs);

    // Mark crouching for hitboxes/footsteps
    set_pev(id, pev_flags, pev(id, pev_flags) | FL_DUCKING);

    // Lower view Z (visual only)
    SetViewZ(id, get_pcvar_float(c_duck_viewofs_z));

    // Apply speed once based on cached base
    ApplyDuckSpeedOnce(id);

    if (get_pcvar_num(c_anim_enable)) ApplyDuckAnim(id);

    return true;
}

stock ExitDuck(id)
{
    if (!IsSpawnedAlive(id)) return;

    // Restore exact standing hull if cached, else safe defaults
    if (g_haveStandHull[id])
        engfunc(EngFunc_SetSize, id, g_stand_mins[id], g_stand_maxs[id]);
    else
        engfunc(EngFunc_SetSize, id, DEF_STAND_MINS, DEF_STAND_MAXS);

    // Clear crouch flag
    set_pev(id, pev_flags, pev(id, pev_flags) & ~FL_DUCKING);

    // Restore exact standing view if cached
    if (g_haveStandView[id])
        set_pev(id, pev_view_ofs, g_stand_viewofs[id]);

    // Restore cached base speed (or sane fallback)
    set_pev(id, pev_maxspeed, (g_baseMax[id] > 0.0) ? g_baseMax[id] : 250.0);
}

stock ApplyDuckSpeedOnce(id)
{
    new Float:mult = get_pcvar_float(c_duck_speed_mult);
    if (mult < 0.1) mult = 0.1;
    if (mult > 1.0) mult = 1.0;

    new Float:base = (g_baseMax[id] > 0.0) ? g_baseMax[id] : 250.0;
    set_pev(id, pev_maxspeed, base * mult);
}

stock Float:SafeReadBaseMax(id)
{
    new Float:cur = pev(id, pev_maxspeed);
    if (cur >= 120.0 && cur <= 400.0) return cur;
    return 250.0;
}

stock bool:CanStandHere(id)
{
    // Try placing a standing hull at current origin; if it starts/all-solid, no headroom
    new Float:origin[3]; pev(id, pev_origin, origin);

    // Build a “standing” hull using cached XY and typical stand Z, or fallbacks
    new Float:mins[3], Float:maxs[3];
    if (g_haveStandHull[id])
    {
        mins[0] = g_stand_mins[id][0];
        mins[1] = g_stand_mins[id][1];
        mins[2] = DEF_STAND_MINS[2];

        maxs[0] = g_stand_maxs[id][0];
        maxs[1] = g_stand_maxs[id][1];
        maxs[2] = DEF_STAND_MAXS[2];
    }
    else
    {
        mins = DEF_STAND_MINS;
        maxs = DEF_STAND_MAXS;
    }

    // Trace an arbitrary hull index that matches human hull dimensions.
    // In HL, HULL_HUMAN (1) is typical, but we can emulate with TraceHull + mins/maxs via traceresult.
    // Easiest option: use engine’s human hull constant.
    new tr = 0;
    engfunc(EngFunc_TraceHull, origin, origin, 0, HULL_HUMAN, id, tr);

    return (get_tr2(tr, TR_StartSolid) == 0 && get_tr2(tr, TR_AllSolid) == 0);
}

stock SetViewZ(id, Float:z)
{
    new Float:v[3]; pev(id, pev_view_ofs, v);
    v[2] = z;
    set_pev(id, pev_view_ofs, v);
    // Optional mirror for debugging
    set_pev(id, pev_vuser1, v);
}

stock ApplyDuckAnim(id)
{
    // Optional: force a common crouch gait. Most HL clients animate fine without this.
    set_pev(id, pev_gaitsequence, 4);
    set_pev(id, pev_frame, 0.0);
    set_pev(id, pev_framerate, 1.0);
    set_pev(id, pev_animtime, get_gametime());
}

stock reset_player_state(id)
{
    g_isDucking[id] = false;

    g_haveStandHull[id] = false;
    g_stand_mins[id][0] = g_stand_mins[id][1] = g_stand_mins[id][2] = 0.0;
    g_stand_maxs[id][0] = g_stand_maxs[id][1] = g_stand_maxs[id][2] = 0.0;

    g_haveStandView[id] = false;
    g_stand_viewofs[id][0] = g_stand_viewofs[id][1] = g_stand_viewofs[id][2] = 0.0;

    g_baseMax[id] = 250.0;
}
