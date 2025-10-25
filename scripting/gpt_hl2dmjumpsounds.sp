/**
 * HL2DM Jump VO
 * - Plays a single jump sound at the player's world position on liftoff.
 * - Autobhop-friendly (detects ground -> air + short recent-press window).
 * - Per-client toggle: !jumpsounds (ClientPrefs; default ON).
 *
 * Requires: SM 1.9/1.10+, sdktools, clientprefs
 */

#include <sourcemod>
#include <sdktools>
#include <sdktools_sound>
#include <clientprefs>

#pragma semicolon 1
// (no #pragma newdecls for broad compiler compatibility)

#define PLUGIN_NAME        "HL2DM Jump VO"
#define PLUGIN_VERSION     "1.0.0"
#define PLUGIN_AUTHOR      "ChatGPT"
#define PLUGIN_DESCRIPTION "Plays a jump VO on liftoff (autobhop-friendly) with per-client toggle"
#define PLUGIN_URL         ""

// ---------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------
const float JUMP_VO_VOLUME      = 1.0;   // set 0.75 if you want it quieter
const float JUMP_SOUND_COOLDOWN = 0.02;  // debounce to avoid double fires in the same tick
const float RECENT_JUMP_WINDOW  = 0.08;  // seconds within which IN_JUMP counts for liftoff

// ---------------------------------------------------------------------
// Sound (relative to "sound/")
// ---------------------------------------------------------------------
static const char g_JumpSample[] = "vo/goon/gunman_jump01.wav";

// ---------------------------------------------------------------------
// ClientPrefs
// ---------------------------------------------------------------------
Handle g_hCookieHear = null;        // "1" = hears jump sounds (default), "0" = muted
bool   g_bHear[MAXPLAYERS + 1];     // cached per-client flag

// ---------------------------------------------------------------------
// Per-client state (jump detection)
// ---------------------------------------------------------------------
int   g_LastButtons[MAXPLAYERS + 1];
bool  g_WasOnGround[MAXPLAYERS + 1];
float g_NextPlayTime[MAXPLAYERS + 1];
float g_LastJumpPressTime[MAXPLAYERS + 1];

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
    g_hCookieHear = RegClientCookie("hl2dm_jumpvo_hear", "HL2DM Jump VO per-client hearing preference (1=on,0=off)", CookieAccess_Public);

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
    // Precache + downloads entry
    PrecacheSound(g_JumpSample, true);

    char fullpath[PLATFORM_MAX_PATH];
    Format(fullpath, sizeof(fullpath), "sound/%s", g_JumpSample);
    AddFileToDownloadsTable(fullpath);
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

    // liftoff when transitioning ground->air and jump was pressed (or pressed recently)
    bool liftoff = (g_WasOnGround[client] && !onGround) &&
                   ( (buttons & IN_JUMP) || (now - g_LastJumpPressTime[client] <= RECENT_JUMP_WINDOW) );

    if (liftoff && now >= g_NextPlayTime[client])
    {
        PlayJumpVO(client);
        g_NextPlayTime[client] = now + JUMP_SOUND_COOLDOWN;
    }

    g_WasOnGround[client] = onGround;
    g_LastButtons[client] = buttons;
    return Plugin_Continue;
}

// ---------------------------------------------------------------------
// Sound emission (recipient-filtered for per-client mute)
// ---------------------------------------------------------------------
static void PlayJumpVO(int client)
{
    if (!IsClientInGame(client) || !IsPlayerAlive(client))
        return;

    // Build recipient list of clients who want to hear jump sounds
    int recips[MAXPLAYERS];
    int n = 0;
    for (int i = 1; i <= MaxClients; i++)
    {
        if (!IsClientInGame(i))  continue;
        if (IsFakeClient(i))     continue; // skip bots
        if (!g_bHear[i])         continue;

        recips[n++] = i;
    }
    if (n == 0)
        return;

    float pos[3];
    GetClientAbsOrigin(client, pos);

    // Play as world sound at the jumper’s origin to filtered recipients
    EmitSound(recips, n, g_JumpSample, SOUND_FROM_WORLD, SNDCHAN_AUTO, SNDLEVEL_NORMAL, SND_NOFLAGS, JUMP_VO_VOLUME, SNDPITCH_NORMAL, -1, pos);
}

// ---------------------------------------------------------------------
// Command: !jumpsounds (toggle per-client hearing)
// ---------------------------------------------------------------------
public Action Cmd_ToggleJumpSounds(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
        return Plugin_Handled;

    g_bHear[client] = !g_bHear[client];
    SetClientCookie(client, g_hCookieHear, g_bHear[client] ? "1" : "0");

    PrintToChat(client, "[JumpVO] You will %s hear jump sounds.", g_bHear[client] ? "now" : "no longer");
    return Plugin_Handled;
}
