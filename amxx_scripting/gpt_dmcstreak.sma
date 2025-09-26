// gpt_dmc_streakhud.sma
// Show "Streak: N" while holding +showscores in DMC (or any HL mod).
// Requires: amxmodx, fakemeta, hamsandwich
//
// Max + helper

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "DMC Streak HUD"
#define VERSION "1.0"
#define AUTHOR  "gpt"

#define MAX_PLAYERS 32
#define TASK_HUD_BASE  8500

// Some HL SDKs miss IN_SCORE in includes; define it if needed.
#if !defined IN_SCORE
    #define IN_SCORE (1<<16)
#endif

// --- CVARs ---
new c_enabled;           // 1=on
new c_minshow;           // minimum streak to display (default 1)
new c_prefix;            // text prefix (default "Streak: ")
new c_x;                 // HUD X (0.0..1.0)
new c_y;                 // HUD Y (0.0..1.0)
new c_r, c_g, c_b;       // HUD color
new c_refresh;           // seconds between HUD refresh while held (e.g., 0.25)
new c_fade;              // HUD hold/fade time (seconds)

// --- State ---
new g_streak[MAX_PLAYERS + 1];
new bool:g_holdingScore[MAX_PLAYERS + 1];   // current +showscores state (from CmdStart)
new bool:g_updaterRunning[MAX_PLAYERS + 1]; // per-player HUD task active?

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    // CVARs
    c_enabled = register_cvar("dmc_streakhud_enable", "1");
    c_minshow = register_cvar("dmc_streakhud_min",    "1");
    c_prefix  = register_cvar("dmc_streakhud_prefix", "Streak: ");
    c_x       = register_cvar("dmc_streakhud_x",      "0.02");
    c_y       = register_cvar("dmc_streakhud_y",      "0.50");
    c_r       = register_cvar("dmc_streakhud_r",      "255");
    c_g       = register_cvar("dmc_streakhud_g",      "255");
    c_b       = register_cvar("dmc_streakhud_b",      "0");
    c_refresh = register_cvar("dmc_streakhud_refresh","0.25");
    c_fade    = register_cvar("dmc_streakhud_fade",   "0.20");

    // Kills/deaths
    RegisterHam(Ham_Killed, "player", "OnPlayerKilledPost", 1);

    // Buttons (authoritative per-usercmd)
    register_forward(FM_CmdStart, "OnCmdStart");
}

public client_putinserver(id)
{
    g_streak[id] = 0;
    g_holdingScore[id] = false;
    stopHudTask(id);
}
public client_disconnect(id)
{
    g_streak[id] = 0;
    g_holdingScore[id] = false;
    stopHudTask(id);
}

// --- Kill/death logic: increment killer, reset victim ---
public OnPlayerKilledPost(victim, attacker, shouldgib)
{
    if (!is_user_connected(victim)) return HAM_IGNORED;

    // Reset victim's streak
    g_streak[victim] = 0;

    // Increment attacker if it's a different, valid player
    if (attacker > 0 && attacker <= MAX_PLAYERS && attacker != victim && is_user_connected(attacker))
    {
        g_streak[attacker]++;
    }

    return HAM_IGNORED;
}

// --- Read +showscores (IN_SCORE) and start/stop HUD updater accordingly ---
public OnCmdStart(id, uc_handle, seed)
{
    if (!is_user_connected(id)) return FMRES_IGNORED;
    if (!get_pcvar_num(c_enabled)) return FMRES_IGNORED;

    new btns = get_uc(uc_handle, UC_Buttons);
    new bool:wants = (btns & IN_SCORE) != 0;

    if (wants && !g_holdingScore[id])
    {
        g_holdingScore[id] = true;
        startHudTask(id);
    }
    else if (!wants && g_holdingScore[id])
    {
        g_holdingScore[id] = false;
        stopHudTask(id);  // let HUD fade quickly
    }

    return FMRES_IGNORED;
}

// --- HUD updater task per player ---
public HudTick(taskid)
{
    // taskid = TASK_HUD_BASE + id
    new id = taskid - TASK_HUD_BASE;
    if (id < 1 || id > MAX_PLAYERS) return;

    if (!get_pcvar_num(c_enabled) || !is_user_alive(id) || !g_holdingScore[id])
    {
        stopHudTask(id);
        return;
    }

    new minshow = get_pcvar_num(c_minshow);
    new streak = g_streak[id];

    // If streak below threshold, don't render (but keep task active while held)
    if (streak < minshow)
    {
        return;
    }

    // Fetch settings
    new prefix[64];
    get_pcvar_string(c_prefix, prefix, charsmax(prefix));
    new Float:x = get_pcvar_float(c_x);
    new Float:y = get_pcvar_float(c_y);
    new r = clamp(get_pcvar_num(c_r), 0, 255);
    new g = clamp(get_pcvar_num(c_g), 0, 255);
    new b = clamp(get_pcvar_num(c_b), 0, 255);
    new Float:fade = get_pcvar_float(c_fade);

    // Build message
    new msg[96];
    formatex(msg, charsmax(msg), "%s %d", prefix, streak);

    // Draw HUD: left-center, yellow, small fade so it disappears quickly when key released
    set_hudmessage(r, g, b, x, y, 0, 0.0, fade, 0.0, 0.0, -1);
    show_hudmessage(id, "%s", msg);
}

stock startHudTask(id)
{
    if (g_updaterRunning[id]) return;

    new Float:interval = get_pcvar_float(c_refresh);
    if (interval < 0.05) interval = 0.05;

    set_task(interval, "HudTick", TASK_HUD_BASE + id, _, _, "b");
    g_updaterRunning[id] = true;
}

stock stopHudTask(id)
{
    if (!g_updaterRunning[id]) return;
    remove_task(TASK_HUD_BASE + id);
    g_updaterRunning[id] = false;
}
