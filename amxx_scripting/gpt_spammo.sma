/*  dmc_spawner_ammo.sma — v1.6.0 (SpawnTouch-only, AMXX 1.8.x friendly)
 *
 *  Deathmatch Classic: while a player stands inside a weapon spawner,
 *  periodically grant ammo by spawning the pickup entity and forcing Touch.
 *
 *  Why SpawnTouch-only?
 *   - Your DMC build did not accept GiveAmmo into weapon inventory.
 *   - SpawnTouch worked (boxes grant lots of ammo), so we throttle with cooldowns.
 *
 *  Requires: fakemeta
 *
 *  CVars:
 *    dmc_spammo_enable        1
 *    dmc_spammo_interval      0.25     // polling tick; not the per-ammo rate
 *    dmc_spammo_sound         1
 *    dmc_spammo_debug         0
 *    dmc_spammo_rate_shells   1.00     // seconds between shell pickups while standing on spawner
 *    dmc_spammo_rate_nails    1.00
 *    dmc_spammo_rate_cells    1.50
 *    dmc_spammo_rate_rockets  5.00
 *
 *  Optional INI: addons/amxmodx/configs/dmc_spawner_ammo.ini
 *    Format: <weapon_classname> <ammo_token>
 *    ammo_token must be one of: shells | nails | cells | rockets
 *    Example:
 *      weapon_shotgun shells
 *      weapon_supershotgun shells
 *      weapon_nailgun nails
 *      weapon_supernailgun nails
 *      weapon_grenadelauncher rockets
 *      weapon_rocketlauncher  rockets
 *      weapon_lightning cells
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN  "DMC Spawner Ammo"
#define VERSION "1.6.0"
#define AUTHOR  "ChatGPT"

// sound constants (guard for old headers)
#if !defined CHAN_ITEM
    #define CHAN_ITEM   5
#endif
#if !defined ATTN_NORM
    #define ATTN_NORM   0.8
#endif
#if !defined PITCH_NORM
    #define PITCH_NORM  100
#endif

#define SOUND_AMMO  "items/9mmclip1.wav"

#define MAX_SPAWNERS  512
#define MAX_CLASSLEN  64

// ammo indices (no typed enums to keep old compilers happy)
#define AK_SHELLS  0
#define AK_NAILS   1
#define AK_CELLS   2
#define AK_ROCKETS 3
#define AK_COUNT   4

// CVars
new g_cvEnable, g_cvInterval, g_cvDebug, g_cvSound;
new g_cvRateShells, g_cvRateNails, g_cvRateCells, g_cvRateRockets;

// spawner cache
new g_spawnerCount;
new g_spawnerEnts[MAX_SPAWNERS];
new Float:g_spMin[MAX_SPAWNERS][3];
new Float:g_spMax[MAX_SPAWNERS][3];
new g_spWpnClass[MAX_SPAWNERS][MAX_CLASSLEN];

// weapon -> ammo token (shells/nails/cells/rockets)
new Trie:g_weapon2ammo;

// per-player next-allowed grant times per ammo kind
new Float:g_nextAllow[33][AK_COUNT];

public plugin_precache()
{
    precache_sound(SOUND_AMMO);
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    g_cvEnable   = register_cvar("dmc_spammo_enable", "1");
    g_cvInterval = register_cvar("dmc_spammo_interval", "0.25");
    g_cvSound    = register_cvar("dmc_spammo_sound", "1");
    g_cvDebug    = register_cvar("dmc_spammo_debug", "0");

    // per-ammo cooldowns (seconds)
    g_cvRateShells  = register_cvar("dmc_spammo_rate_shells",  "1.0");
    g_cvRateNails   = register_cvar("dmc_spammo_rate_nails",   "1.0");
    g_cvRateCells   = register_cvar("dmc_spammo_rate_cells",   "1.5");
    g_cvRateRockets = register_cvar("dmc_spammo_rate_rockets", "5.0");

    g_weapon2ammo = TrieCreate();
    load_default_mapping();
}

public plugin_cfg()
{
    set_task(0.5, "scan_spawners");
    set_task(1.0, "start_tick");
}

public client_putinserver(id)
{
    for (new k = 0; k < AK_COUNT; k++)
        g_nextAllow[id][k] = 0.0;
}

public client_disconnect(id)
{
    for (new k = 0; k < AK_COUNT; k++)
        g_nextAllow[id][k] = 0.0;
}

public plugin_end()
{
    if (g_weapon2ammo) TrieDestroy(g_weapon2ammo);
}

public start_tick()
{
    if (!get_pcvar_num(g_cvEnable)) return;

    new Float:interval = get_pcvar_float(g_cvInterval);
    if (interval <= 0.0) interval = 0.25;

    // repeating
    set_task(interval, "tick_give_ammo", .flags="b");
}

public scan_spawners()
{
    g_spawnerCount = 0;

    new Array:keys = ArrayCreate(MAX_CLASSLEN, 16);
    gather_scan_keys(keys);

    new classname[MAX_CLASSLEN];
    for (new i = 0; i < ArraySize(keys); i++)
    {
        ArrayGetString(keys, i, classname, charsmax(classname));
        new e = 0;
        while ((e = engfunc(EngFunc_FindEntityByString, e, "classname", classname)))
        {
            if (!pev_valid(e)) continue;
            if (g_spawnerCount >= MAX_SPAWNERS) break;

            g_spawnerEnts[g_spawnerCount] = e;
            pev(e, pev_absmin, g_spMin[g_spawnerCount]);
            pev(e, pev_absmax, g_spMax[g_spawnerCount]);
            copy(g_spWpnClass[g_spawnerCount], charsmax(g_spWpnClass[]), classname);
            g_spawnerCount++;
        }
    }
    ArrayDestroy(keys);

    load_ini_mapping();

    if (get_pcvar_num(g_cvDebug))
        server_print("[DMC-SpawnAmmo] cached %d spawners", g_spawnerCount);
}

public tick_give_ammo()
{
    if (!get_pcvar_num(g_cvEnable) || g_spawnerCount <= 0) return;

    new maxp = get_maxplayers();
    new Float:now = get_gametime();

    for (new id = 1; id <= maxp; id++)
    {
        if (!is_user_alive(id)) continue;

        static Float:pos[3];
        pev(id, pev_origin, pos);

        for (new i = 0; i < g_spawnerCount; i++)
        {
            if (!point_in_bbox(pos, g_spMin[i], g_spMax[i])) continue;

            new ammoTok[32];
            if (!TrieGetString(g_weapon2ammo, g_spWpnClass[i], ammoTok, charsmax(ammoTok)))
                break;

            new ak = ammo_index(ammoTok);
            if (ak == -1) break;

            // cooldown check
            if (now < g_nextAllow[id][ak])
                break;

            // attempt pickup spawn-touch
            if (spawn_touch_pickup(id, ak))
            {
                g_nextAllow[id][ak] = now + rate_for_ammo(ak);

                if (get_pcvar_num(g_cvSound))
                    engfunc(EngFunc_EmitSound, id, CHAN_ITEM, SOUND_AMMO, 1.0, ATTN_NORM, 0, PITCH_NORM);

                if (get_pcvar_num(g_cvDebug))
                    client_print(id, print_center, "Spawner ammo: %s", ammoTok);
            }
            else if (get_pcvar_num(g_cvDebug))
            {
                client_print(id, print_center, "Spawner ammo failed: %s", ammoTok);
            }

            // one attempt per tick
            break;
        }
    }
}

/* ---------------- helpers ---------------- */

