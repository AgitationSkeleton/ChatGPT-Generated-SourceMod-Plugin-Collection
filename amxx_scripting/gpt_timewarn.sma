/**
 * Restart Warning (Pacific Time) — SM 1.8–1.11 compatible
 * Fires chat warnings toward a daily restart in America/Los_Angeles,
 * handling U.S. DST rules locally without relying on host TZ.
 *
 * cvars:
 *   sm_timewarn_enable (1/0)           default 1
 *   sm_timewarn_hour (0-23)            default 3   -> restart at 3:00 Pacific
 *   sm_timewarn_start_minute (0-59)    default 50  -> first warn at 2:50 Pacific
 *
 * Warns at: (restartHour-1):startMinute, :55, :58, :59 and restartHour:00.
 */

#include <sourcemod>
#include <sdktools>
#include <sdktools_sound>

#pragma semicolon 1
#pragma newdecls required

#define PLUGIN_NAME        "Restart Warning (Pacific Time)"
#define PLUGIN_VERSION     "1.3"
#define SOUND_BEEP         "buttons/button10.wav"

ConVar gCvarEnable;
ConVar gCvarRestartHour;   // Pacific local hour to warn toward (default 3)
ConVar gCvarStartMinute;   // First warning minute within previous hour (default 50)

int g_LastWarnMinuteKey = -1; // minute-based key from Pacific epoch (/60)

/* ---------------- Plugin Info ---------------- */

public Plugin myinfo =
{
    name        = PLUGIN_NAME,
    author      = "ChatGPT",
    description = "Warns players before daily restart using America/Los_Angeles with correct DST handling.",
    version     = PLUGIN_VERSION,
    url         = ""
};

/* ---------------- Lifecycle ---------------- */

public void OnPluginStart()
{
    gCvarEnable      = CreateConVar("sm_timewarn_enable", "1", "Enable/disable time warnings (1/0).", 0, true, 0.0, true, 1.0);
    gCvarRestartHour = CreateConVar("sm_timewarn_hour", "3", "Pacific local hour (0-23) of scheduled restart.", 0, true, 0.0, true, 23.0);
    gCvarStartMinute = CreateConVar("sm_timewarn_start_minute", "50", "Minute within the previous hour when warnings begin (e.g., 50 -> 2:50 if restart is 3:00).", 0, true, 0.0, true, 59.0);

    AutoExecConfig(true, "gpt_timewarn");

    // Check once a second; cheap and reliable
    CreateTimer(1.0, Timer_Check, _, TIMER_REPEAT | TIMER_FLAG_NO_MAPCHANGE);
}

public void OnMapStart()
{
    g_LastWarnMinuteKey = -1;
    PrecacheSound(SOUND_BEEP, true);
}

/* ---------------- Time / Date math (UTC->Pacific) ----------------
   All routines below avoid FormatTime so we don't depend on host TZ.
   We take a UTC epoch, apply -8h provisional offset, decide DST using
   Pacific local Y/M/D/H, then if DST apply -7h and recompute components.
-------------------------------------------------------------------*/

/** Integer floor-div that works for negatives. */
stock int DivFloor(int a, int b)
{
    int q = a / b;
    int r = a % b;
    if ((r != 0) && ((r < 0) != (b < 0))) q -= 1;
    return q;
}

/** Mod that is always non-negative. */
stock int ModPos(int a, int b)
{
    int m = a % b;
    if (m < 0) m += (b < 0 ? -b : b);
    return m;
}

/** Convert Unix epoch (seconds since 1970-01-01T00:00:00Z) to Y/M/D H:M:S in UTC-like arithmetic (no TZ). */
stock void EpochToYMDHMS(int epoch, int &Y, int &M, int &D, int &h, int &m, int &s)
{
    int day_sec = 86400;
    int z = DivFloor(epoch, day_sec);      // days since 1970-01-01
    int sec = epoch - z * day_sec;         // 0..86399
    if (sec < 0) { sec += day_sec; z -= 1; }

    h = sec / 3600;
    m = (sec / 60) % 60;
    s = sec % 60;

    // Howard Hinnant's civil_from_days algorithm
    int z2 = z + 719468; // shift to civil days
    int era = DivFloor(z2, 146097);
    int doe = z2 - era * 146097;                                   // [0, 146096]
    int yoe = (doe - doe/1460 + doe/36524 - doe/146096) / 365;     // [0, 399]
    int y = yoe + era * 400;
    int doy = doe - (365*yoe + yoe/4 - yoe/100);                    // [0, 365]
    int mp = (5*doy + 2) / 153;                                     // [0, 11]
    int d = doy - (153*mp + 2)/5 + 1;                               // [1, 31]
    int mth = mp + (mp < 10 ? 3 : -9);                              // [1, 12]
    y += (mth <= 2);

    Y = y;
    M = mth;
    D = d;
}

