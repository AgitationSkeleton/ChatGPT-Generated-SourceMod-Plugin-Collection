/*  Ricochet PlayerSounds (Pain + Death, World-positioned, Self-touch fixed)
 *  - Tags disc owner on SetModel by nearest player, with runtime override from pev_owner.
 *  - Skips pain on self-return touches; optional speed gate to ignore slow pickups.
 *
 *  CVARs:
 *    ps_enable            1
 *    ps_pain_enable       1
 *    ps_death_enable      1
 *    ps_pain_cooldown     0.25
 *    ps_disc_model_sub    "disc"
 *    ps_touch_min_speed   120.0   // u/s; 0 = disabled
 *    ps_debug             0       // 1=pain/death logs; 2=+SetModel logs (rate-limited)
 *
 *  Requires: amxmodx, fakemeta
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN  "Ricochet PlayerSounds (WorldPos, Owner-tag)"
#define VERSION "1.3"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32
#define MAX_ENTS    2048

#define CHAN_AUTO   0
#define VOL_NORM    1.0
#define ATTN_NORM   0.8
#define PITCH_NORM  100

// CVARs
new c_enable, c_pain_en, c_death_en, c_pain_cd, c_disc_sub, c_touch_minspeed, c_debug;

// Messages
new g_msgDamage, g_msgDeathMsg;

// State
new bool:g_isDisc[MAX_ENTS];
new g_discOwner[MAX_ENTS];            // fallback owner we record at SetModel
new Float:g_nextPainOk[MAX_PLAYERS + 1];
new Float:g_nextSpamPrint;

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
    for (new i=0;i<PAIN_CT;i++)  precache_sound(PAIN_SND[i]);
    for (new i=0;i<DEATH_CT;i++) precache_sound(DEATH_SND[i]);
}

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    c_enable         = register_cvar("ps_enable",           "1");
    c_pain_en        = register_cvar("ps_pain_enable",      "1");
    c_death_en       = register_cvar("ps_death_enable",     "0");
    c_pain_cd        = register_cvar("ps_pain_cooldown",    "0.25");
    c_disc_sub       = register_cvar("ps_disc_model_sub",   "disc");
    c_touch_minspeed = register_cvar("ps_touch_min_speed",  "120.0"); // 0 disables
    c_debug          = register_cvar("ps_debug",            "0");

    register_forward(FM_SetModel,      "fw_SetModel");
    register_forward(FM_Touch,         "fw_Touch");
    register_forward(FM_RemoveEntity,  "fw_RemoveEntity");

    g_msgDamage   = get_user_msgid("Damage");
    g_msgDeathMsg = get_user_msgid("DeathMsg");
    if (g_msgDamage)   register_message(g_msgDamage,   "msg_Damage");
    if (g_msgDeathMsg) register_message(g_msgDeathMsg, "msg_DeathMsg");

    for (new i=1;i<=MAX_PLAYERS;i++) g_nextPainOk[i]=0.0;
    for (new e=0;e<MAX_ENTS;e++) { g_isDisc[e]=false; g_discOwner[e]=0; }
    g_nextSpamPrint = 0.0;
}

public client_putinserver(id) { g_nextPainOk[id] = 0.0; }
public client_disconnect(id)  { g_nextPainOk[id] = 0.0; }

/* ----- Tag disc entities & record a fallback owner (nearest player) ----- */
public fw_SetModel(ent, const model[])
{
    if (!pev_valid(ent)) return FMRES_IGNORED;

    if (get_pcvar_num(c_debug) >= 2)
    {
        new Float:now = get_gametime();
        if (now >= g_nextSpamPrint)
        {
            g_nextSpamPrint = now + 1.0;
            server_print("[PS] SetModel ent=%d model='%s'", ent, model);
        }
    }

    static want[32];
    get_pcvar_string(c_disc_sub, want, charsmax(want));
    if (want[0] && containi(model, want) != -1)
    {
        if (0 < ent && ent < MAX_ENTS)
        {
            g_isDisc[ent] = true;

            // Record nearest player as fallback owner
            new owner = nearest_player(ent, 96.0);  // 96u radius works well at throw time
            g_discOwner[ent] = owner;               // 0 if none found (we'll still prefer pev_owner later)
            if (get_pcvar_num(c_debug) >= 2)
                server_print("[PS] tag disc %d fallbackOwner=%d", ent, owner);
        }
    }
    return FMRES_IGNORED;
}

/* Clear tags when entity is freed */
public fw_RemoveEntity(ent)
{
    if (0 < ent && ent < MAX_ENTS)
    {
        g_isDisc[ent] = false;
        g_discOwner[ent] = 0;
    }
    return FMRES_IGNORED;
}

stock bool:is_valid_player(id) { return (1 <= id <= MAX_PLAYERS) && is_user_connected(id); }
stock bool:is_disc(ent)        { return (ent > 0 && ent < MAX_ENTS && pev_valid(ent) && g_isDisc[ent]); }

