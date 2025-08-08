#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <pf2> // provides TF2_AddCondition (optional), TFCond, etc.

// Plugin info
#define PLUGIN_VERSION "1.4"

bool g_bNotified[MAXPLAYERS + 1];

ConVar g_cvVersion;
ConVar g_cvDuration;     // float seconds
ConVar g_cvNotify;       // bool
ConVar g_cvEnable;       // 0/1 enabled
ConVar g_cvCondId;       // numeric TFCond id (default 5 = Ubercharged)

bool g_bHasNative_AddCond = false;

public Plugin myinfo =
{
    name        = "Uberspawn (PF2)",
    author      = "Aderic (PF2 port by ChatGPT)",
    description = "Gives spawned players invulnerability (uber) for a short time.",
    version     = PLUGIN_VERSION
};

public void OnPluginStart()
{
    // Ensure we're on PF2
    char folder[32];
    GetGameFolderName(folder, sizeof(folder));
    if (!StrEqual(folder, "pf2", false))
    {
        SetFailState("This build is for Pre-Fortress 2 (game folder 'pf2').");
    }

    g_cvEnable  = CreateConVar("sm_uberspawn_enable",   "1",  "Enable/disable uberspawn.", FCVAR_NONE, true, 0.0, true, 1.0);
    g_cvDuration= CreateConVar("sm_uberspawn_duration", "5.0","Uber duration in seconds.", FCVAR_NONE, true, 0.0);
    g_cvNotify  = CreateConVar("sm_uberspawn_notify",   "0",  "Notify players they get spawn protection.", FCVAR_NONE, true, 0.0, true, 1.0);
    g_cvCondId  = CreateConVar("sm_uberspawn_cond",     "5",  "Condition numeric ID (default 5 = Ubercharged).", FCVAR_NONE, true, 0.0);

    HookEvent("player_spawn",      OnPlayerSpawn);
    HookEvent("player_disconnect", OnPlayerDisconnect, EventHookMode_Pre);

    AutoExecConfig(true, "uberspawn_pf2");

    // Detect whether TF2_AddCondition native is actually available at runtime.
    // If not, we'll fallback to bitmasking m_nPlayerCond.
    #if defined FEATURECORE
        g_bHasNative_AddCond = (GetFeatureStatus(FeatureType_Native, "TF2_AddCondition") == FeatureStatus_Available);
    #else
        // Older SM without FEATURECORE: best effort (assume available).
        g_bHasNative_AddCond = true;
    #endif
}

public void OnConfigsExecuted()
{
    g_cvVersion = CreateConVar("sm_uberspawn", PLUGIN_VERSION,
                    "Current version of the plugin. Read Only",
                    FCVAR_NOTIFY | FCVAR_REPLICATED);
    HookConVarChange(g_cvVersion, OnPluginVersionChanged);
}

public void OnPluginVersionChanged(ConVar cvar, const char[] oldVal, const char[] newVal)
{
    if (!StrEqual(newVal, PLUGIN_VERSION, false))
    {
        cvar.SetString(PLUGIN_VERSION);
    }
}

public Action OnPlayerDisconnect(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client > 0 && client <= MaxClients)
        g_bNotified[client] = false;
    return Plugin_Continue;
}

public Action OnPlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client <= 0 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Continue;

    if (!g_bNotified[client] && g_cvNotify.BoolValue)
    {
        PrintToChat(client, "[Uberspawn] You get %.2f seconds of invulnerability on spawn.", g_cvDuration.FloatValue);
        g_bNotified[client] = true;
    }

    if (!g_cvEnable.BoolValue || g_cvDuration.FloatValue <= 0.0)
        return Plugin_Continue;

    // Apply a tiny delay to ensure entity is fully spawned
    CreateTimer(0.10, Timer_ApplyUber, GetClientUserId(client));

    return Plugin_Continue;
}

public Action Timer_ApplyUber(Handle timer, any userid)
{
    int client = GetClientOfUserId(userid);
    if (client <= 0 || client > MaxClients || !IsClientInGame(client) || !IsPlayerAlive(client))
        return Plugin_Stop;

    float dur = g_cvDuration.FloatValue;
    int condId = g_cvCondId.IntValue; // default 5

    if (g_bHasNative_AddCond)
    {
        // Use native if present
        TF2_AddCondition(client, view_as<TFCond>(condId), dur);
        return Plugin_Stop;
    }

    // Fallback: directly set the bit in m_nPlayerCond, then clear it later.
    int bit = (1 << condId); // condId is small in PF2 (<= 27)
    int flags = GetEntProp(client, Prop_Send, "m_nPlayerCond");
    SetEntProp(client, Prop_Send, "m_nPlayerCond", flags | bit);

    // Schedule removal
    DataPack pack = new DataPack();
    pack.WriteCell(GetClientUserId(client));
    pack.WriteCell(bit);
    CreateTimer(dur, Timer_ClearCondBit, pack, TIMER_FLAG_NO_MAPCHANGE);

    return Plugin_Stop;
}

public Action Timer_ClearCondBit(Handle timer, any dp_any)
{
    DataPack pack = view_as<DataPack>(dp_any);
    pack.Reset();
    int userid = pack.ReadCell();
    int bit    = pack.ReadCell();
    delete pack;

    int client = GetClientOfUserId(userid);
    if (client <= 0 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Stop;

    int flags = GetEntProp(client, Prop_Send, "m_nPlayerCond");
    flags &= ~bit;
    SetEntProp(client, Prop_Send, "m_nPlayerCond", flags);
    return Plugin_Stop;
}
