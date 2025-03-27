#pragma semicolon 1
#include <sourcemod>
#include <sdktools>

#define COOLDOWN_TIME 20.0 // Cooldown in seconds
new Handle:g_Cooldowns;

public Plugin:myinfo =
{
    name = "Fake RTD",
    author = "ChatGPT",
    description = "Fake Roll The Dice that always explodes the player",
    version = "1.0",
    url = ""
};

char g_sExplosionSound[] = "ambient/explosions/explode_4.wav";  // Sound for the explosion

public void OnPluginStart()
{
    g_Cooldowns = CreateTrie();
    
    // Register commands
    RegConsoleCmd("sm_rtd", Command_RTD, "Fake Roll The Dice");
    RegConsoleCmd("sm_rollthedice", Command_RTD, "Fake Roll The Dice");
    RegConsoleCmd("rtd", Command_RTD, "Fake Roll The Dice");
    RegConsoleCmd("!rtd", Command_RTD, "Fake Roll The Dice");
    RegConsoleCmd("!rollthedice", Command_RTD, "Fake Roll The Dice");

    // Register chat listener for "rtd"
    HookEvent("player_say", Event_PlayerSay);

    // Precache the explosion sound to ensure it plays
    PrecacheSound(g_sExplosionSound, true);
    
    // Ensure clients download the sound file
    char fullPath[PLATFORM_MAX_PATH];
    Format(fullPath, sizeof(fullPath), "sound/%s", g_sExplosionSound);
    AddFileToDownloadsTable(fullPath);
}

// Handle chat messages to detect "rtd"
public Action Event_PlayerSay(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(GetEventInt(event, "userid"));
    char message[192];
    GetEventString(event, "text", message, sizeof(message));
    
    if (StrEqual(message, "rtd", false))
    {
        Command_RTD(client, 0);
        return Plugin_Handled;
    }
    
    return Plugin_Continue;
}

// Command handler for rolling the dice
public Action:Command_RTD(client, args)
{
    if (client == 0 || !IsClientInGame(client) || IsFakeClient(client))
    {
        return Plugin_Handled;
    }

    char steamId[32];
    GetClientAuthId(client, AuthId_Steam2, steamId, sizeof(steamId));
    
    float lastUsed;
    if (GetTrieValue(g_Cooldowns, steamId, lastUsed))
    {
        if (GetGameTime() - lastUsed < COOLDOWN_TIME)
        {
            PrintToChat(client, "[RTD] You must wait %.0f seconds before rolling again!", COOLDOWN_TIME - (GetGameTime() - lastUsed));
            return Plugin_Handled;
        }
    }
    
    // Update cooldown timestamp for the player
    SetTrieValue(g_Cooldowns, steamId, GetGameTime());
    
    // Inform everyone that the player has "rolled"
    char name[MAX_NAME_LENGTH];
    GetClientName(client, name, sizeof(name));
    PrintToChatAll("%s rolled: [ Explode ]", name);
    
    // Play the explosion sound and force the player's suicide
    EmitSoundToClient(client, g_sExplosionSound);
    ForcePlayerSuicide(client);
    
    return Plugin_Handled;
}

// Reset cooldowns if cooldown time is exceeded
public void ResetCooldowns()
{
    // Iterate through the trie and reset any expired cooldowns
    for (int i = 1; i <= MaxClients; i++)
    {
        char steamId[32];
        GetClientAuthId(i, AuthId_Steam2, steamId, sizeof(steamId));

        float lastUsed;
        if (GetTrieValue(g_Cooldowns, steamId, lastUsed))
        {
            if (GetGameTime() - lastUsed > COOLDOWN_TIME)
            {
                // Reset the cooldown if it's expired
                SetTrieValue(g_Cooldowns, steamId, 0.0);
            }
        }
    }
}
