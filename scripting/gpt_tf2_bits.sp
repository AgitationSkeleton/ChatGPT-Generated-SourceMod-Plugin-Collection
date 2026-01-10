/**
 * tf2_bits.sp
 * Author: ChatGPT
 *
 * Spawns Tron "Bit" props that wander or follow players as pets.
 * - Bobbing + random rotation
 * - Reacts to nearby chat with yes/no sound + (optional) flex weight pulse
 * - Non-solid, no collision
 *
 * Commands (admin):
 *  sm_petbit     - Toggle your pet Bit
 *  sm_spawnbit   - Spawn a wandering Bit at your location
 *  sm_clearbits  - Remove all Bits
 *  sm_bitstatus  - Debug: list all Bits and their status/position
 */

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>
#include <sdkhooks>

public Plugin myinfo =
{
    name        = "TF2 Bits (Tron Bit)",
    author      = "ChatGPT",
    description = "Spawns Tron Bit entities that wander or follow players and react to chat.",
    version     = "1.0.2",
    url         = ""
};

// -------------------- Assets --------------------

#define BIT_MODEL "models/tron_evolution/characters/bit.mdl"

// For downloads table, include full paths:
static const char g_downloadFiles[][] =
{
    // materials
    "materials/models/tron_evolution/characters/bit/bit.vtf",
    "materials/models/tron_evolution/characters/bit/bit_d.vtf",
    "materials/models/tron_evolution/characters/bit/bit_no.vmt",
    "materials/models/tron_evolution/characters/bit/bit_nuetral.vmt", // typo preserved per request
    "materials/models/tron_evolution/characters/bit/bit_yes.vmt",

    // models
    "models/tron_evolution/characters/bit.dx90.vtx",
    "models/tron_evolution/characters/bit.mdl",
    "models/tron_evolution/characters/bit.vvd",

    // sound
    "sound/bit/yes.wav",
    "sound/bit/no.wav"
};

// For PrecacheSound/EmitSound, omit "sound/" prefix:
#define BIT_SOUND_YES "bit/yes.wav"
#define BIT_SOUND_NO  "bit/no.wav"

// Hardcoded default spawn (small-map tuned)
static const float g_defaultSpawnPos[3] = { -37.499809, -51.016037, 58.760578 };
static const float g_defaultSpawnRadiusXY = 96.0;

// -------------------- ConVars --------------------

ConVar g_cvarEnabled;
ConVar g_cvarMaxBits;
ConVar g_cvarChatRange;
ConVar g_cvarPauseOnChat;
ConVar g_cvarWanderSpeed;
ConVar g_cvarWanderTurnIntervalMin;
ConVar g_cvarWanderTurnIntervalMax;
ConVar g_cvarHoverHeight;
ConVar g_cvarBobAmplitude;
ConVar g_cvarBobSpeed;
ConVar g_cvarPetFollowDist;
ConVar g_cvarPetSideOffset;
ConVar g_cvarPetHeightOffset;
ConVar g_cvarBitScale;
ConVar g_cvarVoiceDelay;
ConVar g_cvarEnableFlex;

// Flex indices (manual)
ConVar g_cvarFlexYesIndex;
ConVar g_cvarFlexNoIndex;

// -------------------- Internals --------------------

#define MAX_BITS 128
#define THINK_INTERVAL 0.05

// Flex sendprop offset for CBaseAnimating::m_flexWeight
int g_flexWeightOffset = -1;

enum BitMode
{
    BitMode_Wander = 0,
    BitMode_Pet = 1
};

int   g_bitEnts[MAX_BITS];
BitMode g_bitMode[MAX_BITS];
int   g_bitOwner[MAX_BITS];          // client index for pets, 0 for wanderers
float g_bitBaseZ[MAX_BITS];          // ground base height for hovering
float g_bitNextTurnAt[MAX_BITS];
float g_bitYaw[MAX_BITS];
float g_bitYawRate[MAX_BITS];        // degrees/sec
float g_bitMoveDirX[MAX_BITS];
float g_bitMoveDirY[MAX_BITS];

// Chat reaction state
bool  g_bitPaused[MAX_BITS];
float g_bitPauseEndsAt[MAX_BITS];
float g_bitFlexPulseEndsAt[MAX_BITS];
int   g_bitFlexIndex[MAX_BITS];      // which flex index is being pulsed now
float g_bitFlexPeakAt[MAX_BITS];     // time of peak (for 0->1->0 shaping)

Handle g_thinkTimer = null;

// One pet per player
int g_petBitEnt[MAXPLAYERS + 1];
bool g_petBitHidden[MAXPLAYERS + 1];


// Initial wander-bit spawn control (defer until waiting-for-players ends)
bool g_pendingInitialSpawn = false;
bool g_hasSpawnedInitial = false;

// Reaction color fallback
bool  g_bitColorActive[MAX_BITS];
float g_bitColorRevertAt[MAX_BITS];
// -------------------- Helpers --------------------

static bool IsValidEdictEnt(int ent)
{
    return (ent > 0 && IsValidEdict(ent) && IsValidEntity(ent));
}

