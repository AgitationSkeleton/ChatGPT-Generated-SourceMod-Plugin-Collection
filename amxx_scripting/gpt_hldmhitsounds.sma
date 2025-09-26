// gpt_hldmhitsounds.sma
// Half-Life Deathmatch: attacker-only hit sound on enemy player damage.
// Place the wav at: sound/Q3/hitsound.wav
//
// CVARs:
//   hldm_hits_enable    1
//   hldm_hits_cooldown  0.04
//   hldm_hits_min_dmg   0.0
//   hldm_hits_teamplay  1
//
// Requires: amxmodx, hamsandwich

#include <amxmodx>
#include <hamsandwich>

#define PLUGIN  "HLDM HitSound"
#define VERSION "1.0"

#define MAX_PLAYERS 32

new c_enable, c_cooldown, c_min_dmg, c_teamplay;
new Float:g_nextOkTime[MAX_PLAYERS + 1];

public plugin_precache()
{
    precache_sound("Q3/hitsound.wav");
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, "");

    c_enable    = register_cvar("hldm_hits_enable",   "1");
    c_cooldown  = register_cvar("hldm_hits_cooldown", "0.04");
    c_min_dmg   = register_cvar("hldm_hits_min_dmg",  "0.0");
    c_teamplay  = register_cvar("hldm_hits_teamplay", "1");

    RegisterHam(Ham_TakeDamage, "player", "OnPlayerTakeDamage");
}

public client_putinserver(id) { g_nextOkTime[id] = 0.0; }
public client_disconnect(id)  { g_nextOkTime[id] = 0.0; }

public OnPlayerTakeDamage(victim, inflictor, attacker, Float:damage, damagebits)
{
    if (!get_pcvar_num(c_enable))
        return HAM_IGNORED;

    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim))
        return HAM_IGNORED;

    if (attacker < 1 || attacker > MAX_PLAYERS || !is_user_connected(attacker) || attacker == victim)
        return HAM_IGNORED;

    if (damage <= get_pcvar_float(c_min_dmg))
        return HAM_IGNORED;

    if (get_pcvar_num(c_teamplay) && get_cvar_num("mp_teamplay") == 1)
    {
        if (get_user_team(attacker) == get_user_team(victim))
            return HAM_IGNORED;
    }

    new Float:now = get_gametime();
    new Float:cd  = get_pcvar_float(c_cooldown);
    if (cd < 0.0) cd = 0.0;

    if (now >= g_nextOkTime[attacker])
    {
        // No inner quotes needed; avoids invalid character constant errors.
        client_cmd(attacker, "spk Q3/hitsound.wav");
        g_nextOkTime[attacker] = now + cd;
    }

    return HAM_IGNORED;
}
