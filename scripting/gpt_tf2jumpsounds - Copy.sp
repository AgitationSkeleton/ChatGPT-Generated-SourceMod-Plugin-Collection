/**
 * TF2 Class Jump VO (autobhop-friendly + Spy cloak/disguise aware + per-client mute + MvM manual sets)
 *
 * - Plays a class-specific VO sound as a world sound (at player's location) at volume JUMP_VO_VOLUME.
 * - Works with autobunnyhop by detecting liftoff (ground->air) with a short recent-press window.
 * - Spy:
 *     * Cloaked  -> silent.
 *     * Disguised -> use disguised class's VO.
 * - Per-client toggle: !jumpsounds (default ON). Persisted with ClientPrefs.
 * - MvM: If player's model contains "bot_", use mvm_ prefixed VO variants (manually defined).
 *
 * Requires: SM 1.10+, sdktools, tf2, tf2_stocks, clientprefs
 */

#include <sourcemod>
#include <sdktools>
#include <sdktools_sound>
#include <tf2>
#include <tf2_stocks>
#include <clientprefs>

#pragma semicolon 1
#pragma newdecls required

#define PLUGIN_NAME        "TF2 Class Jump VO"
#define PLUGIN_VERSION     "1.3.0"
#define PLUGIN_AUTHOR      "ChatGPT"
#define PLUGIN_DESCRIPTION "Class jump VO with autobhop, Spy cloak/disguise, per-client toggle, and MvM variants"
#define PLUGIN_URL         ""

// ---------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------
const float JUMP_VO_VOLUME        = 1.0;   // you had this set to 1.0; change if desired
const float JUMP_SOUND_COOLDOWN   = 0.02;  // debounce
const float RECENT_JUMP_WINDOW    = 0.08;  // seconds

// ---------------------------------------------------------------------
// ClientPrefs
// ---------------------------------------------------------------------
Handle g_hCookieHear = null;                // "1" = hears jump sounds (default), "0" = muted
bool   g_bHear[MAXPLAYERS + 1];             // cached per-client flag

// ---------------------------------------------------------------------
// Per-client state (jump detection)
// ---------------------------------------------------------------------
int   g_LastButtons[MAXPLAYERS + 1];
bool  g_WasOnGround[MAXPLAYERS + 1];
float g_NextPlayTime[MAXPLAYERS + 1];
float g_LastJumpPressTime[MAXPLAYERS + 1];

// ---------------------------------------------------------------------
// Sound tables (paths relative to "sound/")
// ---------------------------------------------------------------------
static const char g_Scout[][]   = {"vo/scout_jump01.wav","vo/scout_jump02.wav","vo/scout_jump03.wav","vo/scout_jump04.wav","vo/scout_jump05.wav","vo/scout_jump06.wav"};
static const char g_Soldier[][] = {"vo/soldier_jump01.wav","vo/soldier_jump02.wav","vo/soldier_jump03.wav","vo/soldier_jump04.wav"};
static const char g_Pyro[][]    = {"vo/pyro_jump01.wav","vo/pyro_jump02.wav","vo/pyro_jump03.wav"};
static const char g_Demo[][]    = {"vo/demo_jump01.wav","vo/demo_jump02.wav","vo/demo_jump03.wav"};
static const char g_Heavy[][]   = {"vo/heavy_jump01.wav","vo/heavy_jump02.wav","vo/heavy_jump03.wav"};
static const char g_Engie[][]   = {"vo/engie_jump01.wav","vo/engie_jump02.wav","vo/engie_jump03.wav","vo/engie_jump04.wav"};
static const char g_Medic[][]   = {"vo/medic_jump01.wav","vo/medic_jump02.wav","vo/medic_jump03.wav"};
static const char g_Sniper[][]  = {"vo/sniper_jump01.wav","vo/sniper_jump02.wav","vo/sniper_jump03.wav"};
static const char g_Spy[][]     = {"vo/spy_jump01.wav","vo/spy_jump02.wav","vo/spy_jump03.wav"};

