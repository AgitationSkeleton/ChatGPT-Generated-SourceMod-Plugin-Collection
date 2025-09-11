/*  gpt_quakesounds.sma  (Ricochet / HL-generic, no Ham)
 *
 *  Ricochet-oriented UT/Quake killstreak sounds using DeathMsg + Fakemeta/Engine.
 *  AMX Mod X 1.8.2 compatible. No Ham Sandwich required.
 *
 *  Sound files expected under: sound/AQS/vox/
 *
 *  Changes in this build:
 *   - No single-kill hype sounds.
 *   - Headshot always plays a headshot line (even at 1 kill).
 *   - UT rapid multikill mapping updated; “Holy Shit” added at high chain.
 *
 *  CVARs:
 *    aqs_enable               1/0   (default 1) Master enable
 *    aqs_debug                1/0   (default 0) Log debug to server console
 *    aqs_sounds               1/0   (default 1) Play sounds
 *    aqs_chat                 1/0   (default 1) Chat announcements
 *    aqs_show_weapon          1/0   (default 1) Append weapon name from DeathMsg
 *    aqs_multikill_window     float (default 4.0) Seconds to chain multi-kills
 *    aqs_min_killannounce     int   (default 1)   Minimum chain size to announce
 *    aqs_min_sound_chain      int   (default 2)   Minimum chain size for multi-kill sounds
 */

#include <amxmodx>
#include <amxmisc>
#include <fakemeta>
#include <engine>

#define PL_NAME   "Ricochet Quake/UT Sounds (FM)"
#define PL_VER    "1.3.0"
#define PL_AUTH   "ChatGPT"

/* ---------- CVARs ---------- */
new gCvarEnable;
new gCvarDebug;
new gCvarSounds;
new gCvarChat;
new gCvarShowWpn;
new gCvarChainWindow;
new gCvarMinAnnounce;
new gCvarMinSoundChain;

/* ---------- Msg IDs ---------- */
new gmsgDeathMsg;
new gmsgSayText;

/* ---------- State ---------- */
const MAX_PLAYERS = 32;
new gKillChain[MAX_PLAYERS + 1];
new Float:gLastKillTime[MAX_PLAYERS + 1];
new bool:gFirstBloodAvailable = true;   // Reset on map start

/* ---------- Colors (SayText) ---------- */
#define AQS_GREEN "^x04"
#define AQS_DEFAULT "^x01"

/* ---------- Sound sets (paths are without "sound/") ---------- */
new const gAllSounds[][] = {
    "AQS/vox/assassin.wav",
    "AQS/vox/bullseye.wav",
    "AQS/vox/comboking.wav",
    "AQS/vox/dominating.wav",
    "AQS/vox/doublekill.wav",
    "AQS/vox/doublekill2.wav",
    "AQS/vox/doublekill3.wav",
    "AQS/vox/eagleeye.wav",
    "AQS/vox/excellent.wav",
    "AQS/vox/firstblood.wav",
    "AQS/vox/firstblood2.wav",
    "AQS/vox/firstblood3.wav",
    "AQS/vox/firstblood4.wav",
    "AQS/vox/flawlessvictory.wav",
    "AQS/vox/godlike.wav",
    "AQS/vox/hattrick.wav",
    "AQS/vox/hattrick2.wav",
    "AQS/vox/headhunter.wav",
    "AQS/vox/headshot.wav",
    "AQS/vox/headshot2.wav",
    "AQS/vox/headshot3.wav",
    "AQS/vox/headshot4.wav",
    "AQS/vox/holyshit.wav",
    "AQS/vox/humiliation.wav",
    "AQS/vox/iamtheoneandonly.wav",
    "AQS/vox/impressive.wav",
    "AQS/vox/killingmachine.wav",
    "AQS/vox/killingspree.wav",
    "AQS/vox/laughs2.wav",
    "AQS/vox/ludicrouskill.wav",
    "AQS/vox/maniac.wav",
    "AQS/vox/massacre.wav",
    "AQS/vox/megakill.wav",
    "AQS/vox/monsterkill.wav",
    "AQS/vox/multikill.wav",
    "AQS/vox/oustanding.wav", // note: spelled as provided
    "AQS/vox/ownage.wav",
    "AQS/vox/pancake.wav",
    "AQS/vox/payback.wav",
    "AQS/vox/pickupyourweaponsandfight.wav",
    "AQS/vox/play.wav",
    "AQS/vox/prepareforbattle.wav",
    "AQS/vox/preparetofight.wav",
    "AQS/vox/rampage.wav",
    "AQS/vox/retribution.wav",
    "AQS/vox/teamkiller.wav",
    "AQS/vox/triplekill.wav",
    "AQS/vox/ultrakill.wav",
    "AQS/vox/unreal.wav",
    "AQS/vox/unstoppable.wav",
    "AQS/vox/vengeance.wav",
    "AQS/vox/whickedsick.wav",
    "AQS/vox/youarethelastmanstanding.wav"
};

