#include <sourcemod>
#include <sdktools>

#define SOUND_BEEP "buttons/button10.wav"
#define TIMEZONE_OFFSET -8 // PST; change to -7 for PDT if needed

public Plugin myinfo = 
{
    name = "Restart Warning",
    author = "GPT-4o",
    description = "Warns players every 10 minutes before 3AM PST restart.",
    version = "1.0",
    url = ""
};

public void OnPluginStart()
{
    CreateTimer(60.0, Timer_CheckTime, _, TIMER_REPEAT);
}

public Action Timer_CheckTime(Handle timer)
{
    int rawTime = GetTime();

    // Convert to hour and minute (UTC)
    char buf[3];
    FormatTime(buf, sizeof(buf), "%H", rawTime);
    int utcHour = StringToInt(buf);

    FormatTime(buf, sizeof(buf), "%M", rawTime);
    int minute = StringToInt(buf);

    // Apply timezone offset
    int hour = utcHour + TIMEZONE_OFFSET;
    if (hour < 0)
        hour += 24;
    else if (hour >= 24)
        hour -= 24;

    if (hour == 2 && minute % 10 == 0 && minute <= 50)
    {
        int minutesLeft = 55 - minute;
        PrintToChatAll("[SERVER] Server will restart in %d minutes. (Restarts at 2:55AM-3AM PST)", minutesLeft);
        EmitBeepToAll();
    }

    return Plugin_Continue;
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
