#include <sourcemod>
#include <sdktools>

#define SOUND_BEEP "buttons/button10.wav"

public Plugin:myinfo = 
{
    name = "Restart Warning",
    author = "GPT-4o",
    description = "Warns players every 10 minutes before 3AM Pacific Time restart (DST-aware).",
    version = "1.1"
};

public OnPluginStart()
{
    CreateTimer(60.0, Timer_CheckTime, _, TIMER_REPEAT);
}

public Action:Timer_CheckTime(Handle:timer)
{
    new rawTime = GetTime();

    decl String:buffer[3];
    FormatTime(buffer, sizeof(buffer), "%H", rawTime);
    new utcHour = StringToInt(buffer);

    FormatTime(buffer, sizeof(buffer), "%M", rawTime);
    new minute = StringToInt(buffer);

    decl String:monthStr[3];
    decl String:dayStr[3];
    decl String:wdayStr[3];

    FormatTime(monthStr, sizeof(monthStr), "%m", rawTime);
    FormatTime(dayStr, sizeof(dayStr), "%d", rawTime);
    FormatTime(wdayStr, sizeof(wdayStr), "%w", rawTime);

    new month = StringToInt(monthStr);
    new day = StringToInt(dayStr);
    new wday = StringToInt(wdayStr);

    new isDST = IsPacificDaylightTime(month, day, wday);

    new pacificHour = utcHour + (isDST ? -7 : -8);
    if (pacificHour < 0) pacificHour += 24;
    else if (pacificHour >= 24) pacificHour -= 24;

    if (pacificHour == 2 && (minute % 10 == 0) && (minute <= 50))
    {
        new minutesLeft = 55 - minute;
        PrintToChatAll("[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM Pacific Time)", minutesLeft);
        EmitBeepToAll();
    }

    return Plugin_Continue;
}

EmitBeepToAll()
{
    for (new i = 1; i <= MaxClients; i++)
    {
        if (IsClientInGame(i) && !IsFakeClient(i))
        {
            EmitSoundToClient(i, SOUND_BEEP);
        }
    }
}

IsPacificDaylightTime(month, day, wday)
{
    if (month == 3)
    {
        if (day >= 8 && day <= 14 && wday == 0) return 1;
        if (day > 14) return 1;
    }

    if (month >= 4 && month <= 10)
    {
        return 1;
    }

    if (month == 11)
    {
        if (day < 7) return 1;
        if (day == 7 && wday == 0) return 0;
        if (day > 7) return 0;
        if (wday != 0) return 1;
    }

    return 0;
}
