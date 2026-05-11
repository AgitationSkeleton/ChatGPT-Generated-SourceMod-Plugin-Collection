#pragma semicolon 1

#include <sourcemod>

public Plugin myinfo =
{
    name = "Empty Special Map Rotator",
    author = "ChatGPT",
    description = "Changes map if special maps are empty for 30 seconds.",
    version = "1.0.0",
    url = ""
};

Handle g_emptyTimer = INVALID_HANDLE;

public void OnPluginStart()
{
    HookEvent("player_disconnect", Event_PlayerChanged, EventHookMode_PostNoCopy);
    HookEvent("player_team", Event_PlayerChanged, EventHookMode_PostNoCopy);

    CheckEmptyState();
}

public void OnMapStart()
{
    StopEmptyTimer();
    CreateTimer(2.0, Timer_DelayedMapCheck, _, TIMER_FLAG_NO_MAPCHANGE);
}

public void OnClientPutInServer(int client)
{
    if (!IsFakeClient(client))
    {
        StopEmptyTimer();
    }
}

public void OnClientDisconnect_Post(int client)
{
    CheckEmptyState();
}

public Action Event_PlayerChanged(Event event, const char[] name, bool dontBroadcast)
{
    CheckEmptyState();
    return Plugin_Continue;
}

public Action Timer_DelayedMapCheck(Handle timer)
{
    CheckEmptyState();
    return Plugin_Stop;
}

void CheckEmptyState()
{
    if (!IsTargetMap())
    {
        StopEmptyTimer();
        return;
    }

    if (GetHumanPlayerCount() > 0)
    {
        StopEmptyTimer();
        return;
    }

    if (g_emptyTimer == INVALID_HANDLE)
    {
        g_emptyTimer = CreateTimer(30.0, Timer_EmptyServerChangeMap, _, TIMER_FLAG_NO_MAPCHANGE);
    }
}

public Action Timer_EmptyServerChangeMap(Handle timer)
{
    g_emptyTimer = INVALID_HANDLE;

    if (!IsTargetMap())
    {
        return Plugin_Stop;
    }

    if (GetHumanPlayerCount() > 0)
    {
        return Plugin_Stop;
    }

    char nextMap[PLATFORM_MAX_PATH];

    if (!GetRandomMapFromMapcycle(nextMap, sizeof(nextMap)))
    {
        LogError("[EmptySpecialMapRotator] Could not pick random map from mapcyclefile.");
        return Plugin_Stop;
    }

    LogMessage("[EmptySpecialMapRotator] No human players for 30 seconds on target map. Changing map to: %s", nextMap);
    ForceChangeLevel(nextMap, "Server empty on special map");

    return Plugin_Stop;
}

bool IsTargetMap()
{
    char currentMap[64];
    GetCurrentMap(currentMap, sizeof(currentMap));

    return StrEqual(currentMap, "4dm_backfort_of_v1", false)
        || StrEqual(currentMap, "2mctf_longestyard_of_v1", false);
}

int GetHumanPlayerCount()
{
    int humanCount = 0;

    for (int client = 1; client <= MaxClients; client++)
    {
        if (IsClientConnected(client) && !IsFakeClient(client))
        {
            humanCount++;
        }
    }

    return humanCount;
}

void StopEmptyTimer()
{
    if (g_emptyTimer != INVALID_HANDLE)
    {
        CloseHandle(g_emptyTimer);
        g_emptyTimer = INVALID_HANDLE;
    }
}

bool GetRandomMapFromMapcycle(char[] selectedMap, int selectedMapSize)
{
    char mapcycleCvarValue[PLATFORM_MAX_PATH];
    char mapcyclePath[PLATFORM_MAX_PATH];

    ConVar mapcycleConvar = FindConVar("mapcyclefile");

    if (mapcycleConvar == null)
    {
        return false;
    }

    mapcycleConvar.GetString(mapcycleCvarValue, sizeof(mapcycleCvarValue));

    if (mapcycleCvarValue[0] == '\0')
    {
        strcopy(mapcycleCvarValue, sizeof(mapcycleCvarValue), "mapcycle.txt");
    }

    Format(mapcyclePath, sizeof(mapcyclePath), "%s", mapcycleCvarValue);

    File mapcycleFile = OpenFile(mapcyclePath, "r");

    if (mapcycleFile == null)
    {
        LogError("[EmptySpecialMapRotator] Could not open mapcycle file: %s", mapcyclePath);
        return false;
    }

    ArrayList validMaps = new ArrayList(ByteCountToCells(64));

    char line[256];
    char currentMap[64];
    GetCurrentMap(currentMap, sizeof(currentMap));

    while (!mapcycleFile.EndOfFile() && mapcycleFile.ReadLine(line, sizeof(line)))
    {
        TrimString(line);

        if (line[0] == '\0')
        {
            continue;
        }

        if (line[0] == '/' && line[1] == '/')
        {
            continue;
        }

        if (line[0] == '#')
        {
            continue;
        }

        if (!IsMapValid(line))
        {
            continue;
        }

        if (StrEqual(line, currentMap, false))
        {
            continue;
        }

        validMaps.PushString(line);
    }

    delete mapcycleFile;

    int mapCount = validMaps.Length;

    if (mapCount <= 0)
    {
        delete validMaps;
        return false;
    }

    int randomIndex = GetRandomInt(0, mapCount - 1);
    validMaps.GetString(randomIndex, selectedMap, selectedMapSize);

    delete validMaps;
    return true;
}