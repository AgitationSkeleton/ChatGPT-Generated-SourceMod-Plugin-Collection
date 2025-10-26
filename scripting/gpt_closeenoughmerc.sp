// gpt_closeenoughmerc.sp — v0.10.5 (targeted fixes per Max)
// Only tf2c.inc + sdkhooks + sdktools.

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <sdktools>
#include <sdkhooks>
#include "tf2c.inc"

// ------------ Cvars ------------
ConVar gCvarEnable;
ConVar gCvarVoDebug;
ConVar gCvarVMType;     // 0=brute m_nModelIndex; 1/2=addon slot type
ConVar gCvarVMDebug;    // 1=log vm path
ConVar gCvarVMInterval; // viewmodel keepalive interval (seconds)

// ------------ Paths ------------
#define VO_PATH                 "vo/customclass/mercenary/%s.wav"
#define MODEL_ARMS_MERC         "models/weapons/c_models/ofmerc/c_merc_arms.mdl"
#define MODEL_ARMS_MERCDM       "models/weapons/c_models/tf2cmerc/c_merc_arms.mdl"

// ------------ State ------------
bool  g_bMercVoice[MAXPLAYERS+1];
bool  g_bBypassVoiceBlock[MAXPLAYERS+1];
bool g_bBotIsGrunt[MAXPLAYERS+1];
Handle g_hArmsTick[MAXPLAYERS+1]; // repeating keepalive timer per client

// ------------ Helpers ------------
static bool IsValidClient(int c) { return (1 <= c && c <= MaxClients) && IsClientInGame(c) && !IsFakeClient(c); }

static bool IsMercModel(const char[] mdl)
{
    if (!mdl[0]) return false;
    return (StrContains(mdl, "mercenary.mdl", false) != -1
         || StrContains(mdl, "merc_deathmatch.mdl", false) != -1
         || StrContains(mdl, "/merc", false) != -1);
}
static void ChooseArmsForModel(const char[] worldModel, char outPath[PLATFORM_MAX_PATH])
{
    outPath[0] = '\0';
    if (StrContains(worldModel, "merc_deathmatch.mdl", false) != -1)
        strcopy(outPath, sizeof outPath, MODEL_ARMS_MERCDM);
    else if (StrContains(worldModel, "mercenary.mdl", false) != -1)
        strcopy(outPath, sizeof outPath, MODEL_ARMS_MERC);
    else if (StrContains(worldModel, "/merc", false) != -1)
        strcopy(outPath, sizeof outPath, MODEL_ARMS_MERC);
}

static void PlayOne(int client, const char[] base)
{
    char path[PLATFORM_MAX_PATH];
    Format(path, sizeof path, VO_PATH, base);
    g_bBypassVoiceBlock[client] = true;                 // let our own VO pass
    EmitSoundToAll(path, client, SNDCHAN_VOICE, SNDLEVEL_TRAFFIC, _, 1.0);
    g_bBypassVoiceBlock[client] = false;
}
static void PlayRand(int client, const char[][] list, int count)
{
    if (count <= 0) return;
    int pick = GetRandomInt(0, count - 1);
    PlayOne(client, list[pick]);
}
static void PrecacheOne(const char[] base)
{
    char path[PLATFORM_MAX_PATH];
    Format(path, sizeof path, VO_PATH, base);
    PrecacheSound(path, true);
}
static void PrecacheSet(const char[][] arr, int count)
{
    for (int i = 0; i < count; i++) PrecacheOne(arr[i]);
}

// ------------ Sound tables ------------
static const char g_medic[][]     = {"mercenary_medic01","mercenary_medic02","mercenary_medic03"};
static const char g_thanks[][]    = {"mercenary_thanks01","mercenary_thanks02"};
static const char g_gogo[][]      = {"mercenary_go01","mercenary_go02","mercenary_go03","mercenary_go04"};
static const char g_moveup[][]    = {"mercenary_moveup01","mercenary_moveup02","mercenary_moveup03"};
static const char g_left[][]      = {"mercenary_headleft01","mercenary_headleft02","mercenary_headleft03"};
static const char g_right[][]     = {"mercenary_headright01","mercenary_headright02","mercenary_headright03"};
static const char g_yes[][]       = {"mercenary_yes01","mercenary_yes02","mercenary_yes03","mercenary_yes04"};
static const char g_no[][]        = {"mercenary_no01","mercenary_no02"};
static const char g_battlecry[][] = {"mercenary_battlecry01","mercenary_battlecry02","mercenary_battlecry03","mercenary_battlecry04","mercenary_battlecry05","mercenary_battlecry06"};

static const char g_helpme[][]    = {"mercenary_helpme01","mercenary_helpme02","mercenary_helpme03"};   // Help!
static const char g_incoming[][]  = {"mercenary_incoming01","mercenary_incoming02","mercenary_incoming03","mercenary_incoming04"};

