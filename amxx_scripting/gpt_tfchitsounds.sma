// gpt_tfc_hitsound.sma
// Team Fortress Classic: play a client-only "hit sound" to the attacker
// when damaging enemies (players and optionally buildings).
//
// Place the wav at: sound/Q3/hitsound.wav
//
// CVARs:
//   tfc_hits_enable       1      // master on/off
//   tfc_hits_cooldown     0.04   // seconds between sounds per attacker (anti-spam)
//   tfc_hits_min_dmg      0.0    // minimum damage to trigger
//   tfc_hits_players      1      // 1 = play on enemy player hits
//   tfc_hits_buildings    1      // 1 = play on enemy building hits
//   tfc_hits_sg           1      // 1 = include sentryguns
//   tfc_hits_disp         1      // 1 = include dispensers
//   tfc_hits_tp           1      // 1 = include teleporters
//
// Notes:
// - Only the attacker hears the sound (client_cmd "spk").
// - Team checks prevent dings on teammates (standard TFC behavior).
// - Buildings' team resolved via pev_team, else via owner->team fallback.
//
// Requires: amxmodx, hamsandwich, fakemeta
// Author: gpt
// Version: 1.1 (TFC)

#include <amxmodx>
#include <hamsandwich>
#include <fakemeta>

#define PLUGIN  "TFC HitSound"
#define VERSION "1.1"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32

new c_enable, c_cooldown, c_min_dmg;
new c_players, c_buildings, c_sg, c_disp, c_tp;

new Float:g_nextOkTime[MAX_PLAYERS + 1];

public plugin_precache()
{
    precache_sound("Q3/hitsound.wav"); // adds to download table
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    c_enable    = register_cvar("tfc_hits_enable",    "1");
    c_cooldown  = register_cvar("tfc_hits_cooldown",  "0.04");
    c_min_dmg   = register_cvar("tfc_hits_min_dmg",   "0.0");

    c_players   = register_cvar("tfc_hits_players",   "1");
    c_buildings = register_cvar("tfc_hits_buildings", "1");
    c_sg        = register_cvar("tfc_hits_sg",        "1");
    c_disp      = register_cvar("tfc_hits_disp",      "1");
    c_tp        = register_cvar("tfc_hits_tp",        "1");

    // Players
    RegisterHam(Ham_TakeDamage, "player", "OnAnyTakeDamage");

    // TFC buildings (class names from server dll)
    RegisterHam(Ham_TakeDamage, "building_sentrygun", "OnAnyTakeDamage");
    RegisterHam(Ham_TakeDamage, "building_dispenser", "OnAnyTakeDamage");
    RegisterHam(Ham_TakeDamage, "building_teleporter", "OnAnyTakeDamage");
}

public client_putinserver(id) { g_nextOkTime[id] = 0.0; }
public client_disconnect(id)  { g_nextOkTime[id] = 0.0; }

public OnAnyTakeDamage(victim, inflictor, attacker, Float:damage, dmgBits)
{
    if (!get_pcvar_num(c_enable))
        return HAM_IGNORED;

    // Attacker must be a valid connected player
    if (attacker < 1 || attacker > MAX_PLAYERS || !is_user_connected(attacker))
        return HAM_IGNORED;

    // Ignore self damage
    if (victim == attacker)
        return HAM_IGNORED;

    // Positive-enough damage?
    if (damage <= get_pcvar_float(c_min_dmg))
        return HAM_IGNORED;

    // Determine whether this victim class should trigger
    static classname[32];
    pev(victim, pev_classname, classname, charsmax(classname));

    new bool:isPlayer = equali(classname, "player");
    if (isPlayer)
    {
        if (!get_pcvar_num(c_players))
            return HAM_IGNORED;
    }
    else
    {
        if (!get_pcvar_num(c_buildings))
            return HAM_IGNORED;

        // Filter specific building types
        if (equali(classname, "building_sentrygun") && !get_pcvar_num(c_sg))   return HAM_IGNORED;
        if (equali(classname, "building_dispenser") && !get_pcvar_num(c_disp)) return HAM_IGNORED;
        if (equali(classname, "building_teleporter") && !get_pcvar_num(c_tp))  return HAM_IGNORED;
    }

    // Team gating: only ding on ENEMY targets
    if (!IsEnemy(attacker, victim, isPlayer))
        return HAM_IGNORED;

    // Per-attacker cooldown (prevents overlapping spam)
    new Float:now = get_gametime();
    new Float:cd = get_pcvar_float(c_cooldown);
    if (cd < 0.0) cd = 0.0;

    if (now >= g_nextOkTime[attacker])
    {
        client_cmd(attacker, "spk ^"Q3/hitsound.wav^""); // attacker-only
        g_nextOkTime[attacker] = now + cd;
    }

    return HAM_IGNORED;
}

// ---------- Helpers ----------

// True if attacker and victim are on opposing teams.
// For buildings, use pev_team; if 0, try owner->team. Fall back to "enemy" if unknown.
stock bool:IsEnemy(attacker, victim, bool:victimIsPlayer)
{
    new aTeam = get_user_team(attacker);

    if (victimIsPlayer)
    {
        new vTeam = get_user_team(victim);
        return (aTeam != vTeam);
    }

    // building: try pev_team
    new vTeam = pev(victim, pev_team);
    if (vTeam == 0)
    {
        // try builder owner chain
        new owner = pev(victim, pev_owner);
        if (owner >= 1 && owner <= MAX_PLAYERS && is_user_connected(owner))
            vTeam = get_user_team(owner);
    }

    // If we still don't know, assume it's an enemy (treat neutral as enemy for feedback).
    if (vTeam <= 0)
        return true;

    return (aTeam != vTeam);
}
