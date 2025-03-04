#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>

public Plugin myinfo = {
    name = "Quake-Style Hit Sounds",
    author = "ChatGPT",
    description = "Plays a hit sound when a player successfully damages another player.",
    version = "1.1"
};

char g_sHitSound[] = "quake/hit.wav";  // Change this to any valid sound path

public void OnPluginStart()
{
    HookEvent("player_hurt", Event_PlayerHurt, EventHookMode_Post);
}

public void OnMapStart()
{
    // Precache sound so it's ready for use
    char fullPath[PLATFORM_MAX_PATH];
    Format(fullPath, sizeof(fullPath), "sound/%s", g_sHitSound);
    
    PrecacheSound(g_sHitSound, true);
    AddFileToDownloadsTable(fullPath);  // Ensures clients download the file
}

public Action Event_PlayerHurt(Event event, const char[] name, bool dontBroadcast)
{
    int attacker = GetClientOfUserId(GetEventInt(event, "attacker"));
    int victim = GetClientOfUserId(GetEventInt(event, "userid"));

    if (attacker <= 0 || attacker > MaxClients || victim <= 0 || victim > MaxClients) return Plugin_Continue;
    if (!IsClientInGame(attacker) || !IsClientInGame(victim)) return Plugin_Continue;
    if (attacker == victim) return Plugin_Continue;  // Ignore self-inflicted damage

    // Play sound only for the attacker
    EmitSoundToClient(attacker, g_sHitSound, _, _, _, _, 0.8);

    return Plugin_Continue;
}