static const char g_spyGeneric[][] = {"mercenary_cloakedspy01","mercenary_cloakedspy02","mercenary_cloakedspy03","mercenary_cloakedspy04"};

#define SND_NEED_TELE   "mercenary_needteleporter01"
#define SND_NEED_DISP   "mercenary_needdispenser01"
#define SND_NEED_SENTRY "mercenary_needsentry01"

static const char g_positive[][]  = {"mercenary_cheers01","mercenary_cheers02","mercenary_cheers03","mercenary_cheers04","mercenary_cheers05","mercenary_cheers06"};
static const char g_negative[][]  = {"mercenary_negativevocalization01","mercenary_negativevocalization02","mercenary_negativevocalization03","mercenary_negativevocalization04","mercenary_negativevocalization05","mercenary_negativevocalization06"};
static const char g_niceshot[][]  = {"mercenary_niceshot01","mercenary_niceshot02","mercenary_niceshot03"};
static const char g_goodjob[][]   = {"mercenary_goodjob01","mercenary_goodjob02","mercenary_goodjob03"};

static const char g_painSharp[][]  = {"mercenary_painsharp01","mercenary_painsharp02","mercenary_painsharp03","mercenary_painsharp04","mercenary_painsharp05","mercenary_painsharp06","mercenary_painsharp07","mercenary_painsharp08"};
static const char g_painSevere[][] = {"mercenary_painsevere01","mercenary_painsevere02","mercenary_painsevere03","mercenary_painsevere04","mercenary_painsevere05","mercenary_painsevere06"};
static const char g_deathCrit[][]  = {"mercenary_paincrticialdeath01","mercenary_paincrticialdeath02","mercenary_paincrticialdeath03","mercenary_paincrticialdeath04"};
static const char g_respawn[][]    = {"mercenary_respawn01","mercenary_respawn02","mercenary_respawn03","mercenary_respawn04","mercenary_respawn05","mercenary_respawn06",
                                      "mercenary_respawn07","mercenary_respawn08","mercenary_respawn09","mercenary_respawn10","mercenary_respawn11","mercenary_respawn12",
                                      "mercenary_respawn13","mercenary_respawn14","mercenary_respawn15","mercenary_respawn16","mercenary_respawn17","mercenary_respawn18",
                                      "mercenary_respawn19","mercenary_respawn20"};

static const char g_flagTaken[][]  = {"mercenary_autograbbedintelligence01","mercenary_autograbbedintelligence02","mercenary_autograbbedintelligence03"};
static const char g_flagCapped[][] = {"mercenary_autocappedintelligence01","mercenary_autocappedintelligence02","mercenary_autocappedintelligence03"};

// ------------ Sound Hook ------------
public Action SNDHook(int clients[MAXPLAYERS], int &numClients,
                      char sample[PLATFORM_MAX_PATH], int &entity, int &channel,
                      float &volume, int &level, int &pitch, int &flags,
                      char soundEntry[PLATFORM_MAX_PATH], int &seed)
{
    if (!gCvarEnable.BoolValue) return Plugin_Continue;
    if (!(1 <= entity && entity <= MaxClients) || !IsClientInGame(entity)) return Plugin_Continue;
    if (!g_bMercVoice[entity]) return Plugin_Continue;

    // For the three problematic calls + flag barks:
    if (channel == SNDCHAN_VOICE && !g_bBypassVoiceBlock[entity])
    {
        // Case-insensitive substring checks on the Soldier sample name.
        if (StrContains(sample, "autograbbedintelligence", false) != -1)
        { PlayRand(entity, g_flagTaken, sizeof g_flagTaken); return Plugin_Stop; }

        if (StrContains(sample, "autocappedintelligence", false) != -1)
        { PlayRand(entity, g_flagCapped, sizeof g_flagCapped); return Plugin_Stop; }

        if (StrContains(sample, "helpme", false) != -1)
        { PlayRand(entity, g_helpme, sizeof g_helpme); return Plugin_Stop; }

        if (StrContains(sample, "incoming", false) != -1)
        { PlayRand(entity, g_incoming, sizeof g_incoming); return Plugin_Stop; }

        if (StrContains(sample, "battlecry", false) != -1)
        { PlayRand(entity, g_battlecry, sizeof g_battlecry); return Plugin_Stop; }

        // Default: block Soldier VO (other voicemenu cases are handled by our cmd handler)
        return Plugin_Stop;
    }

    // Allow non-voice channels, or our own bypassed emits
    return Plugin_Continue;
}

