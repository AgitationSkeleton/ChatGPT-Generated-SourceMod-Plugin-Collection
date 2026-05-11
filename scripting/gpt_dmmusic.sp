#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>

public Plugin myinfo =
{
    name = "Deathmatch Join Sound",
    author = "ChatGPT",
    description = "Plays a soundscript entry once per player per map after joining a valid team.",
    version = "1.0.0",
    url = ""
};

#define JOIN_SOUND "Deathmatch.B4DM_AFG"
#define MAP_DELAY_SECONDS 31.0

bool g_hasPlayedJoinSound[MAXPLAYERS + 1];
bool g_isEligibleMap;
float g_mapStartTime;

public void OnPluginStart()
{
    HookEvent("player_team", Event_PlayerTeam, EventHookMode_Post);
}

public void OnMapStart()
{
    g_mapStartTime = GetGameTime();

    for (int clientIndex = 1; clientIndex <= MaxClients; clientIndex++)
    {
        g_hasPlayedJoinSound[clientIndex] = false;
    }

    char currentMap[PLATFORM_MAX_PATH];
    GetCurrentMap(currentMap, sizeof(currentMap));

    g_isEligibleMap = IsEligibleMap(currentMap);

    LogMessage("[Deathmatch Join Sound] Map: %s | Eligible: %s", currentMap, g_isEligibleMap ? "yes" : "no");
}

public void OnClientDisconnect(int clientIndex)
{
    if (clientIndex >= 1 && clientIndex <= MaxClients)
    {
        g_hasPlayedJoinSound[clientIndex] = false;
    }
}

public void Event_PlayerTeam(Event event, const char[] name, bool dontBroadcast)
{
    if (!g_isEligibleMap)
    {
        return;
    }

    if ((GetGameTime() - g_mapStartTime) < MAP_DELAY_SECONDS)
    {
        return;
    }

    int clientIndex = GetClientOfUserId(event.GetInt("userid"));

    if (clientIndex < 1 || clientIndex > MaxClients)
    {
        return;
    }

    if (!IsClientInGame(clientIndex))
    {
        return;
    }

    if (IsFakeClient(clientIndex))
    {
        return;
    }

    if (g_hasPlayedJoinSound[clientIndex])
    {
        return;
    }

    int newTeam = event.GetInt("team");

    if (newTeam <= 0)
    {
        return;
    }

    ClientCommand(clientIndex, "playgamesound %s", JOIN_SOUND);

    g_hasPlayedJoinSound[clientIndex] = true;

    LogMessage("[Deathmatch Join Sound] Played %s to %N on team %d.", JOIN_SOUND, clientIndex, newTeam);
}

bool IsEligibleMap(const char[] mapName)
{
    if (StrContains(mapName, "inf_", false) != -1)
    {
        return false;
    }

    if (StrContains(mapName, "4dm_", false) != -1)
    {
        return true;
    }

    if (StrContains(mapName, "mctf_", false) != -1)
    {
        return true;
    }

    if (StrContains(mapName, "4rdm_", false) != -1)
    {
        return true;
    }

    if (StrContains(mapName, "mdom_", false) != -1)
    {
        return true;
    }

    if (StrContains(mapName, "4ig_", false) != -1)
    {
        return true;
    }

    if (StrContains(mapName, "4gg_", false) != -1)
    {
        return true;
    }

    return false;
}