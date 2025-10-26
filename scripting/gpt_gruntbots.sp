// gpt_gruntbot_persist.sp — persistent grunt applier for TF2C bots
// Requires: sdktools, sdkhooks, tf2c.inc
//
// Modes:
//   sm_gruntbot_mode 0: tf_bot_force_class "", never grunt
//   sm_gruntbot_mode 1: tf_bot_force_class "soldier", grunt ALL bots on spawn (persistent)
//   sm_gruntbot_mode 2: tf_bot_force_class "", grunt Soldier bots RANDOMLY on spawn (prob sm_gruntbot_chance), persistent
//   sm_gruntbot_mode 3: tf_bot_force_class "", grunt Soldier bots ALWAYS on spawn, persistent
//
//   sm_gruntbot_chance: probability [0..1] for mode 2, default 0.5
//
// Notes:
//   - We re-apply with staggered timers to survive inventory/class rewrites.
//   - We issue "sm_grunt 1" as the bot (FakeClientCommand).
//   - If your sm_grunt expects a target, switch to ServerCommand with a userid.

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>
#include <sdkhooks>
#include "tf2c.inc"

ConVar gCvarMode;
ConVar gCvarChance;
ConVar gCvarTFBotForceClass;

public void OnPluginStart()
{
    gCvarMode  = CreateConVar("sm_gruntbot_mode", "0",
        "0=off; 1=force all bots to Soldier & grunt them; 2=randomly grunt Soldier bots; 3=grunt Soldier bots always.",
        FCVAR_NOTIFY, true, 0.0, true, 3.0);

    gCvarChance = CreateConVar("sm_gruntbot_chance", "0.5",
        "When mode=2, probability [0..1] to grunt a Soldier bot on spawn.",
        FCVAR_NONE, true, 0.0, true, 1.0);

    gCvarTFBotForceClass = FindConVar("tf_bot_force_class");

    // Apply initial tf_bot_force_class
    ApplyForceClassFromMode(gCvarMode.IntValue);

    // Watch mode changes
    HookConVarChange(gCvarMode, OnModeChanged);

    // Events that imply a (re)spawn or re-inventory
    HookEvent("player_spawn", E_PlayerSpawn, EventHookMode_Post);
    HookEvent("player_team",  E_PlayerTeam,  EventHookMode_Post);

    // Also cover bots that join mid-map
    for (int i = 1; i <= MaxClients; i++)
    {
        if (IsClientInGame(i) && IsFakeClient(i))
            SDKHook(i, SDKHook_SpawnPost, SpawnPost);
    }
}

public void OnClientPutInServer(int client)
{
    if (!IsClientInGame(client) || !IsFakeClient(client))
        return;

    SDKHook(client, SDKHook_SpawnPost, SpawnPost);

    // If we’re in mode 1 and the server didn’t yet set the convar (late load),
    // enforce again here:
    ApplyForceClassFromMode(gCvarMode.IntValue);

    // Schedule a small check soon after connect, in case the bot gets spawned immediately
    ScheduleGruntIfShould(client);
}

public void OnClientDisconnect(int client)
{
    if (!IsFakeClient(client)) return;
    SDKUnhook(client, SDKHook_SpawnPost, SpawnPost);
}

public void OnModeChanged(ConVar cvar, const char[] oldValue, const char[] newValue)
{
    ApplyForceClassFromMode(StringToInt(newValue));
}

public void OnConfigsExecuted()
{
    // Keep tf_bot_force_class consistent with the configured mode after cfg reloads
    ApplyForceClassFromMode(gCvarMode.IntValue);
}

static void ApplyForceClassFromMode(int mode)
{
    if (gCvarTFBotForceClass == null)
        return;

    if (mode == 1)
        SetConVarString(gCvarTFBotForceClass, "soldier", true, true);
    else
        SetConVarString(gCvarTFBotForceClass, "", true, true);
}

public void E_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!(1 <= client && client <= MaxClients) || !IsClientInGame(client) || !IsFakeClient(client))
        return;

    ScheduleGruntIfShould(client);
}

public void E_PlayerTeam(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!(1 <= client && client <= MaxClients) || !IsClientInGame(client) || !IsFakeClient(client))
        return;

    // Team changes often precede class loadouts; schedule re-application
    ScheduleGruntIfShould(client);
}

public void SpawnPost(int client)
{
    if (!IsClientInGame(client) || !IsFakeClient(client))
        return;
    ScheduleGruntIfShould(client);
}

static void ScheduleGruntIfShould(int client)
{
    if (!ShouldGrunt(client))
        return;

    // Staggered re-application to survive post-spawn rewrites.
    int userid = GetClientUserId(client);
    CreateTimer(0.00, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(0.05, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(0.25, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(0.50, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(0.75, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
    CreateTimer(1.00, Timer_GruntApply, userid, TIMER_FLAG_NO_MAPCHANGE);
}

public Action Timer_GruntApply(Handle t, any userid)
{
    int client = GetClientOfUserId(userid);
    if (!(1 <= client && client <= MaxClients) || !IsClientInGame(client) || !IsFakeClient(client))
        return Plugin_Stop;

    // Re-check right before applying (class may have changed)
    if (!ShouldGrunt(client))
        return Plugin_Stop;

    // Apply grunt state as the bot
    FakeClientCommand(client, "sm_grunt 1");
    return Plugin_Continue;
}

static bool ShouldGrunt(int client)
{
    int mode = gCvarMode.IntValue;
    if (mode == 0) return false;

    if (mode == 1)
    {
        // Force-class soldier is already set; grunt all bots
        return true;
    }

    // Modes 2 & 3: only apply to Soldier bots
    TFClassType cls = TF2_GetPlayerClass(client);
    if (cls != TFClass_Soldier)
        return false;

    if (mode == 3)
        return true;

    // mode == 2 → random chance
    float p = gCvarChance.FloatValue;
    if (p <= 0.0) return false;
    if (p >= 1.0) return true;
    return (GetURandomFloat() <= p);
}