// ------------ Voicemenu (only tweak the three you flagged) ------------
public Action Cmd_VoiceMenu(int client, int args)
{
    if (!gCvarEnable.BoolValue || !IsValidClient(client)) return Plugin_Handled;
    if (!g_bMercVoice[client]) return Plugin_Continue;

    int cat = GetCmdArgInt(1);
    int cmd = GetCmdArgInt(2);
    if (gCvarVoDebug.BoolValue) PrintToServer("[merc] vm cat=%d cmd=%d from #%d", cat, cmd, client);

    switch (cat)
    {
        case 0:
        {
            switch (cmd)
            {
                case 0: { PlayRand(client, g_medic, sizeof g_medic); return Plugin_Handled; }      // Medic!
                case 1: { PlayRand(client, g_thanks, sizeof g_thanks); return Plugin_Handled; }    // Thanks!
                case 2: { PlayRand(client, g_gogo, sizeof g_gogo); return Plugin_Handled; }        // Go Go Go!
                case 3: { PlayRand(client, g_moveup, sizeof g_moveup); return Plugin_Handled; }    // Move Up!
                case 4: { PlayRand(client, g_left, sizeof g_left); return Plugin_Handled; }        // Go Left
                case 5: { PlayRand(client, g_right, sizeof g_right); return Plugin_Handled; }      // Go Right
                case 6: { PlayRand(client, g_yes, sizeof g_yes); return Plugin_Handled; }          // Yes
                case 7: { PlayRand(client, g_no,  sizeof g_no ); return Plugin_Handled; }          // No
                case 8:
                {
                    // Let engine fire Soldier battlecry so SNDHook can map → Merc (was silent on your build)
                    return Plugin_Continue;
                }
            }
            return Plugin_Handled;
        }

        case 1:
        {
            switch (cmd)
            {
                case 0:
                {
                    // Let engine fire Soldier Help so SNDHook can map → Merc (was silent on your build)
                    return Plugin_Continue;
                }
                case 1: { PlayRand(client, g_spyGeneric, sizeof g_spyGeneric); return Plugin_Handled; } // Spy! (generic)
				case 2: { PlayRand(client, g_incoming, sizeof g_incoming); return Plugin_Handled; } // Sentry Ahead! → Incoming lines
                case 3: { PlayOne(client, SND_NEED_TELE);   return Plugin_Handled; } // Teleporter here
                case 4: { PlayOne(client, SND_NEED_DISP);   return Plugin_Handled; } // Dispenser here
                case 5: { PlayOne(client, SND_NEED_SENTRY); return Plugin_Handled; } // Sentry here
                case 6: { PlayRand(client, g_gogo, sizeof g_gogo); return Plugin_Handled; }          // Activate Charge
            }
            return Plugin_Handled;
        }

        case 2:
        {
            // Leave your previously-working emotes as-is
            switch (cmd)
            {
				case 0: { PlayRand(client, g_helpme, sizeof g_helpme); return Plugin_Handled; } // Help!
				case 1: { PlayRand(client, g_battlecry, sizeof g_battlecry); return Plugin_Handled; } // Battle Cry
                case 2: { PlayRand(client, g_positive, sizeof g_positive); return Plugin_Handled; } // Cheers
                case 3: { PlayRand(client, g_negative, sizeof g_negative); return Plugin_Handled; } // Jeers
                case 4: { PlayRand(client, g_positive, sizeof g_positive); return Plugin_Handled; } // Positive
                case 5: { PlayRand(client, g_negative, sizeof g_negative); return Plugin_Handled; } // Negative
                case 6: { PlayRand(client, g_niceshot, sizeof g_niceshot); return Plugin_Handled; } // Nice Shot
                case 7: { PlayRand(client, g_goodjob, sizeof g_goodjob);   return Plugin_Handled; } // Good Job
            }
            return Plugin_Handled;
        }
    }

    return Plugin_Handled;
}

// ------------ Events ------------
static void TryHookEvent(const char[] name, EventHook hook, EventHookMode mode)
{
    HookEventEx(name, hook, mode); // safe on TF2C
}

