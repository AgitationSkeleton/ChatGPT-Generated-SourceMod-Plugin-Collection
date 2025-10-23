/**
 * Fortress Forever — Pyro jetpack disabler (no primary-fire stutter)
 * - Only acts when +attack2 is pressed, or if we detect jetpack already active (one-time cancel).
 * - No per-frame hammering of m_nButtons / jetpack props.
 */

#include <sourcemod>
#include <sdktools>

#if !defined IN_ATTACK2
    #define IN_ATTACK2 (1<<11) // 2048 typical
#endif

public Plugin myinfo =
{
    name        = "FF Pyro Jetpack Disabler (gentle)",
    author      = "ChatGPT",
    description = "Blocks Pyro jetpack without disturbing primary fire",
    version     = "2.1.0"
};

// CVars
ConVar gC_Enable;
ConVar gC_RequireModel;       // also require model contains "pyro"
ConVar gC_Attack2Mask;        // override if mod uses non-standard bit
ConVar gC_HardPinWeapon;      // pin m_flNextSecondaryAttack only when +attack2 or while jetpacking
ConVar gC_Debug;

static const char PYRO_WEAPONS[][] =
{
    "ff_weapon_crowbar",
    "ff_weapon_shotgun",
    "ff_weapon_flamethrower",
    "ff_weapon_ic"
};

public void OnPluginStart()
{
    gC_Enable        = CreateConVar("sm_ff_pyroblock_enable", "1",  "Enable jetpack blocking", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gC_RequireModel  = CreateConVar("sm_ff_pyroblock_require_model", "0", "Also require model contains 'pyro'", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gC_Attack2Mask   = CreateConVar("sm_ff_pyroblock_attack2_mask", "2048", "Bit for +attack2 (default 2048)", FCVAR_NOTIFY);
    gC_HardPinWeapon = CreateConVar("sm_ff_pyroblock_hardpin", "1", "Pin m_flNextSecondaryAttack only when needed", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gC_Debug         = CreateConVar("sm_ff_pyroblock_debug", "0", "Debug logs", FCVAR_NONE, true, 0.0, true, 1.0);

    AutoExecConfig(true, "ff_pyro_jetblock_gentle");
}

// Act only on input frames and only when +attack2 is actually down
public Action OnPlayerRunCmd(int client, int &buttons, int &impulse, float vel[3], float ang[3], int &weapon)
{
    if (!gC_Enable.BoolValue)
        return Plugin_Continue;

    if (!IsValidLiveClient(client))
        return Plugin_Continue;

    if (!ShouldBlockForClient(client))
        return Plugin_Continue;

    int mask = gC_Attack2Mask.IntValue;
    if (mask <= 0) mask = IN_ATTACK2;

    // If player is pressing alt-fire, strip it and cancel jetpack once
    if ((buttons & mask) != 0)
    {
        buttons &= ~mask;

        // Mirror into m_nButtons only when alt-fire is pressed
        int btn = GetEntProp(client, Prop_Send, "m_nButtons");
        if ((btn & mask) != 0)
            SetEntProp(client, Prop_Send, "m_nButtons", (btn & ~mask));

        CancelJetpackOnce(client, /*pinSecondary=*/gC_HardPinWeapon.BoolValue);

        if (gC_Debug.BoolValue)
        {
            char cls[64]; GetActiveWeaponClass(client, cls, sizeof(cls));
            PrintToServer("[FF-JetBlock] stripped +attack2 for %N (wep=%s)", client, cls);
        }
        return Plugin_Changed;
    }

    // If alt-fire is not pressed, do NOT touch buttons or props.
    // But: if the mod already set jetpack active somehow, cancel it once.
    if (IsJetpacking(client))
    {
        CancelJetpackOnce(client, /*pinSecondary=*/gC_HardPinWeapon.BoolValue);
        if (gC_Debug.BoolValue)
            PrintToServer("[FF-JetBlock] detected active jetpack -> canceled for %N", client);
    }

    return Plugin_Continue;
}

// No per-frame prop writes—only a light watchdog to cancel active jetpack if it ever slips through.
public void OnGameFrame()
{
    if (!gC_Enable.BoolValue)
        return;

    for (int i = 1; i <= MaxClients; i++)
    {
        if (!IsValidLiveClient(i))
            continue;
        if (!ShouldBlockForClient(i))
            continue;

        if (IsJetpacking(i))
        {
            CancelJetpackOnce(i, /*pinSecondary=*/gC_HardPinWeapon.BoolValue);
            if (gC_Debug.BoolValue)
                PrintToServer("[FF-JetBlock] watchdog cancel for %N", i);
        }
    }
}

// ---------- core helpers ----------

static bool ShouldBlockForClient(int client)
{
    if (gC_RequireModel.BoolValue && !ModelLooksLikePyro(client))
        return false;

    return ActiveWeaponIsPyroUnique(client);
}

static bool IsJetpacking(int client)
{
    return (GetEntProp(client, Prop_Send, "m_bJetpacking") != 0);
}

// Cancel/disable jetpack exactly once (no repeated writes)
static void CancelJetpackOnce(int client, bool pinSecondary)
{
    // Disallow starting and stop if already on
    if (GetEntProp(client, Prop_Send, "m_bCanUseJetpack") != 0)
        SetEntProp(client, Prop_Send, "m_bCanUseJetpack", 0);

    if (GetEntProp(client, Prop_Send, "m_bJetpacking") != 0)
        SetEntProp(client, Prop_Send, "m_bJetpacking", 0);

    if (pinSecondary)
        PinSecondaryOnActiveWeapon(client);
}

static void PinSecondaryOnActiveWeapon(int client)
{
    int wep = GetEntPropEnt(client, Prop_Send, "m_hActiveWeapon");
    if (wep <= 0 || !IsValidEntity(wep))
        return;

    char cls[64]; GetEntityClassname(wep, cls, sizeof(cls));
    if (!IsPyroWeaponClass(cls))
        return;

    float t = GetGameTime() + 99999.0;
    SetEntPropFloat(wep, Prop_Send, "m_flNextSecondaryAttack", t);
    SetEntPropFloat(wep, Prop_Data, "m_flNextSecondaryAttack", t);
}

// ---------- utility ----------

static bool IsValidLiveClient(int client)
{
    if (client < 1 || client > MaxClients) return false;
    if (!IsClientInGame(client))          return false;
    if (IsClientObserver(client))         return false;
    if (!IsPlayerAlive(client))           return false;
    return true;
}

static bool ModelLooksLikePyro(int client)
{
    char model[PLATFORM_MAX_PATH];
    GetClientModel(client, model, sizeof(model));
    return (StrContains(model, "pyro", false) != -1);
}

static bool ActiveWeaponIsPyroUnique(int client)
{
    int wep = GetEntPropEnt(client, Prop_Send, "m_hActiveWeapon");
    if (wep <= 0 || !IsValidEntity(wep))
        return false;

    char cls[64]; GetEntityClassname(wep, cls, sizeof(cls));
    return IsPyroWeaponClass(cls);
}

static bool IsPyroWeaponClass(const char[] cls)
{
    for (int i = 0; i < sizeof(PYRO_WEAPONS); i++)
        if (StrEqual(cls, PYRO_WEAPONS[i], false))
            return true;
    return false;
}

static void GetActiveWeaponClass(int client, char[] outCls, int maxlen)
{
    outCls[0] = '\0';
    int wep = GetEntPropEnt(client, Prop_Send, "m_hActiveWeapon");
    if (wep > 0 && IsValidEntity(wep))
        GetEntityClassname(wep, outCls, maxlen);
}