static int FindFreeBitSlot()
{
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (!IsValidEdictEnt(g_bitEnts[i]))
            return i;
    }
    return -1;
}

static int FindBitSlotByEnt(int ent)
{
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (g_bitEnts[i] == ent && IsValidEdictEnt(ent))
            return i;
    }
    return -1;
}

static int CountBits()
{
    int count = 0;
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (IsValidEdictEnt(g_bitEnts[i]))
            count++;
    }
    return count;
}

static void ClearBitSlot(int slot)
{
    g_bitEnts[slot] = 0;
    g_bitMode[slot] = BitMode_Wander;
    g_bitOwner[slot] = 0;
    g_bitBaseZ[slot] = 0.0;
    g_bitNextTurnAt[slot] = 0.0;
    g_bitYaw[slot] = 0.0;
    g_bitYawRate[slot] = 0.0;
    g_bitMoveDirX[slot] = 0.0;
    g_bitMoveDirY[slot] = 0.0;

    g_bitPaused[slot] = false;
    g_bitPauseEndsAt[slot] = 0.0;
    g_bitFlexPulseEndsAt[slot] = 0.0;
    g_bitFlexIndex[slot] = -1;
    g_bitFlexPeakAt[slot] = 0.0;

    g_bitColorActive[slot] = false;
    g_bitColorRevertAt[slot] = 0.0;
}

static void RemoveBitBySlot(int slot)
{
    int ent = g_bitEnts[slot];
    if (IsValidEdictEnt(ent))
    {
        // If pet, clear owner mapping
        if (g_bitMode[slot] == BitMode_Pet)
        {
            int owner = g_bitOwner[slot];
            if (owner >= 1 && owner <= MaxClients && g_petBitEnt[owner] == ent)
            {
                g_petBitEnt[owner] = 0;
            }
        }

        AcceptEntityInput(ent, "Kill");
    }
    ClearBitSlot(slot);
}

static void RemoveAllBits()
{
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (IsValidEdictEnt(g_bitEnts[i]))
            RemoveBitBySlot(i);
        else
            ClearBitSlot(i);
    }

    for (int c = 1; c <= MaxClients; c++)
    {
        g_petBitEnt[c] = 0;
        g_petBitHidden[c] = false;
    }
}

static bool TraceToGround(const float startPos[3], float outGroundPos[3])
{
    float endPos[3];
    endPos[0] = startPos[0];
    endPos[1] = startPos[1];
    endPos[2] = startPos[2] - 2000.0;

    Handle trace = TR_TraceRayFilterEx(startPos, endPos, MASK_SOLID, RayType_EndPoint, TraceEntityFilterPlayers, 0);
    bool hit = TR_DidHit(trace);
    if (hit)
    {
        TR_GetEndPosition(outGroundPos, trace);
    }
    CloseHandle(trace);
    return hit;
}

public bool TraceEntityFilterPlayers(int entity, int contentsMask, any data)
{
    // Ignore players so Bits can hover near them without grounding on them
    if (entity >= 1 && entity <= MaxClients)
        return false;
    return true;
}

static void PickNewWanderHeading(int slot, float now)
{
    // Random yaw and yaw rate
    g_bitYaw[slot] = GetRandomFloat(0.0, 360.0);
    g_bitYawRate[slot] = GetRandomFloat(-120.0, 120.0);

    float rad = DegToRad(g_bitYaw[slot]);
    g_bitMoveDirX[slot] = Cosine(rad);
    g_bitMoveDirY[slot] = Sine(rad);

    float tmin = g_cvarWanderTurnIntervalMin.FloatValue;
    float tmax = g_cvarWanderTurnIntervalMax.FloatValue;
    if (tmax < tmin)
        tmax = tmin;

    g_bitNextTurnAt[slot] = now + GetRandomFloat(tmin, tmax);
}

static void MakeNonSolidNoCollision(int ent)
{
    // Strong "non-solid" set:
    SetEntProp(ent, Prop_Send, "m_nSolidType", 0);
    SetEntProp(ent, Prop_Data, "m_nSolidType", 0);

    // 2 is often COLLISION_GROUP_DEBRIS; 0/2 are typical for non-interfering props
    SetEntProp(ent, Prop_Send, "m_CollisionGroup", 2);
    SetEntProp(ent, Prop_Data, "m_CollisionGroup", 2);

    // Sometimes needed:
    SetEntProp(ent, Prop_Data, "m_usSolidFlags", 0);
}

