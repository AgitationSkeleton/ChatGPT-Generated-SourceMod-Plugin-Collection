/* Ricochet Menu Vote Helper (no Ham)
 * Adds chat-based voting while a menu is open:
 *   say: 1..10, !1..!10, /1.. /10  -> menuselect N
 * Shows a HUD hint "Type 1-10 in chat to vote" when a menu opens.
 *
 * Cvars:
 *   rcvote_helper_enable 1
 *   rcvote_helper_hint   1   // show HUD hint when menu appears
 */
#include <amxmodx>

#define PLUGIN  "Ricochet Menu Vote Helper"
#define VERSION "1.2"
#define AUTHOR  "gpt"

new g_pEnable, g_pHint;

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);
    g_pEnable = register_cvar("rcvote_helper_enable", "1");
    g_pHint   = register_cvar("rcvote_helper_hint",   "1");

    // Chat hooks
    register_clcmd("say",      "hook_say");
    register_clcmd("say_team", "hook_say");

    // Poll for menu state to display hint (no engine hook for "menu opened")
    set_task(0.5, "task_menu_hint", .flags="b");
}

public hook_say(id)
{
    if (!get_pcvar_num(g_pEnable)) return PLUGIN_CONTINUE;
    if (!is_user_connected(id))    return PLUGIN_CONTINUE;

    static msg[64]; read_args(msg, charsmax(msg)); remove_quotes(msg);
    if (!msg[0]) return PLUGIN_CONTINUE;

    // Normalize inputs: "1".."10", "!1".. "!10", "/1".."/10"
    new n = parse_vote_number(msg);
    if (n < 1 || n > 10) return PLUGIN_CONTINUE;

    // Only act if a menu is open for this player
    new keys, menuid;
    if (get_user_menu(id, keys, menuid) == 0) return PLUGIN_CONTINUE;

    client_cmd(id, "menuselect %d", n);
    return PLUGIN_HANDLED; // hide the chat message
}

stock parse_vote_number(const s[])
{
    // skip leading symbols
    new i=0;
    if (s[i]=='!' || s[i]=='/') i++;
    // parse int
    new val=0, found=0;
    while (s[i]>='0' && s[i]<='9')
    {
        val = val*10 + (s[i]-'0'); i++; found=1;
        if (val>10) break;
    }
    return found ? val : 0;
}

public task_menu_hint()
{
    if (!get_pcvar_num(g_pEnable) || !get_pcvar_num(g_pHint)) return;

    // show hint to any player that currently has a menu open
    for (new id=1; id<=get_maxplayers(); id++)
    {
        if (!is_user_connected(id)) continue;
        new keys, menuid;
        if (get_user_menu(id, keys, menuid) != 0)
        {
            // brief, subtle HUD text at top
            set_hudmessage(200, 200, 255, -1.0, 0.10, 0, 0.0, 0.6, 0.0, 0.0, 2);
            show_hudmessage(id, "Menu open: Type 1 - 10 in chat to vote");
        }
    }
}
