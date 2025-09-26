// gpt_playersounds.sma
// Classic player sounds (jump / pain / death) for HLDM / TFC / DMC.
//
// Default: play sounds client-side only (attacker/victim hear their own).
// Optional: set ps_world 1 to emit from the player entity so others nearby hear them.
//
// CVARs:
//   ps_enable          1      // master enable
//   ps_jump_enable     1
//   ps_pain_enable     1
//   ps_death_enable    1
//   ps_world           0      // 0: client_cmd spk (local only); 1: emit_sound from player (audible to others)
//   ps_pain_cooldown   0.25   // seconds between pain sounds per player
//   ps_jump_cooldown   0.20   // seconds between jump sounds per player
//
// Modules: amxmodx, fakemeta, hamsandwich
// No author attribution per request.

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "PlayerSounds"
#define VERSION "1.0"

#define MAX_PLAYERS 32

// Some HL SDKs miss IN_JUMP in older includes; define if needed.
#if !defined IN_JUMP
    #define IN_JUMP (1<<1)
#endif

// -------- CVARs --------
new c_enable, c_jump_en, c_pain_en, c_death_en, c_world, c_pain_cd, c_jump_cd;

// -------- State --------
new bool:g_prevHoldJump[MAX_PLAYERS + 1];
new Float:g_nextPainOk[MAX_PLAYERS + 1];
new Float:g_nextJumpOk[MAX_PLAYERS + 1];

// Sound tables
new const JUMP_SND[] = "player/plyrjmp8.wav";

new const PAIN_SND[][32] = {
    "player/pain1.wav",
    "player/pain2.wav",
    "player/pain3.wav",
    "player/pain4.wav",
    "player/pain5.wav",
    "player/pain6.wav"
};
const PAIN_CT = sizeof PAIN_SND;

new const DEATH_SND[][32] = {
    "player/death1.wav",
    "player/death2.wav",
    "player/death3.wav",
    "player/death4.wav",
    "player/death5.wav"
};
const DEATH_CT = sizeof DEATH_SND;

public plugin_precache()
{
    // Precache & add to download table
    precache_sound(JUMP_SND);

    for (new i = 0; i < PAIN_CT; i++)
        precache_sound(PAIN_SND[i]);

    for (new i = 0; i < DEATH_CT; i++)
        precache_sound(DEATH_SND[i]);
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

    // Jump (edge-detect +jump via CmdStart)
    register_forward(FM_CmdStart, "OnCmdStart");

    // Pain / death
    RegisterHam(Ham_TakeDamage, "player", "OnPlayerTakeDamage");   // victim path
    RegisterHam(Ham_Killed,     "player", "OnPlayerKilledPost", 1);// post for clean state
}

public client_putinserver(id)
{
    g_prevHoldJump[id] = false;
    g_nextPainOk[id] = 0.0;
    g_nextJumpOk[id] = 0.0;
}
public client_disconnect(id)
{
    g_prevHoldJump[id] = false;
    g_nextPainOk[id] = 0.0;
    g_nextJumpOk[id] = 0.0;
}

// -------- Jump detection (rising edge of +jump while on ground) --------
public OnCmdStart(id, uc, seed)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_jump_en))
        return FMRES_IGNORED;
    if (!is_user_connected(id) || !is_user_alive(id))
        return FMRES_IGNORED;

    // ignore observers (iuser1 != 0 in many HL mods)
    if (pev(id, pev_iuser1) != 0)
        return FMRES_IGNORED;

    // Require "on ground" to avoid mid-air spam; let shallow water still work
    new flags = pev(id, pev_flags);
    new ground = pev(id, pev_groundentity); // -1 if airborne
    if (!(flags & FL_ONGROUND) || ground == -1)
    {
        g_prevHoldJump[id] = false; // disarm edge until grounded again
        return FMRES_IGNORED;
    }

    new btns = get_uc(uc, UC_Buttons);
    new bool:holding = (btns & IN_JUMP) != 0;

    // Rising edge: not held last frame, held now
    if (holding && !g_prevHoldJump[id])
    {
        // Cooldown to avoid double fire from prediction quirks
        new Float:now = get_gametime();
        new Float:cd  = get_pcvar_float(c_jump_cd);
        if (now >= g_nextJumpOk[id])
        {
            PlaySoundTo(id, JUMP_SND);
            g_nextJumpOk[id] = now + (cd < 0.0 ? 0.0 : cd);
        }
    }

    g_prevHoldJump[id] = holding;
    return FMRES_IGNORED;
}

// -------- Pain (victim hears) --------
public OnPlayerTakeDamage(victim, inflictor, attacker, Float:damage, dmgBits)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en))
        return HAM_IGNORED;

    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim))
        return HAM_IGNORED;
    if (!is_user_alive(victim)) // already dead? let death hook handle it
        return HAM_IGNORED;

    // Small >0 damage check; tweak threshold if you want
    if (damage <= 0.0)
        return HAM_IGNORED;

    // Cooldown per victim to avoid spam on rapid multi-hit damage
    new Float:now = get_gametime();
    new Float:cd  = get_pcvar_float(c_pain_cd);
    if (now < g_nextPainOk[victim])
        return HAM_IGNORED;

    // Random pain sound
    new idx = random_num(0, PAIN_CT - 1);
    PlaySoundTo(victim, PAIN_SND[idx]);

    g_nextPainOk[victim] = now + (cd < 0.0 ? 0.0 : cd);
    return HAM_IGNORED;
}

// -------- Death (victim hears) --------
public OnPlayerKilledPost(victim, attacker, shouldgib)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_death_en))
        return HAM_IGNORED;

    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim))
        return HAM_IGNORED;

    // Random death sound
    new idx = random_num(0, DEATH_CT - 1);
    PlaySoundTo(victim, DEATH_SND[idx]);

    // Reset jump edge on death
    g_prevHoldJump[victim] = false;

    return HAM_IGNORED;
}

// -------- Sound dispatch (client-only or world-emit) --------
stock PlaySoundTo(id, const path[])
{
    if (get_pcvar_num(c_world))
    {
        // Emit from the player so others can hear it (world audible).
        // CHAN_VOICE keeps it sensible; adjust ATTN_NORM if you want longer range.
        if (is_user_connected(id))
        {
            emit_sound(id, CHAN_VOICE, path, VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
        }
    }
    else
    {
        // Client-only: the target player hears it; others do not.
        client_cmd(id, "spk %s", path);
    }
}