/** Day of week for Gregorian Y/M/D; 0=Sunday..6=Saturday. */
stock int DayOfWeek(int y, int m, int d)
{
    static const int t[12] = {0,3,2,5,0,3,5,1,4,6,2,4};
    if (m < 3) y -= 1;
    int w = (y + y/4 - y/100 + y/400 + t[m-1] + d) % 7;
    return (w + 7) % 7;
}

/** True if US DST is in effect for Pacific time on the given Pacific local Y/M/D/H. */
stock bool PacificIsDST(int year, int month, int day, int hour)
{
    if (month < 3 || month > 11) return false;     // Jan, Feb, Dec
    if (month > 3 && month < 11) return true;      // Apr..Oct

    if (month == 3)
    {
        // 2nd Sunday in March at 02:00
        int dowMar1 = DayOfWeek(year, 3, 1); // 0=Sun..6=Sat
        int firstSun = (dowMar1 == 0) ? 1 : (8 - dowMar1);
        int secondSun = firstSun + 7;
        if (day < secondSun) return false;
        if (day > secondSun) return true;
        return (hour >= 2);
    }
    // month == 11
    int dowNov1 = DayOfWeek(year, 11, 1);
    int firstSunNov = (dowNov1 == 0) ? 1 : (8 - dowNov1);
    if (day < firstSunNov) return true;
    if (day > firstSunNov) return false;
    // On switchover Sunday: DST until 02:00, then off.
    return (hour < 2);
}

/** Compute Pacific-local H/M (and minute-key) from current UTC epoch. */
stock void GetPacificHMAndKey(int utcEpoch, int &H, int &M, int &minuteKeyOut)
{
    // Provisional Pacific Standard Time: UTC-8h
    int pacStdEpoch = utcEpoch - 8 * 3600;
    int Y, Mon, D, h, m, s;
    EpochToYMDHMS(pacStdEpoch, Y, Mon, D, h, m, s);

    bool dst = PacificIsDST(Y, Mon, D, h);
    int pacEpoch = dst ? (utcEpoch - 7 * 3600) : pacStdEpoch;

    // Recompute final components from pacEpoch
    EpochToYMDHMS(pacEpoch, Y, Mon, D, h, m, s);

    H = h;
    M = m;
    // Minute key is Pacific minutes since epoch (works as duplication guard across day rollovers)
    minuteKeyOut = DivFloor(pacEpoch, 60);
}

/* ---------------- Warning logic ---------------- */

public Action Timer_Check(Handle timer, any data)
{
    if (!gCvarEnable.BoolValue)
        return Plugin_Continue;

    int utc = GetTime();

    int H, M, minuteKey;
    GetPacificHMAndKey(utc, H, M, minuteKey);

    int restartHour = gCvarRestartHour.IntValue;
    int startMinute = gCvarStartMinute.IntValue;

    int prevHour = (restartHour + 23) % 24; // hour before restartHour

    bool shouldWarn = false;
    int which = 0; // 1=~10m,2=5m,3=2m,4=1m,5=now

    if (H == prevHour && M == startMinute) { shouldWarn = true; which = 1; }
    else if (H == prevHour && M == 55)     { shouldWarn = true; which = 2; }
    else if (H == prevHour && M == 58)     { shouldWarn = true; which = 3; }
    else if (H == prevHour && M == 59)     { shouldWarn = true; which = 4; }
    else if (H == restartHour && M == 0)   { shouldWarn = true; which = 5; }

    if (!shouldWarn)
        return Plugin_Continue;

    if (g_LastWarnMinuteKey == minuteKey)
        return Plugin_Continue; // already fired this minute

    g_LastWarnMinuteKey = minuteKey;

    switch (which)
    {
        case 1:
        {
            WarnAll("Server restarts at %02d:00 Pacific (~10 minutes). Please finish your round.", restartHour);
        }
        case 2:
        {
            WarnAll("Server restarts at %02d:00 Pacific (5 minutes). Wrap up!", restartHour);
        }
        case 3:
        {
            WarnAll("Server restarts in ~2 minutes (Pacific).", restartHour);
        }
        case 4:
        {
            WarnAll("Server restarts in ~1 minute (Pacific).", restartHour);
        }
        case 5:
        {
            WarnAll("Server restarting now (Pacific).", restartHour);
        }
    }

    EmitBeepToAll();
    return Plugin_Continue;
}

stock void WarnAll(const char[] fmt, int restartHour)
{
    char msg[256];
    Format(msg, sizeof(msg), "[SERVER] ");
    int baseLen = strlen(msg);
    VFormat(msg[baseLen], sizeof(msg) - baseLen, fmt, 2);

    PrintToChatAll("%s", msg);
    PrintToServer("%s", msg);
}

stock void EmitBeepToAll()
{
    for (int i = 1; i <= MaxClients; i++)
    {
        if (IsClientInGame(i))
        {
            EmitSoundToClient(i, SOUND_BEEP);
        }
    }
}