/* Triples / Headshots / First blood / Very high chain */
new const gDoubles[][ ] = {
    "AQS/vox/doublekill.wav",
    "AQS/vox/doublekill2.wav",
    "AQS/vox/doublekill3.wav"
};

new const gTriples[][ ] = {
    "AQS/vox/triplekill.wav",
    "AQS/vox/hattrick.wav",
    "AQS/vox/hattrick2.wav"
};

new const gHeadshots[][ ] = {
    "AQS/vox/headshot.wav",
    "AQS/vox/headshot2.wav",
    "AQS/vox/headshot3.wav",
    "AQS/vox/headshot4.wav"
};

new const gFirstBlood[][ ] = {
    "AQS/vox/firstblood.wav",
    "AQS/vox/firstblood2.wav",
    "AQS/vox/firstblood3.wav",
    "AQS/vox/firstblood4.wav"
};

new const gHighChainCycle[][ ] = {
    "AQS/vox/unstoppable.wav",
    "AQS/vox/unreal.wav",
    "AQS/vox/massacre.wav",
    "AQS/vox/killingmachine.wav",
    "AQS/vox/maniac.wav"
};

/* ---------- Forwards ---------- */
public plugin_init()
{
    register_plugin(PL_NAME, PL_VER, PL_AUTH);

    gCvarEnable        = register_cvar("aqs_enable", "1");
    gCvarDebug         = register_cvar("aqs_debug", "0");
    gCvarSounds        = register_cvar("aqs_sounds", "1");
    gCvarChat          = register_cvar("aqs_chat", "1");
    gCvarShowWpn       = register_cvar("aqs_show_weapon", "1");
    gCvarChainWindow   = register_cvar("aqs_multikill_window", "4.0");
    gCvarMinAnnounce   = register_cvar("aqs_min_killannounce", "1");
    gCvarMinSoundChain = register_cvar("aqs_min_sound_chain", "2");

    gmsgDeathMsg = get_user_msgid("DeathMsg");
    gmsgSayText  = get_user_msgid("SayText");

    register_event("DeathMsg", "Ev_DeathMsg", "a");
    if (gmsgDeathMsg) register_message(gmsgDeathMsg, "Msg_DeathMsg");

    register_event("ResetHUD", "OnResetHUD", "be");
    register_clcmd("aqs_test", "Cmd_TestAQS", ADMIN_KICK, "<2..12|suicide|teamkill|headshot> - test");

    for (new i = 1; i <= MAX_PLAYERS; i++) {
        gKillChain[i] = 0;
        gLastKillTime[i] = 0.0;
    }
    gFirstBloodAvailable = true;
}

public plugin_precache()
{
    for (new i = 0; i < sizeof gAllSounds; i++) {
        precache_sound(gAllSounds[i]);
    }
}

public plugin_cfg() { gFirstBloodAvailable = true; }
public client_connect(id)    { gKillChain[id] = 0; gLastKillTime[id] = 0.0; }
public client_disconnect(id) { gKillChain[id] = 0; gLastKillTime[id] = 0.0; }
public OnResetHUD(id) {}

/* ---------- DeathMsg handlers ---------- */
public Ev_DeathMsg()
{
    new killer   = read_data(1);
    new victim   = read_data(2);
    new headshot = read_data(3); // Ricochet likely 0 except decap
    static weapon[32]; read_data(4, weapon, 31);

    handle_deathmsg(killer, victim, headshot, weapon);
    return PLUGIN_CONTINUE;
}

public Msg_DeathMsg(msgid, dest, id)
{
    new killer   = get_msg_arg_int(1);
    new victim   = get_msg_arg_int(2);
    new headshot = get_msg_arg_int(3);
    static weapon[32]; weapon[0] = 0; get_msg_arg_string(4, weapon, 31);

    handle_deathmsg(killer, victim, headshot, weapon);
    return PLUGIN_CONTINUE;
}

