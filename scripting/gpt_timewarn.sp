#include <sourcemod>
#include <sdktools>

#define SOUND_BEEP "buttons/button10.wav" // HL2 beep sound

public Plugin myinfo = 
{
    name = "Restart Warning",
    author = "GPT-4o",
    description = "Warns players every 10 minutes before 3AM Pacific Time restart (DST-aware).",
    version = "1.1",
    url = ""
};

public void OnPluginStart()
{
    // Run check every 60 seconds
    CreateTimer(60.0, Timer_CheckTime, _, TIMER_REPEAT);
}

public Action Timer_CheckTime(Handle timer)
{
    int rawTime = GetTime();

    // Get UTC hour and minute
    char buffer[3];
    FormatTime(buffer, sizeof(buffer), "%H", rawTime);
    int utcHour = StringToInt(buffer);

    FormatTime(buffer, sizeof(buffer), "%M", rawTime);
    int minute = StringToInt(buffer);

    // Get date for DST check
    char monthStr[3], dayStr[3], wdayStr[3];
    FormatTime(monthStr, sizeof(monthStr), "%m", rawTime); // 01-12
    FormatTime(dayStr, sizeof(dayStr), "%d", rawTime);     // 01-31
    FormatTime(wdayStr, sizeof(wdayStr), "%w", rawTime);   // 0=Sun, 1=Mon...6=Sat

    int month = StringToInt(monthStr);
    int day = StringToInt(dayStr);
    int wday = StringToInt(wdayStr);

    // Detect if it's daylight saving time in Pacific timezone
    bool isDST = IsPacificDaylightTime(month, day, wday);

    // Apply UTC offset
    int pacificHour = utcHour + (isDST ? -7 : -8);
    if (pacificHour < 0)
        pacificHour += 24;
    else if (pacificHour >= 24)
        pacificHour -= 24;

    // Trigger every 10 minutes from 2:00 to 2:50 AM Pacific Time
    if (pacificHour == 2 && minute % 10 == 0 && minute <= 50)
    {
        int minutesLeft = 55 - minute;
        PrintToChatAll("[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM Pacific Time)", minutesLeft);
        EmitBeepToAll();
    }

    return Plugin_Continue;
}

bool IsPacificDaylightTime(int month, int day, int wday)
{
    // March: DST starts on the 2nd Sunday
    if (month == 3)
    {
        if (day >= 8 && day <= 14 && wday == 0) return true;
        if (day > 14) return true;
    }

    // April–October: always DST
    if (month >= 4 && month <= 10)
    {
        return true;
    }

    // November: DST ends on the 1st Sunday
    if (month == 11)
    {
        if (day < 7) return true;
        if (day == 7 && wday == 0) return false;
        if (day > 7) return false;
        if (wday != 0) return true;
    }

    return false; // December–February = Standard Time
}

void EmitBeepToAll()
{
    for (int i = 1; i <= MaxClients; i++)
    {
        if (IsClientInGame(i))
        {
            EmitSoundToClient(i, SOUND_BEEP);
        }
    }
}
