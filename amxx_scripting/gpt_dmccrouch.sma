// dmc_restore_crouch.sma
// Restores server-side crouching for Deathmatch Classic
// Hull + view with safe spawn behavior and exact view restore.
// Requires: fakemeta, hamsandwich

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "DMC Restore Crouch"
#define VERSION "0.3c"
#define AUTHOR  "gpt"

// --- Cvars ---
new c_duck_speed_mult;   // 0.55 default; set 1.0 to disable speed change
new c_duck_viewofs_z;    // target eye Z while crouched (visual only)
new c_duck_mins_x, c_duck_mins_y, c_duck_mins_z;
new c_duck_maxs_x, c_duck_maxs_y, c_duck_maxs_z;
new c_headroom_trace;    // 1 = enforce headroom to stand
new c_anim_enable;       // kept but default off

// Per-player state
new bool:g_isDucking[33];

// Cache of standing hull & view (exact restore)
new bool:g_haveStandHull[33];
new Float:g_stand_mins[33][3];
new Float:g_stand_maxs[33][3];

new bool:g_haveStandView[33];
new Float:g_stand_viewofs[33][3];

// Cached base maxspeed to avoid compounding
new Float:g_baseMax[33];

// Default stand hull (fallback only; we restore cached hull when available)
new const Float:DEF_STAND_MINS[3] = { -16.0, -16.0, -36.0 };
new const Float:DEF_STAND_MAXS[3] = {  16.0,  16.0,  36.0 };

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    c_duck_speed_mult  = register_cvar("dmc_duck_speed_mult", "0.55");
    c_duck_viewofs_z   = register_cvar("dmc_duck_view_z",     "12.0");

    // Hull sizes (override if your maps/models need it)
    c_duck_mins_x = register_cvar("dmc_duck_mins_x", "-16.0");
    c_duck_mins_y = register_cvar("dmc_duck_mins_y", "-16.0");
    c_duck_mins_z = register_cvar("dmc_duck_mins_z", "-18.0");
    c_duck_maxs_x = register_cvar("dmc_duck_maxs_x",  "16.0");
    c_duck_maxs_y = register_cvar("dmc_duck_maxs_y",  "16.0");
    c_duck_maxs_z = register_cvar("dmc_duck_maxs_z",  "18.0");

    c_headroom_trace = register_cvar("dmc_duck_headroom", "1");
    c_anim_enable    = register_cvar("dmc_duck_anim",     "0"); // HL client handles crouch anims fine

    // Per-frame hook
    RegisterHam(Ham_Player_PreThink, "player", "OnPlayerPreThink");

    // Reset on spawn/death (do NOT touch hull/view here)
    RegisterHam(Ham_Spawn,  "player", "OnPlayerSpawnPost", 1);
    RegisterHam(Ham_Killed, "player", "OnPlayerKilledPost", 1);
}

public client_putinserver(id)  { reset_player_state(id); }
public client_disconnect(id)   { reset_player_state(id); }

public OnPlayerSpawnPost(id)
{
    if (!is_user_connected(id)) return;
    // Do not set hull or view on spawn; let the game/client establish the baseline
    reset_player_state(id);
}

public OnPlayerKilledPost(victim, killer, shouldgib)
{
    if (!is_user_connected(victim)) return;
    g_isDucking[victim] = false;
}

// ---------- ALIVE/SPAwned guard ----------
stock bool:IsSpawnedAlive(id)
{
    // Must be in-game, alive, and not in spectator/observer mode
    if (!is_user_connected(id) || !is_user_alive(id)) return false;

    // Spectator check: HL uses iuser1 for observer modes (0 == not observing)
    // If unavailable in a given mod, this will just read 0 and pass.
    new iobs = pev(id, pev_iuser1);
    if (iobs != 0) return false;

    // Optional: ignore players with no collision (not spawned in world)
    new solid = pev(id, pev_solid);
    if (solid == SOLID_NOT) return false;

    return true;
}

