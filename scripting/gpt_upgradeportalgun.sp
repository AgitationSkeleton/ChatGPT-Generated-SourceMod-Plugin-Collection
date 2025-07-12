#include <sourcemod>
#include <sdktools>

public Plugin myinfo = {
    name = "Upgrade Portal Gun (cheat bypass)",
    author = "ChatGPT",
    description = "Attempts to upgrade the portal gun using sv_cheats workaround",
    version = "1.1"
};

public void OnPluginStart()
{
    RegConsoleCmd("sm_upgrade", Cmd_Upgrade);
    RegConsoleCmd("sm_upgradeportalgun", Cmd_Upgrade);

    RegConsoleCmd("say", Cmd_Say);
    RegConsoleCmd("say_team", Cmd_Say);
}

public Action Cmd_Say(int client, int args)
{
    if (!IsClientInGame(client)) return Plugin_Continue;

    char msg[192];
    GetCmdArgString(msg, sizeof(msg));
    TrimString(msg);

    if (StrEqual(msg, "!upgrade", false) || StrEqual(msg, "!upgradeportalgun", false))
    {
        return Cmd_Upgrade(client, 0);
    }

    return Plugin_Continue;
}

public Action Cmd_Upgrade(int client, int args)
{
    if (!IsClientInGame(client) || !IsPlayerAlive(client))
    {
        PrintToChat(client, "[PortalGun] You must be alive to upgrade the portal gun.");
        return Plugin_Handled;
    }

    PrintToChat(client, "[PortalGun] Attempting to upgrade your portal gun...");

    // Enable sv_cheats and wait 0.1s before executing upgrade command
    ServerCommand("sv_cheats 1");
    CreateTimer(0.1, Timer_DoUpgrade, client);
    return Plugin_Handled;
}

public Action Timer_DoUpgrade(Handle timer, any client)
{
    if (!IsClientInGame(client))
    {
        return Plugin_Stop;
    }

    PrintToChat(client, "[PortalGun] Running upgrade_portalgun command...");
    FakeClientCommand(client, "upgrade_portalgun");

    // Disable sv_cheats again shortly after
    CreateTimer(0.1, Timer_ResetCheats);
    return Plugin_Stop;
}

public Action Timer_ResetCheats(Handle timer)
{
    ServerCommand("sv_cheats 0");
    return Plugin_Stop;
}
