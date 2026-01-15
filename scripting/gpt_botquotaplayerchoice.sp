// gpt_playerchoicebotquota.sp
#include <sourcemod>

#define PLUGIN_NAME    "Player Choice Bot Quota"
#define PLUGIN_AUTHOR  "ChatGPT"
#define PLUGIN_VERSION "1.3"

public Plugin myinfo =
{
    name = PLUGIN_NAME,
    author = PLUGIN_AUTHOR,
    description = "Allows players to control tf_bot_quota via chat/console commands",
    version = PLUGIN_VERSION
};

const int MIN_BOTS = 0;

ConVar g_cvarBotQuota;
ConVar g_cvarVisibleMaxPlayers;
ConVar g_cvarMaxPlayers;

int g_maxBots = 30; // runtime computed

public void OnPluginStart()
{
    g_cvarBotQuota = FindConVar("tf_bot_quota");
    if (g_cvarBotQuota == null)
    {
        SetFailState("Could not find ConVar tf_bot_quota. Is this a TF2 server?");
        return;
    }

    // Prefer visible maxplayers if available
    g_cvarVisibleMaxPlayers = FindConVar("sv_visiblemaxplayers");
    g_cvarMaxPlayers        = FindConVar("sv_maxplayers");

    if (g_cvarVisibleMaxPlayers != null)
        g_cvarVisibleMaxPlayers.AddChangeHook(OnMaxPlayersCvarChanged);

    if (g_cvarMaxPlayers != null)
        g_cvarMaxPlayers.AddChangeHook(OnMaxPlayersCvarChanged);

    RecalcMaxBots();

    // Main commands
    RegConsoleCmd("sm_bots", Command_SetBots);
    RegConsoleCmd("sm_addbot", Command_AddBot);
    RegConsoleCmd("sm_botadd", Command_AddBot);
    RegConsoleCmd("sm_removebot", Command_RemoveBot);
    RegConsoleCmd("sm_kickbot", Command_RemoveBot);
    RegConsoleCmd("sm_botremove", Command_RemoveBot);
    RegConsoleCmd("sm_botkick", Command_RemoveBot);

    // Requested aliases for sm_bots
    RegConsoleCmd("sm_bot", Command_SetBots);
    RegConsoleCmd("sm_setbots", Command_SetBots);

    // Extra likely aliases for "set bots"
    RegConsoleCmd("sm_botcount", Command_SetBots);
    RegConsoleCmd("sm_botquota", Command_SetBots);
    RegConsoleCmd("sm_setbot", Command_SetBots);
    RegConsoleCmd("sm_setbotcount", Command_SetBots);
    RegConsoleCmd("sm_setbotquota", Command_SetBots);
    RegConsoleCmd("sm_botscount", Command_SetBots);
    RegConsoleCmd("sm_quota", Command_SetBots);

    // Requested chat-ish aliases as console cmds too
    RegConsoleCmd("sm_tf_bot_add", Command_AddBot);
    RegConsoleCmd("sm_tf_bot_kick", Command_RemoveBot);
    RegConsoleCmd("sm_tf_bot_remove", Command_RemoveBot);

    // Extra likely aliases for add/remove
    RegConsoleCmd("sm_bot+", Command_AddBot);
    RegConsoleCmd("sm_plusbot", Command_AddBot);
    RegConsoleCmd("sm_botadd1", Command_AddBot);

    RegConsoleCmd("sm_killbot", Command_RemoveBot);
    RegConsoleCmd("sm_minusbot", Command_RemoveBot);
    RegConsoleCmd("sm_bot-", Command_RemoveBot);
    RegConsoleCmd("sm_removebots", Command_RemoveBot);
    RegConsoleCmd("sm_kickbots", Command_RemoveBot);

    // Chat parsing
    AddCommandListener(SayListener, "say");
    AddCommandListener(SayListener, "say_team");
}

public void OnConfigsExecuted()
{
    // In case maxplayers cvars are set by cfg after plugin loads.
    RecalcMaxBots();
}

public void OnMapStart()
{
    RecalcMaxBots();
}

public void OnMaxPlayersCvarChanged(ConVar convar, const char[] oldValue, const char[] newValue)
{
    RecalcMaxBots();
}

