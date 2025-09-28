/*  Ricochet Jump (Simulated) + Local Jump Sound
 *  Requires: amxmodx, fakemeta
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN_NAME    "Ricochet Jump (Simulated + Sound)"
#define PLUGIN_AUTHOR  "ChatGPT"
#define PLUGIN_VER     "1.1.0"

#define FL_ONGROUND    (1<<9)
#define FL_WATERJUMP   (1<<11)
#define IN_JUMP        (1<<1)

new g_pEnable, g_pPower, g_pCooldown, g_pSoundEn;
new Float:g_nextJump[33];

new const JUMP_SND[] = "player/plyrjmp8.wav";

public plugin_precache()
{
    precache_sound(JUMP_SND);
}

public plugin_init()
{
    register_plugin(PLUGIN_NAME, PLUGIN_VER, PLUGIN_AUTHOR);

    g_pEnable   = register_cvar("ricojump_enable", "1");
    g_pPower    = register_cvar("ricojump_power",  "285.0");
    g_pCooldown = register_cvar("ricojump_cd",     "0.26");
    g_pSoundEn  = register_cvar("ricojump_sound",  "1");   // 1 = play local jump sound

    register_forward(FM_CmdStart, "OnCmdStart");
}

public client_connect(id)      { g_nextJump[id] = 0.0; }
public client_disconnected(id) { g_nextJump[id] = 0.0; }

public OnCmdStart(id, uc_handle, seed)
{
    if (!get_pcvar_num(g_pEnable)) return FMRES_IGNORED;
    if (id < 1 || id > 32)         return FMRES_IGNORED;
    if (!is_user_connected(id) || !is_user_alive(id)) return FMRES_IGNORED;

    new flags = pev(id, pev_flags);
    if (flags & FL_WATERJUMP)      return FMRES_IGNORED;
    if (!(flags & FL_ONGROUND))    return FMRES_IGNORED;

    new buttons = get_uc(uc_handle, UC_Buttons);
    if (!(buttons & IN_JUMP))      return FMRES_IGNORED;

    new Float:now = get_gametime();
    if (now < g_nextJump[id])      return FMRES_IGNORED;

    new Float:waterlevel = float(pev(id, pev_waterlevel));
    if (waterlevel >= 2.0)         return FMRES_IGNORED;

    // Apply upward velocity
    new Float:vel[3];
    pev(id, pev_velocity, vel);

    new Float:power = floatmax(120.0, get_pcvar_float(g_pPower));
    vel[2] = power;
    set_pev(id, pev_velocity, vel);

    g_nextJump[id] = now + floatmax(0.0, get_pcvar_float(g_pCooldown));

    // Local jump sound for this player only
    if (get_pcvar_num(g_pSoundEn))
    {
        client_cmd(id, "spk ^"%s^"", JUMP_SND);
    }

    // Clear IN_JUMP to prevent sticky input
    buttons &= ~IN_JUMP;
    set_uc(uc_handle, UC_Buttons, buttons);

    return FMRES_HANDLED;
}