// ----- Manual MvM ("mvm_") variants -----
static const char g_Scout_MVM[][]   = {"vo/mvm_scout_jump01.wav","vo/mvm_scout_jump02.wav","vo/mvm_scout_jump03.wav","vo/mvm_scout_jump04.wav","vo/mvm_scout_jump05.wav","vo/mvm_scout_jump06.wav"};
static const char g_Soldier_MVM[][] = {"vo/mvm_soldier_jump01.wav","vo/mvm_soldier_jump02.wav","vo/mvm_soldier_jump03.wav","vo/mvm_soldier_jump04.wav"};
static const char g_Pyro_MVM[][]    = {"vo/mvm_pyro_jump01.wav","vo/mvm_pyro_jump02.wav","vo/mvm_pyro_jump03.wav"};
static const char g_Demo_MVM[][]    = {"vo/mvm_demo_jump01.wav","vo/mvm_demo_jump02.wav","vo/mvm_demo_jump03.wav"};
static const char g_Heavy_MVM[][]   = {"vo/mvm_heavy_jump01.wav","vo/mvm_heavy_jump02.wav","vo/mvm_heavy_jump03.wav"};
static const char g_Engie_MVM[][]   = {"vo/mvm_engie_jump01.wav","vo/mvm_engie_jump02.wav","vo/mvm_engie_jump03.wav","vo/mvm_engie_jump04.wav"};
static const char g_Medic_MVM[][]   = {"vo/mvm_medic_jump01.wav","vo/mvm_medic_jump02.wav","vo/mvm_medic_jump03.wav"};
static const char g_Sniper_MVM[][]  = {"vo/mvm_sniper_jump01.wav","vo/mvm_sniper_jump02.wav","vo/mvm_sniper_jump03.wav"};
static const char g_Spy_MVM[][]     = {"vo/mvm_spy_jump01.wav","vo/mvm_spy_jump02.wav","vo/mvm_spy_jump03.wav"};

// ---------------------------------------------------------------------
// Plugin info
// ---------------------------------------------------------------------
public Plugin myinfo =
{
    name        = PLUGIN_NAME,
    author      = PLUGIN_AUTHOR,
    description = PLUGIN_DESCRIPTION,
    version     = PLUGIN_VERSION,
    url         = PLUGIN_URL
};

// ---------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------
public void OnPluginStart()
{
    // ClientPrefs cookie (default to hear = true)
    g_hCookieHear = RegClientCookie("tf2_jumpvo_hear", "TF2 Jump VO per-client hearing preference (1=on,0=off)", CookieAccess_Public);

    // Chat/console command: !jumpsounds
    RegConsoleCmd("sm_jumpsounds", Cmd_ToggleJumpSounds);

    for (int c = 1; c <= MaxClients; c++)
    {
        ResetClientState(c);
        g_bHear[c] = true; // until cookies arrive
    }
}

public void OnClientPutInServer(int client)
{
    ResetClientState(client);
    g_bHear[client] = true; // default until cookies cached
}

public void OnClientDisconnect(int client)
{
    ResetClientState(client);
}

static void ResetClientState(int client)
{
    g_LastButtons[client] = 0;
    g_WasOnGround[client] = false;
    g_NextPlayTime[client] = 0.0;
    g_LastJumpPressTime[client] = -9999.0;
}

public void OnClientCookiesCached(int client)
{
    if (!IsClientInGame(client))
        return;

    char val[8];
    GetClientCookie(client, g_hCookieHear, val, sizeof(val));
    if (val[0] == '\0')
    {
        // Not set yet: default to ON
        g_bHear[client] = true;
        SetClientCookie(client, g_hCookieHear, "1");
    }
    else
    {
        g_bHear[client] = (val[0] != '0');
    }
}