stock bool:point_in_bbox(const Float:p[3], const Float:bmin[3], const Float:bmax[3])
{
    return (p[0] >= bmin[0] && p[0] <= bmax[0] &&
            p[1] >= bmin[1] && p[1] <= bmax[1] &&
            p[2] >= bmin[2] && p[2] <= bmax[2]);
}

// map token -> index
stock ammo_index(const tok[])
{
    if (equali(tok, "shells"))  return AK_SHELLS;
    if (equali(tok, "nails"))   return AK_NAILS;
    if (equali(tok, "cells"))   return AK_CELLS;
    if (equali(tok, "rockets")) return AK_ROCKETS;
    return -1;
}

stock Float:rate_for_ammo(ak)
{
    if (ak == AK_SHELLS)  return get_pcvar_float(g_cvRateShells);
    if (ak == AK_NAILS)   return get_pcvar_float(g_cvRateNails);
    if (ak == AK_CELLS)   return get_pcvar_float(g_cvRateCells);
    if (ak == AK_ROCKETS) return get_pcvar_float(g_cvRateRockets);
    return 1.0;
}

// choose pickup classname for SpawnTouch (nails tries spikes first, then nails)
stock pickup_class_for_ak(ak, out[], len)
{
    if (ak == AK_SHELLS)  { copy(out, len, "item_shells");  return; }
    if (ak == AK_NAILS)   { copy(out, len, "item_spikes");  return; } // preferred
    if (ak == AK_CELLS)   { copy(out, len, "item_cells");   return; }
    if (ak == AK_ROCKETS) { copy(out, len, "item_rockets"); return; }
    out[0] = 0;
}

