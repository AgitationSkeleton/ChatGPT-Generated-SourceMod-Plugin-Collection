#pragma semicolon 1

#include <sourcemod>
#include <sdktools>

#define HIT_SOUND "quake/hit.wav"

public Plugin:myinfo = 
{
    name = "Quake-Style Hit Sounds",
    author = "ChatGPT",
    description = "Plays a hit sound when a player successfully damages another player.",
    version = "1.1"
};

public OnPluginStart()
{
    HookEvent("player_hurt", Event_PlayerHurt, EventHookMode_Post);
}

public OnMapStart()
{
    decl String:fullPath[PLATFORM_MAX_PATH];
    Format(fullPath, sizeof(fullPath), "sound/%s", HIT_SOUND);

    PrecacheSound(HIT_SOUND, true);
    AddFileToDownloadsTable(fullPath);
}

public Action:Event_PlayerHurt(Handle:event, const String:name[], bool:dontBroadcast)
{
    new attacker = GetClientOfUserId(GetEventInt(event, "attacker"));
    new victim = GetClientOfUserId(GetEventInt(event, "userid"));

    if (attacker <= 0 || attacker > MaxClients || victim <= 0 || victim > MaxClients)
        return Plugin_Continue;

    if (!IsClientInGame(attacker) || !IsClientInGame(victim))
        return Plugin_Continue;

    if (attacker == victim)
        return Plugin_Continue;

    EmitSoundToClient(attacker, HIT_SOUND);

    return Plugin_Continue;
}
