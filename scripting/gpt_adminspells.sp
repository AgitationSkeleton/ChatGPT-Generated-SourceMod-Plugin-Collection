/**
 * gpt_adminspells.sp
 * Applies Halloween spell attributes to certain players' weapons on spawn/resupply.
 *
 * Per-client toggle only (stored via clientprefs cookie).
 *
 * Requires:
 *  - TF2Attributes extension (tf2attributes)
 *  - Client Preferences (clientprefs)
 *
 * Author: ChatGPT
 */

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>
#include <clientprefs>
#include <tf2_stocks>
#include <tf2attributes>

public Plugin myinfo =
{
    name        = "Admin Spells",
    author      = "ChatGPT",
    description = "Applies selected Halloween spell attributes to certain players' weapons on spawn/resupply.",
    version     = "1.3.1",
    url         = ""
};

// Put your Steam2 string here (either STEAM_0 or STEAM_1 is fine; we match by AccountID)
static const char gWhitelistedSteam2Ids[][] =
{
    "STEAM_0:1:32388925"
};

// Spell attribute defindexes
#define ATTR_PUMPKIN_BOMBS_DEFIDX 1007
#define ATTR_EXORCISM_DEFIDX      1009

Handle gCookieClientEnabled;
bool gClientEnabled[MAXPLAYERS + 1];

ConVar gCvarDebug;

public void OnPluginStart()
{
    gCookieClientEnabled = RegClientCookie(
        "adminspells_enabled",
        "Whether this client has AdminSpells enabled for themselves (1/0).",
        CookieAccess_Private
    );

    RegAdminCmd("sm_adminspells", Cmd_AdminSpells, ADMFLAG_GENERIC, "Usage: sm_adminspells [0/1]. Toggles or sets your AdminSpells preference.");

    gCvarDebug = CreateConVar(
        "sm_adminspells_debug",
        "0",
        "Debug logging for AdminSpells (1 = on, 0 = off).",
        FCVAR_NOTIFY,
        true, 0.0,
        true, 1.0
    );

    HookEvent("post_inventory_application", Event_PostInventoryApplication, EventHookMode_Post);
	HookEvent("player_upgraded", Event_PlayerUpgraded, EventHookMode_Post);
    HookEvent("player_spawn", Event_PlayerSpawn, EventHookMode_Post);

    for (int i = 1; i <= MaxClients; i++)
    {
        gClientEnabled[i] = true;
    }
}

public void OnClientPutInServer(int client)
{
    gClientEnabled[client] = true;
}

public void OnClientCookiesCached(int client)
{
    char value[8];
    GetClientCookie(client, gCookieClientEnabled, value, sizeof(value));

    if (value[0] == '\0')
    {
        gClientEnabled[client] = true;
        SetClientCookie(client, gCookieClientEnabled, "1");
        return;
    }

    gClientEnabled[client] = (StringToInt(value) != 0);
}

public void OnClientDisconnect(int client)
{
    gClientEnabled[client] = true;
}

public void Event_PlayerUpgraded(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client))
    {
        return;
    }

    if (!IsSteamIdWhitelisted(client) || !gClientEnabled[client])
    {
        return;
    }

    // Reapply after upgrade purchases/commits.
    ScheduleMultiApply(client, "player_upgraded");
}

// -------------------- Commands --------------------

public Action Cmd_AdminSpells(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
    {
        return Plugin_Handled;
    }

    if (!IsSteamIdWhitelisted(client))
    {
        ReplyToCommand(client, "[AdminSpells] You are not whitelisted.");
        return Plugin_Handled;
    }

    if (args >= 1)
    {
        char arg1[16];
        GetCmdArg(1, arg1, sizeof(arg1));
        gClientEnabled[client] = (StringToInt(arg1) != 0);
    }
    else
    {
        gClientEnabled[client] = !gClientEnabled[client];
    }

    SetClientCookie(client, gCookieClientEnabled, gClientEnabled[client] ? "1" : "0");
    ReplyToCommand(client, "[AdminSpells] %s", gClientEnabled[client] ? "Enabled for you." : "Disabled for you.");

    if (gClientEnabled[client])
    {
        ScheduleMultiApply(client, "command_toggle");
    }

    return Plugin_Handled;
}

// -------------------- Events --------------------

public void Event_PostInventoryApplication(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client))
    {
        return;
    }

    if (!IsSteamIdWhitelisted(client))
    {
        return;
    }

    if (!gClientEnabled[client])
    {
        if (gCvarDebug.BoolValue)
        {
            PrintToServer("[AdminSpells] post_inventory_application: %N disabled (cookie).", client);
        }
        return;
    }

    ScheduleMultiApply(client, "post_inventory_application");
}

public void Event_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client))
    {
        return;
    }

    if (!IsSteamIdWhitelisted(client) || !gClientEnabled[client])
    {
        return;
    }

    ScheduleMultiApply(client, "player_spawn");
}