public void OnPluginStart()
{
    gCvarEnable   = CreateConVar("sm_closemerc_enable",  "1", "Enable Merc VO + arms.", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gCvarVoDebug  = CreateConVar("sm_closemerc_vodebug", "0", "Log voicemenu cat/cmd", FCVAR_NONE, true, 0.0, true, 1.0);
    gCvarVMType   = CreateConVar("sm_closemerc_vmtype",  "1", "CTFViewModel addon type (0=brute,1/2=addon).", FCVAR_NONE, true, 0.0, true, 3.0);
    gCvarVMDebug  = CreateConVar("sm_closemerc_vmdebug", "0", "Log viewmodel path", FCVAR_NONE, true, 0.0, true, 1.0);
    gCvarVMInterval = CreateConVar("sm_closemerc_vminterval", "0.10", "Arms keepalive interval (sec).", FCVAR_NONE, true, 0.05, true, 0.50);

    AddNormalSoundHook(SNDHook);
	AddNormalSoundHook(BotMercSNDHook);
	AddCommandListener(GruntListener, "sm_grunt");
    RegConsoleCmd("voicemenu", Cmd_VoiceMenu);

    HookEvent("player_spawn", Evt_PlayerSpawn, EventHookMode_Post);
    HookEvent("player_death", Evt_PlayerDeath, EventHookMode_Post);
    HookEvent("player_hurt",  Evt_PlayerHurt,  EventHookMode_Post);   // pain via event (revert)

    // Flag barks: TF2C generic + optional TF2 names
    HookEvent("teamplay_flag_event", Evt_FlagGeneric, EventHookMode_Post);
    TryHookEvent("ctf_flag_pickup",    Evt_FlagPickup,   EventHookMode_Post);
    TryHookEvent("ctf_flag_captured",  Evt_FlagCaptured, EventHookMode_Post);

    PrecacheAll();
    PrecacheModel(MODEL_ARMS_MERC, true);
    PrecacheModel(MODEL_ARMS_MERCDM, true);
}

public void OnClientPutInServer(int client)
{
    if (!IsValidClient(client)) return;
    SDKHook(client, SDKHook_SpawnPost, Hook_SpawnPost);
    SDKHook(client, SDKHook_WeaponSwitchPost, OnWeaponSwitchPost);
}
public void OnClientDisconnect(int client)
{
    g_bMercVoice[client] = false;
    SDKUnhook(client, SDKHook_SpawnPost, Hook_SpawnPost);
    SDKUnhook(client, SDKHook_WeaponSwitchPost, OnWeaponSwitchPost);
    if (g_hArmsTick[client] != null) { CloseHandle(g_hArmsTick[client]); g_hArmsTick[client] = null; }
}

public void Evt_PlayerSpawn(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client)) return;

    // Keep the arms timer running across lives
    StartArmsKeepalive(client);

    // Decide voice mode based on model
    char mdl[PLATFORM_MAX_PATH];
    GetClientModel(client, mdl, sizeof mdl);
    g_bMercVoice[client] = IsMercModel(mdl);

    if (g_bMercVoice[client])
    {
        if (GetRandomInt(0, 3) == 0) PlayRand(client, g_respawn, sizeof g_respawn);
    }
}
public void Hook_SpawnPost(int client)
{
    if (!IsValidClient(client)) return;
    StartArmsKeepalive(client);
}
public void OnWeaponSwitchPost(int client, int weapon)
{
    if (!IsValidClient(client)) return;
    ApplyArmsOnce(client, false); // immediate reassert on weapon change
}

// Death & Pain
public void Evt_PlayerDeath(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client) || !g_bMercVoice[client]) return;
    PlayRand(client, g_deathCrit, sizeof g_deathCrit);
}
// Pain (reverted to event-based like your old plugin)
public void Evt_PlayerHurt(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client) || !g_bMercVoice[client]) return;

    int dmg = event.GetInt("damageamount");
    if (dmg <= 0) return;

    static float nextPain[MAXPLAYERS+1];
    float now = GetEngineTime();
    if (now < nextPain[client]) return;

    g_bBypassVoiceBlock[client] = true;
    if (dmg >= 35) PlayRand(client, g_painSevere, sizeof g_painSevere);
    else           PlayRand(client, g_painSharp,  sizeof g_painSharp);
    g_bBypassVoiceBlock[client] = false;

    nextPain[client] = now + 1.5;
}

// Flag barks (support multiple field names so pickup fires)
public void Evt_FlagGeneric(Event event, const char[] name, bool dontBroadcast)
{
    int type = event.GetInt("eventtype", -1); // 1=pickup, 2=capture (TF2 convention)
    int userid = event.GetInt("player");
    if (userid == 0) userid = event.GetInt("userid");
    if (userid == 0) userid = event.GetInt("capper");
    if (userid == 0) userid = event.GetInt("owner");

    int client = GetClientOfUserId(userid);
    if (!IsValidClient(client) || !g_bMercVoice[client]) return;

    if (type == 1)      PlayRand(client, g_flagTaken, sizeof g_flagTaken);
    else if (type == 2) PlayRand(client, g_flagCapped, sizeof g_flagCapped);
}
public void Evt_FlagPickup(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("userid"));
    if (!IsValidClient(client) || !g_bMercVoice[client]) return;
    PlayRand(client, g_flagTaken, sizeof g_flagTaken);
}
public void Evt_FlagCaptured(Event event, const char[] name, bool dontBroadcast)
{
    int client = GetClientOfUserId(event.GetInt("capper"));
    if (!IsValidClient(client) || !g_bMercVoice[client]) return;
    PlayRand(client, g_flagCapped, sizeof g_flagCapped);
}

