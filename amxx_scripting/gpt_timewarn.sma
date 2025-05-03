#include <amxmodx>
#include <fakemeta>

#define PLUGIN "Restart Warning"
#define VERSION "1.1"
#define AUTHOR "GPT-4o"

#define SOUND_PATH "fvox/bell.wav"

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR)
    set_task(60.0, "check_restart_time", _, _, _, "b") // Repeat every minute
}

public plugin_precache()
{
    precache_sound(SOUND_PATH)
}

public check_restart_time()
{
    new time = get_systime()
    
    // Extract hour/minute (UTC)
    new szHour[3], szMinute[3]
    format_time(szHour, charsmax(szHour), "%H", time)
    format_time(szMinute, charsmax(szMinute), "%M", time)
    
    new utcHour = str_to_num(szHour)
    new minute = str_to_num(szMinute)

    // Extract calendar info for DST detection
    new szMonth[3], szDay[3], szWday[3]
    format_time(szMonth, charsmax(szMonth), "%m", time)
    format_time(szDay, charsmax(szDay), "%d", time)
    format_time(szWday, charsmax(szWday), "%w", time)

    new month = str_to_num(szMonth)
    new day = str_to_num(szDay)
    new wday = str_to_num(szWday) // 0 = Sunday, 6 = Saturday

    // Determine if PDT is active
    new isDST = is_pacific_daylight_time(month, day, wday)

    new hour = utcHour + (isDST ? -7 : -8)
    if (hour < 0) hour += 24
    if (hour >= 24) hour -= 24

    // Trigger warnings at 2:00–2:50 AM PT every 10 minutes
    if (hour == 2 && (minute % 10 == 0) && minute <= 50)
    {
        new mins_left = 55 - minute
        client_print(0, print_chat, "[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM Pacific Time)", mins_left)
        emit_beep()
    }
}

public is_pacific_daylight_time(month, day, wday)
{
    if (month == 3)
    {
        if (day >= 8 && day <= 14 && wday == 0) return 1; // 2nd Sunday
        if (day > 14) return 1;
    }

    if (month >= 4 && month <= 10)
    {
        return 1;
    }

    if (month == 11)
    {
        if (day < 7) return 1;
        if (day == 7 && wday == 0) return 0; // 1st Sunday of Nov
        if (day > 7) return 0;
        if (wday != 0) return 1;
    }

    return 0; // Dec, Jan, Feb = PST
}

public emit_beep()
{
    new players[32], num
    get_players(players, num, "ch")

    for (new i = 0; i < num; i++)
    {
        emit_sound(players[i], CHAN_AUTO, SOUND_PATH, VOL_NORM, ATTN_NORM, 0, PITCH_NORM)
    }
}
