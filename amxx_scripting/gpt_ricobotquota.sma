/*  Ricochet Bot Quota (with !bots command)
 *  Version 1.1.1 (AMXX compile fixes)
 *  - Adds amxmisc include for cmd_access/access
 *  - Renames helper clamp() -> clampi() to avoid name clash
 *
 *  Notes:
 *    - No HamSandwich required (Ricochet-friendly).
 */

#include <amxmodx>
#include <amxmisc>

#define PLUGIN   "Ricochet Bot Quota"
#define VERSION  "1.1.1"
#define AUTHOR   "gpt"

#define TASK_QUOTA   42421

new g_pCvarQuota;
new g_pCvarInterval;
new g_pCvarEnable;
new g_pCvarKickMode;
new g_pCvarCmdMode;
new g_pCvarReserve;

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    g_pCvarQuota    = register_cvar("ricobot_quota",    "4");
    g_pCvarInterval = register_cvar("ricobot_interval", "3.0");
    g_pCvarEnable   = register_cvar("ricobot_enable",   "1");
    g_pCvarKickMode = register_cvar("ricobot_kickmode", "0"); // 0=oldest, 1=random
    g_pCvarCmdMode  = register_cvar("ricobot_cmdmode",  "2"); // 0=off, 1=admin, 2=all
    g_pCvarReserve  = register_cvar("ricobot_reserve",  "2"); // keep at least N human slots

    register_concmd("amx_ricobot_check", "cmd_force_check", ADMIN_KICK,
        "Force immediate Ricochet bot quota reconciliation.");

    // Chat hooks for !bots / /bots
    register_clcmd("say",      "hook_say");
    register_clcmd("say_team", "hook_say");

    // Start periodic task a couple seconds after plugin loads
    new Float:interval = get_pcvar_float(g_pCvarInterval);
    if (interval < 1.0) interval = 1.0;

    set_task(2.0, "quota_task_once");
    set_task(interval, "quota_task_repeat", TASK_QUOTA, _, _, "b");

    // Generic map start hook to settle early
    register_event("HLTV", "on_new_round", "a", "1=0", "2=0");
}

/* Triggered at round start/map load to settle early */
public on_new_round()
{
    set_task(1.0, "quota_task_once");
}

/* Immediate one-shot reconciliation */
public quota_task_once()
{
    reconcile_quota();
}

/* Periodic reconciliation */
public quota_task_repeat()
{
    new Float:interval = get_pcvar_float(g_pCvarInterval);
    if (interval < 1.0) interval = 1.0;

    // If someone changed the interval, reschedule the repeating task:
    if (task_exists(TASK_QUOTA))
    {
        remove_task(TASK_QUOTA);
        set_task(interval, "quota_task_repeat", TASK_QUOTA, _, _, "b");
    }

    reconcile_quota();
}

/* React faster to connect/disconnect without HamSandwich */
public client_putinserver(id)
{
    set_task(0.5, "quota_task_once");
}

public client_disconnect(id)
{
    set_task(0.5, "quota_task_once");
}

/* Admin command to force a check now */
public cmd_force_check(id, level, cid)
{
    if (!cmd_access(id, level, cid, 1))
        return PLUGIN_HANDLED;

    reconcile_quota();
    console_print(id, "[%s] Quota reconciliation executed.", PLUGIN);
    return PLUGIN_HANDLED;
}

/* Chat command: !bots <n> or /bots <n> */
public hook_say(id)
{
    static msg[192];
    read_args(msg, charsmax(msg));
    remove_quotes(msg);

    if (!msg[0]) return PLUGIN_CONTINUE;

    // Accept both "!bots" and "/bots"
    if (equali(msg, "!bots", 5) || equali(msg, "/bots", 5))
    {
        // Permissions
        new mode = get_pcvar_num(g_pCvarCmdMode);
        if (mode == 0)
        {
            client_print(id, print_chat, "[%s] The bots command is disabled.", PLUGIN);
            return PLUGIN_HANDLED;
        }
        if (mode == 1 && !access(id, ADMIN_KICK))
        {
            client_print(id, print_chat, "[%s] You do not have permission to use this command.", PLUGIN);
            return PLUGIN_HANDLED;
        }

        // Parse number after command token
        new argStart = 5; // length of "!bots"/"/bots"
        while (msg[argStart] == ' ') argStart++;

        if (!msg[argStart])
        {
            show_quota_help(id);
            return PLUGIN_HANDLED;
        }

        new newQuota = parse_int(msg[argStart]);
        if (newQuota < 0)
        {
            client_print(id, print_chat, "[%s] Invalid number. Try '!bots 0' up to the allowed max.", PLUGIN);
            return PLUGIN_HANDLED;
        }

        // Enforce upper bound: maxplayers - reserve
        new maxp = get_maxplayers();
        new reserve = get_pcvar_num(g_pCvarReserve);
        if (reserve < 0) reserve = 0;
        if (reserve > maxp) reserve = maxp;

        new maxAllowed = maxp - reserve;
        if (maxAllowed < 0) maxAllowed = 0;

        if (newQuota > maxAllowed)
        {
            client_print(id, print_chat, "[%s] Clamped to %d to keep %d slot(s) free for humans (maxplayers=%d).",
                         PLUGIN, maxAllowed, reserve, maxp);
            newQuota = maxAllowed;
        }

        set_pcvar_num(g_pCvarQuota, newQuota);
        log_amx("[%s] %s set ricobot_quota to %d (reserve=%d, maxplayers=%d).",
                PLUGIN, get_name_safe(id), newQuota, reserve, maxp);

        client_print(id, print_chat, "[%s] Bot quota set to %d. Reconciling...", PLUGIN, newQuota);
        set_task(0.1, "quota_task_once");

        return PLUGIN_HANDLED; // hide the raw "!bots ..." from public chat
    }

    return PLUGIN_CONTINUE;
}