public void OnMapStart()
{
    // Precache + downloads for both normal and MvM variants
    PrecacheAndAddDownloadList(g_Scout,   sizeof(g_Scout));
    PrecacheAndAddDownloadList(g_Soldier, sizeof(g_Soldier));
    PrecacheAndAddDownloadList(g_Pyro,    sizeof(g_Pyro));
    PrecacheAndAddDownloadList(g_Demo,    sizeof(g_Demo));
    PrecacheAndAddDownloadList(g_Heavy,   sizeof(g_Heavy));
    PrecacheAndAddDownloadList(g_Engie,   sizeof(g_Engie));
    PrecacheAndAddDownloadList(g_Medic,   sizeof(g_Medic));
    PrecacheAndAddDownloadList(g_Sniper,  sizeof(g_Sniper));
    PrecacheAndAddDownloadList(g_Spy,     sizeof(g_Spy));

    PrecacheAndAddDownloadList(g_Scout_MVM,   sizeof(g_Scout_MVM));
    PrecacheAndAddDownloadList(g_Soldier_MVM, sizeof(g_Soldier_MVM));
    PrecacheAndAddDownloadList(g_Pyro_MVM,    sizeof(g_Pyro_MVM));
    PrecacheAndAddDownloadList(g_Demo_MVM,    sizeof(g_Demo_MVM));
    PrecacheAndAddDownloadList(g_Heavy_MVM,   sizeof(g_Heavy_MVM));
    PrecacheAndAddDownloadList(g_Engie_MVM,   sizeof(g_Engie_MVM));
    PrecacheAndAddDownloadList(g_Medic_MVM,   sizeof(g_Medic_MVM));
    PrecacheAndAddDownloadList(g_Sniper_MVM,  sizeof(g_Sniper_MVM));
    PrecacheAndAddDownloadList(g_Spy_MVM,     sizeof(g_Spy_MVM));
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------
static void PrecacheAndAddDownloadList(const char[][] list, int count)
{
    for (int i = 0; i < count; i++)
    {
        PrecacheSound(list[i], true);

        char fullpath[PLATFORM_MAX_PATH];
        Format(fullpath, sizeof(fullpath), "sound/%s", list[i]);
        AddFileToDownloadsTable(fullpath);
    }
}

static void PickRandomFrom(const char[][] list, int count, char[] out, int outlen)
{
    int idx = GetRandomInt(0, count - 1);
    strcopy(out, outlen, list[idx]);
}

static bool GetRandomClassJumpSound(TFClassType cls, bool robot, char[] out, int outlen)
{
    if (!robot)
    {
        switch (cls)
        {
            case TFClass_Scout:    { PickRandomFrom(g_Scout,   sizeof(g_Scout),   out, outlen); return true; }
            case TFClass_Soldier:  { PickRandomFrom(g_Soldier, sizeof(g_Soldier), out, outlen); return true; }
            case TFClass_Pyro:     { PickRandomFrom(g_Pyro,    sizeof(g_Pyro),    out, outlen); return true; }
            case TFClass_DemoMan:  { PickRandomFrom(g_Demo,    sizeof(g_Demo),    out, outlen); return true; }
            case TFClass_Heavy:    { PickRandomFrom(g_Heavy,   sizeof(g_Heavy),   out, outlen); return true; }
            case TFClass_Engineer: { PickRandomFrom(g_Engie,   sizeof(g_Engie),   out, outlen); return true; }
            case TFClass_Medic:    { PickRandomFrom(g_Medic,   sizeof(g_Medic),   out, outlen); return true; }
            case TFClass_Sniper:   { PickRandomFrom(g_Sniper,  sizeof(g_Sniper),  out, outlen); return true; }
            case TFClass_Spy:      { PickRandomFrom(g_Spy,     sizeof(g_Spy),     out, outlen); return true; }
        }
    }
    else
    {
        switch (cls)
        {
            case TFClass_Scout:    { PickRandomFrom(g_Scout_MVM,   sizeof(g_Scout_MVM),   out, outlen); return true; }
            case TFClass_Soldier:  { PickRandomFrom(g_Soldier_MVM, sizeof(g_Soldier_MVM), out, outlen); return true; }
            case TFClass_Pyro:     { PickRandomFrom(g_Pyro_MVM,    sizeof(g_Pyro_MVM),    out, outlen); return true; }
            case TFClass_DemoMan:  { PickRandomFrom(g_Demo_MVM,    sizeof(g_Demo_MVM),    out, outlen); return true; }
            case TFClass_Heavy:    { PickRandomFrom(g_Heavy_MVM,   sizeof(g_Heavy_MVM),   out, outlen); return true; }
            case TFClass_Engineer: { PickRandomFrom(g_Engie_MVM,   sizeof(g_Engie_MVM),   out, outlen); return true; }
            case TFClass_Medic:    { PickRandomFrom(g_Medic_MVM,   sizeof(g_Medic_MVM),   out, outlen); return true; }
            case TFClass_Sniper:   { PickRandomFrom(g_Sniper_MVM,  sizeof(g_Sniper_MVM),  out, outlen); return true; }
            case TFClass_Spy:      { PickRandomFrom(g_Spy_MVM,     sizeof(g_Spy_MVM),     out, outlen); return true; }
        }
    }
    out[0] = '\0';
    return false;
}

/**
 * Returns false if the Spy is cloaked (should not play).
 * Otherwise writes the "effective" class (disguise if present, else real class) to outCls and returns true.
 */
static bool GetEffectiveClassForJump(int client, TFClassType &outCls)
{
    if (TF2_IsPlayerInCondition(client, TFCond_Cloaked))
        return false;

    if (TF2_IsPlayerInCondition(client, TFCond_Disguised))
    {
        int d = GetEntProp(client, Prop_Send, "m_nDisguiseClass");
        if (d >= TFClass_Scout && d <= TFClass_Engineer)
        {
            outCls = view_as<TFClassType>(d);
            return true;
        }
    }

    outCls = TF2_GetPlayerClass(client);
    return true;
}

static bool IsRobotPlayerModel(int client)
{
    char model[PLATFORM_MAX_PATH];
    GetClientModel(client, model, sizeof(model));
    return (StrContains(model, "bot_", false) != -1);
}

// ---------------------------------------------------------------------
// Input hook (autobhop-friendly jump detection)
// ---------------------------------------------------------------------
public Action OnPlayerRunCmd(int client, int &buttons, int &impulse, float vel[3], float angles[3],
                             int &weapon, int &subtype, int &cmdnum, int &tickcount, int &seed, int mouse[2])
{
    if (client < 1 || client > MaxClients)
        return Plugin_Continue;

    if (!IsClientInGame(client) || !IsPlayerAlive(client))
    {
        g_LastButtons[client] = buttons;
        return Plugin_Continue;
    }

    float now = GetEngineTime();

    if (buttons & IN_JUMP)
    {
        g_LastJumpPressTime[client] = now;
    }

    bool onGround = (GetEntityFlags(client) & FL_ONGROUND) != 0;

    bool liftoff = (g_WasOnGround[client] && !onGround) &&
                   ( (buttons & IN_JUMP) || (now - g_LastJumpPressTime[client] <= RECENT_JUMP_WINDOW) );

    if (liftoff && now >= g_NextPlayTime[client])
    {
        PlayClassJumpVO(client);
        g_NextPlayTime[client] = now + JUMP_SOUND_COOLDOWN;
    }

    g_WasOnGround[client] = onGround;
    g_LastButtons[client] = buttons;
    return Plugin_Continue;
}

// ---------------------------------------------------------------------
// Sound emission (recipient-filtered for per-client mute, MvM-aware)
// ---------------------------------------------------------------------
static void PlayClassJumpVO(int client)
{
    if (!IsClientInGame(client) || !IsPlayerAlive(client))
        return;

    TFClassType effClass;
    if (!GetEffectiveClassForJump(client, effClass))
        return; // cloaked Spy -> silent

    bool robot = IsRobotPlayerModel(client);

    char sample[PLATFORM_MAX_PATH];
    if (!GetRandomClassJumpSound(effClass, robot, sample, sizeof(sample)) || sample[0] == '\0')
        return;

    // Build recipient list of clients who want to hear jump sounds
    int recips[MAXPLAYERS];
    int n = 0;
    for (int i = 1; i <= MaxClients; i++)
    {
        if (!IsClientInGame(i))  continue;
        if (IsFakeClient(i))     continue; // no need to send to bots
        if (!g_bHear[i])         continue;

        recips[n++] = i;
    }

    if (n == 0)
        return;

    float pos[3];
    GetClientAbsOrigin(client, pos);

    // Play as world sound at the jumper's origin to filtered recipients
    EmitSound(recips, n, sample, SOUND_FROM_WORLD, SNDCHAN_AUTO, SNDLEVEL_NORMAL, SND_NOFLAGS, JUMP_VO_VOLUME, SNDPITCH_NORMAL, -1, pos);
}

// ---------------------------------------------------------------------
// Command: !jumpsounds
// ---------------------------------------------------------------------
public Action Cmd_ToggleJumpSounds(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
        return Plugin_Handled;

    g_bHear[client] = !g_bHear[client];

    // Persist to cookie
    SetClientCookie(client, g_hCookieHear, g_bHear[client] ? "1" : "0");

    PrintToChat(client, "[JumpVO] You will %s hear jump sounds.", g_bHear[client] ? "now" : "no longer");
    return Plugin_Handled;
}
