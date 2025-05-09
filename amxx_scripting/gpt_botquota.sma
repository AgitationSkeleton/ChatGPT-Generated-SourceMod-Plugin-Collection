// gpt_botquota.sma
#include <amxmodx>

#define PLUGIN "Player Choice Bot Quota"
#define VERSION "1.0"
#define AUTHOR "ChatGPT"

const MIN_BOTS = 0
const MAX_BOTS = 30

new g_currentBotQuota = 0

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR)

    register_clcmd("say", "HandleSay")
    register_clcmd("say_team", "HandleSay")

    g_currentBotQuota = 0 // default quota
}

public HandleSay(id)
{
    new args[192]
    read_args(args, charsmax(args))
    remove_quotes(args)

    if (equali(args, "!addbot") || equali(args, "!botadd"))
    {
        if (g_currentBotQuota < MAX_BOTS)
        {
            g_currentBotQuota++
            set_bot_quota(g_currentBotQuota)
            client_print(id, print_chat, "[RCBot] Increased bot quota to %d.", g_currentBotQuota)
        }
        else
        {
            client_print(id, print_chat, "[RCBot] Bot quota already at maximum (%d).", MAX_BOTS)
        }
        return PLUGIN_HANDLED
    }

    if (equali(args, "!removebot") || equali(args, "!kickbot") || equali(args, "!botremove") || equali(args, "!botkick"))
    {
        if (g_currentBotQuota > MIN_BOTS)
        {
            g_currentBotQuota--
            set_bot_quota(g_currentBotQuota)
            client_print(id, print_chat, "[RCBot] Decreased bot quota to %d.", g_currentBotQuota)
        }
        else
        {
            client_print(id, print_chat, "[RCBot] Bot quota already at minimum (0).")
        }
        return PLUGIN_HANDLED
    }

    if (contain(args, "!bots") == 0)
    {
        new token[8]
        parse(args, token, charsmax(token))

        new quota = str_to_num(args[6]) // assumes format "!bots X"
        if (quota >= MIN_BOTS && quota <= MAX_BOTS)
        {
            g_currentBotQuota = quota
            set_bot_quota(quota)
            client_print(id, print_chat, "[RCBot] Bot quota set to %d.", quota)
        }
        else
        {
            client_print(id, print_chat, "[RCBot] Quota must be between 0 and 30.")
        }
        return PLUGIN_HANDLED
    }

    return PLUGIN_CONTINUE
}

stock set_bot_quota(quota)
{
    new cmd[64]
    format(cmd, charsmax(cmd), "rcbot config max_bots %d", quota)
    server_cmd("%s", cmd)
    server_exec()
}
