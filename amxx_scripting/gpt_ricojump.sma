/*  Ricochet Jump Re-Enable (Simulated Jump)
 *  Requires: fakemeta
 *  Author: ChatGPT
 *  Version: 1.0.0
 *
 *  Idea: detect +jump and, when on ground, set upward velocity.
 *  Works around Ricochet disabling jump in the game DLL.
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN_NAME    "Ricochet Jump (Simulated)"
#define PLUGIN_AUTHOR  "ChatGPT"
#define PLUGIN_VER     "1.0.0"

// HL flags & inputs
#define FL_ONGROUND    (1<<9)
#define FL_WATERJUMP   (1<<11)

#define IN_JUMP        (1<<1)

new g_pEnable;
new g_pPower;
new g_pCooldown;

new Float:g_nextJump[33];

public plugin_init()
{
    register_plugin(PLUGIN_NAME, PLUGIN_VER, PLUGIN_AUTHOR);

    g_pEnable   = register_cvar("ricojump_enable", "1");
    g_pPower    = register_cvar("ricojump_power",  "285.0");
    g_pCooldown = register_cvar("ricojump_cd",     "0.26");

    // See player button state each frame
    register_forward(FM_CmdStart, "OnCmdStart");
}

public client_connect(id)
{
    g_nextJump[id] = 0.0;
}

public client_disconnected(id)
{
    g_nextJump[id] = 0.0;
}

public OnCmdStart(id, uc_handle, seed)
{
    if (!get_pcvar_num(g_pEnable)) return FMRES_IGNORED;
    if (id < 1 || id > 32)        return FMRES_IGNORED;
    if (!is_user_connected(id))    return FMRES_IGNORED;
    if (!is_user_alive(id))        return FMRES_IGNORED;

    // Don’t allow while waterjumping or on ladder, etc.
    new flags = pev(id, pev_flags);
    if (flags & FL_WATERJUMP)      return FMRES_IGNORED;

    // Only when on ground
    if (!(flags & FL_ONGROUND))    return FMRES_IGNORED;

    // Read usercmd buttons
    new buttons = get_uc(uc_handle, UC_Buttons);
    if (!(buttons & IN_JUMP))      return FMRES_IGNORED;

    // Cooldown
    new Float:now = get_gametime();
    if (now < g_nextJump[id])      return FMRES_IGNORED;

    // Basic “not underwater” guard (optional — Ricochet barely uses water)
    new Float:waterlevel = float(pev(id, pev_waterlevel));
    if (waterlevel >= 2.0)         return FMRES_IGNORED; // mostly submerged

    // Apply upward velocity
    new Float:vel[3];
    pev(id, pev_velocity, vel);

    new Float:power = floatmax(120.0, get_pcvar_float(g_pPower)); // minimum sane pulse
    vel[2] = power;

    set_pev(id, pev_velocity, vel);

    // Nudge next-allowed time
    new Float:cd = floatmax(0.0, get_pcvar_float(g_pCooldown));
    g_nextJump[id] = now + cd;

    // Optional: clear IN_JUMP so the engine/mod doesn’t interfere
    // (Ricochet ignores jump anyway; this prevents sticky input.)
    buttons &= ~IN_JUMP;
    set_uc(uc_handle, UC_Buttons, buttons);

    return FMRES_HANDLED;
}