// Bot-only VO remap/mute for merc-model bots.
public Action BotMercSNDHook(int clients[MAXPLAYERS], int &numClients,
                             char sample[PLATFORM_MAX_PATH], int &entity, int &channel,
                             float &volume, int &level, int &pitch, int &flags,
                             char soundEntry[PLATFORM_MAX_PATH], int &seed)
{
    // Only bots
    if (!(1 <= entity && entity <= MaxClients) || !IsClientInGame(entity) || !IsFakeClient(entity))
        return Plugin_Continue;

	// Treat as Merc if bot is using a merc model OR we saw "sm_grunt 1" run on it
	bool isMercModel = false;
	char mdl[PLATFORM_MAX_PATH];
	GetClientModel(entity, mdl, sizeof mdl);
	if (mdl[0] != '\0')
	{
		if (StrContains(mdl, "mercenary.mdl", false) != -1
		|| StrContains(mdl, "merc_deathmatch.mdl", false) != -1
		|| StrContains(mdl, "/merc", false) != -1)
		{
			isMercModel = true;
		}
	}
	
	if (!isMercModel && !g_bBotIsGrunt[entity])
    return Plugin_Continue;

    // Only voice channel
    if (channel != SNDCHAN_VOICE)
        return Plugin_Continue;

    // Key to match on: prefer soundscript (soundEntry), fall back to file path (sample)
    char key[PLATFORM_MAX_PATH];
    if (soundEntry[0] != '\0') strcopy(key, sizeof key, soundEntry);
    else                       strcopy(key, sizeof key, sample);

    // Lowercase for case-insensitive checks
    for (int i = 0; key[i] != '\0'; i++) if (key[i] >= 'A' && key[i] <= 'Z') key[i] += 32;

    // ----- CTF intel auto lines -----
    if (StrContains(key, "autograbbedintelligence", false) != -1) { PlayRand(entity, g_flagTaken,  sizeof g_flagTaken);  return Plugin_Stop; }
    if (StrContains(key, "autocappedintelligence",  false) != -1) { PlayRand(entity, g_flagCapped, sizeof g_flagCapped); return Plugin_Stop; }

    // ----- Tactical -----
    if (StrContains(key, "help", false) != -1 || StrContains(key, "helpme", false) != -1)
        { PlayRand(entity, g_helpme, sizeof g_helpme); return Plugin_Stop; }

    // Spy identification (cover more variants/class-specific IDs)
    if (StrContains(key, "spy", false) != -1
     || StrContains(key, "cloakedspy", false) != -1
     || StrContains(key, "is_a_spy", false) != -1
     || StrContains(key, "is-spy", false) != -1
     || StrContains(key, "is_spy", false) != -1
     || StrContains(key, "spy_scout", false) != -1
     || StrContains(key, "spy_soldier", false) != -1
     || StrContains(key, "spy_pyro", false) != -1
     || StrContains(key, "spy_demoman", false) != -1
     || StrContains(key, "spy_heavy", false) != -1
     || StrContains(key, "spy_engineer", false) != -1
     || StrContains(key, "spy_medic", false) != -1
     || StrContains(key, "spy_sniper", false) != -1
     || StrContains(key, "spy_spy", false) != -1
     || StrContains(key, "spy_civilian", false) != -1)
        { PlayRand(entity, g_spyGeneric, sizeof g_spyGeneric); return Plugin_Stop; }

    // Sentry ahead → incoming
    if (StrContains(key, "incoming", false) != -1
     || StrContains(key, "sentry_ahead", false) != -1)
        { PlayRand(entity, g_incoming, sizeof g_incoming); return Plugin_Stop; }

    if (StrContains(key, "teleporter_here", false) != -1 || StrContains(key, "needteleporter", false) != -1)
        { PlayOne(entity, SND_NEED_TELE); return Plugin_Stop; }
    if (StrContains(key, "dispenser_here", false) != -1 || StrContains(key, "needdispenser", false) != -1)
        { PlayOne(entity, SND_NEED_DISP); return Plugin_Stop; }
    if (StrContains(key, "sentry_here", false) != -1 || StrContains(key, "needsentry", false) != -1)
        { PlayOne(entity, SND_NEED_SENTRY); return Plugin_Stop; }

    // ----- Core -----
    if (StrContains(key, "medic", false) != -1)
        { PlayRand(entity, g_medic, sizeof g_medic); return Plugin_Stop; }

    if (StrContains(key, "thanks", false) != -1)
        { PlayRand(entity, g_thanks, sizeof g_thanks); return Plugin_Stop; }

    if (StrContains(key, "gogogo", false) != -1 || StrContains(key, "go!", false) != -1 || StrContains(key, "go_go_go", false) != -1)
        { PlayRand(entity, g_gogo, sizeof g_gogo); return Plugin_Stop; }

    if (StrContains(key, "moveup", false) != -1)
        { PlayRand(entity, g_moveup, sizeof g_moveup); return Plugin_Stop; }

    if (StrContains(key, "headleft", false) != -1 || StrContains(key, "go_left", false) != -1)
        { PlayRand(entity, g_left, sizeof g_left); return Plugin_Stop; }

    if (StrContains(key, "headright", false) != -1 || StrContains(key, "go_right", false) != -1)
        { PlayRand(entity, g_right, sizeof g_right); return Plugin_Stop; }

    if (StrContains(key, "yes", false) != -1)
        { PlayRand(entity, g_yes, sizeof g_yes); return Plugin_Stop; }

    if (StrContains(key, "no", false) != -1)
        { PlayRand(entity, g_no, sizeof g_no); return Plugin_Stop; }

    if (StrContains(key, "battlecry", false) != -1)
        { PlayRand(entity, g_battlecry, sizeof g_battlecry); return Plugin_Stop; }

    // ----- Emotes / compliments -----
    if (StrContains(key, "cheers", false) != -1 || StrContains(key, "positive", false) != -1)
        { PlayRand(entity, g_positive, sizeof g_positive); return Plugin_Stop; }

    if (StrContains(key, "jeers", false) != -1 || StrContains(key, "negative", false) != -1 || StrContains(key, "boo", false) != -1)
        { PlayRand(entity, g_negative, sizeof g_negative); return Plugin_Stop; }

    if (StrContains(key, "niceshot", false) != -1)
        { PlayRand(entity, g_niceshot, sizeof g_niceshot); return Plugin_Stop; }

    if (StrContains(key, "goodjob", false) != -1)
        { PlayRand(entity, g_goodjob, sizeof g_goodjob); return Plugin_Stop; }

    // ----- Activate Charge → Go Go Go -----
    if (StrContains(key, "activatecharge", false) != -1 || StrContains(key, "activate_uber", false) != -1)
        { PlayRand(entity, g_gogo, sizeof g_gogo); return Plugin_Stop; }

    // ----- Ubercharged threats (Soldier “invincible” set etc.) -----
    if (StrContains(key, "invincible", false) != -1
     || StrContains(key, "uber", false) != -1
     || StrContains(key, "charged", false) != -1
     || StrContains(key, "kritz", false) != -1)
        { PlayRand(entity, g_battlecry, sizeof g_battlecry); return Plugin_Stop; }

    // ----- Death FIRST (before pain; Soldier scripts contain "pain" in death tokens) -----
    if (StrContains(key, "criticaldeath", false) != -1
     || StrContains(key, "crit_death", false) != -1
     || StrContains(key, "paincriticaldeath", false) != -1      // common Soldier token
     || StrContains(key, "paincrticialdeath", false) != -1       // TF2C misspelling seen in files
     || StrContains(key, "death", false) != -1)
        { PlayRand(entity, g_deathCrit, sizeof g_deathCrit); return Plugin_Stop; }

    // ----- Pain AFTER death -----
    if (StrContains(key, "painsevere", false) != -1)
        { PlayRand(entity, g_painSevere, sizeof g_painSevere); return Plugin_Stop; }

    if (StrContains(key, "painsharp", false) != -1
     || StrContains(key, "pain", false) != -1)
        { PlayRand(entity, g_painSharp, sizeof g_painSharp); return Plugin_Stop; }

    // Anything else Soldier tried to say → mute per your instruction.
    return Plugin_Stop;

}