/* Core logic: counts clients and adjusts bots to meet quota */
stock reconcile_quota()
{
    if (get_pcvar_num(g_pCvarEnable) == 0)
        return;

    new quota = get_pcvar_num(g_pCvarQuota);
    if (quota < 0) quota = 0;

    // Build list of connected clients and bots
    new maxplayers = get_maxplayers();
    new total = 0;
    new botCount = 0;

    new botIds[32];
    new botTimes[32];

    for (new i = 1; i <= maxplayers; i++)
    {
        if (!is_user_connected(i))
            continue;

        if (is_user_hltv(i))
            continue;

        total++;

        if (is_user_bot(i))
        {
            if (botCount < sizeof botIds)
            {
                botIds[botCount]   = i;
                botTimes[botCount] = get_user_time(i, 0);
                botCount++;
            }
        }
    }

    if (total == quota)
        return; // already satisfied

    if (total < quota)
    {
        // Need to add bots
        new need = quota - total;
        for (new k = 0; k < need; k++)
        {
            server_cmd("bot addbot");
        }
        server_exec();
        log_amx("[%s] Added %d bot(s) to reach quota %d (was %d).", PLUGIN, need, quota, total);
        return;
    }

    // total > quota: need to kick some bots (if any available)
    new excess = total - quota;
    if (botCount <= 0)
    {
        log_amx("[%s] Total %d exceeds quota %d but there are no bots to kick.", PLUGIN, total, quota);
        return;
    }

    if (excess > botCount)
        excess = botCount;

    new kickMode = clampi(get_pcvar_num(g_pCvarKickMode), 0, 1);

    for (new c = 0; c < excess; c++)
    {
        new target = -1;

        if (kickMode == 0)
        {
            // Oldest-connected bot
            new bestIdx = -1;
            new bestTime = -1;
            for (new j = 0; j < botCount; j++)
            {
                if (botIds[j] <= 0)
                    continue;
                botTimes[j] = get_user_time(botIds[j], 0);
                if (botTimes[j] > bestTime)
                {
                    bestTime = botTimes[j];
                    bestIdx = j;
                }
            }
            if (bestIdx != -1)
            {
                target = botIds[bestIdx];
                botIds[bestIdx] = 0;
            }
        }
        else
        {
            // Random bot
            new avail[32], ac = 0;
            for (new j = 0; j < botCount; j++)
            {
                if (botIds[j] > 0)
                    avail[ac++] = botIds[j];
            }
            if (ac > 0)
            {
                new pick = random_num(0, ac - 1);
                target = avail[pick];
                for (new j = 0; j < botCount; j++)
                {
                    if (botIds[j] == target)
                    {
                        botIds[j] = 0;
                        break;
                    }
                }
            }
        }

        if (target > 0 && is_user_connected(target) && is_user_bot(target))
        {
            new userid = get_user_userid(target);
            if (userid > 0)
                server_cmd("kick #%d", userid);
        }
    }

    server_exec();
    log_amx("[%s] Kicked %d bot(s) to reach quota %d (was %d).", PLUGIN, excess, quota, total);
}

/* Helpers */

stock clampi(value, minVal, maxVal)
{
    if (value < minVal) return minVal;
    if (value > maxVal) return maxVal;
    return value;
}

stock parse_int(const str[])
{
    // Simple integer parser: returns -1 on failure
    new i = 0, sign = 1, result = 0;

    while (str[i] == ' ') i++;
    if (str[i] == '+') { i++; }
    else if (str[i] == '-') { sign = -1; i++; }

    new found = 0;
    while (str[i] >= '0' && str[i] <= '9')
    {
        result = result * 10 + (str[i] - '0');
        i++;
        found = 1;
    }
    if (!found) return -1;
    return result * sign;
}

stock get_name_safe(id)
{
    static name[32];
    if (id > 0 && is_user_connected(id))
        get_user_name(id, name, charsmax(name));
    else
        copy(name, charsmax(name), "console");
    return name;
}

stock show_quota_help(id)
{
    new maxp = get_maxplayers();
    new reserve = get_pcvar_num(g_pCvarReserve);
    if (reserve < 0) reserve = 0;
    if (reserve > maxp) reserve = maxp;

    new maxAllowed = maxp - reserve;
    if (maxAllowed < 0) maxAllowed = 0;

    new cur = get_pcvar_num(g_pCvarQuota);
    client_print(id, print_chat, "[%s] Usage: !bots <0..%d>  (current=%d, reserve=%d, maxplayers=%d)",
                 PLUGIN, maxAllowed, cur, reserve, maxp);
}