static int CreateBitEntity(const float origin[3], bool asPet, int ownerClient)
{
    if (!g_cvarEnabled.BoolValue)
        return 0;

    int current = CountBits();
    if (current >= g_cvarMaxBits.IntValue)
        return 0;

    int slot = FindFreeBitSlot();
    if (slot == -1)
        return 0;

    int ent = CreateEntityByName("prop_dynamic");
    if (!IsValidEdictEnt(ent))
        return 0;

    DispatchKeyValue(ent, "model", BIT_MODEL);
    DispatchKeyValue(ent, "solid", "0");
    DispatchKeyValue(ent, "disableshadows", "1");
    DispatchKeyValue(ent, "StartDisabled", "0");

    DispatchSpawn(ent);
    ActivateEntity(ent);

    // Ensure model + rendering are applied (helps on some TF2 setups)
    SetEntityModel(ent, BIT_MODEL);
    SetEntityRenderMode(ent, RENDER_NORMAL);
    SetEntityRenderColor(ent, 255, 255, 255, 255);

    float scale = g_cvarBitScale.FloatValue;
    SetEntPropFloat(ent, Prop_Send, "m_flModelScale", scale);
    DispatchKeyValueFloat(ent, "modelscale", scale);

    // Move type: we will position it manually
    SetEntityMoveType(ent, MOVETYPE_FLY);

    MakeNonSolidNoCollision(ent);

    // Spawn initially at "eye-ish level" so it is visible immediately
    float spawnPos[3];
    spawnPos[0] = origin[0];
    spawnPos[1] = origin[1];
    spawnPos[2] = origin[2] + (asPet ? g_cvarPetHeightOffset.FloatValue : g_cvarHoverHeight.FloatValue);

    TeleportEntity(ent, spawnPos, NULL_VECTOR, NULL_VECTOR);

    // Track it
    g_bitEnts[slot] = ent;
    g_bitMode[slot] = asPet ? BitMode_Pet : BitMode_Wander;
    g_bitOwner[slot] = asPet ? ownerClient : 0;

    float now = GetGameTime();
    g_bitPaused[slot] = false;
    g_bitPauseEndsAt[slot] = 0.0;
    g_bitFlexPulseEndsAt[slot] = 0.0;
    g_bitFlexIndex[slot] = -1;
    g_bitFlexPeakAt[slot] = 0.0;

    // Base ground height
    float startForGround[3];
    startForGround[0] = origin[0];
    startForGround[1] = origin[1];
    startForGround[2] = origin[2] + 256.0;

    float groundPos[3];
    if (TraceToGround(startForGround, groundPos))
        g_bitBaseZ[slot] = groundPos[2];
    else
        g_bitBaseZ[slot] = origin[2];

    // Wander init
    if (!asPet)
        PickNewWanderHeading(slot, now);

    // If pet, remember per-player
    if (asPet && ownerClient >= 1 && ownerClient <= MaxClients)
        g_petBitEnt[ownerClient] = ent;

    return ent;
}

static void GetHardcodedSpawnOrigin(float outPos[3])
{
    // Spawn near a known-good point on this small map.
    outPos[0] = g_defaultSpawnPos[0] + GetRandomFloat(-g_defaultSpawnRadiusXY, g_defaultSpawnRadiusXY);
    outPos[1] = g_defaultSpawnPos[1] + GetRandomFloat(-g_defaultSpawnRadiusXY, g_defaultSpawnRadiusXY);
    outPos[2] = g_defaultSpawnPos[2];
}

static bool GetHardcodedGroundSpawn(float outGround[3])
{
    float pos[3];
    GetHardcodedSpawnOrigin(pos);

    float start[3];
    start[0] = pos[0];
    start[1] = pos[1];
    start[2] = pos[2] + 256.0;

    float ground[3];
    if (TraceToGround(start, ground))
    {
        outGround[0] = ground[0];
        outGround[1] = ground[1];
        outGround[2] = ground[2];
        return true;
    }

    outGround[0] = pos[0];
    outGround[1] = pos[1];
    outGround[2] = pos[2];
    return true;
}

static void StartBitFlexPulse(int slot, bool isYes)
{
    if (!g_cvarEnableFlex.BoolValue)
        return;
    int ent = g_bitEnts[slot];
    if (!IsValidEdictEnt(ent))
        return;

    int flexIndex = isYes ? g_cvarFlexYesIndex.IntValue : g_cvarFlexNoIndex.IntValue;
    if (flexIndex < 0)
    {
        // Flex disabled / unknown index
        g_bitFlexIndex[slot] = -1;
        g_bitFlexPulseEndsAt[slot] = 0.0;
        g_bitFlexPeakAt[slot] = 0.0;
        return;
    }

    float now = GetGameTime();
    float duration = 0.45; // quick 0->1->0
    g_bitFlexIndex[slot] = flexIndex;
    g_bitFlexPulseEndsAt[slot] = now + duration;
    g_bitFlexPeakAt[slot] = now + (duration * 0.5);
}

static void SetFlexWeightSafe(int ent, int flexIndex, float value01)
{
    if (flexIndex < 0)
        return;

    if (value01 < 0.0) value01 = 0.0;
    if (value01 > 1.0) value01 = 1.0;

    // Safer array write (avoids raw offsets that can cause visual corruption on some entities)
    SetEntPropFloat(ent, Prop_Send, "m_flexWeight", value01, flexIndex);
}

// -------------------- SM Lifecycle --------------------

