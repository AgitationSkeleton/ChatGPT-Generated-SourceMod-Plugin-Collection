/*  Ricochet PlayerSounds (Pain + Death only, No Ham, World-positioned)
 *
 *  - Pain sound to VICTIM on disc bump (Touch) and on real damage (Damage msg).
 *  - Death sound to VICTIM on kill (DeathMsg).
 *  - Sounds are emitted at the victim entity (world-spatial), not via client "spk".
 *
 *  CVARs:
 *    ps_enable            1
 *    ps_pain_enable       1
 *    ps_death_enable      1
 *    ps_pain_cooldown     0.25
 *    ps_disc_model_sub    disc   // substring used to tag disc projectiles from SetModel
 *    ps_debug             0      // 1=log pain/death; 2=also log SetModel lines (rate-limited)
 *
 *  Requires: amxmodx, fakemeta
 */

#include <amxmodx>
#include <fakemeta>

#define PLUGIN  "Ricochet PlayerSounds (Pain+Death, WorldPos)"
#define VERSION "1.1"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32
#define MAX_ENTS    2048

// emit_sound channel/pitch
#define CHAN_AUTO   0
#define VOL_NORM    1.0
#define ATTN_NORM   0.8
#define PITCH_NORM  100

// CVARs
new c_enable, c_pain_en, c_death_en, c_pain_cd, c_disc_sub, c_debug;

// Messages
new g_msgDamage, g_msgDeathMsg;

// State
new bool:g_isDisc[MAX_ENTS];
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

    c_enable   = register_cvar("ps_enable",          "1");
    c_pain_en  = register_cvar("ps_pain_enable",     "1");
    c_death_en = register_cvar("ps_death_enable",    "1");
    c_pain_cd  = register_cvar("ps_pain_cooldown",   "0.25");
    c_disc_sub = register_cvar("ps_disc_model_sub",  "disc");
    c_debug    = register_cvar("ps_debug",           "0");

    // Tag projectiles + detect bumps
    register_forward(FM_SetModel,      "fw_SetModel");
    register_forward(FM_Touch,         "fw_Touch");
    register_forward(FM_RemoveEntity,  "fw_RemoveEntity"); // clear tags on free

    // Message hooks
    g_msgDamage   = get_user_msgid("Damage");
    g_msgDeathMsg = get_user_msgid("DeathMsg");
    if (g_msgDamage)   register_message(g_msgDamage,   "msg_Damage");     // to victim
    if (g_msgDeathMsg) register_message(g_msgDeathMsg, "msg_DeathMsg");   // killer/victim userids

    for (new i=1;i<=MAX_PLAYERS;i++) g_nextPainOk[i]=0.0;
    g_nextSpamPrint = 0.0;
}

public client_putinserver(id) { g_nextPainOk[id] = 0.0; }
public client_disconnect(id)  { g_nextPainOk[id] = 0.0; }

/* ----- Tag disc entities when model is set ----- */
public fw_SetModel(ent, const model[])
{
    if (!pev_valid(ent)) return FMRES_IGNORED;

    if (get_pcvar_num(c_debug) >= 2)
    {
        new Float:now = get_gametime();
        if (now >= g_nextSpamPrint)
        {
            g_nextSpamPrint = now + 1.0;
            server_print("[PlayerSounds] SetModel ent=%d model='%s'", ent, model);
        }
    }

    static want[32];
    get_pcvar_string(c_disc_sub, want, charsmax(want));
    if (want[0] && containi(model, want) != -1)
    {
        if (0 < ent && ent < MAX_ENTS) g_isDisc[ent] = true;
    }
    return FMRES_IGNORED;
}

/* Clear tag when entity is removed (prevents stale indices) */
public fw_RemoveEntity(ent)
{
    if (0 < ent && ent < MAX_ENTS) g_isDisc[ent] = false;
    return FMRES_IGNORED;
}

stock bool:is_valid_player(id) { return (1 <= id <= MAX_PLAYERS) && is_user_connected(id); }
stock bool:is_disc(ent)        { return (ent > 0 && ent < MAX_ENTS && pev_valid(ent) && g_isDisc[ent]); }

/* ----- Bump detector (non-lethal hits) ----- */
public fw_Touch(a, b)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en)) return FMRES_IGNORED;

    if (is_disc(a) && is_valid_player(b)) { pain_victim_world(b, true);  return FMRES_IGNORED; }
    if (is_valid_player(a) && is_disc(b)) { pain_victim_world(a, true);  return FMRES_IGNORED; }

    return FMRES_IGNORED;
}

/* ----- Damage usermessage (real HP loss) ----- */
public msg_Damage(msg_id, msg_dest, victim)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_pain_en)) return PLUGIN_CONTINUE;
    if (!is_valid_player(victim) || !is_user_alive(victim))     return PLUGIN_CONTINUE;

    // Arg 2 = dmg_take (byte) > 0 means HP actually dropped.
    if (get_msg_args() >= 2 && get_msg_arg_int(2) > 0)
    {
        pain_victim_world(victim, false);
    }
    return PLUGIN_CONTINUE;
}

/* ----- Death message (always plays death sound) ----- */
public msg_DeathMsg(msg_id, msg_dest, id)
{
    if (!get_pcvar_num(c_enable) || !get_pcvar_num(c_death_en)) return PLUGIN_CONTINUE;
    if (get_msg_args() < 2) return PLUGIN_CONTINUE;

    new victim_uid = get_msg_arg_int(2);
    new victim = find_player("k", victim_uid);
    if (!is_valid_player(victim)) return PLUGIN_CONTINUE;

    // Emit death sound at the victim entity (works even at death time)
    world_sound(victim, DEATH_SND[random_num(0, DEATH_CT-1)]);

    if (get_pcvar_num(c_debug) >= 1)
    {
        static n[32]; get_user_name(victim, n, charsmax(n));
        server_print("[PlayerSounds] death -> victim=%s (%d)", n, victim);
    }
    return PLUGIN_CONTINUE;
}

/* ----- Helpers: play at victim entity, cooldown, logging ----- */
stock pain_victim_world(victim, bool:fromTouch)
{
    if (!is_valid_player(victim)) return;
    if (!is_user_alive(victim))   return;

    new Float:now = get_gametime();
    new Float:cd  = floatmax(0.0, get_pcvar_float(c_pain_cd));
    if (now < g_nextPainOk[victim]) return;

    world_sound(victim, PAIN_SND[random_num(0, PAIN_CT-1)]);
    g_nextPainOk[victim] = now + cd;

    if (get_pcvar_num(c_debug) >= 1)
    {
        static n[32]; get_user_name(victim, n, charsmax(n));
        server_print("[PlayerSounds] pain (%s) -> victim=%s (%d)",
                     fromTouch ? "touch" : "damage", n, victim);
    }
}

stock world_sound(ent, const sample[])
{
    if (!pev_valid(ent)) return;
    engfunc(EngFunc_EmitSound, ent, CHAN_AUTO, sample, VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
}
