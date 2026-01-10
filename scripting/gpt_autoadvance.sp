/**
 * =============================================================================
 * Empty-Server Auto-Advance (Selected Maps)
 * - If server stays on certain maps for 30 minutes AND no humans are connected,
 *   change to the next map in the cycle.
 * - Fallback next map: ctf_2fort
 *
 * Author: ChatGPT
 * =============================================================================
 */

#include <sourcemod>
#include <nextmap>

public Plugin myinfo =
{
    name        = "Empty Server Auto Advance (Selected Maps)",
    author      = "ChatGPT",
    description = "After 30 minutes on specified maps, if no humans are connected, change to next map (fallback ctf_2fort).",
    version     = "1.0.1",
    url         = ""
};

#define CHECK_DELAY_SECONDS 1800.0
#define FALLBACK_MAP "ctf_2fort"

Handle g_checkTimer = null;

static const char g_targetMaps[][] =
{
    "koth_traingrid_b2",
    "MARIO_KART",
    "harbl_hotel",
    "balloon_race_v2b",
    "wacky_races_v2"
};

public void OnMapStart()
{
    KillCheckTimer();

    char currentMap[PLATFORM_MAX_PATH];
    GetCurrentMap(currentMap, sizeof(currentMap));

    if (!IsTargetMap(currentMap))
    {
        return;
    }

    g_checkTimer = CreateTimer(CHECK_DELAY_SECONDS, Timer_CheckEmptyHumans, _, TIMER_FLAG_NO_MAPCHANGE);
}

public void OnMapEnd()
{
    KillCheckTimer();
}

public Action Timer_CheckEmptyHumans(Handle timer)
{
    g_checkTimer = null;

    // Safety: If map changed before the timer fired, stop.
    char currentMap[PLATFORM_MAX_PATH];
    GetCurrentMap(currentMap, sizeof(currentMap));
    if (!IsTargetMap(currentMap))
    {
        return Plugin_Stop;
    }

    if (CountHumanPlayers() > 0)
    {
        return Plugin_Stop;
    }

    char nextMap[PLATFORM_MAX_PATH];
    nextMap[0] = '\0';

    // Primary: nextmap extension/native
    if (!GetNextMap(nextMap, sizeof(nextMap)))
    {
        nextMap[0] = '\0';
    }

    // Secondary: sm_nextmap cvar
    if (nextMap[0] == '\0')
    {
        ConVar smNextMap = FindConVar("sm_nextmap");
        if (smNextMap != null)
        {
            smNextMap.GetString(nextMap, sizeof(nextMap));
        }
    }

    // Final fallback: hardcoded
    if (nextMap[0] == '\0')
    {
        strcopy(nextMap, sizeof(nextMap), FALLBACK_MAP);
    }

    LogMessage("[EmptyAutoAdvance] No humans after 30 minutes on %s; changing to: %s", currentMap, nextMap);
    ForceChangeLevel(nextMap, "Empty server on special map for 30 minutes");

    return Plugin_Stop;
}

static void KillCheckTimer()
{
    if (g_checkTimer != null)
    {
        KillTimer(g_checkTimer);
        g_checkTimer = null;
    }
}

static bool IsTargetMap(const char[] mapName)
{
    for (int i = 0; i < sizeof(g_targetMaps); i++)
    {
        if (StrEqual(mapName, g_targetMaps[i], false))
        {
            return true;
        }
    }
    return false;
}

static int CountHumanPlayers()
{
    int humanCount = 0;

    for (int client = 1; client <= MaxClients; client++)
    {
        if (!IsClientInGame(client))
        {
            continue;
        }

        if (IsFakeClient(client))
        {
            continue;
        }

        humanCount++;
    }

    return humanCount;
}