public void OnPluginStart()
{
    g_cvarEnabled = CreateConVar("sm_bits_enabled", "1", "Enable TF2 Bits plugin.", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    g_cvarMaxBits = CreateConVar("sm_bits_max", "16", "Maximum total Bits (wanderers + pets).", FCVAR_NOTIFY, true, 0.0, true, 128.0);

    g_cvarChatRange = CreateConVar("sm_bits_chat_range", "300.0", "Chat reaction range (units).", FCVAR_NOTIFY, true, 0.0, true, 5000.0);
    g_cvarPauseOnChat = CreateConVar("sm_bits_pause_time", "0.8", "How long Bit pauses when reacting to chat.", FCVAR_NOTIFY, true, 0.0, true, 10.0);

    g_cvarWanderSpeed = CreateConVar("sm_bits_wander_speed", "75.0", "Wandering speed (units/sec).", FCVAR_NOTIFY, true, 0.0, true, 1000.0);
    g_cvarWanderTurnIntervalMin = CreateConVar("sm_bits_turn_min", "1.2", "Min time between wander heading changes.", FCVAR_NOTIFY, true, 0.1, true, 30.0);
    g_cvarWanderTurnIntervalMax = CreateConVar("sm_bits_turn_max", "3.0", "Max time between wander heading changes.", FCVAR_NOTIFY, true, 0.1, true, 30.0);

    // Raised default hover height to be more "eye-level" by default
    g_cvarHoverHeight = CreateConVar("sm_bits_hover_height", "66.0", "Hover height above ground (units).", FCVAR_NOTIFY, true, 0.0, true, 256.0);
    g_cvarBobAmplitude = CreateConVar("sm_bits_bob_amp", "12.0", "Bobbing amplitude (units).", FCVAR_NOTIFY, true, 0.0, true, 64.0);
    g_cvarBobSpeed = CreateConVar("sm_bits_bob_speed", "2.2", "Bobbing speed multiplier.", FCVAR_NOTIFY, true, 0.0, true, 20.0);

    g_cvarPetFollowDist = CreateConVar("sm_bits_pet_dist", "62.0", "Pet follow distance behind player.", FCVAR_NOTIFY, true, 0.0, true, 512.0);
    g_cvarPetSideOffset = CreateConVar("sm_bits_pet_side", "14.0", "Pet side offset (+right).", FCVAR_NOTIFY, true, -256.0, true, 256.0);
    g_cvarPetHeightOffset = CreateConVar("sm_bits_pet_height", "66.0", "Pet height offset above player origin.", FCVAR_NOTIFY, true, -128.0, true, 256.0);
    g_cvarBitScale = CreateConVar("sm_bits_scale", "1.5", "Scale of Bit model (1.0 = normal).", FCVAR_NOTIFY, true, 0.1, true, 10.0);
    g_cvarVoiceDelay = CreateConVar("sm_bits_voicemenu_delay", "2.65", "Delay (seconds) after voicemenu before Bit reacts, if in range.", FCVAR_NOTIFY, true, 0.0, true, 10.0);
    g_cvarEnableFlex = CreateConVar("sm_bits_enable_flex", "0", "Enable flex-based Yes/No visuals (may not work on all models).", FCVAR_NOTIFY, true, 0.0, true, 1.0);


    g_cvarFlexYesIndex = CreateConVar("sm_bit_flex_yes_index", "-1", "Flex index for YES (set >=0 to enable).", FCVAR_NOTIFY, true, -1.0, true, 255.0);
    g_cvarFlexNoIndex  = CreateConVar("sm_bit_flex_no_index", "-1", "Flex index for NO (set >=0 to enable).", FCVAR_NOTIFY, true, -1.0, true, 255.0);

    RegAdminCmd("sm_petbit", Command_PetBit, ADMFLAG_GENERIC, "Toggle a pet Bit following you.");
    RegAdminCmd("sm_spawnbit", Command_SpawnBit, ADMFLAG_GENERIC, "Spawn a wandering Bit at your location.");
    RegAdminCmd("sm_clearbits", Command_ClearBits, ADMFLAG_GENERIC, "Remove all Bits.");
    RegAdminCmd("sm_bitstatus", Command_BitStatus, ADMFLAG_GENERIC, "List all Bits and their status.");

    AddCommandListener(Command_SayListener, "say");
    AddCommandListener(Command_SayListener, "say_team");
    AddCommandListener(Command_VoiceMenuListener, "voicemenu");

    HookEvent("teamplay_round_start", Event_RoundStart, EventHookMode_PostNoCopy);
    HookEvent("arena_round_start", Event_RoundStart, EventHookMode_PostNoCopy);
    HookEvent("teamplay_waiting_ends", Event_WaitingEnds, EventHookMode_PostNoCopy);
    HookEvent("player_death", Event_PlayerDeath, EventHookMode_Post);
    HookEvent("player_spawn", Event_PlayerSpawn, EventHookMode_Post);


    g_flexWeightOffset = FindSendPropInfo("CBaseAnimating", "m_flexWeight");

    for (int i = 0; i < MAX_BITS; i++)
        ClearBitSlot(i);

    for (int c = 1; c <= MaxClients; c++)
        g_petBitEnt[c] = 0;

    if (g_thinkTimer == null)
        g_thinkTimer = CreateTimer(THINK_INTERVAL, Timer_Think, _, TIMER_REPEAT | TIMER_FLAG_NO_MAPCHANGE);
}

public void OnMapStart()
{
    // Downloads table
    for (int i = 0; i < sizeof(g_downloadFiles); i++)
    {
        AddFileToDownloadsTable(g_downloadFiles[i]);
    }

    PrecacheModel(BIT_MODEL, true);
    PrecacheSound(BIT_SOUND_YES, true);
    PrecacheSound(BIT_SOUND_NO, true);

    // Clean slate on map start
    RemoveAllBits();

    for (int i = 0; i < MAX_BITS; i++)
        ClearBitSlot(i);

    for (int c = 1; c <= MaxClients; c++)
    {
        g_petBitEnt[c] = 0;
        g_petBitHidden[c] = false;
    }

    // Restart think timer after mapchange
    if (g_thinkTimer == null)
        g_thinkTimer = CreateTimer(THINK_INTERVAL, Timer_Think, _, TIMER_REPEAT | TIMER_FLAG_NO_MAPCHANGE);

    // Defer initial spawn until waiting-for-players ends (prevents invisible/outside-map spawns)
    g_pendingInitialSpawn = true;
    g_hasSpawnedInitial = false;
}


public void OnMapEnd()
{
    // Stop think timer (it is created with TIMER_FLAG_NO_MAPCHANGE)
    if (g_thinkTimer != null)
    {
        CloseHandle(g_thinkTimer);
        g_thinkTimer = null;
    }

    // Fully clear all tracked entities/state
    RemoveAllBits();


    g_pendingInitialSpawn = false;
    g_hasSpawnedInitial = false;
    for (int i = 0; i < MAX_BITS; i++)
        ClearBitSlot(i);

    for (int c = 1; c <= MaxClients; c++)
    {
        g_petBitEnt[c] = 0;
        g_petBitHidden[c] = false;
    }
}

public void OnClientDisconnect(int client)
{
    int ent = g_petBitEnt[client];
    if (IsValidEdictEnt(ent))
    {
        int slot = FindBitSlotByEnt(ent);
        if (slot != -1)
            RemoveBitBySlot(slot);
        else
            AcceptEntityInput(ent, "Kill");
    }
    g_petBitEnt[client] = 0;
    g_petBitHidden[client] = false;
}

public void Event_WaitingEnds(Event event, const char[] name, bool dontBroadcast)
{
    if (!g_cvarEnabled.BoolValue)
        return;

    if (!g_pendingInitialSpawn || g_hasSpawnedInitial)
        return;

    float groundPos[3];
    if (GetHardcodedGroundSpawn(groundPos))
    {
        CreateBitEntity(groundPos, false, 0);
        g_hasSpawnedInitial = true;
    }

    g_pendingInitialSpawn = false;
}

public void Event_RoundStart(Event event, const char[] name, bool dontBroadcast)
{
    if (!g_cvarEnabled.BoolValue)
        return;


    // Some configs/maps may not use waiting-for-players; spawn on first round start if still pending.
    if (g_pendingInitialSpawn && !g_hasSpawnedInitial)
    {
        float groundPos[3];
        if (GetHardcodedGroundSpawn(groundPos))
        {
            CreateBitEntity(groundPos, false, 0);
            g_hasSpawnedInitial = true;
        }
        g_pendingInitialSpawn = false;
    }
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (g_bitEnts[i] != 0 && !IsValidEdictEnt(g_bitEnts[i]))
            ClearBitSlot(i);
    }

    bool hasWanderer = false;
    for (int i = 0; i < MAX_BITS; i++)
    {
        if (IsValidEdictEnt(g_bitEnts[i]) && g_bitMode[i] == BitMode_Wander)
        {
            hasWanderer = true;
            break;
        }
    }

    if (!hasWanderer)
    {
        float spawnPos[3];
        if (GetHardcodedGroundSpawn(spawnPos))
            CreateBitEntity(spawnPos, false, 0);
    }
}


