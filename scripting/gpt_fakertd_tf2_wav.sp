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

// Explosion sound variations
char g_sExplosionSounds[4][PLATFORM_MAX_PATH] = {
    "ambient/explosions/explode_1.wav",
    "ambient/explosions/explode_2.wav",
    "ambient/explosions/explode_3.wav",
    "ambient/explosions/explode_4.wav"
};

// Voiceline sounds
char g_sVoicelines[9][PLATFORM_MAX_PATH] = {
    "vo/demoman_laughlong02.wav",
    "vo/engineer_laughhappy03.wav",
    "vo/heavy_laughterbig01.wav",
    "vo/medic_laughlong01.wav",
    "vo/pyro_laugh_addl04.wav",
    "vo/scout_laughlong02.wav",
    "vo/sniper_laughlong02.wav",
    "vo/soldier_laughlong03.wav",
    "vo/spy_laughlong01.wav"
};

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

    // Precache explosion sounds and voicelines
    for (int i = 0; i < 4; i++)
    {
        PrecacheSound(g_sExplosionSounds[i], true);
        AddFileToDownloadsTable(g_sExplosionSounds[i]);
    }

    for (int i = 0; i < 9; i++)
    {
        PrecacheSound(g_sVoicelines[i], true);
        AddFileToDownloadsTable(g_sVoicelines[i]);
    }
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
    
    // Create a white screen flash effect
    Handle fade = CreateEntityByName("env_fade");
    if (IsValidEntity(fade))
    {
        DispatchKeyValue(fade, "duration", "0.5");
        DispatchKeyValue(fade, "holdtime", "0.1");
        DispatchKeyValue(fade, "rendercolor", "255 255 255");
        DispatchKeyValue(fade, "renderamt", "255");
        DispatchKeyValue(fade, "spawnflags", "1"); // Only affect the client
        DispatchSpawn(fade);
        AcceptEntityInput(fade, "Fade", client);
        CreateTimer(1.0, DeleteEntity, fade);
    }
    
    // Play a random explosion sound
    char explosionSound[PLATFORM_MAX_PATH];
    strcopy(explosionSound, sizeof(explosionSound), g_sExplosionSounds[GetRandomInt(0, 3)]);
    EmitSoundToClient(client, explosionSound);
    
    // Play a random voiceline
    char voiceline[PLATFORM_MAX_PATH];
    strcopy(voiceline, sizeof(voiceline), g_sVoicelines[GetRandomInt(0, 8)]);
    EmitSoundToClient(client, voiceline);

    // Force the player's suicide
    ForcePlayerSuicide(client);
    
    return Plugin_Handled;
}

// Timer to delete the fade entity
public Action DeleteEntity(Handle timer, int entity)
{
    if (IsValidEntity(entity))
    {
        RemoveEdict(entity);
    }
    return Plugin_Stop;
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
