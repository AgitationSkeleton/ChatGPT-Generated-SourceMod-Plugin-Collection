#include <sourcemod>
#include <sdktools>

#define SOUND_BEEP "buttons/button10.wav"
#define TIMEZONE_OFFSET -8 // PST

public Plugin:myinfo = 
{
    name = "Restart Warning",
    author = "GPT-4o",
    description = "Warns players every 10 minutes before 3AM PST restart.",
    version = "1.0"
};

public OnPluginStart()
{
    PrecacheSound(SOUND_BEEP, true);
    CreateTimer(60.0, Timer_CheckTime, _, TIMER_REPEAT);
}

public Action:Timer_CheckTime(Handle:timer)
{
    new rawTime = GetTime();

    new String:buf[3];
    FormatTime(buf, sizeof(buf), "%H", rawTime);
    new utcHour = StringToInt(buf);

    FormatTime(buf, sizeof(buf), "%M", rawTime);
    new minute = StringToInt(buf);

    new hour = utcHour + TIMEZONE_OFFSET;
    if (hour < 0)
        hour += 24;
    else if (hour >= 24)
        hour -= 24;

    if (hour == 2 && (minute % 10 == 0) && (minute <= 50))
    {
        new minutesLeft = 55 - minute;
        PrintToChatAll("[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM PST)", minutesLeft);
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
