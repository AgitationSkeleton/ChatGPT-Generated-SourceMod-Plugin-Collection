#include <sourcemod>
#include <sdktools>

public Plugin myinfo =
{
    name = "Force Open Doors",
    author = "Your Name",
    description = "Forces all func_doors in the map to stay open every 30 seconds",
    version = "1.0",
    url = "N/A"
};

public void OnPluginStart()
{
    // Start a repeating timer every 30 seconds
    CreateTimer(30.0, Timer_OpenDoors, _, TIMER_REPEAT);
}

// Timer function to find and open all func_door entities
public Action Timer_OpenDoors(Handle timer)
{
    int entity = -1;

    // Iterate through all entities and find func_doors
    while ((entity = FindEntityByClassname(entity, "func_door")) != -1)
    {
        AcceptEntityInput(entity, "Open");  // Send the Open input to the door
    }

    return Plugin_Continue;
}
