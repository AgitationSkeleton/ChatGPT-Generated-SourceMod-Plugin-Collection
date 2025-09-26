// gpt_dmc_hitsound.sma
// DMC / GoldSrc: play a client-only "hit sound" to the attacker when they damage an enemy.
// Requires: amxmodx, hamsandwich, fakemeta (fakemeta not strictly needed here but common)
//
// Place the wav at: sound/Q3/hitsound.wav
//
// CVARs:
//   dmc_hits_enable    1      // master on/off
//   dmc_hits_cooldown  0.04   // seconds between sounds per attacker (anti-spam)
//   dmc_hits_min_dmg   0.0    // minimum damage to trigger
//   dmc_hits_teamplay  1      // when mp_teamplay is 1, only play on enemies (different team)

#include <amxmodx>
#include <hamsandwich>
#include <fakemeta>

#define PLUGIN  "DMC HitSound"
#define VERSION "1.0"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32

new c_enable, c_cooldown, c_min_dmg, c_teamplay;

new Float:g_nextOkTime[MAX_PLAYERS + 1];

public plugin_precache()
{
    // Precache adds to download table
    precache_sound("Q3/hitsound.wav");
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    c_enable    = register_cvar("dmc_hits_enable",   "1");
    c_cooldown  = register_cvar("dmc_hits_cooldown", "0.04");
    c_min_dmg   = register_cvar("dmc_hits_min_dmg",  "0.0");
    c_teamplay  = register_cvar("dmc_hits_teamplay", "1");

    RegisterHam(Ham_TakeDamage, "player", "OnPlayerTakeDamage"); // pre is fine for this
}

public client_putinserver(id)
{
    g_nextOkTime[id] = 0.0;
}
public client_disconnect(id)
{
    g_nextOkTime[id] = 0.0;
}

public OnPlayerTakeDamage(victim, inflictor, attacker, Float:damage, damagebits)
{
    if (!get_pcvar_num(c_enable))
        return HAM_IGNORED;

    // Valid victim?
    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim))
        return HAM_IGNORED;

    // Valid attacker & not self-damage
    if (attacker < 1 || attacker > MAX_PLAYERS || !is_user_connected(attacker) || attacker == victim)
        return HAM_IGNORED;

    // Must be positive damage (you can raise this with cvar if you want)
    new Float:min_dmg = get_pcvar_float(c_min_dmg);
    if (damage <= min_dmg)
        return HAM_IGNORED;

    // Optional: respect teamplay (no ding on friendly fire when mp_teamplay=1)
    if (get_pcvar_num(c_teamplay) && get_cvar_num("mp_teamplay") == 1)
    {
        // get_user_team returns team index; equal => same team
        if (get_user_team(attacker) == get_user_team(victim))
            return HAM_IGNORED;
    }

    // Per-attacker cooldown to avoid overlapping spam
    new Float:now = get_gametime();
    new Float:cd  = get_pcvar_float(c_cooldown);
    if (cd < 0.0) cd = 0.0;

    if (now >= g_nextOkTime[attacker])
    {
        // Client-only playback so only the attacker hears it.
        // (emit_sound would be audible to others; client_cmd "spk" is private.)
        client_cmd(attacker, "spk ^"Q3/hitsound.wav^"");
        g_nextOkTime[attacker] = now + cd;
    }

    return HAM_IGNORED;
}
