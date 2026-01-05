/**
 * Custom Weapon Nag – TF2 / TF2 Classic (old syntax)
 * Author: ChatGPT
 */

#pragma semicolon 1

#include <sourcemod>
#include <sdktools>

#define STALKER_SOUND "npc/stalker/go_alert2.wav"

// Use the WRAPPER overlay material (no extension)
#define OVERLAY_MATERIAL "overlays/heavy_face_overlay"

// This is only the .vmt wrapper we ship (tiny). The base heavy VTF already exists.
#define OVERLAY_VMT_DOWNLOAD "materials/overlays/heavy_face_overlay.vmt"

static const String:gReminderText[] = "For custom weapons, check your loadout!!!";

static const String:gTriggers[][] =
{
    "!custom", "/custom",
    "!weapons", "/weapons",
    "!weapon", "/weapon",
    "!customweapons", "/customweapons",
    "!wepon", "/wepon",
    "!costom", "/costom",
    "!wep", "/wep",
    "!weps", "/weps",
    "!equip", "/equip",
    "!taunts", "!taunt",
    "/taunts", "/taunt"
};

new Handle:gExplodeTimer[MAXPLAYERS + 1];
new Handle:gMessageTimer[MAXPLAYERS + 1];

new gExplosionSprite = -1;

public Plugin:myinfo =
{
    name        = "Custom Weapons Nag (TF2/TF2C)",
    author      = "ChatGPT",
    description = "Overlay + stalker sound + explode for custom weapon commands",
    version     = "1.3.0",
    url         = ""
};

public OnPluginStart()
{
    AddCommandListener(Command_Say, "say");
    AddCommandListener(Command_Say, "say_team");
}

public OnMapStart()
{
    PrecacheSound(STALKER_SOUND, true);

    // Ship only the overlay wrapper VMT
    AddFileToDownloadsTable(OVERLAY_VMT_DOWNLOAD);

    // Precache the overlay material (name without materials/ and without extension)
    PrecacheDecal(OVERLAY_MATERIAL, true);

    gExplosionSprite = PrecacheModel("sprites/zerogxplode.spr", true);
}

public OnClientDisconnect(client)
{
    KillClientTimers(client);
}

public Action:Command_Say(client, const String:command[], argc)
{
    if (client <= 0 || !IsClientInGame(client))
    {
        return Plugin_Continue;
    }

    decl String:msg[256];
    GetCmdArgString(msg, sizeof(msg));
    StripQuotes(msg);
    TrimString(msg);

    if (msg[0] == '\0')
    {
        return Plugin_Continue;
    }

    decl String:token[64];
    token[0] = '\0';
    BreakString(msg, token, sizeof(token));
    TrimString(token);

    if (token[0] == '\0')
    {
        return Plugin_Continue;
    }

    ToLowerInPlace(token);

    if (!IsTrigger(token))
    {
        return Plugin_Continue;
    }

    new bool:isTaunt = (StrContains(token, "taunt", false) != -1);

    StartSequence(client, isTaunt);

    return Plugin_Handled;
}

static bool:IsTrigger(const String:token[])
{
    for (new i = 0; i < sizeof(gTriggers); i++)
    {
        if (StrEqual(token, gTriggers[i], false))
        {
            return true;
        }
    }
    return false;
}

static StartSequence(client, bool:isTaunt)
{
    KillClientTimers(client);

    EmitSoundToClient(client, STALKER_SOUND);

    SetOverlayCheat(false);
    ClientCommand(client, "r_screenoverlay \"%s\"", OVERLAY_MATERIAL);
    SetOverlayCheat(true);

    gExplodeTimer[client] = CreateTimer(2.0, Timer_Explode, GetClientUserId(client), TIMER_FLAG_NO_MAPCHANGE);

    if (!isTaunt)
    {
        gMessageTimer[client] = CreateTimer(3.0, Timer_Message, GetClientUserId(client), TIMER_FLAG_NO_MAPCHANGE);
    }
}

public Action:Timer_Explode(Handle:timer, any:userid)
{
    new client = GetClientOfUserId(userid);
    if (client <= 0 || !IsClientInGame(client))
    {
        return Plugin_Stop;
    }

    gExplodeTimer[client] = INVALID_HANDLE;

    SetOverlayCheat(false);
    ClientCommand(client, "r_screenoverlay \"\"");
    SetOverlayCheat(true);

    decl Float:pos[3];
    GetClientAbsOrigin(client, pos);

    if (gExplosionSprite != -1)
    {
        TE_SetupExplosion(pos, gExplosionSprite, 5.0, 1, 0, 200, 200);
        TE_SendToAll();
    }

    if (IsPlayerAlive(client))
    {
        ForcePlayerSuicide(client);
    }

    return Plugin_Stop;
}

public Action:Timer_Message(Handle:timer, any:userid)
{
    new client = GetClientOfUserId(userid);
    if (client <= 0 || !IsClientInGame(client))
    {
        return Plugin_Stop;
    }

    gMessageTimer[client] = INVALID_HANDLE;

    PrintToChat(client, "%s", gReminderText);
    PrintCenterText(client, "%s", gReminderText);

    return Plugin_Stop;
}

static KillClientTimers(client)
{
    if (gExplodeTimer[client] != INVALID_HANDLE)
    {
        KillTimer(gExplodeTimer[client]);
        gExplodeTimer[client] = INVALID_HANDLE;
    }

    if (gMessageTimer[client] != INVALID_HANDLE)
    {
        KillTimer(gMessageTimer[client]);
        gMessageTimer[client] = INVALID_HANDLE;
    }
}

static SetOverlayCheat(bool:enableCheatFlag)
{
    new flags = GetCommandFlags("r_screenoverlay");
    if (flags == INVALID_FCVAR_FLAGS)
    {
        return;
    }

    if (enableCheatFlag)
    {
        SetCommandFlags("r_screenoverlay", flags | FCVAR_CHEAT);
    }
    else
    {
        SetCommandFlags("r_screenoverlay", flags & ~FCVAR_CHEAT);
    }
}

static ToLowerInPlace(String:text[])
{
    new len = strlen(text);
    for (new i = 0; i < len; i++)
    {
        if (text[i] >= 'A' && text[i] <= 'Z')
        {
            text[i] = text[i] + ('a' - 'A');
        }
    }
}