/* ---------- Core death processing ---------- */
stock handle_deathmsg(killer, victim, headshot, const weapon[])
{
    if (!get_pcvar_num(gCvarEnable)) return;
    if (victim < 1 || victim > MAX_PLAYERS || !is_user_connected(victim)) return;

    /* Suicide / fall / world */
    if (killer == 0 || killer == victim || !is_user_connected(killer))
    {
        if (get_pcvar_num(gCvarDebug))
            server_print("[AQS] Suicide/World: victim=%d weapon=%s", victim, weapon);

        reset_chain(victim);
        play_suicide_or_world(victim, weapon);
        return;
    }

    /* Teamkill */
    if (get_user_team(killer) == get_user_team(victim))
    {
        if (get_pcvar_num(gCvarDebug))
            server_print("[AQS] Teamkill: killer=%d victim=%d weapon=%s", killer, victim, weapon);

        reset_chain(killer);
        play_teamkill(killer, victim, weapon);
        return;
    }

    /* Normal kill */
    new Float:now = get_gametime();
    new Float:win = get_pcvar_float(gCvarChainWindow);

    if (now - gLastKillTime[killer] <= win) gKillChain[killer]++;
    else gKillChain[killer] = 1;

    gLastKillTime[killer] = now;

    /* First blood (once per map) */
    if (gFirstBloodAvailable)
    {
        gFirstBloodAvailable = false;
        if (get_pcvar_num(gCvarDebug)) server_print("[AQS] First Blood by %d", killer);
        spk_first_blood();
    }

    if (get_pcvar_num(gCvarDebug))
        server_print("[AQS] Kill: killer=%d victim=%d headshot=%d weapon=%s chain=%d",
            killer, victim, headshot, weapon, gKillChain[killer]);

    announce_kill(killer, victim, weapon, gKillChain[killer], headshot);
    play_chain_sound(gKillChain[killer], headshot);
}

stock reset_chain(id) { gKillChain[id] = 0; gLastKillTime[id] = 0.0; }

/* ---------- Special cases ---------- */
stock play_suicide_or_world(victim, const weapon[])
{
    if (get_pcvar_num(gCvarChat))
    {
        static vname[32], wpn[32], msg[192];
        get_user_name(victim, vname, 31);
        form_weapon_str(weapon, wpn, 31);
        if (wpn[0])
            format(msg, 191, "%s[AQS]%s %s met an unfortunate end (%s)", AQS_GREEN, AQS_DEFAULT, vname, wpn);
        else
            format(msg, 191, "%s[AQS]%s %s met an unfortunate end", AQS_GREEN, AQS_DEFAULT, vname);
        say_color(0, msg);
    }
    if (get_pcvar_num(gCvarSounds)) spk_all("AQS/vox/humiliation.wav");
}

stock play_teamkill(killer, victim, const weapon[])
{
    if (get_pcvar_num(gCvarChat))
    {
        static kname[32], vname[32], wpn[32], msg[192];
        get_user_name(killer, kname, 31);
        get_user_name(victim, vname, 31);
        form_weapon_str(weapon, wpn, 31);
        if (wpn[0])
            format(msg, 191, "%s[AQS]%s %s team-killed %s with %s!", AQS_GREEN, AQS_DEFAULT, kname, vname, wpn);
        else
            format(msg, 191, "%s[AQS]%s %s team-killed %s!", AQS_GREEN, AQS_DEFAULT, kname, vname);
        say_color(0, msg);
    }
    if (get_pcvar_num(gCvarSounds)) spk_all("AQS/vox/teamkiller.wav");
}

/* ---------- Announce + Sounds ---------- */
stock announce_kill(killer, victim, const weapon[], chain, headshot)
{
    if (!get_pcvar_num(gCvarChat)) return;
    if (chain < get_pcvar_num(gCvarMinAnnounce)) return;

    static kname[32], vname[32], wpn[32], suffix[40], msg[192];
    get_user_name(killer, kname, 31);
    get_user_name(victim, vname, 31);
    form_weapon_str(weapon, wpn, 31);

    suffix[0] = 0;
    switch (chain) {
        case 1: suffix[0] = 0;                        // no text upgrade for single kill
        case 2: copy(suffix, 39, " - DOUBLE KILL!");
        case 3: copy(suffix, 39, " - MULTI KILL!");
        case 4: copy(suffix, 39, " - MEGA KILL!");
        case 5: copy(suffix, 39, " - ULTRA KILL!");
        case 6: copy(suffix, 39, " - MONSTER KILL!");
        case 7: copy(suffix, 39, " - LUDICROUS KILL!");
        case 8: copy(suffix, 39, " - HOLY SHIT!");
        case 9: copy(suffix, 39, " - WICKED SICK!");
        default: copy(suffix, 39, " - GODLIKE!");
    }

    if (!get_pcvar_num(gCvarShowWpn) || !wpn[0])
        format(msg, 191, "%s[AQS]%s %s eliminated %s%s", AQS_GREEN, AQS_DEFAULT, kname, vname, suffix);
    else
        format(msg, 191, "%s[AQS]%s %s eliminated %s with %s%s", AQS_GREEN, AQS_DEFAULT, kname, vname, wpn, suffix);

    /* touch 'headshot' to silence “never used” warning if mod never sets it */
    headshot = headshot;

    say_color(0, msg);
}

