/**
 * TF2 2Fort Quake - BLU Flag Bot Unstucker (Sphere)
 * - Only runs on ctf_2fort_quake and ctf_2fort_quake_mvm
 * - If a BLU bot is carrying the RED flag and stays within a sphere for > N seconds,
 *   teleport them to a fixed "below" position.
 *
 * Author: ChatGPT
 */

#include <sourcemod>
#include <sdktools>
#include <tf2>
#include <tf2_stocks>

public Plugin myinfo =
{
    name        = "TF2 2Fort Quake Flag Bot Unstuck (Sphere)",
    author      = "ChatGPT",
    description = "Teleports BLU bots carrying RED flag if stuck in a sphere region on 2fort_quake.",
    version     = "2.0.0",
    url         = ""
};

#define TEAM_RED 2
#define TEAM_BLU 3

// Sphere center: "likely stuck" region
static const float g_StuckCenter[3] = { 529.690735, 569.537231, 1364.031372 };

// Teleport destination "below"
static const float g_DestPos[3]     = { 395.451904, 567.795776, 1156.031372 };

// If you want to force a specific facing angle, set this to true.
// Otherwise, the bot keeps its current angles.
static const bool  g_ForceAngles = true;
static const float g_DestAng[3]  = { 17.773373, -175.320389, 0.000000 };

// State
Handle g_Timer = null;
bool g_EnabledForThisMap = false;
float g_TimeInSphere[MAXPLAYERS + 1];

// ConVars
ConVar g_CvarEnabled;
ConVar g_CvarSeconds;
ConVar g_CvarRadius;
ConVar g_CvarInterval;
ConVar g_CvarDebug;

public void OnPluginStart()
{
    g_CvarEnabled  = CreateConVar("sm_2fortquake_botunstuck_enabled", "1", "Enable/disable bot unstucker on supported maps.", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    g_CvarSeconds  = CreateConVar("sm_2fortquake_botunstuck_seconds", "5.0", "Seconds bot must remain in sphere while carrying RED flag.", FCVAR_NOTIFY, true, 1.0, true, 60.0);
    g_CvarRadius   = CreateConVar("sm_2fortquake_botunstuck_radius", "140.0", "Sphere radius (units) around stuck center.", FCVAR_NOTIFY, true, 20.0, true, 1000.0);
    g_CvarInterval = CreateConVar("sm_2fortquake_botunstuck_interval", "0.5", "How often (seconds) to check bots.", FCVAR_NOTIFY, true, 0.1, true, 5.0);
    g_CvarDebug    = CreateConVar("sm_2fortquake_botunstuck_debug", "0", "Debug logging (0/1).", FCVAR_NOTIFY, true, 0.0, true, 1.0);

    for (int client = 1; client <= MaxClients; client++)
    {
        g_TimeInSphere[client] = 0.0;
    }
}

public void OnMapStart()
{
    g_EnabledForThisMap = false;

    char mapName[PLATFORM_MAX_PATH];
    GetCurrentMap(mapName, sizeof(mapName));

    if (StrEqual(mapName, "ctf_2fort_quake", false) || StrEqual(mapName, "ctf_2fort_quake_mvm", false))
    {
        g_EnabledForThisMap = true;
    }

    if (g_Timer != null)
    {
        CloseHandle(g_Timer);
        g_Timer = null;
    }

    if (g_EnabledForThisMap && g_CvarEnabled.BoolValue)
    {
        g_Timer = CreateTimer(g_CvarInterval.FloatValue, Timer_CheckBots, _, TIMER_REPEAT | TIMER_FLAG_NO_MAPCHANGE);
    }

    for (int client = 1; client <= MaxClients; client++)
    {
        g_TimeInSphere[client] = 0.0;
    }
}

public void OnConfigsExecuted()
{
    if (!g_EnabledForThisMap)
        return;

    bool enabled = g_CvarEnabled.BoolValue;

    if (enabled && g_Timer == null)
    {
        g_Timer = CreateTimer(g_CvarInterval.FloatValue, Timer_CheckBots, _, TIMER_REPEAT | TIMER_FLAG_NO_MAPCHANGE);
    }
    else if (!enabled && g_Timer != null)
    {
        CloseHandle(g_Timer);
        g_Timer = null;
    }
}

public void OnClientDisconnect(int client)
{
    if (client >= 1 && client <= MaxClients)
    {
        g_TimeInSphere[client] = 0.0;
    }
}

public Action Timer_CheckBots(Handle timer)
{
    if (!g_EnabledForThisMap || !g_CvarEnabled.BoolValue)
        return Plugin_Continue;

    int redFlag = FindRedFlagEntity();
    if (redFlag == -1)
        return Plugin_Continue;

    float interval = g_CvarInterval.FloatValue;
    float requiredSeconds = g_CvarSeconds.FloatValue;
    float radius = g_CvarRadius.FloatValue;

    for (int client = 1; client <= MaxClients; client++)
    {
        if (!IsClientInGame(client) || !IsPlayerAlive(client))
        {
            g_TimeInSphere[client] = 0.0;
            continue;
        }

        if (!IsFakeClient(client))
        {
            g_TimeInSphere[client] = 0.0;
            continue;
        }

        if (GetClientTeam(client) != TEAM_BLU)
        {
            g_TimeInSphere[client] = 0.0;
            continue;
        }

        if (!ClientHasThisFlag(client, redFlag))
        {
            g_TimeInSphere[client] = 0.0;
            continue;
        }

        float pos[3];
        GetClientAbsOrigin(client, pos);

        if (IsInsideSphere(pos, g_StuckCenter, radius))
        {
            g_TimeInSphere[client] += interval;

            if (g_TimeInSphere[client] >= requiredSeconds)
            {
                TeleportBotDown(client);

                if (g_CvarDebug.BoolValue)
                {
                    PrintToServer("[2fort_quake_botunstuck] Teleported bot %N after %.1f seconds in sphere with RED flag.", client, g_TimeInSphere[client]);
                }

                g_TimeInSphere[client] = 0.0;
            }
        }
        else
        {
            g_TimeInSphere[client] = 0.0;
        }
    }

    return Plugin_Continue;
}

bool IsInsideSphere(const float pos[3], const float center[3], float radius)
{
    float dx = pos[0] - center[0];
    float dy = pos[1] - center[1];
    float dz = pos[2] - center[2];
    float distSq = (dx * dx) + (dy * dy) + (dz * dz);
    return (distSq <= (radius * radius));
}

void TeleportBotDown(int client)
{
    float ang[3];
    if (g_ForceAngles)
    {
        ang[0] = g_DestAng[0];
        ang[1] = g_DestAng[1];
        ang[2] = g_DestAng[2];
    }
    else
    {
        GetClientAbsAngles(client, ang);
    }

    float vel[3] = { 0.0, 0.0, 0.0 };
    TeleportEntity(client, g_DestPos, ang, vel);
}

/**
 * Finds the RED team flag entity (item_teamflag with teamnum=2).
 */
int FindRedFlagEntity()
{
    int ent = -1;
    while ((ent = FindEntityByClassname(ent, "item_teamflag")) != -1)
    {
        int team = GetEntProp(ent, Prop_Send, "m_iTeamNum");
        if (team == TEAM_RED)
            return ent;
    }
    return -1;
}

/**
 * True if the given client is currently the owner/carrier of that flag entity.
 */
bool ClientHasThisFlag(int client, int flagEnt)
{
    if (flagEnt <= MaxClients || !IsValidEntity(flagEnt))
        return false;

    int owner = GetEntPropEnt(flagEnt, Prop_Send, "m_hOwnerEntity");
    return (owner == client);
}
