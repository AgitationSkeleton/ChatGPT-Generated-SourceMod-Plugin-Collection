// gpt_playerchoicebotquota.sp
#include <sourcemod>
#include <clientprefs>

#define PLUGIN_NAME "Player Choice Bot Quota"
#define PLUGIN_AUTHOR "ChatGPT"
#define PLUGIN_VERSION "1.0"

public Plugin myinfo = 
{
    name = PLUGIN_NAME,
    author = PLUGIN_AUTHOR,
    description = "Allows players to control tf_bot_quota via chat commands",
    version = PLUGIN_VERSION
};

const MAX_BOTS = 7;
const MIN_BOTS = 0;

public void OnPluginStart()
{
    RegConsoleCmd("sm_bots", Command_SetBots);
    RegConsoleCmd("sm_addbot", Command_AddBot);
    RegConsoleCmd("sm_botadd", Command_AddBot);
    RegConsoleCmd("sm_removebot", Command_RemoveBot);
    RegConsoleCmd("sm_kickbot", Command_RemoveBot);
    RegConsoleCmd("sm_botremove", Command_RemoveBot);
    RegConsoleCmd("sm_botkick", Command_RemoveBot);
}

public Action Command_SetBots(int client, int args)
{
    if (args != 1)
    {
        ReplyToCommand(client, "Usage: !bots <0-7>");
        return Plugin_Handled;
    }

    char arg[5];
    GetCmdArg(1, arg, sizeof(arg));
    int quota = StringToInt(arg);

    if (quota < MIN_BOTS || quota > MAX_BOTS)
    {
        ReplyToCommand(client, "Bot quota must be between 0 and 7.");
        return Plugin_Handled;
    }

    SetBotQuota(quota);
    ReplyToCommand(client, "Bot quota set to %d.", quota);
    return Plugin_Handled;
}

public Action Command_AddBot(int client, int args)
{
    int current = GetBotQuota();
    if (current < MAX_BOTS)
    {
        SetBotQuota(current + 1);
        ReplyToCommand(client, "Increased bot quota to %d.", current + 1);
    }
    else
    {
        ReplyToCommand(client, "Bot quota already at maximum (%d).", MAX_BOTS);
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
        ReplyToCommand(client, "Bot quota already at minimum (0). No bots to remove.");
    }
    return Plugin_Handled;
}

int GetBotQuota()
{
    return GetConVarInt(FindConVar("tf_bot_quota"));
}

void SetBotQuota(int quota)
{
    SetConVarInt(FindConVar("tf_bot_quota"), quota);
}