public Action GruntListener(int client, const char[] command, int argc)
{
    // We only care if a BOT runs "sm_grunt ..."
    if (!(1 <= client && client <= MaxClients) || !IsClientInGame(client) || !IsFakeClient(client))
        return Plugin_Continue;

    // Read the first arg ("1" to enable, "0" to disable), default to 1 if omitted
    char arg[8];
    GetCmdArg(1, arg, sizeof arg);

    bool enable = (arg[0] == '\0') ? true : (StringToInt(arg) != 0);
    g_bBotIsGrunt[client] = enable;

    return Plugin_Continue; // don’t block the real command
}

// ------------ Viewmodel control ------------
static bool ApplyArmsOnce(int client, bool logit)
{
    // Always check current model and pick arms accordingly.
    char worldMdl[PLATFORM_MAX_PATH];
    GetClientModel(client, worldMdl, sizeof worldMdl);
    if (!IsMercModel(worldMdl)) return false;

    char arms[PLATFORM_MAX_PATH];
    ChooseArmsForModel(worldMdl, arms);
    if (!arms[0]) return false;

    int idx = PrecacheModel(arms, true);
    if (idx <= 0) return false;

    bool applied = false;

    for (int i = 0; i < 2; i++)
    {
        int vm = GetEntPropEnt(client, Prop_Send, "m_hViewModel", i); // sendprop array
        if (vm <= MaxClients || !IsValidEntity(vm)) continue;

        char cls[64]; GetEntityClassname(vm, cls, sizeof cls);

        if (FindSendPropInfo("CTFViewModel", "m_iViewModelAddonModelIndex") > 0 &&
            FindSendPropInfo("CTFViewModel", "m_iViewModelType") > 0 &&
            gCvarVMType.IntValue > 0)
        {
            SetEntProp(vm, Prop_Send, "m_iViewModelAddonModelIndex", idx);
            SetEntProp(vm, Prop_Send, "m_iViewModelType", gCvarVMType.IntValue);
            applied = true;
            if (logit && gCvarVMDebug.BoolValue)
                LogMessage("[merc] VM slot %d (%s): addon=%d type=%d", i, cls, idx, gCvarVMType.IntValue);
        }
        else if (FindSendPropInfo("CBaseAnimating", "m_nModelIndex") > 0)
        {
            SetEntProp(vm, Prop_Send, "m_nModelIndex", idx);
            applied = true;
            if (logit && gCvarVMDebug.BoolValue)
                LogMessage("[merc] VM slot %d (%s): brute m_nModelIndex=%d", i, cls, idx);
        }
    }

    return applied;
}

