#include <amxmodx>
#include <fun>

#define COOLDOWN_TIME 20.0

new Float:g_fLastRTD[33];

public plugin_init()
{
    register_plugin("Fake RTD Slay", "1.1", "ChatGPT");

    register_concmd("amxx_rtd", "cmdFakeRTD");
    register_concmd("amxx_rollthedice", "cmdFakeRTD");
    register_clcmd("say", "handleSay");
}

public handleSay(id)
{
    if (!is_user_alive(id))
        return PLUGIN_CONTINUE;

    new args[192];
    read_args(args, charsmax(args));
    remove_quotes(args);

    if (equali(args, "rtd"))
    {
        cmdFakeRTD(id);
        return PLUGIN_HANDLED;
    }

    return PLUGIN_CONTINUE;
}

public cmdFakeRTD(id)
{
    if (!is_user_alive(id))
        return PLUGIN_HANDLED;

    new Float:currentTime = get_gametime();
    if (currentTime - g_fLastRTD[id] < COOLDOWN_TIME)
    {
        new Float:timeLeft = COOLDOWN_TIME - (currentTime - g_fLastRTD[id]);
        client_print(id, print_chat, "[RTD] You must wait %.0f more seconds before rolling again!", timeLeft);
        return PLUGIN_HANDLED;
    }

    g_fLastRTD[id] = currentTime;

    new name[32];
    get_user_name(id, name, charsmax(name));
    client_print(0, print_chat, "[RTD] %s rolled: Instant Death", name);

    user_kill(id);

    return PLUGIN_HANDLED;
}