public OnPlayerPreThink(id)
{
    if (!IsSpawnedAlive(id)) return HAM_IGNORED;

    // Read buttons
    new buttons = pev(id, pev_button);
    new bool:wantsDuck = (buttons & IN_DUCK) != 0;

    if (wantsDuck)
    {
        if (!g_isDucking[id])
        {
            // Cache exact standing hull & view right before we change them
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
            // No per-frame speed scaling (prevents freeze). Anim forcing optional.
            if (get_pcvar_num(c_anim_enable)) ApplyDuckAnim(id);
        }
    }
    else if (g_isDucking[id])
    {
        if (CanStandHere(id) || !get_pcvar_num(c_headroom_trace))
        {
            ExitDuck(id);
            g_isDucking[id] = false;
        }
        // else: remain crouched until headroom is clear
    }

    return HAM_IGNORED;
}

// --- Core ops ---

stock bool:EnterDuck(id)
{
    if (!IsSpawnedAlive(id)) return false;

    // Build duck hull using live standing XY (from cache) and duck Z from cvars
    new Float:mins[3], Float:maxs[3];

    if (g_haveStandHull[id])
    {
        mins[0] = g_stand_mins[id][0];
        mins[1] = g_stand_mins[id][1];
        maxs[0] = g_stand_maxs[id][0];
        maxs[1] = g_stand_maxs[id][1];
    }
    else
    {
        // Fallback to live values if somehow missing
        pev(id, pev_mins, mins);
        pev(id, pev_maxs, maxs);
    }

    mins[2] = get_pcvar_float(c_duck_mins_z);
    maxs[2] = get_pcvar_float(c_duck_maxs_z);

    engfunc(EngFunc_SetSize, id, mins, maxs);

    // Mark ducking for footsteps/hitboxes (optional; comment if prediction feels off)
    set_pev(id, pev_flags, pev(id, pev_flags) | FL_DUCKING);

    // Lower view (visual). Keep X/Y, only change Z.
    SetViewZ(id, get_pcvar_float(c_duck_viewofs_z));

    // Apply speed once based on cached base; no per-frame compounding
    ApplyDuckSpeedOnce(id);

    if (get_pcvar_num(c_anim_enable)) ApplyDuckAnim(id);

    return true;
}

stock ExitDuck(id)
{
    if (!IsSpawnedAlive(id)) return;

    // Restore exact standing hull if known; otherwise safe defaults
    if (g_haveStandHull[id])
        engfunc(EngFunc_SetSize, id, g_stand_mins[id], g_stand_maxs[id]);
    else
        engfunc(EngFunc_SetSize, id, DEF_STAND_MINS, DEF_STAND_MAXS);

    // Clear flag
    set_pev(id, pev_flags, pev(id, pev_flags) & ~FL_DUCKING);

    // Restore the exact standing view we cached, if available
    if (g_haveStandView[id])
        set_pev(id, pev_view_ofs, g_stand_viewofs[id]);

    // Restore cached base speed (or a sane fallback)
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
    // Trace a standing hull at the player's origin; if starts in solid, no headroom
    new Float:origin[3]; pev(id, pev_origin, origin);

    new tr = 0;
    engfunc(EngFunc_TraceHull, origin, origin, 0, HULL_HUMAN, id, tr);

    return (get_tr2(tr, TR_StartSolid) == 0 && get_tr2(tr, TR_AllSolid) == 0);
}

stock SetViewZ(id, Float:z)
{
    // Adjust only Z, keep X/Y from current view_ofs; this respects client prediction.
    new Float:v[3]; pev(id, pev_view_ofs, v);
    v[2] = z;
    set_pev(id, pev_view_ofs, v);
    // Optional aux mirror
    set_pev(id, pev_vuser1, v);
}

// Only needed if you ever enable c_anim_enable (not recommended for stock HL models)
stock ApplyDuckAnim(id)
{
    set_pev(id, pev_gaitsequence, 4); // common crouch gait in HL player.mdl
    set_pev(id, pev_frame, 0.0);
    set_pev(id, pev_framerate, 1.0);
    set_pev(id, pev_animtime, get_gametime());
}

// --- Helpers ---
stock GetDuckHull(Float:mins[3], Float:maxs[3])
{
    mins[0] = get_pcvar_float(c_duck_mins_x);
    mins[1] = get_pcvar_float(c_duck_mins_y);
    mins[2] = get_pcvar_float(c_duck_mins_z);
    maxs[0] = get_pcvar_float(c_duck_maxs_x);
    maxs[1] = get_pcvar_float(c_duck_maxs_y);
    maxs[2] = get_pcvar_float(c_duck_maxs_z);
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
