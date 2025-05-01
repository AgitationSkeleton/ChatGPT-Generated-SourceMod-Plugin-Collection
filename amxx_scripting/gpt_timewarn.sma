#include <amxmodx>
#include <fakemeta>
#include <engine>

#define PLUGIN "Restart Warning"
#define VERSION "1.0"
#define AUTHOR "GPT-4o"

#define TIMEZONE_OFFSET -8 // PST = UTC-8 (adjust to -7 for PDT if needed)
#define SOUND_PATH "fvox/bell.wav" // HL1-style warning beep

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR)
    set_task(60.0, "check_restart_time", _, _, _, "b") // Repeat every 60 sec
}

public plugin_precache()
{
    precache_sound(SOUND_PATH)
}

public check_restart_time()
{
    static szHour[3], szMinute[3]
    new time = get_systime()

    // Convert to UTC hour and minute
    format_time(szHour, charsmax(szHour), "%H", time)
    format_time(szMinute, charsmax(szMinute), "%M", time)

    new hour = str_to_num(szHour)
    new minute = str_to_num(szMinute)

    // Apply timezone offset manually
    hour += TIMEZONE_OFFSET
    if (hour < 0) hour += 24
    if (hour >= 24) hour -= 24

    // Trigger every 10 minutes between 2:00 and 2:50 PST
    if (hour == 2 && (minute % 10 == 0) && minute <= 50)
    {
        new mins_left = 55 - minute
        client_print(0, print_chat, "[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM PST)", mins_left)
        emit_beep()
    }
}

public emit_beep()
{
    new players[32], num
    get_players(players, num, "ch") // "c" = connected, "h" = not bots

    for (new i = 0; i < num; i++)
    {
        emit_sound(players[i], CHAN_AUTO, SOUND_PATH, VOL_NORM, ATTN_NORM, 0, PITCH_NORM)
    }
}
