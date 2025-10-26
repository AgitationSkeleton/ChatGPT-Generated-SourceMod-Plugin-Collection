/**
 * Admin Golden Wrench (SDKCall, no gamedata file)
 * - Gives Golden Wrench (defindex 169) to Steam2 ID STEAM_0:1:32388925 when Engineer
 * - Works on spawn and resupply (post_inventory_application)
 * - Per-client toggle via ClientPrefs (default ON)
 * - Uses SDKCall virtual: WeaponEquip with hard-coded vtable index (Win=272, Lin=273)
 *
 * Requires: SM 1.10+ (or newer), sdktools, sdkhooks, clientprefs
 */

#include <sourcemod>
#include <sdktools>
#include <sdkhooks>
#include <tf2>
#include <tf2_stocks>
#include <clientprefs>

#pragma semicolon 1
#pragma newdecls required

#define PLUGIN_NAME        "Admin Golden Wrench (SDKCall, no gamedata)"
#define PLUGIN_VERSION     "1.0.2"

// Target Steam2 ID
#define TARGET_STEAM2 "STEAM_0:1:32388925"

// Golden Wrench
#define DEFIDX_GOLDEN_WRENCH 169
#define SLOT_MELEE 2

// ClientPrefs cookie
#define COOKIE_NAME "sm_admingoldenwrench"

ConVar gCvar_Enable;
Handle gCookieToggle = INVALID_HANDLE;

// void CBasePlayer::Weapon_Equip(CBaseCombatWeapon*)
Handle g_hCall_WeaponEquip = INVALID_HANDLE;

// Hard-coded virtual offsets (from your working gamedata)
// Windows (32/64): 272; Linux (32/64): 273; mac typically matches Linux
#if defined _WIN32
    #define VINDEX_WEAPONEQUIP 272
#else
    #define VINDEX_WEAPONEQUIP 273
#endif

public Plugin myinfo =
{
    name = PLUGIN_NAME,
    author = "ChatGPT",
    description = "Give Golden Wrench to a specific admin while Engineer (spawn + resupply), no TF2Items, no gamedata.",
    version = PLUGIN_VERSION,
    url = ""
};

public APLRes AskPluginLoad2(Handle myself, bool late, char[] error, int err_max)
{
    if (GetEngineVersion() != Engine_TF2)
    {
        strcopy(error, err_max, "This plugin only supports TF2.");
        return APLRes_Failure;
    }
    return APLRes_Success;
}

public void OnPluginStart()
{
    gCvar_Enable = CreateConVar("sm_admingoldenwrench_enable", "1",
        "Enable Admin Golden Wrench plugin (1=yes,0=no)", FCVAR_NOTIFY, true, 0.0, true, 1.0);

    gCookieToggle = RegClientCookie(COOKIE_NAME,
        "Give me the Golden Wrench automatically (1=on,0=off).", CookieAccess_Public);
    if (gCookieToggle == INVALID_HANDLE)
    {
        SetFailState("Failed to register ClientPrefs cookie.");
    }

    RegConsoleCmd("sm_admingoldenwrench", Cmd_ToggleSelf,
        "Enable/disable your Golden Wrench auto-give (default ON). Usage: sm_admingoldenwrench [0|1]");

    HookEvent("player_spawn", Event_PlayerSpawn, EventHookMode_PostNoCopy);
    HookEvent("post_inventory_application", Event_PostInventory, EventHookMode_PostNoCopy);

    // Prepare SDKCall using hard-coded virtual index (no gamedata needed)
    StartPrepSDKCall(SDKCall_Player);
    PrepSDKCall_SetVirtual(VINDEX_WEAPONEQUIP);
    PrepSDKCall_AddParameter(SDKType_CBaseEntity, SDKPass_Pointer); // CBaseCombatWeapon*
    g_hCall_WeaponEquip = EndPrepSDKCall();
    if (g_hCall_WeaponEquip == INVALID_HANDLE)
    {
        SetFailState("Failed to create SDKCall for Weapon_Equip.");
    }
}

public void OnClientPutInServer(int client)
{
    if (!IsFakeClient(client))
    {
        char current[4];
        GetClientCookie(client, gCookieToggle, current, sizeof current);
        if (current[0] == '\0')
        {
            SetClientCookie(client, gCookieToggle, "1"); // default ON
        }
    }
}

