#include <sourcemod>
#include <sdktools>

public Plugin myinfo = {
    name = "Impulse 101 Trigger (Cheat Bypass)",
    author = "ChatGPT",
    description = "Temporarily enables sv_cheats to execute impulse 101 in player's console.",
    version = "1.3"
};

public void OnPluginStart()
{
    RegConsoleCmd("sm_guns", Cmd_Impulse);
    RegConsoleCmd("sm_impulse101", Cmd_Impulse);
    RegConsoleCmd("say", Cmd_Say);
    RegConsoleCmd("say_team", Cmd_Say);
}

public Action Cmd_Say(int client, int args)
{
    if (!IsClientInGame(client)) return Plugin_Continue;

    char msg[192];
    GetCmdArgString(msg, sizeof(msg));
    TrimString(msg);

    if (StrEqual(msg, "!guns", false) || StrEqual(msg, "!impulse101", false))
    {
        return Cmd_Impulse(client, 0);
    }

    return Plugin_Continue;
}

public Action Cmd_Impulse(int client, int args)
{
    if (!IsClientInGame(client) || !IsPlayerAlive(client))
    {
        PrintToChat(client, "[Impulse 101] You must be alive to use this command.");
        return Plugin_Handled;
    }

    // Enable sv_cheats
    ServerCommand("sv_cheats 1");

    // Run impulse 101 after a short delay
    CreateTimer(0.1, Timer_RunImpulse, client);

    // Disable sv_cheats again shortly after
    CreateTimer(0.2, Timer_DisableCheats);

    return Plugin_Handled;
}

public Action Timer_RunImpulse(Handle timer, any client)
{
    if (IsClientInGame(client))
    {
        ClientCommand(client, "impulse 101");
        PrintToChat(client, "[Impulse 101] Executed.");
    }
    return Plugin_Stop;
}

public Action Timer_DisableCheats(Handle timer)
{
    ServerCommand("sv_cheats 0");
    return Plugin_Stop;
}