// create pickup entity at player origin and force Touch.
// returns true if Touch succeeded
stock bool:spawn_touch_pickup(id, ak)
{
    new cls[32];
    pickup_class_for_ak(ak, cls, charsmax(cls));
    if (!cls[0]) return false;

    if (spawn_touch_once(id, cls))
        return true;

    // nails fallback from spikes -> nails
    if (ak == AK_NAILS)
    {
        if (spawn_touch_once(id, "item_nails"))
            return true;
    }

    return false;
}

stock bool:spawn_touch_once(id, const pickupClass[])
{
    new si = engfunc(EngFunc_AllocString, pickupClass);
    if (!si) return false;

    new ent = engfunc(EngFunc_CreateNamedEntity, si);
    if (!ent || !pev_valid(ent)) return false;

    static Float:pos[3];
    pev(id, pev_origin, pos);

    set_pev(ent, pev_origin, pos);
    set_pev(ent, pev_solid, SOLID_TRIGGER);
    set_pev(ent, pev_movetype, MOVETYPE_TOSS);

    dllfunc(DLLFunc_Spawn, ent);
    dllfunc(DLLFunc_Touch, ent, id);

    if (pev_valid(ent))
        engfunc(EngFunc_RemoveEntity, ent);

    return true;
}

stock load_default_mapping()
{
    TrieSetString(g_weapon2ammo, "weapon_shotgun",         "shells");
    TrieSetString(g_weapon2ammo, "weapon_supershotgun",    "shells");
    TrieSetString(g_weapon2ammo, "weapon_nailgun",         "nails");
    TrieSetString(g_weapon2ammo, "weapon_supernailgun",    "nails");
    TrieSetString(g_weapon2ammo, "weapon_grenadelauncher", "rockets");
    TrieSetString(g_weapon2ammo, "weapon_rocketlauncher",  "rockets");
    TrieSetString(g_weapon2ammo, "weapon_lightning",       "cells");
}

// Back-compat path to configs/
stock get_configs_path(out[], len)
{
    get_localinfo("amxx_configsdir", out, len);
    if (out[0]) return;

    get_localinfo("amxx_basedir", out, len);
    if (out[0]) { add(out, len, "/configs"); return; }

    copy(out, len, "addons/amxmodx/configs");
}

stock load_ini_mapping()
{
    new cfg[192];
    get_configs_path(cfg, charsmax(cfg));
    add(cfg, charsmax(cfg), "/dmc_spawner_ammo.ini");

    new fp = fopen(cfg, "rt");
    if (!fp) return;

    new line[256], w[MAX_CLASSLEN], a[MAX_CLASSLEN];
    while (!feof(fp))
    {
        fgets(fp, line, charsmax(line));
        trim(line);
        if (!line[0] || line[0] == ';' || line[0] == '#') continue;

        if (parse(line, w, charsmax(w), a, charsmax(a)) >= 2)
        {
            // Only accept the four bare tokens
            if (equali(a, "shells") || equali(a, "nails") || equali(a, "cells") || equali(a, "rockets"))
                TrieSetString(g_weapon2ammo, w, a);
        }
    }
    fclose(fp);
}

stock gather_scan_keys(Array:out)
{
    static const keys[][] =
    {
        "weapon_shotgun",
        "weapon_supershotgun",
        "weapon_nailgun",
        "weapon_supernailgun",
        "weapon_grenadelauncher",
        "weapon_rocketlauncher",
        "weapon_lightning"
    };
    for (new i = 0; i < sizeof keys; i++)
        ArrayPushString(out, keys[i]);

    // Include any weapons from INI
    new cfg[192];
    get_configs_path(cfg, charsmax(cfg));
    add(cfg, charsmax(cfg), "/dmc_spawner_ammo.ini");

    new fp = fopen(cfg, "rt");
    if (!fp) return;

    new line[256], w[MAX_CLASSLEN], a[MAX_CLASSLEN];
    while (!feof(fp))
    {
        fgets(fp, line, charsmax(line));
        trim(line);
        if (!line[0] || line[0] == ';' || line[0] == '#') continue;

        if (parse(line, w, charsmax(w), a, charsmax(a)) >= 2)
        {
            if (!array_has_string(out, w))
                ArrayPushString(out, w);
        }
    }
    fclose(fp);
}

stock bool:array_has_string(Array:a, const s[])
{
    new n = ArraySize(a);
    new tmp[128];
    for (new i = 0; i < n; i++)
    {
        ArrayGetString(a, i, tmp, charsmax(tmp));
        if (equali(tmp, s)) return true;
    }
    return false;
}
