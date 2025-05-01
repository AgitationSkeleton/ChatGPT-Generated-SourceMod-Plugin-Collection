#pragma semicolon 1
#include <sourcemod>
#include <sdktools>

#define COOLDOWN_TIME 20.0
new Handle:g_Cooldowns;
new String:g_sExplosionSound[64] = "ambient/explosions/explode_4.wav";

public Plugin:myinfo =
{
    name = "Fake RTD",
    author = "ChatGPT",
    description = "Fake Roll The Dice that always explodes the player",
    version = "1.0"
};

public OnPluginStart()
{
    g_Cooldowns = CreateTrie();

    RegConsoleCmd("sm_rtd", Command_RTD);
    RegConsoleCmd("sm_rollthedice", Command_RTD);
    RegConsoleCmd("rtd", Command_RTD);

    HookEvent("player_say", Event_PlayerSay);

    PrecacheSound(g_sExplosionSound, true);

    decl String:fullPath[PLATFORM_MAX_PATH];
    Format(fullPath, sizeof(fullPath), "sound/%s", g_sExplosionSound);
    AddFileToDownloadsTable(fullPath);
}

public Action:Event_PlayerSay(Handle:event, const String:name[], bool:dontBroadcast)
{
    new client = GetClientOfUserId(GetEventInt(event, "userid"));
    decl String:message[192];
    GetEventString(event, "text", message, sizeof(message));

    if (StrEqual(message, "rtd", false))
    {
        Command_RTD(client, 0);
        return Plugin_Handled;
    }

    return Plugin_Continue;
}

public Action:Command_RTD(client, args)
{
    if (client <= 0 || !IsClientInGame(client) || IsFakeClient(client))
        return Plugin_Handled;

    decl String:steamId[32];
    GetClientAuthString(client, steamId, sizeof(steamId));

    new Float:lastUsed;
    if (GetTrieValue(g_Cooldowns, steamId, lastUsed))
    {
        new Float:delta = GetGameTime() - lastUsed;
        if (delta < COOLDOWN_TIME)
        {
            PrintToChat(client, "[RTD] Wait %.0f seconds before rolling again!", COOLDOWN_TIME - delta);
            return Plugin_Handled;
        }
    }

    SetTrieValue(g_Cooldowns, steamId, GetGameTime());

    decl String:name[64];
    GetClientName(client, name, sizeof(name));
    PrintToChatAll("%s rolled: [ Explode ]", name);

    EmitSoundToClient(client, g_sExplosionSound);
    ForcePlayerSuicide(client);

    return Plugin_Handled;
}