static void RecalcMaxBots()
{
    int maxPlayers = 0;

    // sv_visiblemaxplayers is usually what admins actually use to control population
    if (g_cvarVisibleMaxPlayers != null)
        maxPlayers = g_cvarVisibleMaxPlayers.IntValue;

    // If it's not set / is 0, fall back
    if (maxPlayers <= 0 && g_cvarMaxPlayers != null)
        maxPlayers = g_cvarMaxPlayers.IntValue;

    // Final fallback to slot count
    if (maxPlayers <= 0)
        maxPlayers = MaxClients;

    int computed = maxPlayers - 2;

    // Never allow more bots than actual available slots - 2
    int slotsCap = MaxClients - 2;

    if (computed < 0) computed = 0;
    if (slotsCap < 0) slotsCap = 0;
    if (computed > slotsCap) computed = slotsCap;

    g_maxBots = computed;
}

public Action Command_SetBots(int client, int args)
{
    if (args != 1)
    {
        ReplyToCommand(client, "Usage: !bots <0-%d>", g_maxBots);
        return Plugin_Handled;
    }

    char arg[16];
    GetCmdArg(1, arg, sizeof(arg));
    int quota = StringToInt(arg);

    if (quota < MIN_BOTS || quota > g_maxBots)
    {
        ReplyToCommand(client, "Bot quota must be between %d and %d.", MIN_BOTS, g_maxBots);
        return Plugin_Handled;
    }

    SetBotQuota(quota);
    ReplyToCommand(client, "Bot quota set to %d.", quota);
    return Plugin_Handled;
}

public Action Command_AddBot(int client, int args)
{
    int current = GetBotQuota();
    if (current < g_maxBots)
    {
        SetBotQuota(current + 1);
        ReplyToCommand(client, "Increased bot quota to %d.", current + 1);
    }
    else
    {
        ReplyToCommand(client, "Bot quota already at maximum (%d).", g_maxBots);
    }
    return Plugin_Handled;
}

public Action Command_RemoveBot(int client, int args)
{
    int current = GetBotQuota();
    if (current > MIN_BOTS)
    {
        SetBotQuota(current - 1);
        ReplyToCommand(client, "Decreased bot quota to %d.", current - 1);
    }
    else
    {
        ReplyToCommand(client, "Bot quota already at minimum (%d). No bots to remove.", MIN_BOTS);
    }
    return Plugin_Handled;
}

int GetBotQuota()
{
    return g_cvarBotQuota.IntValue;
}

void SetBotQuota(int quota)
{
    // Enforce max even if someone tries to set tf_bot_quota via rcon/etc
    if (quota > g_maxBots) quota = g_maxBots;
    if (quota < MIN_BOTS)  quota = MIN_BOTS;

    g_cvarBotQuota.IntValue = quota;
}

static bool IsHumanClient(int client)
{
    return (client > 0 && client <= MaxClients && IsClientInGame(client) && !IsFakeClient(client));
}

public Action SayListener(int client, const char[] command, int args)
{
    if (!IsHumanClient(client))
        return Plugin_Continue;

    char raw[256];
    GetCmdArgString(raw, sizeof(raw));

    StripQuotes(raw);
    TrimString(raw);

    if (raw[0] == '\0')
        return Plugin_Continue;

    // Allow leading ! or /, but also allow no prefix at all.
    char msg[256];
    strcopy(msg, sizeof(msg), raw);

    if (msg[0] == '!' || msg[0] == '/')
    {
        strcopy(msg, sizeof(msg), msg[1]);
        TrimString(msg);
        if (msg[0] == '\0')
            return Plugin_Continue;
    }

    // Tokenize: supports separators space, '=', ':'
    char cmdWord[64];
    char argWord[64];

    cmdWord[0] = '\0';
    argWord[0] = '\0';

    SplitFirstToken(msg, cmdWord, sizeof(cmdWord), argWord, sizeof(argWord));
    ToLowerInPlace(cmdWord);
    ToLowerInPlace(argWord);

    if (IsSetQuotaAlias(cmdWord))
    {
        if (argWord[0] == '\0')
        {
            ReplyToCommand(client, "Usage: bots <0-%d>   (also supports: cmd=12, cmd:12)", g_maxBots);
            return Plugin_Handled;
        }

        int quota = StringToInt(argWord);
        if (quota < MIN_BOTS || quota > g_maxBots)
        {
            ReplyToCommand(client, "Bot quota must be between %d and %d.", MIN_BOTS, g_maxBots);
            return Plugin_Handled;
        }

        SetBotQuota(quota);
        ReplyToCommand(client, "Bot quota set to %d.", quota);
        return Plugin_Handled;
    }

    if (IsAddAlias(cmdWord))
    {
        Command_AddBot(client, 0);
        return Plugin_Handled;
    }

    if (IsRemoveAlias(cmdWord))
    {
        Command_RemoveBot(client, 0);
        return Plugin_Handled;
    }

    return Plugin_Continue;
}