public void Event_PlayerDeath(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client < 1 || client > MaxClients)
        return;

    int ent = g_petBitEnt[client];
    if (!IsValidEdictEnt(ent))
        return;

    // Hide pet Bit while owner is dead
    int effects = GetEntProp(ent, Prop_Send, "m_fEffects");
    effects |= 32; // EF_NODRAW
    SetEntProp(ent, Prop_Send, "m_fEffects", effects);

    g_petBitHidden[client] = true;
}

public void Event_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return;

    int ent = g_petBitEnt[client];
    if (!IsValidEdictEnt(ent))
        return;

    // Unhide and "re-summon" behind the player
    int effects = GetEntProp(ent, Prop_Send, "m_fEffects");
    effects &= ~32; // clear EF_NODRAW
    SetEntProp(ent, Prop_Send, "m_fEffects", effects);

    g_petBitHidden[client] = false;

    // Move immediately near owner; Timer_Think will take over smoothing
    float ownerPos[3];
    GetClientAbsOrigin(client, ownerPos);

    float spawnPos[3];
    spawnPos[0] = ownerPos[0];
    spawnPos[1] = ownerPos[1];
    spawnPos[2] = ownerPos[2] + g_cvarPetHeightOffset.FloatValue;

    TeleportEntity(ent, spawnPos, NULL_VECTOR, NULL_VECTOR);
}