void ScheduleMultiApply(int client, const char[] reason)
{
    int userId = GetClientUserId(client);

    if (gCvarDebug.BoolValue)
    {
        PrintToServer("[AdminSpells] Scheduling apply for %N (reason=%s)", client, reason);
    }

    CreateTimer(0.20, Timer_ApplySpells, userId, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(0.60, Timer_ApplySpells, userId, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(1.20, Timer_ApplySpells, userId, TIMER_FLAG_NO_MAPCHANGE);
}

public Action Timer_ApplySpells(Handle timer, any userId)
{
    int client = GetClientOfUserId(userId);
    if (!IsValidClient(client))
    {
        return Plugin_Stop;
    }

    if (!IsSteamIdWhitelisted(client) || !gClientEnabled[client])
    {
        return Plugin_Stop;
    }

    ApplySpellsForClient(client);
    return Plugin_Stop;
}

// -------------------- Core logic --------------------

void ApplySpellsForClient(int client)
{
    TFClassType classType = TF2_GetPlayerClass(client);

    if (classType == TFClass_Soldier)
    {
        // Primary: Exorcism + Pumpkin Bombs (unchanged)
        int primaryEnt = GetPlayerWeaponSlot(client, TFWeaponSlot_Primary);
        ApplyToWeaponIfValid(client, primaryEnt, "soldier_primary", true, true);

        // Secondary: Exorcism only (new)
        int secondaryEnt = GetPlayerWeaponSlot(client, TFWeaponSlot_Secondary);
        ApplyToWeaponIfValid(client, secondaryEnt, "soldier_secondary", true, false);

        // Melee: Exorcism only (new)
        int meleeEnt = GetPlayerWeaponSlot(client, TFWeaponSlot_Melee);
        ApplyToWeaponIfValid(client, meleeEnt, "soldier_melee", true, false);
    }
    else if (classType == TFClass_Engineer)
    {
        // Primary: Exorcism only (new)
        int primaryEnt = GetPlayerWeaponSlot(client, TFWeaponSlot_Primary);
        ApplyToWeaponIfValid(client, primaryEnt, "engineer_primary", true, false);

        // Melee: Exorcism + Pumpkin Bombs (unchanged)
        int meleeEnt = GetPlayerWeaponSlot(client, TFWeaponSlot_Melee);
        ApplyToWeaponIfValid(client, meleeEnt, "engineer_melee", true, true);
    }
    else
    {
        if (gCvarDebug.BoolValue)
        {
            PrintToServer("[AdminSpells] %N: class %d not handled.", client, view_as<int>(classType));
        }
    }
}

void ApplyToWeaponIfValid(int client, int weaponEnt, const char[] label, bool doExorcism, bool doPumpkinBombs)
{
    if (!IsValidEntity(weaponEnt))
    {
        if (gCvarDebug.BoolValue)
        {
            PrintToServer("[AdminSpells] %N: %s weapon entity invalid.", client, label);
        }
        return;
    }

    char className[64];
    GetEntityClassname(weaponEnt, className, sizeof(className));

    int defIndex = -1;
    if (HasEntProp(weaponEnt, Prop_Send, "m_iItemDefinitionIndex"))
    {
        defIndex = GetEntProp(weaponEnt, Prop_Send, "m_iItemDefinitionIndex");
    }

    if (doExorcism)
    {
        TF2Attrib_SetByDefIndex(weaponEnt, ATTR_EXORCISM_DEFIDX, 1.0);
    }

    if (doPumpkinBombs)
    {
        TF2Attrib_SetByDefIndex(weaponEnt, ATTR_PUMPKIN_BOMBS_DEFIDX, 1.0);
    }

    if (gCvarDebug.BoolValue)
    {
        PrintToServer("[AdminSpells] %N: applied (%s%s) to %s ent=%d classname=%s defindex=%d",
            client,
            doExorcism ? "Exorcism" : "",
            doPumpkinBombs ? (doExorcism ? "+PumpkinBombs" : "PumpkinBombs") : "",
            label,
            weaponEnt,
            className,
            defIndex
        );
    }
}

// -------------------- Whitelist (AccountID compare) --------------------

bool IsSteamIdWhitelisted(int client)
{
    char clientSteam2[32];
    if (!GetClientAuthId(client, AuthId_Steam2, clientSteam2, sizeof(clientSteam2), true))
    {
        return false;
    }

    int clientAccountId = Steam2ToAccountId(clientSteam2);
    if (clientAccountId < 0)
    {
        if (gCvarDebug.BoolValue)
        {
            PrintToServer("[AdminSpells] Could not parse client Steam2: %s", clientSteam2);
        }
        return false;
    }

    for (int i = 0; i < sizeof(gWhitelistedSteam2Ids); i++)
    {
        int allowedAccountId = Steam2ToAccountId(gWhitelistedSteam2Ids[i]);
        if (allowedAccountId >= 0 && allowedAccountId == clientAccountId)
        {
            return true;
        }
    }

    if (gCvarDebug.BoolValue)
    {
        PrintToServer("[AdminSpells] Client %N Steam2=%s AccountID=%d not in whitelist.", client, clientSteam2, clientAccountId);
    }

    return false;
}

int Steam2ToAccountId(const char[] steam2)
{
    if (strncmp(steam2, "STEAM_", 6, false) != 0)
    {
        return -1;
    }

    char parts[3][32];
    int count = ExplodeString(steam2, ":", parts, sizeof(parts), sizeof(parts[]));
    if (count != 3)
    {
        return -1;
    }

    int y = StringToInt(parts[1]);
    int z = StringToInt(parts[2]);

    if (y != 0 && y != 1)
    {
        return -1;
    }
    if (z < 0)
    {
        return -1;
    }

    return (z * 2) + y;
}

// -------------------- Helpers --------------------

bool IsValidClient(int client)
{
    return (client > 0 && client <= MaxClients && IsClientInGame(client) && !IsFakeClient(client));
}