static void StartArmsKeepalive(int client)
{
    ApplyArmsOnce(client, true);

    if (g_hArmsTick[client] != null) return;
    float iv = gCvarVMInterval.FloatValue; if (iv < 0.05) iv = 0.05;
    g_hArmsTick[client] = CreateTimer(iv, Timer_ArmsTick, GetClientUserId(client), TIMER_REPEAT|TIMER_FLAG_NO_MAPCHANGE);
}
public Action Timer_ArmsTick(Handle timer, any userid)
{
    int client = GetClientOfUserId(userid);
    if (!IsValidClient(client)) return Plugin_Stop;

    ApplyArmsOnce(client, false); // only applies when on merc model
    return Plugin_Continue;
}

// ------------ Precaching ------------
static void PrecacheAll()
{
    PrecacheSet(g_medic, sizeof g_medic);
    PrecacheSet(g_thanks, sizeof g_thanks);
    PrecacheSet(g_gogo, sizeof g_gogo);
    PrecacheSet(g_moveup, sizeof g_moveup);
    PrecacheSet(g_left, sizeof g_left);
    PrecacheSet(g_right, sizeof g_right);
    PrecacheSet(g_yes, sizeof g_yes);
    PrecacheSet(g_no, sizeof g_no);
    PrecacheSet(g_battlecry, sizeof g_battlecry);

    PrecacheSet(g_helpme, sizeof g_helpme);
    PrecacheSet(g_incoming, sizeof g_incoming);
    PrecacheSet(g_spyGeneric, sizeof g_spyGeneric);

    PrecacheSet(g_positive, sizeof g_positive);
    PrecacheSet(g_negative, sizeof g_negative);
    PrecacheSet(g_niceshot, sizeof g_niceshot);
    PrecacheSet(g_goodjob, sizeof g_goodjob);

    PrecacheSet(g_painSharp, sizeof g_painSharp);
    PrecacheSet(g_painSevere, sizeof g_painSevere);
    PrecacheSet(g_deathCrit, sizeof g_deathCrit);
    PrecacheSet(g_respawn, sizeof g_respawn);

    PrecacheSet(g_flagTaken, sizeof g_flagTaken);
    PrecacheSet(g_flagCapped, sizeof g_flagCapped);

    PrecacheOne(SND_NEED_TELE);
    PrecacheOne(SND_NEED_DISP);
    PrecacheOne(SND_NEED_SENTRY);
}

// === Downloads table support (no behavior changes elsewhere) ===
static void DL_Add(const char[] path)
{
    AddFileToDownloadsTable(path);
}

static void DL_AddMercArmModels()
{
    // TF2C merc arms (per your file list)   TF2C merc dir
    DL_Add("models/weapons/c_models/tf2cmerc/c_merc_arms.mdl");
    DL_Add("models/weapons/c_models/tf2cmerc/c_merc_arms.vvd");
    DL_Add("models/weapons/c_models/tf2cmerc/c_merc_arms.dx90.vtx");
    DL_Add("models/weapons/c_models/tf2cmerc/c_merc_arms.dx80.vtx");
    DL_Add("models/weapons/c_models/tf2cmerc/c_merc_arms.sw.vtx");

    // OF merc arms (per your file list)   OF merc dir
    DL_Add("models/weapons/c_models/ofmerc/c_merc_arms.mdl");
    DL_Add("models/weapons/c_models/ofmerc/c_merc_arms.vvd");
    DL_Add("models/weapons/c_models/ofmerc/c_merc_arms.dx90.vtx");

    // If you have .phy or other vtx variants, add them here as well.
    // If you ship materials for these arms, add them too, e.g.:
    // DL_Add("materials/models/weapons/c_models/ofmerc/your_vmt_or_vtf");
    // DL_Add("materials/models/weapons/c_models/tf2cmerc/your_vmt_or_vtf");
}

