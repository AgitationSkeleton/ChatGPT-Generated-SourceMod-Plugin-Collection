#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>

static const char PL_NAME[] = "Gunman Pain Sounds";
static const char PL_AUTHOR[] = "ChatGPT";
static const char PL_DESCRIPTION[] = "Plays a random pain sound when a player is damaged but not killed, audible to others.";
static const char PL_VERSION[] = "1.0.1";

char g_sSounds[6][PLATFORM_MAX_PATH] = {
    "vo/goon/gunman_pain01.wav",
    "vo/goon/gunman_pain02.wav",
    "vo/goon/gunman_pain03.wav",
    "vo/goon/gunman_pain04.wav",
    "vo/goon/gunman_pain05.wav",
    "vo/goon/gunman_pain06.wav"
};

public Plugin myinfo = {
    name = PL_NAME,
    author = PL_AUTHOR,
    description = PL_DESCRIPTION,
    version = PL_VERSION
};

public void OnPluginStart()
{
    HookEvent("player_hurt", Event_PlayerHurt, EventHookMode_Pre);
}

public void OnMapStart()
{
    for (int i = 0; i < 6; i++)
    {
        PrecacheSound(g_sSounds[i], true);
        AddFileToDownloadsTable(g_sSounds[i]);
    }
}

public Action Event_PlayerHurt(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(GetEventInt(event, "userid"));
    if (client < 1 || client > MaxClients || !IsClientInGame(client) || IsFakeClient(client)) return Plugin_Continue;

    int health = GetClientHealth(client);
    if (health <= 0) return Plugin_Continue; // Don't play sound if player is dead

    char soundFile[PLATFORM_MAX_PATH];
    strcopy(soundFile, sizeof(soundFile), g_sSounds[GetRandomInt(0, 5)]);
    
    EmitSoundToClient(client, soundFile, SOUND_FROM_PLAYER, SNDCHAN_AUTO, SNDLEVEL_NORMAL, SND_NOFLAGS, 1.0);
    EmitSoundToAll(soundFile, client, SNDCHAN_AUTO, SNDLEVEL_NORMAL, SND_NOFLAGS, 1.0);
    
    return Plugin_Continue;
}
