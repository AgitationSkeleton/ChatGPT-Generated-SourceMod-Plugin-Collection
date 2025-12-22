/**
 * TF2 December Trees (ctf_2fort_quake_mvm)
 * Spawns 2 Christmas tree prop_dynamics only during December (unless forced).
 *
 * Revision:
 * - Fixed coordinates (no floor detection).
 * - On plugin load/reload: delete any existing prop_dynamic with matching model, then respawn.
 * - Spawn on map start and after round transitions for persistence.
 * - Trees are solid.
 *
 * Author: ChatGPT
 */

#include <sourcemod>
#include <sdktools>

#define PLUGIN_VERSION "1.2.0"

static const char TREE_MODEL[] = "models/unconid/xmas/xmas_tree.mdl";

// --- Model files
static const char MODEL_FILES[][] =
{
    "models/unconid/xmas/xmas_tree.dx90.vtx",
    "models/unconid/xmas/xmas_tree.mdl",
    "models/unconid/xmas/xmas_tree.phy",
    "models/unconid/xmas/xmas_tree.vvd"
};

// --- Material files (relative to game root)
static const char MATERIAL_FILES[][] =
{
    "materials/models/unconid/xmas/bb_warp.vtf",
    "materials/models/unconid/xmas/gift_normal.vtf",
    "materials/models/unconid/xmas/lights.vmt",
    "materials/models/unconid/xmas/lights.vtf",
    "materials/models/unconid/xmas/lights_2.vmt",
    "materials/models/unconid/xmas/lights_2.vtf",
    "materials/models/unconid/xmas/lights_normal.vtf",
    "materials/models/unconid/xmas/lights_paintable.vmt",
    "materials/models/unconid/xmas/lights_paintable.vtf",
    "materials/models/unconid/xmas/lights_s.vmt",
    "materials/models/unconid/xmas/lights_s.vtf",
    "materials/models/unconid/xmas/ornament_1.vmt",
    "materials/models/unconid/xmas/ornament_1.vtf",
    "materials/models/unconid/xmas/ornament_2.vmt",
    "materials/models/unconid/xmas/ornament_2.vtf",
    "materials/models/unconid/xmas/ornament_3.vmt",
    "materials/models/unconid/xmas/ornament_3.vtf",
    "materials/models/unconid/xmas/ornament_normal_1.vtf",
    "materials/models/unconid/xmas/ornament_normal_2.vtf",
    "materials/models/unconid/xmas/ornament_normal_3.vtf",
    "materials/models/unconid/xmas/pyroland_envmap.vtf",
    "materials/models/unconid/xmas/top_star.vmt",
    "materials/models/unconid/xmas/top_star.vtf",
    "materials/models/unconid/xmas/tree_xmas.vmt",
    "materials/models/unconid/xmas/tree_xmas.vtf",
    "materials/models/unconid/xmas/warpu.vtf"
};

ConVar gCvarEnable = null;
ConVar gCvarForce = null;

// Only two trees on this map.
int gTreeEntRefs[2] = { INVALID_ENT_REFERENCE, INVALID_ENT_REFERENCE };
bool gDownloadsAdded = false;

public Plugin myinfo =
{
    name        = "TF2 December Trees (ctf_2fort_quake_mvm)",
    author      = "ChatGPT",
    description = "Spawns 2 Christmas tree props on ctf_2fort_quake_mvm during December (or forced) and adds required downloads.",
    version     = PLUGIN_VERSION,
    url         = ""
};

