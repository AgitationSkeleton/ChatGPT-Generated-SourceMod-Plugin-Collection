/**
 * Coop Restart (2-player agreement)
 * Intended for Portal 1 Coop, but works on generic SourceMod.
 *
 * Author: ChatGPT
 */

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>

public Plugin myinfo =
{
    name        = "Coop Restart (2-player agreement)",
    author      = "ChatGPT",
    description = "Requires two human players to agree before reloading the current map.",
    version     = "1.0.0",
    url         = ""
};

#define RESTART_TIMEOUT_SECONDS 300.0   // 5 minutes
#define RESTART_DELAY_SECONDS   3.0

// Use a common HL2-era sound that typically exists in Source games.
// If your Portal 1 server doesn't have this sound, change it to one that does.
static const char g_beepSound[] = "buttons/button17.wav";

bool  g_hasRequested[MAXPLAYERS + 1];
int   g_requestCount = 0;

Handle g_timeoutTimer = null;
Handle g_restartTimer = null;

public void OnPluginStart()
{
    RegConsoleCmd("sm_restart", Cmd_Restart, "Request a cooperative restart (2 humans must agree).");

    ResetRestartState();
}

public void OnMapStart()
{
    PrecacheSound(g_beepSound, true);
    ResetRestartState();
}

public void OnMapEnd()
{
    ResetRestartState();
}

public void OnClientDisconnect(int client)
{
    if (client < 1 || client > MaxClients)
        return;

    // If someone who had requested leaves, reduce the tally.
    if (g_hasRequested[client])
    {
        g_hasRequested[client] = false;
        if (g_requestCount > 0)
            g_requestCount--;

        // If nobody remains as a requester, clear timers.
        if (g_requestCount <= 0)
        {
            ResetRestartState();
        }
    }
}

public Action Cmd_Restart(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
        return Plugin_Handled;

    if (IsFakeClient(client))
    {
        ReplyToCommand(client, "[SM] Bots cannot request a restart.");
        return Plugin_Handled;
    }

    // If we already scheduled a restart, tell them it's happening.
    if (g_restartTimer != null)
    {
        ReplyToCommand(client, "[SM] Restart already agreed. Restarting shortly...");
        return Plugin_Handled;
    }

    // If this player already requested, inform them.
    if (g_hasRequested[client])
    {
        ReplyToCommand(client, "[SM] You have already requested to restart the map. Your cooperative partner must also agree with the !restart command.");
        return Plugin_Handled;
    }

    // If the prior request expired naturally, clear state before counting this one.
    // (Timer should handle it, but this guards against edge cases.)
    // If timeout timer exists, it means a request is pending; we leave it alone.
    // The timer itself will reset after 5 minutes.

    // Record this requester.
    g_hasRequested[client] = true;
    if (g_requestCount < 2)
        g_requestCount++;

    char clientName[64];
    GetClientName(client, clientName, sizeof(clientName));

    if (g_requestCount == 1)
    {
        // First request: announce + beep to everyone, start 5-min timeout.
        PrintToChatAll("%s, would like to restart the map. To do so, both players must agree. Type !restart to reload the current map.", clientName);
        EmitSoundToAll(g_beepSound);

        StartOrRestartTimeoutTimer();
    }
    else if (g_requestCount >= 2)
    {
        // Second unique human agreed: announce and restart in 3 seconds.
        PrintToChatAll("Both players agree to restart the map. Restarting in 3 seconds...");

        KillTimerSafe(g_timeoutTimer);
        g_timeoutTimer = null;

        KillTimerSafe(g_restartTimer);
        g_restartTimer = CreateTimer(RESTART_DELAY_SECONDS, Timer_DoRestart, _, TIMER_FLAG_NO_MAPCHANGE);
    }

    return Plugin_Handled;
}

public Action Timer_TimeoutReset(Handle timer)
{
    // If this fires, the pending request window expired.
    g_timeoutTimer = null;

    // Only reset if we haven't already scheduled a restart.
    if (g_restartTimer == null)
    {
        ResetRestartState();
        PrintToChatAll("[SM] Restart request expired (no agreement within 5 minutes).");
    }

    return Plugin_Stop;
}

public Action Timer_DoRestart(Handle timer)
{
    g_restartTimer = null;

    // Reload the current map.
    char currentMap[PLATFORM_MAX_PATH];
    GetCurrentMap(currentMap, sizeof(currentMap));

    // Reset state before changing, so we come up clean on the next load.
    ResetRestartState();

    ForceChangeLevel(currentMap, "Coop restart requested");

    return Plugin_Stop;
}

void StartOrRestartTimeoutTimer()
{
    KillTimerSafe(g_timeoutTimer);
    g_timeoutTimer = CreateTimer(RESTART_TIMEOUT_SECONDS, Timer_TimeoutReset, _, TIMER_FLAG_NO_MAPCHANGE);
}

void ResetRestartState()
{
    for (int i = 1; i <= MaxClients; i++)
    {
        g_hasRequested[i] = false;
    }

    g_requestCount = 0;

    KillTimerSafe(g_timeoutTimer);
    g_timeoutTimer = null;

    KillTimerSafe(g_restartTimer);
    g_restartTimer = null;
}

void KillTimerSafe(Handle &timerHandle)
{
    if (timerHandle != null)
    {
        KillTimer(timerHandle);
        timerHandle = null;
    }
}