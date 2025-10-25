/**
 * Pre-Fortress 2 Class Jump VO
 * - Autobhop-friendly jump detection (ground→air + recent press window)
 * - Spy: silent while cloaked; if disguised, use disguised class
 * - Per-client toggle: !jumpsounds (ClientPrefs; default ON)
 * - Civilian: uses "vo/civilian_jump01.wav"
 *
 * Requires: SM 1.9/1.10+, sdktools, clientprefs, PF2 includes
 */

#include <sourcemod>
#include <sdktools>
#include <sdktools_sound>
#include <clientprefs>
#include <pf2>

#pragma semicolon 1
// (no #pragma newdecls for PF2 compiler compatibility)

#define PLUGIN_NAME        "PF2 Class Jump VO"
#define PLUGIN_VERSION     "1.0.1"
#define PLUGIN_AUTHOR      "ChatGPT"
#define PLUGIN_DESCRIPTION "Class jump VO for Pre-Fortress 2 with autobhop, Spy rules, per-client toggle, Civilian VO"
#define PLUGIN_URL         ""

// ---------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------
const float JUMP_VO_VOLUME      = 1.0;   // set 0.75 if you prefer it quieter
const float JUMP_SOUND_COOLDOWN = 0.02;  // debounce to avoid double fires
const float RECENT_JUMP_WINDOW  = 0.08;  // seconds within which IN_JUMP counts for liftoff

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
// Sound tables (paths are relative to "sound/")
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

// Civilian: single clip (added to downloads on map start)
static const char g_CivilianSingle[] = "vo/civilian_jump01.wav";

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
    g_hCookieHear = RegClientCookie("pf2_jumpvo_hear", "PF2 Jump VO per-client hearing preference (1=on,0=off)", CookieAccess_Public);

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
    // Precache + downloads for all normal VO
    PrecacheAndAddDownloadList(g_Scout,   sizeof(g_Scout));
    PrecacheAndAddDownloadList(g_Soldier, sizeof(g_Soldier));
    PrecacheAndAddDownloadList(g_Pyro,    sizeof(g_Pyro));
    PrecacheAndAddDownloadList(g_Demo,    sizeof(g_Demo));
    PrecacheAndAddDownloadList(g_Heavy,   sizeof(g_Heavy));
    PrecacheAndAddDownloadList(g_Engie,   sizeof(g_Engie));
    PrecacheAndAddDownloadList(g_Medic,   sizeof(g_Medic));
    PrecacheAndAddDownloadList(g_Sniper,  sizeof(g_Sniper));
    PrecacheAndAddDownloadList(g_Spy,     sizeof(g_Spy));

    // Civilian single clip
    PrecacheSound(g_CivilianSingle, true);
    char fullpath[PLATFORM_MAX_PATH];
    Format(fullpath, sizeof(fullpath), "sound/%s", g_CivilianSingle);
    AddFileToDownloadsTable(fullpath);
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------
static void PrecacheAndAddDownloadList(const char[][] list, int count)
{
    for (int i = 0; i < count; i++)
    {
        PrecacheSound(list[i], true);

        char full[PLATFORM_MAX_PATH];
        Format(full, sizeof(full), "sound/%s", list[i]);
        AddFileToDownloadsTable(full);
    }
}

static void PickRandomFrom(const char[][] list, int count, char[] out, int outlen)
{
    int idx = GetRandomInt(0, count - 1);
    strcopy(out, outlen, list[idx]);
}

/**
 * Returns false if the Spy is cloaked (should not play).
 * Otherwise writes the "effective" class (disguise if present, else real class) to outCls and returns true.
 *
 * PF2 class enum (given): Scout, Sniper, Soldier, Demoman, Medic, HeavyWeapons, Pyro, Spy, Engineer, Civilian.
 */
static bool GetEffectiveClassForJump(int client, TFClassType &outCls)
{
    // Silent while cloaked
    if (TF2_IsPlayerInCondition(client, TFCond_Cloaked))
        return false;

    // If disguised, use disguised class (compare as ints to avoid tag-mismatch warnings)
    if (TF2_IsPlayerInCondition(client, TFCond_Disguised))
    {
        int d = GetEntProp(client, Prop_Send, "m_nDisguiseClass");

        int lo = view_as<int>(TFClass_Scout);
        int hi = view_as<int>(TFClass_Civilian);

        if (d >= lo && d <= hi)
        {
            outCls = view_as<TFClassType>(d);
            return true;
        }
        // If out-of-range, fall back to real class
    }

    outCls = TF2_GetPlayerClass(client);
    return true;
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
// Sound emission (recipient-filtered for per-client mute)
// ---------------------------------------------------------------------
static void PlayClassJumpVO(int client)
{
    if (!IsClientInGame(client) || !IsPlayerAlive(client))
        return;

    char sample[PLATFORM_MAX_PATH];
    if (!GetRandomClassJumpSound(client, sample, sizeof(sample)) || sample[0] == '\0')
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

    // World sound at the jumper's origin, to filtered recipients
    EmitSound(recips, n, sample, SOUND_FROM_WORLD, SNDCHAN_AUTO, SNDLEVEL_NORMAL, SND_NOFLAGS, JUMP_VO_VOLUME, SNDPITCH_NORMAL, -1, pos);
}

/**
 * Select a VO sample for the effective class.
 * - Civilian: "vo/civilian_jump01.wav"
 */
static bool GetRandomClassJumpSound(int client, char[] out, int outlen)
{
    TFClassType cls;
    if (!GetEffectiveClassForJump(client, cls))
    {
        out[0] = '\0'; // e.g., cloaked Spy
        return false;
    }

    if (cls == TFClass_Civilian)
    {
        strcopy(out, outlen, g_CivilianSingle);
        return true;
    }

    switch (cls)
    {
        case TFClass_Scout:        { PickRandomFrom(g_Scout,   sizeof(g_Scout),   out, outlen); return true; }
        case TFClass_Soldier:      { PickRandomFrom(g_Soldier, sizeof(g_Soldier), out, outlen); return true; }
        case TFClass_Pyro:         { PickRandomFrom(g_Pyro,    sizeof(g_Pyro),    out, outlen); return true; }
        case TFClass_Demoman:      { PickRandomFrom(g_Demo,    sizeof(g_Demo),    out, outlen); return true; }
        case TFClass_HeavyWeapons: { PickRandomFrom(g_Heavy,   sizeof(g_Heavy),   out, outlen); return true; }
        case TFClass_Engineer:     { PickRandomFrom(g_Engie,   sizeof(g_Engie),   out, outlen); return true; }
        case TFClass_Medic:        { PickRandomFrom(g_Medic,   sizeof(g_Medic),   out, outlen); return true; }
        case TFClass_Sniper:       { PickRandomFrom(g_Sniper,  sizeof(g_Sniper),  out, outlen); return true; }
        case TFClass_Spy:          { PickRandomFrom(g_Spy,     sizeof(g_Spy),     out, outlen); return true; }
    }

    out[0] = '\0';
    return false;
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