public void OnPluginStart()
{
    gCvarEnable = CreateConVar("sm_xmastrees_enable", "1", "Enable/disable December trees plugin.", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gCvarForce  = CreateConVar("sm_xmastrees_force",  "0", "Force-enable trees & downloads regardless of month.", FCVAR_NOTIFY, true, 0.0, true, 1.0);

    HookEvent("teamplay_waiting_ends", Event_WaitingEnds, EventHookMode_PostNoCopy);
    HookEvent("teamplay_round_start", Event_RoundStart, EventHookMode_PostNoCopy);

    AutoExecConfig(true, "gpt_xmas_trees");

    // If the plugin is (re)loaded mid-map, clean up any existing matching trees and respawn.
    if (ShouldRunNow())
    {
        // Precache for late-load reliability (downloads table still only happens on OnMapStart).
        PrecacheModel(TREE_MODEL, true);

        CreateTimer(0.5, Timer_CleanupAndSpawn, _, TIMER_FLAG_NO_MAPCHANGE);
    }
}

public void OnMapStart()
{
    for (int i = 0; i < 2; i++)
    {
        gTreeEntRefs[i] = INVALID_ENT_REFERENCE;
    }
    gDownloadsAdded = false;

    if (!ShouldRunNow())
    {
        return;
    }

    AddDownloadsAndPrecache();

    // Spawn after a short delay so the map is fully initialized.
    CreateTimer(2.0, Timer_CleanupAndSpawn, _, TIMER_FLAG_NO_MAPCHANGE);
}

public void OnMapEnd()
{
    RemoveTreesByRefs();
}

public Action Event_WaitingEnds(Event event, const char[] name, bool dontBroadcast)
{
    if (!ShouldRunNow())
    {
        return Plugin_Continue;
    }

    // Safety net: some servers transition/cleanup after waiting ends.
    CreateTimer(3.0, Timer_CleanupAndSpawn, _, TIMER_FLAG_NO_MAPCHANGE);
    return Plugin_Continue;
}

public Action Event_RoundStart(Event event, const char[] name, bool dontBroadcast)
{
    if (!ShouldRunNow())
    {
        return Plugin_Continue;
    }

    // Spawn after cleanup.
    CreateTimer(0.5, Timer_CleanupAndSpawn, _, TIMER_FLAG_NO_MAPCHANGE);
    return Plugin_Continue;
}

public Action Timer_CleanupAndSpawn(Handle timer, any data)
{
    if (!ShouldRunNow())
    {
        return Plugin_Stop;
    }

    // Ensure downloads + precache if coming from OnMapStart path.
    if (!gDownloadsAdded)
    {
        // OnMapStart is the correct place to add downloads table entries.
        // But if we're late-loaded, we still at least precache.
        PrecacheModel(TREE_MODEL, true);
    }

    // Delete any existing xmas_tree.mdl prop_dynamic (even if not created by this plugin).
    RemoveExistingTreesInWorld();

    // Now place ours.
    SpawnTrees_FixedCoords();

    return Plugin_Stop;
}

bool ShouldRunNow()
{
    if (gCvarEnable != null && !gCvarEnable.BoolValue)
    {
        return false;
    }

    char mapName[64];
    GetCurrentMap(mapName, sizeof(mapName));
    if (!StrEqual(mapName, "ctf_2fort_quake_mvm", false))
    {
        return false;
    }

    if (gCvarForce != null && gCvarForce.BoolValue)
    {
        return true;
    }

    return IsDecember();
}

bool IsDecember()
{
    int now = GetTime();
    char monthStr[3];
    FormatTime(monthStr, sizeof(monthStr), "%m", now); // 01-12
    return (StringToInt(monthStr) == 12);
}

void AddDownloadsAndPrecache()
{
    for (int i = 0; i < sizeof(MODEL_FILES); i++)
    {
        AddFileToDownloadsTable(MODEL_FILES[i]);
    }

    for (int i = 0; i < sizeof(MATERIAL_FILES); i++)
    {
        AddFileToDownloadsTable(MATERIAL_FILES[i]);
    }

    PrecacheModel(TREE_MODEL, true);
    gDownloadsAdded = true;
}

void RemoveTreesByRefs()
{
    for (int i = 0; i < 2; i++)
    {
        int entIndex = EntRefToEntIndex(gTreeEntRefs[i]);
        if (entIndex != -1 && entIndex != INVALID_ENT_REFERENCE && IsValidEntity(entIndex))
        {
            AcceptEntityInput(entIndex, "Kill");
        }
        gTreeEntRefs[i] = INVALID_ENT_REFERENCE;
    }
}

bool EntityModelMatchesTree(int entIndex)
{
    char modelName[PLATFORM_MAX_PATH];
    modelName[0] = '\0';

    // For prop_dynamic and most prop entities, m_ModelName is available.
    GetEntPropString(entIndex, Prop_Data, "m_ModelName", modelName, sizeof(modelName));

    if (modelName[0] == '\0')
    {
        return false;
    }

    return StrEqual(modelName, TREE_MODEL, false);
}

void RemoveExistingTreesInWorld()
{
    // Also clear our stored refs so we don't point at dead entities.
    for (int i = 0; i < 2; i++)
    {
        gTreeEntRefs[i] = INVALID_ENT_REFERENCE;
    }

    int maxEnts = GetMaxEntities();

    for (int entIndex = MaxClients + 1; entIndex < maxEnts; entIndex++)
    {
        if (!IsValidEntity(entIndex))
        {
            continue;
        }

        char className[64];
        GetEntityClassname(entIndex, className, sizeof(className));

        // Only look at prop_dynamic style entities to avoid false positives.
        if (!StrEqual(className, "prop_dynamic", false) && !StrEqual(className, "prop_dynamic_override", false))
        {
            continue;
        }

        if (!EntityModelMatchesTree(entIndex))
        {
            continue;
        }

        AcceptEntityInput(entIndex, "Kill");
    }
}

void SpawnTrees_FixedCoords()
{
    // Your provided coordinates, with Z lowered by 26.0:
    // 1156.031372 - 26.0 = 1130.031372
    float origins[2][3] =
    {
        { -4528.616699, 1552.604736, 1088.0 },
        {  1775.030762, -403.392731, 1088.0 }
    };

    float angles[2][3] =
    {
        { 0.0,  47.835888, 0.0 },
        { 0.0, 96.360107, 0.0 }
    };

    for (int i = 0; i < 2; i++)
    {
        int ent = CreateEntityByName("prop_dynamic");
        if (ent == -1 || !IsValidEntity(ent))
        {
            continue;
        }

        DispatchKeyValue(ent, "model", TREE_MODEL);

        // Solid VPhysics
        DispatchKeyValue(ent, "solid", "6");

        // Don't fade out at distance
        DispatchKeyValue(ent, "fademindist", "-1");
        DispatchKeyValue(ent, "fademaxdist", "0");

        DispatchSpawn(ent);

        TeleportEntity(ent, origins[i], angles[i], NULL_VECTOR);

        gTreeEntRefs[i] = EntIndexToEntRef(ent);
    }
}
