#include <sourcemod>
#include <sdktools>

bool g_bEnabled = false;

public Plugin myinfo =
{
    name = "Force Open Doors",
    author = "ChatGPT",
    description = "Forces all func_doors in qualifying maps to stay open every 10 seconds",
    version = "1.2",
    url = "N/A"
};

public void OnPluginStart()
{
    UpdateMapEnabledState();

    // Start a repeating timer every 10 seconds.
    CreateTimer(10.0, Timer_OpenDoors, _, TIMER_REPEAT);
}

public void OnMapStart()
{
    UpdateMapEnabledState();
}

void UpdateMapEnabledState()
{
    char mapName[PLATFORM_MAX_PATH];
    GetCurrentMap(mapName, sizeof(mapName));

    // Only enable on maps whose name contains "2mctf" and either "tdc" or "presplit".
    g_bEnabled = (StrContains(mapName, "2mctf", false) != -1 &&
        (StrContains(mapName, "tdc", false) != -1 || StrContains(mapName, "presplit", false) != -1));
}

// Timer function to find and open all func_door entities.
public Action Timer_OpenDoors(Handle timer)
{
    if (!g_bEnabled)
    {
        return Plugin_Continue;
    }

    int entity = -1;

    // Iterate through all entities and find func_doors.
    while ((entity = FindEntityByClassname(entity, "func_door")) != -1)
    {
        AcceptEntityInput(entity, "Open");
    }

    return Plugin_Continue;
}
