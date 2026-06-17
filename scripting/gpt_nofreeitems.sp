#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools_sound>

public Plugin myinfo =
{
    name = "No Free Items",
    author = "ChatGPT",
    description = "Plays a random TF2 merc 'No' voice line when players ask for free items.",
    version = "1.0.0",
    url = ""
};

static const char g_NoSounds[][] =
{
    "vo/scout_no01.mp3",
    "vo/scout_no02.mp3",

    "vo/soldier_no01.mp3",
    "vo/soldier_no02.mp3",

    "vo/pyro_no01.mp3",

    "vo/demoman_no01.mp3",
    "vo/demoman_no02.mp3",

    "vo/heavy_no01.mp3",
    "vo/heavy_no02.mp3",

    "vo/engineer_no01.mp3",
    "vo/engineer_no02.mp3",

    "vo/medic_no01.mp3",
    "vo/medic_no02.mp3",

    "vo/sniper_no01.mp3",
    "vo/sniper_no02.mp3",

    "vo/spy_no01.mp3",
    "vo/spy_no02.mp3"
};

public void OnPluginStart()
{
    RegConsoleCmd("sm_givemeall", Command_NoItems);
    RegConsoleCmd("sm_items", Command_NoItems);
    RegConsoleCmd("sm_freeitems", Command_NoItems);
}

public void OnMapStart()
{
    for (int soundIndex = 0; soundIndex < sizeof(g_NoSounds); soundIndex++)
    {
        PrecacheSound(g_NoSounds[soundIndex], true);
    }
}

public Action Command_NoItems(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
    {
        return Plugin_Handled;
    }

    int randomIndex = GetRandomInt(0, sizeof(g_NoSounds) - 1);
    char selectedSound[PLATFORM_MAX_PATH];
    strcopy(selectedSound, sizeof(selectedSound), g_NoSounds[randomIndex]);

    EmitSoundToAll(
        selectedSound,
        client,
        SNDCHAN_VOICE,
        SNDLEVEL_NORMAL
    );

    return Plugin_Handled;
}