// -------------------- Commands --------------------

public Action Command_ClearBits(int client, int args)
{
    RemoveAllBits();
    ReplyToCommand(client, "[Bits] Cleared all Bits.");
    return Plugin_Handled;
}

public Action Command_SpawnBit(int client, int args)
{
    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Handled;

    float pos[3];
    GetClientAbsOrigin(client, pos);

    int ent = CreateBitEntity(pos, false, 0);
    if (ent == 0)
    {
        ReplyToCommand(client, "[Bits] Failed to spawn Bit (limit reached or disabled).");
        return Plugin_Handled;
    }

    ReplyToCommand(client, "[Bits] Spawned a wandering Bit.");
    return Plugin_Handled;
}

public Action Command_PetBit(int client, int args)
{
    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Handled;

    int existing = g_petBitEnt[client];
    if (IsValidEdictEnt(existing))
    {
        int slot = FindBitSlotByEnt(existing);
        if (slot != -1)
            RemoveBitBySlot(slot);
        else
            AcceptEntityInput(existing, "Kill");

        g_petBitEnt[client] = 0;
        ReplyToCommand(client, "[Bits] Pet Bit dismissed.");
        return Plugin_Handled;
    }

    float pos[3];
    GetClientAbsOrigin(client, pos);

    int ent = CreateBitEntity(pos, true, client);
    if (ent == 0)
    {
        ReplyToCommand(client, "[Bits] Failed to create pet Bit (limit reached or disabled).");
        return Plugin_Handled;
    }

    ReplyToCommand(client, "[Bits] Pet Bit created. Run sm_petbit again to dismiss.");
    return Plugin_Handled;
}

public Action Command_BitStatus(int client, int args)
{
    int total = 0;
    ReplyToCommand(client, "[Bits] ---- Bit status ----");

    for (int i = 0; i < MAX_BITS; i++)
    {
        int ent = g_bitEnts[i];
        if (!IsValidEdictEnt(ent))
            continue;

        total++;

        float pos[3];
        GetEntPropVector(ent, Prop_Send, "m_vecOrigin", pos);

        char modeText[16];
        if (g_bitMode[i] == BitMode_Pet)
            strcopy(modeText, sizeof(modeText), "pet");
        else
            strcopy(modeText, sizeof(modeText), "wander");

        char actionText[32];
        float now = GetGameTime();
        if (g_bitPaused[i] && now < g_bitPauseEndsAt[i])
            strcopy(actionText, sizeof(actionText), "reacting");
        else if (g_bitMode[i] == BitMode_Pet)
            strcopy(actionText, sizeof(actionText), "following");
        else
            strcopy(actionText, sizeof(actionText), "wandering");

        int owner = g_bitOwner[i];
        if (g_bitMode[i] == BitMode_Pet && owner >= 1 && owner <= MaxClients && IsClientInGame(owner))
        {
            ReplyToCommand(client, "[Bits] slot=%d ent=%d mode=%s owner=%N action=%s pos=(%.1f %.1f %.1f)",
                i, ent, modeText, owner, actionText, pos[0], pos[1], pos[2]);
        }
        else
        {
            ReplyToCommand(client, "[Bits] slot=%d ent=%d mode=%s action=%s pos=(%.1f %.1f %.1f)",
                i, ent, modeText, actionText, pos[0], pos[1], pos[2]);
        }
    }

    ReplyToCommand(client, "[Bits] Total active Bits: %d", total);
    return Plugin_Handled;
}


static void TriggerBitReaction(int slot)
{
    if (slot < 0 || slot >= MAX_BITS)
        return;

    int ent = g_bitEnts[slot];
    if (!IsValidEdictEnt(ent))
        return;

    float now = GetGameTime();

    if (g_bitPaused[slot] && now < g_bitPauseEndsAt[slot])
        return;

    g_bitPaused[slot] = true;
    g_bitPauseEndsAt[slot] = now + g_cvarPauseOnChat.FloatValue;

    bool isYes = (GetRandomInt(0, 1) == 1);

    //EmitSoundToAll(
    // Visual response: flex if enabled, otherwise temporary color tint.
    if (!g_cvarEnableFlex.BoolValue)
    {
        if (isYes)
            SetEntityRenderColor(ent, 255, 255, 0, 255);
        else
            SetEntityRenderColor(ent, 255, 0, 0, 255);

        g_bitColorActive[slot] = true;
        g_bitColorRevertAt[slot] = now + g_cvarPauseOnChat.FloatValue;
    }

    EmitSoundToAll(isYes ? BIT_SOUND_YES : BIT_SOUND_NO, ent, SNDCHAN_AUTO, SNDLEVEL_NORMAL);
    StartBitFlexPulse(slot, isYes);
}

