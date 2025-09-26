// gpt_streakhud.sma
// Streak HUD for HLDM / TFC / DMC with per-team colors (TFC-friendly).
// DeathMsg-based streaks; +showscores detection via clcmd/CmdStart/PreThink.
// No author attribution per request.

#include <amxmodx>
#include <fakemeta>

#define PLUGIN  "Streak HUD"
#define VERSION "1.4"

#define MAX_PLAYERS 32
#define TASK_HUD_BASE 8500
#define HUD_CHANNEL 3   // change if another plugin uses this slot

#if !defined IN_SCORE
    #define IN_SCORE (1<<16)
#endif

// --- CVARs (base) ---
new c_enabled;           // 1=on
new c_minshow;           // minimum streak displayed
new c_prefix;            // text prefix
new c_x, c_y;            // HUD pos [0..1]
new c_r, c_g, c_b;       // fallback/global color
new c_refresh;           // HUD refresh while holding
new c_fade;              // HUD fade time
new c_team_mode;         // 0 ignore teams; 1 respect mp_teamplay; 2 always team game

// --- CVARs (per-team color) ---
new c_team_colors;       // 1 = enable team-colored HUD text
new c_red_r,    c_red_g,    c_red_b;
new c_blue_r,   c_blue_g,   c_blue_b;
new c_yel_r,    c_yel_g,    c_yel_b;
new c_grn_r,    c_grn_g,    c_grn_b;

// --- State ---
new g_streak[MAX_PLAYERS + 1];
new bool:g_holdingScore[MAX_PLAYERS + 1];
new bool:g_updaterRunning[MAX_PLAYERS + 1];

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, "");

    // Base CVARs
    c_enabled   = register_cvar("streakhud_enable",   "1");
    c_minshow   = register_cvar("streakhud_min",      "1");
    c_prefix    = register_cvar("streakhud_prefix",   "Streak: ");
    c_x         = register_cvar("streakhud_x",        "0.02");
    c_y         = register_cvar("streakhud_y",        "0.50");
    c_r         = register_cvar("streakhud_r",        "255");
    c_g         = register_cvar("streakhud_g",        "255");
    c_b         = register_cvar("streakhud_b",        "0");
    c_refresh   = register_cvar("streakhud_refresh",  "0.25");
    c_fade      = register_cvar("streakhud_fade",     "0.20");
    c_team_mode = register_cvar("streakhud_team_mode","2"); // TFC-friendly default

    // Per-team colors (TFC team names: Red/Blue/Yellow/Green)
    c_team_colors = register_cvar("streakhud_team_colors", "1");
    c_red_r  = register_cvar("streakhud_red_r",    "255");
    c_red_g  = register_cvar("streakhud_red_g",    "64");
    c_red_b  = register_cvar("streakhud_red_b",    "64");

    c_blue_r = register_cvar("streakhud_blue_r",   "64");
    c_blue_g = register_cvar("streakhud_blue_g",   "128");
    c_blue_b = register_cvar("streakhud_blue_b",   "255");

    c_yel_r  = register_cvar("streakhud_yel_r",    "255");
    c_yel_g  = register_cvar("streakhud_yel_g",    "255");
    c_yel_b  = register_cvar("streakhud_yel_b",    "64");

    c_grn_r  = register_cvar("streakhud_grn_r",    "64");
    c_grn_g  = register_cvar("streakhud_grn_g",    "255");
    c_grn_b  = register_cvar("streakhud_grn_b",    "64");

    // Streaks via DeathMsg (robust across TFC cases)
    register_event("DeathMsg", "OnDeathMsg", "a");

    // Scoreboard detection: 3 paths
    register_forward(FM_CmdStart,       "OnCmdStart");        // path 1
    register_forward(FM_PlayerPreThink, "OnPlayerPreThink");  // path 2
    register_clcmd("+showscores", "CmdShowScoresDown");       // path 3
    register_clcmd("-showscores", "CmdShowScoresUp");
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

// ----- DeathMsg -> streak update -----
public OnDeathMsg()
{
    if (!get_pcvar_num(c_enabled)) return;

    new killer = read_data(1);
    new victim = read_data(2);

    if (victim >= 1 && victim <= MAX_PLAYERS && is_user_connected(victim))
        g_streak[victim] = 0;

    if (killer >= 1 && killer <= MAX_PLAYERS && is_user_connected(killer) && killer != victim)
    {
        if (ShouldCountKill(killer, victim))
            g_streak[killer]++;
    }
}

stock bool:ShouldCountKill(attacker, victim)
{
    new mode = get_pcvar_num(c_team_mode);

    if (mode == 0) return true;

    if (mode == 1)
    {
        if (get_cvar_num("mp_teamplay") != 1)
            return true;
        // fallthrough to team diff
    }
    return (get_user_team(attacker) != get_user_team(victim));
}