// Supports:
// "tf_bot_quota 12"
// "tf_bot_quota=12" or "tf_bot_quota = 12"
// "tf_bot_quota:12" or "tf_bot_quota: 12"
static void SplitFirstToken(const char[] text, char[] outCmd, int outCmdLen, char[] outArg, int outArgLen)
{
    outCmd[0] = '\0';
    outArg[0] = '\0';

    int len = strlen(text);
    int i = 0;

    while (i < len && text[i] == ' ')
        i++;

    if (i >= len)
        return;

    int cmdStart = i;
    while (i < len && text[i] != ' ' && text[i] != '=' && text[i] != ':')
        i++;

    int cmdCount = i - cmdStart;
    if (cmdCount <= 0)
        return;

    if (cmdCount >= outCmdLen)
        cmdCount = outCmdLen - 1;

    strcopy(outCmd, outCmdLen, text[cmdStart]);
    outCmd[cmdCount] = '\0';

    while (i < len && (text[i] == ' ' || text[i] == '=' || text[i] == ':'))
        i++;
    while (i < len && text[i] == ' ')
        i++;

    if (i >= len)
        return;

    int argStart = i;
    while (i < len && text[i] != ' ')
        i++;

    int argCount = i - argStart;
    if (argCount <= 0)
        return;

    if (argCount >= outArgLen)
        argCount = outArgLen - 1;

    strcopy(outArg, outArgLen, text[argStart]);
    outArg[argCount] = '\0';
}

static void ToLowerInPlace(char[] s)
{
    int len = strlen(s);
    for (int i = 0; i < len; i++)
    {
        if (s[i] >= 'A' && s[i] <= 'Z')
            s[i] = s[i] + ('a' - 'A');
    }
}

static bool IsSetQuotaAlias(const char[] cmd)
{
    if (StrEqual(cmd, "tf_bot_count") || StrEqual(cmd, "tf_bot_quota"))
        return true;

    if (StrEqual(cmd, "botcount") || StrEqual(cmd, "bot_count") || StrEqual(cmd, "bots") || StrEqual(cmd, "bot"))
        return true;

    if (StrEqual(cmd, "botquota") || StrEqual(cmd, "bot_quota") || StrEqual(cmd, "quota") || StrEqual(cmd, "setbots"))
        return true;

    if (StrEqual(cmd, "tfbotquota") || StrEqual(cmd, "tfbot_count") || StrEqual(cmd, "tfbotcount"))
        return true;

    if (StrEqual(cmd, "tf_bot_qouta") || StrEqual(cmd, "tf_bot_quotae") || StrEqual(cmd, "tf_bot_quouta"))
        return true;

    if (StrEqual(cmd, "tf_bot_cout") || StrEqual(cmd, "tf_bot_coun") || StrEqual(cmd, "tf_bot_cont"))
        return true;

    return false;
}

static bool IsAddAlias(const char[] cmd)
{
    if (StrEqual(cmd, "tf_bot_add"))
        return true;

    if (StrEqual(cmd, "addbot") || StrEqual(cmd, "botadd") || StrEqual(cmd, "add") || StrEqual(cmd, "plusbot"))
        return true;

    if (StrEqual(cmd, "tf_bot_ad") || StrEqual(cmd, "tf_bot_addd"))
        return true;

    return false;
}

static bool IsRemoveAlias(const char[] cmd)
{
    if (StrEqual(cmd, "tf_bot_kick") || StrEqual(cmd, "tf_bot_remove"))
        return true;

    if (StrEqual(cmd, "kickbot") || StrEqual(cmd, "removebot") || StrEqual(cmd, "botkick") || StrEqual(cmd, "botremove"))
        return true;

    if (StrEqual(cmd, "kick") || StrEqual(cmd, "remove") || StrEqual(cmd, "minusbot"))
        return true;

    if (StrEqual(cmd, "tf_bot_kik") || StrEqual(cmd, "tf_bot_kcik") || StrEqual(cmd, "tf_bot_remvoe"))
        return true;

    return false;
}
