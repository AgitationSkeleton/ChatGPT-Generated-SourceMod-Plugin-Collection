#pragma semicolon 1

#include <sourcemod>
#include <sdktools>

#define CHECK_INTERVAL 5.0       // Check bots every 5 seconds
#define MAX_INACTIVE_TIME 120.0  // Time in seconds before slaying

new Float:g_fLastScoreTime[MAXPLAYERS + 1];
new g_iLastScore[MAXPLAYERS + 1];

public Plugin:myinfo =
{
    name = "Bot Inactivity Slay (TF2-port)",
    author = "ChatGPT",
    description = "Slays bots if they haven't scored in 120 seconds.",
    version = "1.1"
};

public OnPluginStart()
{
    CreateTimer(CHECK_INTERVAL, Timer_CheckBotScores, _, TIMER_REPEAT);

    HookEvent("player_spawn", Event_PlayerSpawn);
    HookEvent("teamplay_point_captured", Event_PlayerScored);
    HookEvent("player_death", Event_PlayerScored);
}

public Action:Timer_CheckBotScores(Handle:timer)
{
    new Float:currentTime = GetGameTime();

    for (new client = 1; client <= MaxClients; client++)
    {
        if (!IsClientInGame(client) || !IsFakeClient(client))
            continue;

        if (GetClientTeam(client) <= 1)
            continue;

        new currentScore = GetClientFrags(client);
        if (currentScore > g_iLastScore[client])
        {
            g_fLastScoreTime[client] = currentTime;
            g_iLastScore[client] = currentScore;
        }
        else if ((currentTime - g_fLastScoreTime[client]) >= MAX_INACTIVE_TIME)
        {
            PrintToServer("[BotSlay] Slaying bot %N for inactivity.", client);
            FakeClientCommand(client, "kill");
            g_fLastScoreTime[client] = currentTime;
        }
    }

    return Plugin_Continue;
}

public Event_PlayerSpawn(Handle:event, const String:name[], bool:dontBroadcast)
{
    new client = GetClientOfUserId(GetEventInt(event, "userid"));
    if (client > 0 && client <= MaxClients && IsClientInGame(client) && IsFakeClient(client))
    {
        g_fLastScoreTime[client] = GetGameTime();
        g_iLastScore[client] = GetClientFrags(client);
    }
}

public Event_PlayerScored(Handle:event, const String:name[], bool:dontBroadcast)
{
    new client = GetClientOfUserId(GetEventInt(event, "userid"));
    if (client > 0 && client <= MaxClients && IsClientInGame(client) && IsFakeClient(client))
    {
        g_fLastScoreTime[client] = GetGameTime();
        g_iLastScore[client] = GetClientFrags(client);
    }
}