// ----- Scoreboard detection (3 paths) -----
public OnCmdStart(id, uc, seed)
{
    if (!is_user_connected(id) || !get_pcvar_num(c_enabled)) return FMRES_IGNORED;
    UpdateScoreHolding(id, (get_uc(uc, UC_Buttons) & IN_SCORE) != 0);
    return FMRES_IGNORED;
}
public OnPlayerPreThink(id)
{
    if (!is_user_connected(id) || !get_pcvar_num(c_enabled)) return FMRES_IGNORED;
    UpdateScoreHolding(id, (pev(id, pev_button) & IN_SCORE) != 0);
    return FMRES_IGNORED;
}
public CmdShowScoresDown(id)
{
    if (!is_user_connected(id) || !get_pcvar_num(c_enabled)) return PLUGIN_CONTINUE;
    UpdateScoreHolding(id, true);
    return PLUGIN_CONTINUE;
}
public CmdShowScoresUp(id)
{
    if (!is_user_connected(id) || !get_pcvar_num(c_enabled)) return PLUGIN_CONTINUE;
    UpdateScoreHolding(id, false);
    return PLUGIN_CONTINUE;
}

stock UpdateScoreHolding(id, bool:wants)
{
    if (wants && !g_holdingScore[id])
    {
        g_holdingScore[id] = true;
        startHudTask(id);
    }
    else if (!wants && g_holdingScore[id])
    {
        g_holdingScore[id] = false;
        stopHudTask(id);
    }
}

// ----- HUD updater -----
public HudTick(taskid)
{
    new id = taskid - TASK_HUD_BASE;
    if (id < 1 || id > MAX_PLAYERS) return;

    if (!get_pcvar_num(c_enabled) || !is_user_connected(id) || !g_holdingScore[id])
    {
        stopHudTask(id);
        return;
    }

    // If you want to see it while dead, remove the next 3 lines
    if (!is_user_alive(id))
    {
        stopHudTask(id);
        return;
    }

    new streak = g_streak[id];
    if (streak < get_pcvar_num(c_minshow))
        return;

    // Build message
    new prefix[64]; get_pcvar_string(c_prefix, prefix, charsmax(prefix));
    new msg[96];    formatex(msg, charsmax(msg), "%s %d", prefix, streak);

    // Position and fade
    new Float:x = get_pcvar_float(c_x);
    new Float:y = get_pcvar_float(c_y);
    new Float:fade = get_pcvar_float(c_fade);

    // Resolve color (per-team if enabled, otherwise global)
    new r, g, b;
    ResolveHudColor(id, r, g, b);

    set_hudmessage(r, g, b, x, y, 0, 0.0, fade, 0.0, 0.0, HUD_CHANNEL);
    show_hudmessage(id, "%s", msg);
}

stock ResolveHudColor(id, &r, &g, &b)
{
    if (!get_pcvar_num(c_team_colors))
    {
        r = clampi(get_pcvar_num(c_r), 0, 255);
        g = clampi(get_pcvar_num(c_g), 0, 255);
        b = clampi(get_pcvar_num(c_b), 0, 255);
        return;
    }

    // Try to color by team NAME (works in TFC: "Red","Blue","Yellow","Green")
    new tname[16];
    get_user_team(id, tname, charsmax(tname));

    if (equali(tname, "red"))
    {
        r = clampi(get_pcvar_num(c_red_r), 0, 255);
        g = clampi(get_pcvar_num(c_red_g), 0, 255);
        b = clampi(get_pcvar_num(c_red_b), 0, 255);
    }
    else if (equali(tname, "blue"))
    {
        r = clampi(get_pcvar_num(c_blue_r), 0, 255);
        g = clampi(get_pcvar_num(c_blue_g), 0, 255);
        b = clampi(get_pcvar_num(c_blue_b), 0, 255);
    }
    else if (equali(tname, "yellow"))
    {
        r = clampi(get_pcvar_num(c_yel_r), 0, 255);
        g = clampi(get_pcvar_num(c_yel_g), 0, 255);
        b = clampi(get_pcvar_num(c_yel_b), 0, 255);
    }
    else if (equali(tname, "green"))
    {
        r = clampi(get_pcvar_num(c_grn_r), 0, 255);
        g = clampi(get_pcvar_num(c_grn_g), 0, 255);
        b = clampi(get_pcvar_num(c_grn_b), 0, 255);
    }
    else
    {
        // Fallback to global color if team name unknown (HLDM FFA, spectators, etc.)
        r = clampi(get_pcvar_num(c_r), 0, 255);
        g = clampi(get_pcvar_num(c_g), 0, 255);
        b = clampi(get_pcvar_num(c_b), 0, 255);
    }
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

stock clampi(val, lo, hi)
{
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