/* Resolve disc owner: prefer pev_owner, else our recorded fallback */
stock resolve_disc_owner(ent)
{
    new owner = pev(ent, pev_owner);
    if (1 <= owner <= MAX_PLAYERS) return owner;
    return g_discOwner[ent];
}

/* Get speed magnitude of entity (units/sec) */
stock Float:ent_speed(ent)
{
    static Float:vel[3]; pev(ent, pev_velocity, vel);
    return floatsqroot(vel[0]*vel[0] + vel[1]*vel[1] + vel[2]*vel[2]);
}

/* ----- Bump detector (with self-return suppression + speed gate) ----- */
public fw_Touch(a, b)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en)) return FMRES_IGNORED;

    new Float:minspd = get_pcvar_float(c_touch_minspeed);
    if (minspd < 0.0) minspd = 0.0;

    // disc a → player b
    if (is_disc(a) && is_valid_player(b))
    {
        if (minspd > 0.0 && ent_speed(a) < minspd) return FMRES_IGNORED;

        new owner = resolve_disc_owner(a);
        if (owner == b && owner != 0) return FMRES_IGNORED; // self-return

        // If owner is unknown (0), allow pain for enemy bumps but not for self-touch: we
        // can best-effort skip if player is holding the disc model already, but Ricochet
        // doesn’t expose that cleanly; rely on speed gate to suppress pickups.
        pain_victim_world(b, true);
        return FMRES_IGNORED;
    }

    // player a → disc b
    if (is_valid_player(a) && is_disc(b))
    {
        if (minspd > 0.0 && ent_speed(b) < minspd) return FMRES_IGNORED;

        new owner = resolve_disc_owner(b);
        if (owner == a && owner != 0) return FMRES_IGNORED; // self-return
        pain_victim_world(a, true);
        return FMRES_IGNORED;
    }

    return FMRES_IGNORED;
}

/* ----- Real HP loss (always OK to play) ----- */
public msg_Damage(msg_id, msg_dest, victim)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en)) return PLUGIN_CONTINUE;
    if (!is_valid_player(victim) || !is_user_alive(victim))     return PLUGIN_CONTINUE;

    if (get_msg_args() >= 2 && get_msg_arg_int(2) > 0)
        pain_victim_world(victim, false);

    return PLUGIN_CONTINUE;
}

/* ----- Death ----- */
public msg_DeathMsg(msg_id, msg_dest, id)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_death_en)) return PLUGIN_CONTINUE;
    if (get_msg_args() < 2) return PLUGIN_CONTINUE;

    new victim_uid = get_msg_arg_int(2);
    new victim = find_player("k", victim_uid);
    if (!is_valid_player(victim)) return PLUGIN_CONTINUE;

    world_sound(victim, DEATH_SND[random_num(0, DEATH_CT-1)]);
    if (get_pcvar_num(c_debug) >= 1)
    {
        static n[32]; get_user_name(victim, n, charsmax(n));
        server_print("[PS] death -> victim=%s (%d)", n, victim);
    }
    return PLUGIN_CONTINUE;
}

/* ----- Helpers: world sound + cooldown ----- */
stock pain_victim_world(victim, bool:fromTouch)
{
    if (!is_valid_player(victim) || !is_user_alive(victim)) return;

    new Float:now = get_gametime();
    new Float:cd  = floatmax(0.0, get_pcvar_float(c_pain_cd));
    if (now < g_nextPainOk[victim]) return;

    world_sound(victim, PAIN_SND[random_num(0, PAIN_CT-1)]);
    g_nextPainOk[victim] = now + cd;

    if (get_pcvar_num(c_debug) >= 1)
    {
        static n[32]; get_user_name(victim, n, charsmax(n));
        server_print("[PS] pain (%s) -> victim=%s (%d)", fromTouch ? "touch" : "damage", n, victim);
    }
}

stock world_sound(ent, const sample[])
{
    if (!pev_valid(ent)) return;
    engfunc(EngFunc_EmitSound, ent, CHAN_AUTO, sample, VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
}

/* ----- Utility: nearest player to an entity within radius ----- */
stock nearest_player(ent, Float:radius)
{
    if (!pev_valid(ent)) return 0;

    static Float:org[3]; pev(ent, pev_origin, org);
    new best = 0;
    new Float:bestd2 = radius*radius;

    for (new id=1; id<=MAX_PLAYERS; id++)
    {
        if (!is_user_connected(id) || !is_user_alive(id)) continue;

        static Float:porg[3]; pev(id, pev_origin, porg);
        new Float:dx = porg[0]-org[0];
        new Float:dy = porg[1]-org[1];
        new Float:dz = porg[2]-org[2];
        new Float:d2 = dx*dx + dy*dy + dz*dz;

        if (d2 <= bestd2) { bestd2 = d2; best = id; }
    }
    return best; // 0 if none within radius
}