static int FindNearestBitInRange(int client, float range, float &outDistSq)
{
    outDistSq = 0.0;

    float clientPos[3];
    GetClientAbsOrigin(client, clientPos);

    float rangeSq = range * range;

    int bestSlot = -1;
    float bestDist = 0.0;

    for (int i = 0; i < MAX_BITS; i++)
    {
        int ent = g_bitEnts[i];
        if (!IsValidEdictEnt(ent))
            continue;

        float bitPos[3];
        GetEntPropVector(ent, Prop_Send, "m_vecOrigin", bitPos);

        float dx = clientPos[0] - bitPos[0];
        float dy = clientPos[1] - bitPos[1];
        float dz = clientPos[2] - bitPos[2];
        float distSq = dx*dx + dy*dy + dz*dz;

        if (distSq <= rangeSq && (bestSlot == -1 || distSq < bestDist))
        {
            bestSlot = i;
            bestDist = distSq;
        }
    }

    if (bestSlot != -1)
        outDistSq = bestDist;

    return bestSlot;
}

public Action Command_VoiceMenuListener(int client, const char[] command, int argc)
{
    if (!g_cvarEnabled.BoolValue)
        return Plugin_Continue;

    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Continue;

    float dummyDistSq;
    int slot = FindNearestBitInRange(client, g_cvarChatRange.FloatValue, dummyDistSq);
    if (slot == -1)
        return Plugin_Continue;

    float delay = g_cvarVoiceDelay.FloatValue;
    int packed = (GetClientUserId(client) << 16) | (slot & 0xFFFF);
    CreateTimer(delay, Timer_DelayedBitReact, packed, TIMER_FLAG_NO_MAPCHANGE);

    return Plugin_Continue;
}

public Action Timer_DelayedBitReact(Handle timer, any packed)
{
    int slot = packed & 0xFFFF;
    int userId = (packed >> 16) & 0xFFFF;

    int client = GetClientOfUserId(userId);
    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Stop;

    if (slot < 0 || slot >= MAX_BITS)
        return Plugin_Stop;

    float dummyDistSq;
    int nearestNow = FindNearestBitInRange(client, g_cvarChatRange.FloatValue, dummyDistSq);
    if (nearestNow != slot)
        return Plugin_Stop;

    TriggerBitReaction(slot);
    return Plugin_Stop;
}

// -------------------- Chat Listener --------------------

public Action Command_SayListener(int client, const char[] command, int argc)
{
    if (!g_cvarEnabled.BoolValue)
        return Plugin_Continue;

    if (client < 1 || client > MaxClients || !IsClientInGame(client))
        return Plugin_Continue;

    float clientPos[3];
    GetClientAbsOrigin(client, clientPos);

    float range = g_cvarChatRange.FloatValue;
    float rangeSq = range * range;

    int bestSlot = -1;
    float bestDistSq = 0.0;

    for (int i = 0; i < MAX_BITS; i++)
    {
        int ent = g_bitEnts[i];
        if (!IsValidEdictEnt(ent))
            continue;

        float bitPos[3];
        GetEntPropVector(ent, Prop_Send, "m_vecOrigin", bitPos);

        float dx = clientPos[0] - bitPos[0];
        float dy = clientPos[1] - bitPos[1];
        float dz = clientPos[2] - bitPos[2];
        float distSq = dx*dx + dy*dy + dz*dz;

        if (distSq <= rangeSq)
        {
            if (bestSlot == -1 || distSq < bestDistSq)
            {
                bestSlot = i;
                bestDistSq = distSq;
            }
        }
    }

    if (bestSlot != -1)
    {
        TriggerBitReaction(bestSlot);
    }

    return Plugin_Continue;
}

// -------------------- Think Loop --------------------

