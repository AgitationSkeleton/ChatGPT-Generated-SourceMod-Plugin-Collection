/**
 * Admin Cosmetic FX (strict hats only)
 * - Targets Steam2 ID: STEAM_0:1:32388925
 *
 * Applies ONLY if the corresponding hat is equipped:
 *  - Soldier: Team Captain (def 378) -> HUD: Dead Presidents (attr 134 = 60), Voices From Below (1006), Horsemann's Footsteps (1005, style cvar)
 *  - Engineer: Industrial Festivizer (def 338) -> HUD: Phosphorous (attr 134 = 63), Voices From Below (1006)
 *  - Medic/Heavy: Team Captain (def 378) -> same as Soldier case above
 *
 * Notes:
 *  - No TF2Items, no world particles, no tempents, no SDKCalls/gamedata.
 *  - Sets attributes via TF2Attributes (HUD-visible; world Unusuals are economy-gated).
 */

#include <sourcemod>
#include <sdktools>
#include <tf2>
#include <tf2attributes>


#pragma semicolon 1
#pragma newdecls required

// ---- Config ----
#define TARGET_STEAM2 "STEAM_0:1:32388925"

// Exact cosmetics
#define DEFIDX_TEAM_CAPTAIN       378
#define DEFIDX_INDUSTRIAL_FESTIV  338

// Attribute defindexes
#define ATTRIDX_PARTICLE_EFFECT   134    // Unusual particle (HUD only on players)
#define ATTRIDX_VOICES_BELOW      1006   // Voices From Below
#define ATTRIDX_FOOTSTEP_TYPE     1005   // Horsemann's Footsteps (style)

// Effect IDs (HUD)
#define EFFECT_DEAD_PRESIDENTS    60
#define EFFECT_PHOSPHOROUS        63

// Timings
#define DELAY_AFTER_SPAWN         0.05
#define DELAY_AFTER_POSTINV       0.10

ConVar gCvar_Enable;
ConVar gCvar_FootstepStyle;   // 1..4 per schema (pick the value that maps to Horseshoes for you)
ConVar gCvar_ForceHoliday;    // optional: 2 forces Halloween for consistent spell SFX

public Plugin myinfo =
{
    name        = "Admin Cosmetic FX (strict hats only)",
    author      = "ChatGPT",
    description = "HUD Unusual + spell attributes only when specific hats are actually equipped.",
    version     = "1.1.0",
    url         = ""
};

public APLRes AskPluginLoad2(Handle myself, bool late, char[] err, int err_max)
{
    if (GetEngineVersion() != Engine_TF2)
    {
        strcopy(err, err_max, "This plugin only supports TF2.");
        return APLRes_Failure;
    }
    return APLRes_Success;
}

public void OnPluginStart()
{
    gCvar_Enable = CreateConVar("sm_adminfx_enable", "1",
        "Enable Admin Cosmetic FX (strict) (1=yes,0=no)", FCVAR_NOTIFY, true, 0.0, true, 1.0);

    gCvar_FootstepStyle = CreateConVar("sm_adminfx_footsteps_style", "2",
        "Horsemann's Footsteps style (Soldier/TC path). Try 1..4 to match your schema.",
        FCVAR_NOTIFY, true, 0.0, true, 10.0);

    gCvar_ForceHoliday = CreateConVar("sm_adminfx_force_holiday", "0",
        "If set to 2, forces Halloween (tf_forced_holiday 2) for consistent spell SFX.", FCVAR_NOTIFY, true, 0.0, true, 10.0);

    HookEvent("player_spawn", Event_PlayerSpawn, EventHookMode_PostNoCopy);
    HookEvent("post_inventory_application", Event_PostInventory, EventHookMode_PostNoCopy);
}

public void Event_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    if (!gCvar_Enable.BoolValue) return;

    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsTarget(client)) return;

    if (gCvar_ForceHoliday.IntValue == 2)
    {
        ConVar holiday = FindConVar("tf_forced_holiday");
        if (holiday != null) SetConVarInt(holiday, 2);
    }

    CreateTimer(DELAY_AFTER_SPAWN, Timer_Apply, GetClientUserId(client), TIMER_FLAG_NO_MAPCHANGE);
}

public void Event_PostInventory(Event event, const char[] name, bool dontBroadcast)
{
    if (!gCvar_Enable.BoolValue) return;

    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsTarget(client)) return;

    CreateTimer(DELAY_AFTER_POSTINV, Timer_Apply, GetClientUserId(client), TIMER_FLAG_NO_MAPCHANGE);
}

public Action Timer_Apply(Handle timer, any userid_any)
{
    int client = GetClientOfUserId(view_as<int>(userid_any));
    if (!IsTarget(client)) return Plugin_Stop;

    TFClassType cls = view_as<TFClassType>(GetEntProp(client, Prop_Send, "m_iClass"));

    switch (cls)
    {
        case TFClass_Soldier:
        {
            int hatTC = FindExactHat(client, DEFIDX_TEAM_CAPTAIN);
            if (hatTC != -1)
            {
                int style = gCvar_FootstepStyle.IntValue;
                ApplyHatHUDAttrs(hatTC, EFFECT_DEAD_PRESIDENTS, true, true, style);
            }
        }
        case TFClass_Engineer:
        {
            int hatFest = FindExactHat(client, DEFIDX_INDUSTRIAL_FESTIV);
            if (hatFest != -1)
            {
                ApplyHatHUDAttrs(hatFest, EFFECT_PHOSPHOROUS, true, false);
            }
        }
        case TFClass_Medic, TFClass_Heavy:
        {
            int hatTC = FindExactHat(client, DEFIDX_TEAM_CAPTAIN);
            if (hatTC != -1)
            {
                int style = gCvar_FootstepStyle.IntValue;
                ApplyHatHUDAttrs(hatTC, EFFECT_DEAD_PRESIDENTS, true, true, style);
            }
        }
        default: {}
    }

    return Plugin_Stop;
}

// ---------------- helpers ----------------

static bool IsTarget(int client)
{
    if (!(client > 0 && IsClientInGame(client) && !IsFakeClient(client)))
        return false;

    char auth[64];
    if (!GetClientAuthId(client, AuthId_Steam2, auth, sizeof auth, true))
        return false;

    return StrEqual(auth, TARGET_STEAM2, false);
}

/** Return the tf_wearable entity if the exact defindex is equipped, else -1. */
static int FindExactHat(int client, int defindex)
{
    int ent = -1;
    while ((ent = FindEntityByClassname(ent, "tf_wearable")) != -1)
    {
        if (!IsValidEntity(ent)) continue;
        if (GetEntPropEnt(ent, Prop_Send, "m_hOwnerEntity") != client) continue;

        if (GetEntProp(ent, Prop_Send, "m_iItemDefinitionIndex") == defindex)
            return ent;
    }
    return -1;
}

/** Apply HUD-only Unusual + spells to a wearable via attributes. */
static void ApplyHatHUDAttrs(int wearable, int particleEffectId, bool voicesBelow, bool horsemannSteps, int footstepStyle = 1)
{
    if (!(wearable > MaxClients && IsValidEntity(wearable))) return;

    TF2Attrib_SetByDefIndex(wearable, ATTRIDX_PARTICLE_EFFECT, float(particleEffectId));
    if (voicesBelow)
        TF2Attrib_SetByDefIndex(wearable, ATTRIDX_VOICES_BELOW, 1.0);
    if (horsemannSteps && footstepStyle > 0)
        TF2Attrib_SetByDefIndex(wearable, ATTRIDX_FOOTSTEP_TYPE, float(footstepStyle));
}