public Action Cmd_ToggleSelf(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
        return Plugin_Handled;

    int val;
    if (args >= 1)
    {
        char sArg[8];
        GetCmdArg(1, sArg, sizeof sArg);
        if (!(StrEqual(sArg, "0") || StrEqual(sArg, "1")))
        {
            ReplyToCommand(client, "[GoldenWrench] Usage: sm_admingoldenwrench [0|1]");
            return Plugin_Handled;
        }
        val = StringToInt(sArg);
    }
    else
    {
        val = GetToggle(client) ? 0 : 1; // toggle
    }

    SetToggle(client, val != 0);
    ReplyToCommand(client, "[GoldenWrench] Auto-give is now %s.", val ? "ON" : "OFF");
    return Plugin_Handled;
}

public void Event_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    if (!gCvar_Enable.BoolValue) return;

    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsRealClient(client)) return;

    // Defer to next frame so inventory has settled.
    RequestFrame(EnsureGoldenWrench_RF, GetClientUserId(client));
}

public void Event_PostInventory(Event event, const char[] name, bool dontBroadcast)
{
    if (!gCvar_Enable.BoolValue) return;

    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsRealClient(client)) return;

    // Resupply/changeclass pass – defer one frame before checking.
    RequestFrame(EnsureGoldenWrench_RF, GetClientUserId(client));
}

void EnsureGoldenWrench_RF(any userid_any)
{
    int client = GetClientOfUserId(view_as<int>(userid_any));
    if (!IsRealClient(client)) return;

    if (!IsTargetSteamID(client)) return;
    if (TF2_GetPlayerClass(client) != TFClass_Engineer) return;
    if (!GetToggle(client)) return;

    // Already Golden Wrench in melee? Leave it (preserves MvM upgrades).
    int melee = GetPlayerWeaponSlot(client, SLOT_MELEE);
    if (melee > MaxClients && IsValidEntity(melee))
    {
        int def = GetEntProp(melee, Prop_Send, "m_iItemDefinitionIndex");
        if (def == DEFIDX_GOLDEN_WRENCH)
            return;
    }

    GiveGoldenWrench(client);
}

static bool IsRealClient(int client)
{
    return (client > 0 && IsClientInGame(client) && !IsFakeClient(client));
}

static bool GetToggle(int client)
{
    if (!IsClientInGame(client) || IsFakeClient(client))
        return false;

    char val[4];
    GetClientCookie(client, gCookieToggle, val, sizeof val);
    if (val[0] == '\0') return true; // default ON
    return (StringToInt(val) != 0);
}

static void SetToggle(int client, bool on)
{
    if (!IsClientInGame(client) || IsFakeClient(client))
        return;

    SetClientCookie(client, gCookieToggle, on ? "1" : "0");
}

static bool IsTargetSteamID(int client)
{
    char auth[64];
    if (!GetClientAuthId(client, AuthId_Steam2, auth, sizeof auth, true))
        return false;
    return StrEqual(auth, TARGET_STEAM2, false);
}

static void GiveGoldenWrench(int client)
{
    // Clear current melee
    int melee = GetPlayerWeaponSlot(client, SLOT_MELEE);
    if (melee > MaxClients && IsValidEntity(melee))
    {
        RemovePlayerItem(client, melee);
        AcceptEntityInput(melee, "Kill");
    }

    // Create base wrench entity
    int weapon = CreateEntityByName("tf_weapon_wrench");
    if (weapon <= MaxClients || !IsValidEntity(weapon))
    {
        PrintToServer("[GoldenWrench] CreateEntityByName failed for tf_weapon_wrench.");
        return;
    }

    // Minimal setup to mark it as a Golden Wrench
    char entclass[64];
    GetEntityNetClass(weapon, entclass, sizeof(entclass));

    int off_def = FindSendPropInfo(entclass, "m_iItemDefinitionIndex");
    if (off_def != -1) SetEntData(weapon, off_def, DEFIDX_GOLDEN_WRENCH);

    int off_init = FindSendPropInfo(entclass, "m_bInitialized");
    if (off_init != -1) SetEntData(weapon, off_init, 1);

    SetEntProp(weapon, Prop_Send, "m_iEntityLevel", 25);
    SetEntProp(weapon, Prop_Send, "m_iEntityQuality", 6); // Unique
    SetEntProp(weapon, Prop_Send, "m_iAccountID", -1);

    SetEntPropEnt(weapon, Prop_Send, "m_hOwnerEntity", client);

    DispatchSpawn(weapon);

    // Equip via Weapon_Equip (SDKCall)
    SDKCall(g_hCall_WeaponEquip, client, weapon);

    // Ensure it ended up in melee slot
    int equipped = GetPlayerWeaponSlot(client, SLOT_MELEE);
    if (equipped != weapon)
    {
        EquipPlayerWeapon(client, weapon);
    }
}
