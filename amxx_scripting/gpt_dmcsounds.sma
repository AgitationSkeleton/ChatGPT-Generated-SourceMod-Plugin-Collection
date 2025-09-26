// gpt_playersounds.sma
// Classic player sounds (jump / pain / death) for HLDM / TFC / DMC.
//
// CVARs:
//   ps_enable          1
//   ps_jump_enable     1
//   ps_pain_enable     1
//   ps_death_enable    1
//   ps_world           1    // 0: client-only via spk; 1: world-audible via emit_sound
//   ps_pain_cooldown   0.25
//   ps_jump_cooldown   0.20
//
// Modules: amxmodx, fakemeta, hamsandwich
// No author attribution per request.

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "PlayerSounds"
#define VERSION "1.2"

#define MAX_PLAYERS 32
#if !defined IN_JUMP
    #define IN_JUMP (1<<1)
#endif

// CVARs
new c_enable, c_jump_en, c_pain_en, c_death_en, c_world, c_pain_cd, c_jump_cd;

// State
new bool:g_jumpArmed[MAX_PLAYERS + 1];     // armed only after a key release on GROUND
new Float:g_nextPainOk[MAX_PLAYERS + 1];
new Float:g_nextJumpOk[MAX_PLAYERS + 1];

// Sounds
new const JUMP_SND[] = "player/plyrjmp8.wav"; // fixed path (with 'y')

new const PAIN_SND[][32] = {
    "player/pain1.wav","player/pain2.wav","player/pain3.wav",
    "player/pain4.wav","player/pain5.wav","player/pain6.wav"
};
const PAIN_CT = sizeof PAIN_SND;

new const DEATH_SND[][32] = {
    "player/death1.wav","player/death2.wav","player/death3.wav",
    "player/death4.wav","player/death5.wav"
};
const DEATH_CT = sizeof DEATH_SND;

public plugin_precache()
{
    precache_sound(JUMP_SND);
    for (new i=0;i<PAIN_CT;i++) precache_sound(PAIN_SND[i]);
    for (new i=0;i<DEATH_CT;i++) precache_sound(DEATH_SND[i]);
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, "");

    c_enable   = register_cvar("ps_enable",          "1");
    c_jump_en  = register_cvar("ps_jump_enable",     "1");
    c_pain_en  = register_cvar("ps_pain_enable",     "1");
    c_death_en = register_cvar("ps_death_enable",    "1");
    c_world    = register_cvar("ps_world",           "1");
    c_pain_cd  = register_cvar("ps_pain_cooldown",   "0.25");
    c_jump_cd  = register_cvar("ps_jump_cooldown",   "0.20");

    // Jump key handling
    register_forward(FM_CmdStart, "OnCmdStart");

    // Pain / Death
    RegisterHam(Ham_TakeDamage, "player", "OnPlayerTakeDamage");
    RegisterHam(Ham_Killed,     "player", "OnPlayerKilledPost", 1);
}

public client_putinserver(id)
{
    g_jumpArmed[id]  = true;  // first press on ground should fire
    g_nextPainOk[id] = 0.0;
    g_nextJumpOk[id] = 0.0;
}
public client_disconnect(id)
{
    g_jumpArmed[id]  = true;
    g_nextPainOk[id] = 0.0;
    g_nextJumpOk[id] = 0.0;
}

// ---- Jump: only on real keypress while grounded; mid-air presses are ignored for landing ----
public OnCmdStart(id, uc, seed)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_jump_en)) return FMRES_IGNORED;
    if (!is_user_connected(id) || !is_user_alive(id))          return FMRES_IGNORED;
    if (pev(id, pev_iuser1) != 0)                               return FMRES_IGNORED; // observers

    // Ground check
    new flags = pev(id, pev_flags);
    new ground = pev(id, pev_groundentity);
    new bool:onGround = ((flags & FL_ONGROUND) && ground != -1);

    new btns = get_uc(uc, UC_Buttons);
    new bool:holding = (btns & IN_JUMP) != 0;

    if (onGround)
    {
        // Re-arm only when the key is released on ground
        if (!holding)
        {
            g_jumpArmed[id] = true;
        }
        else if (g_jumpArmed[id])
        {
            // Holding + armed + on ground => play once, then disarm
            new Float:now = get_gametime();
            new Float:cd  = get_pcvar_float(c_jump_cd);
            if (now >= g_nextJumpOk[id])
            {
                PlaySoundTo(id, JUMP_SND);
                g_nextJumpOk[id] = now + (cd < 0.0 ? 0.0 : cd);
            }
            g_jumpArmed[id] = false;
        }
    }
    else
    {
        // Airborne: if the key is held in mid-air, explicitly DISARM so landing won't fire
        if (holding)
            g_jumpArmed[id] = false;
        // If released mid-air, leave as-is (we'll re-arm only when released on ground)
    }

    return FMRES_IGNORED;
}

// ---- Pain (victim hears) ----
public OnPlayerTakeDamage(victim, inflictor, attacker, Float:damage, dmgBits)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en)) return HAM_IGNORED;
    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim)) return HAM_IGNORED;
    if (!is_user_alive(victim)) return HAM_IGNORED;
    if (damage <= 0.0) return HAM_IGNORED;

    new Float:now = get_gametime();
    if (now < g_nextPainOk[victim]) return HAM_IGNORED;

    new idx = random_num(0, PAIN_CT - 1);
    PlaySoundTo(victim, PAIN_SND[idx]);
    g_nextPainOk[victim] = now + floatmax(0.0, get_pcvar_float(c_pain_cd));

    return HAM_IGNORED;
}

// ---- Death (victim hears) ----
public OnPlayerKilledPost(victim, attacker, shouldgib)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_death_en)) return HAM_IGNORED;
    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim)) return HAM_IGNORED;

    new idx = random_num(0, DEATH_CT - 1);
    PlaySoundTo(victim, DEATH_SND[idx]);

    // After death, let the first on-ground keypress play again
    g_jumpArmed[victim] = true;
    return HAM_IGNORED;
}

// ---- Sound dispatch ----
stock PlaySoundTo(id, const path[])
{
    if (get_pcvar_num(c_world))
    {
        if (is_user_connected(id))
            emit_sound(id, CHAN_VOICE, path, VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
    }
    else
    {
        client_cmd(id, "spk %s", path);
    }
}