static void DL_AddOneSound(const char[] base) // base = "mercenary_*.wav" without folder
{
    char p[PLATFORM_MAX_PATH];
    // Your sounds live at: sound/vo/customclass/mercenary/<base>.wav
    Format(p, sizeof p, "sound/vo/customclass/mercenary/%s.wav", base);
    AddFileToDownloadsTable(p);
}

// Adds every sound from your existing arrays to the download table.
// Note: duplicates are fine; the engine dedupes.
static void DL_AddAllMercSounds()
{
    // Keep this in sync with your arrays used for playback.
    static const char g_all[][] = {
        // medic
        "mercenary_medic01","mercenary_medic02","mercenary_medic03",
        // thanks
        "mercenary_thanks01","mercenary_thanks02",
        // go go go
        "mercenary_go01","mercenary_go02","mercenary_go03","mercenary_go04",
        // move up
        "mercenary_moveup01","mercenary_moveup02","mercenary_moveup03",
        // left/right
        "mercenary_headleft01","mercenary_headleft02","mercenary_headleft03",
        "mercenary_headright01","mercenary_headright02","mercenary_headright03",
        // yes/no
        "mercenary_yes01","mercenary_yes02","mercenary_yes03","mercenary_yes04",
        "mercenary_no01","mercenary_no02",
        // battle cry
        "mercenary_battlecry01","mercenary_battlecry02","mercenary_battlecry03",
        "mercenary_battlecry04","mercenary_battlecry05","mercenary_battlecry06",
        // help / incoming / spy generic
        "mercenary_helpme01","mercenary_helpme02","mercenary_helpme03",
        "mercenary_incoming01","mercenary_incoming02","mercenary_incoming03","mercenary_incoming04",
        "mercenary_cloakedspy01","mercenary_cloakedspy02","mercenary_cloakedspy03","mercenary_cloakedspy04",
        // “need building” singles
        "mercenary_needteleporter01","mercenary_needdispenser01","mercenary_needsentry01",
        // positive/negative
        "mercenary_cheers01","mercenary_cheers02","mercenary_cheers03","mercenary_cheers04","mercenary_cheers05","mercenary_cheers06",
        "mercenary_negativevocalization01","mercenary_negativevocalization02","mercenary_negativevocalization03",
        "mercenary_negativevocalization04","mercenary_negativevocalization05","mercenary_negativevocalization06",
        // compliments
        "mercenary_niceshot01","mercenary_niceshot02","mercenary_niceshot03",
        "mercenary_goodjob01","mercenary_goodjob02","mercenary_goodjob03",
        // pain / death / respawn
        "mercenary_painsharp01","mercenary_painsharp02","mercenary_painsharp03","mercenary_painsharp04",
        "mercenary_painsharp05","mercenary_painsharp06","mercenary_painsharp07","mercenary_painsharp08",
        "mercenary_painsevere01","mercenary_painsevere02","mercenary_painsevere03",
        "mercenary_painsevere04","mercenary_painsevere05","mercenary_painsevere06",
        "mercenary_paincrticialdeath01","mercenary_paincrticialdeath02","mercenary_paincrticialdeath03","mercenary_paincrticialdeath04",
        "mercenary_respawn01","mercenary_respawn02","mercenary_respawn03","mercenary_respawn04","mercenary_respawn05","mercenary_respawn06",
        "mercenary_respawn07","mercenary_respawn08","mercenary_respawn09","mercenary_respawn10","mercenary_respawn11","mercenary_respawn12",
        "mercenary_respawn13","mercenary_respawn14","mercenary_respawn15","mercenary_respawn16","mercenary_respawn17","mercenary_respawn18",
        "mercenary_respawn19","mercenary_respawn20",
        // intel lines
        "mercenary_autograbbedintelligence01","mercenary_autograbbedintelligence02","mercenary_autograbbedintelligence03",
        "mercenary_autocappedintelligence01","mercenary_autocappedintelligence02","mercenary_autocappedintelligence03"
    };

    for (int i = 0; i < sizeof(g_all); i++)
        DL_AddOneSound(g_all[i]);
}

// Call from OnMapStart so the table is ready before clients connect
public void OnMapStart()
{
    DL_AddMercArmModels();
    DL_AddAllMercSounds();
}
