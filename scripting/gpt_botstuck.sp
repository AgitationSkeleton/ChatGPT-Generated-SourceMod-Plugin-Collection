#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>

#define CHECK_INTERVAL 5.0       // Check bots every 5 seconds
#define MAX_INACTIVE_TIME 120.0  // Time in seconds before slaying

public Plugin myinfo =
{
    name = "Bot Inactivity Slay",
    author = "ChatGPT",
    description = "Slays bot players if they haven't scored a point in 120 seconds.",
    version = "1.0.0"
};

// Store last known scores and timestamps
float g_fLastScoreTime[MAXPLAYERS + 1];
int g_iLastScore[MAXPLAYERS + 1];

public void OnPluginStart()
{
    CreateTimer(CHECK_INTERVAL, Timer_CheckBotScores, _, TIMER_REPEAT);
    HookEvent("player_spawn", Event_PlayerSpawn);
    HookEvent("teamplay_point_captured", Event_PlayerScored);
    HookEvent("player_death", Event_PlayerScored);
}

// Reset bot score tracking on spawn
public void Event_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client > 0 && client <= MaxClients && IsClientInGame(client) && IsFakeClient(client))
    {
        g_fLastScoreTime[client] = GetGameTime();
        g_iLastScore[client] = GetClientFrags(client);
    }
}

// Update bot score tracking on scoring events
public void Event_PlayerScored(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client > 0 && client <= MaxClients && IsClientInGame(client) && IsFakeClient(client))
    {
        g_fLastScoreTime[client] = GetGameTime();
        g_iLastScore[client] = GetClientFrags(client);
    }
}

// Check if any bot should be slain for inactivity
public Action Timer_CheckBotScores(Handle timer)
{
    float currentTime = GetGameTime();
    for (int client = 1; client <= MaxClients; client++)
    {
        if (IsClientInGame(client) && IsFakeClient(client) && GetClientTeam(client) > 1)
        {
            int currentScore = GetClientFrags(client);
            if (currentScore > g_iLastScore[client])
            {
                g_fLastScoreTime[client] = currentTime;
                g_iLastScore[client] = currentScore;
            }
            else if ((currentTime - g_fLastScoreTime[client]) >= MAX_INACTIVE_TIME)
            {
                PrintToServer("[BotSlay] Slaying bot %N for inactivity.", client);
                ForcePlayerSuicide(client);
                g_fLastScoreTime[client] = currentTime; // Reset timer
            }
        }
    }
    return Plugin_Continue;
}