/* Chain → sound (UT-ish). No single-kill hype. Headshot overrides. */
stock play_chain_sound(chain, headshot)
{
    if (!get_pcvar_num(gCvarSounds)) return;

    /* Headshot / decap takes priority even at 1 kill */
    if (headshot > 0) { spk_rand_from_group(gHeadshots, sizeof gHeadshots); return; }

    /* No sound at chain == 1 */
    if (chain < 2) return;

    if (chain == 2) { spk_rand_from_group(gDoubles, sizeof gDoubles); return; }
    if (chain == 3) { spk_rand_from_group(gTriples, sizeof gTriples); return; }
    if (chain == 4) { spk_all("AQS/vox/megakill.wav"); return; }
    if (chain == 5) { spk_all("AQS/vox/ultrakill.wav"); return; }
    if (chain == 6) { spk_all("AQS/vox/monsterkill.wav"); return; }
    if (chain == 7) { spk_all("AQS/vox/ludicrouskill.wav"); return; }
    if (chain == 8) { spk_all("AQS/vox/holyshit.wav"); return; }
    if (chain == 9) { spk_all("AQS/vox/whickedsick.wav"); return; }

    /* 10+ use a strong pool */
    spk_rand_from_group(gHighChainCycle, sizeof gHighChainCycle);
}

/* First Blood */
stock spk_first_blood() { spk_rand_from_group(gFirstBlood, sizeof gFirstBlood); }

/* ---------- Utils ---------- */
stock spk_all(const soundpath[]) { client_cmd(0, "spk ^"%s^"", soundpath); }

stock spk_rand_from_group(const group[][], count)
{
    new idx = random_num(0, count - 1);
    client_cmd(0, "spk ^"%s^"", group[idx]);
}

stock form_weapon_str(const wpn_in[], wpn_out[], len)
{
    if (!wpn_in[0]) { wpn_out[0] = 0; return; }
    static tmp[32]; copy(tmp, 31, wpn_in);
    for (new i = 0; tmp[i] != 0; i++) if (tmp[i] == '_') tmp[i] = ' ';
    copy(wpn_out, len, tmp);
}

/* Minimal SayText color print */
stock say_color(const id, const message[])
{
    if (!gmsgSayText) gmsgSayText = get_user_msgid("SayText");
    if (id >= 1 && id <= MAX_PLAYERS) {
        message_begin(MSG_ONE, gmsgSayText, _, id);
        write_byte(id); write_string(message); message_end();
    } else {
        message_begin(MSG_ALL, gmsgSayText);
        write_byte(1); write_string(message); message_end();
    }
}

/* ---------- Admin test ---------- */
public Cmd_TestAQS(id, level, cid)
{
    if (!cmd_access(id, level, cid, 2)) return PLUGIN_HANDLED;

    new arg[16]; read_argv(1, arg, 15);

    if (equali(arg, "suicide")) { play_suicide_or_world(id, "fall"); return PLUGIN_HANDLED; }
    if (equali(arg, "teamkill")) {
        new t = find_any_teammate(id); if (!t) t = id;
        play_teamkill(id, t, "disc"); return PLUGIN_HANDLED;
    }
    if (equali(arg, "headshot")) { spk_rand_from_group(gHeadshots, sizeof gHeadshots); return PLUGIN_HANDLED; }

    new n = str_to_num(arg); if (n < 2) n = 2; if (n > 12) n = 12;  // no single-kill tests

    if (get_pcvar_num(gCvarDebug)) server_print("[AQS] Test chain=%d", n);

    gKillChain[id] = n; gLastKillTime[id] = get_gametime();
    announce_kill(id, id, "disc", n, 0);
    play_chain_sound(n, 0);
    return PLUGIN_HANDLED;
}

stock find_any_teammate(id)
{
    new myteam = get_user_team(id);
    for (new i = 1; i <= MAX_PLAYERS; i++) {
        if (i == id) continue;
        if (is_user_connected(i) && get_user_team(i) == myteam) return i;
    }
    return 0;
}