public Action Timer_Think(Handle timer, any data)
{
    if (!g_cvarEnabled.BoolValue)
        return Plugin_Continue;

    float now = GetGameTime();

    float hover = g_cvarHoverHeight.FloatValue;
    float bobAmp = g_cvarBobAmplitude.FloatValue;
    float bobSpeed = g_cvarBobSpeed.FloatValue;
    float wanderSpeed = g_cvarWanderSpeed.FloatValue;

    for (int i = 0; i < MAX_BITS; i++)
    {
        int ent = g_bitEnts[i];
        if (!IsValidEdictEnt(ent))
            continue;

        if (g_bitMode[i] == BitMode_Pet)
        {
            int owner = g_bitOwner[i];
            if (owner < 1 || owner > MaxClients || !IsClientInGame(owner))
            {
                RemoveBitBySlot(i);
                continue;
            }
        }

        float pos[3];
        GetEntPropVector(ent, Prop_Send, "m_vecOrigin", pos);

        float traceStart[3];
        traceStart[0] = pos[0];
        traceStart[1] = pos[1];
        traceStart[2] = pos[2] + 256.0;

        float groundPos[3];
        if (TraceToGround(traceStart, groundPos))
            g_bitBaseZ[i] = groundPos[2];

        float bob = Sine(now * bobSpeed) * bobAmp;
        float targetZ = g_bitBaseZ[i] + hover + bob;

        g_bitYaw[i] += g_bitYawRate[i] * THINK_INTERVAL;

        bool paused = (g_bitPaused[i] && now < g_bitPauseEndsAt[i]);

        if (!paused)
        {
            if (g_bitMode[i] == BitMode_Pet)
            {
                int owner = g_bitOwner[i];

                // Hide pet while owner is dead; reappears on respawn
                if (!IsPlayerAlive(owner))
                {
                    if (!g_petBitHidden[owner])
                    {
                        int effects = GetEntProp(ent, Prop_Send, "m_fEffects");
                        effects |= 32; // EF_NODRAW
                        SetEntProp(ent, Prop_Send, "m_fEffects", effects);
                        g_petBitHidden[owner] = true;
                    }
                    continue;
                }
                else if (g_petBitHidden[owner])
                {
                    int effects = GetEntProp(ent, Prop_Send, "m_fEffects");
                    effects &= ~32; // clear EF_NODRAW
                    SetEntProp(ent, Prop_Send, "m_fEffects", effects);
                    g_petBitHidden[owner] = false;
                }

                float ownerPos[3];
                float ownerAng[3];
                GetClientAbsOrigin(owner, ownerPos);
                GetClientEyeAngles(owner, ownerAng);

                // Pet Z follows owner height (plus bob), not ground hover
                targetZ = ownerPos[2] + g_cvarPetHeightOffset.FloatValue + bob;

                float forwardVec[3], rightVec[3], upVec[3];
                GetAngleVectors(ownerAng, forwardVec, rightVec, upVec);

                float distBack = g_cvarPetFollowDist.FloatValue;
                float side = g_cvarPetSideOffset.FloatValue;
                float height = g_cvarPetHeightOffset.FloatValue;

                float desired[3];
                desired[0] = ownerPos[0] - (forwardVec[0] * distBack) + (rightVec[0] * side);
                desired[1] = ownerPos[1] - (forwardVec[1] * distBack) + (rightVec[1] * side);
                desired[2] = ownerPos[2] + height;

                float desiredTraceStart[3];
                desiredTraceStart[0] = desired[0];
                desiredTraceStart[1] = desired[1];
                desiredTraceStart[2] = desired[2] + 256.0;

                float desiredGround[3];
                if (TraceToGround(desiredTraceStart, desiredGround))
                    g_bitBaseZ[i] = desiredGround[2];

                float lerp = 0.20;
                pos[0] = pos[0] + (desired[0] - pos[0]) * lerp;
                pos[1] = pos[1] + (desired[1] - pos[1]) * lerp;
            }
            else
            {
                if (now >= g_bitNextTurnAt[i])
                    PickNewWanderHeading(i, now);

                float step = wanderSpeed * THINK_INTERVAL;

                float nextPos[3];
                nextPos[0] = pos[0] + g_bitMoveDirX[i] * step;
                nextPos[1] = pos[1] + g_bitMoveDirY[i] * step;
                nextPos[2] = pos[2];

                Handle tr = TR_TraceRayFilterEx(pos, nextPos, MASK_SOLID, RayType_EndPoint, TraceEntityFilterPlayers, 0);
                bool hit = TR_DidHit(tr);
                CloseHandle(tr);

                if (hit)
                {
                    PickNewWanderHeading(i, now);
                }
                else
                {
                    pos[0] = nextPos[0];
                    pos[1] = nextPos[1];
                }
            }
        }
        else
        {
            if (g_bitPaused[i] && now >= g_bitPauseEndsAt[i])
                g_bitPaused[i] = false;
        }

        pos[2] = targetZ;

        float ang[3];
        ang[0] = 0.0;
        ang[1] = g_bitYaw[i];
        ang[2] = 0.0;

        TeleportEntity(ent, pos, ang, NULL_VECTOR);


        // Revert temporary reaction tint (flex-disabled visual)
        if (g_bitColorActive[i] && now >= g_bitColorRevertAt[i])
        {
            SetEntityRenderColor(ent, 255, 255, 255, 255);
            g_bitColorActive[i] = false;
            g_bitColorRevertAt[i] = 0.0;
        }
        if (g_bitFlexIndex[i] >= 0 && now <= g_bitFlexPulseEndsAt[i])
        {
            float start = g_bitFlexPulseEndsAt[i] - 0.45;
            float peak = g_bitFlexPeakAt[i];
            float end  = g_bitFlexPulseEndsAt[i];

            float value = 0.0;
            if (now <= peak)
            {
                float t = (now - start) / (peak - start);
                if (t < 0.0) t = 0.0;
                if (t > 1.0) t = 1.0;
                value = t;
            }
            else
            {
                float t = (now - peak) / (end - peak);
                if (t < 0.0) t = 0.0;
                if (t > 1.0) t = 1.0;
                value = 1.0 - t;
            }

            SetFlexWeightSafe(ent, g_bitFlexIndex[i], value);
        }
        else if (g_bitFlexIndex[i] >= 0 && now > g_bitFlexPulseEndsAt[i])
        {
            SetFlexWeightSafe(ent, g_bitFlexIndex[i], 0.0);
            g_bitFlexIndex[i] = -1;
            g_bitFlexPulseEndsAt[i] = 0.0;
            g_bitFlexPeakAt[i] = 0.0;
        }
    }

    return Plugin_Continue;
}
