#include <sourcemod>
#include <sdktools>

public Plugin myinfo = {
    name = "TF Birthday Setter",
    author = "OpenAI / ChatGPT",
    description = "Sets tf_birthday to 1 on August 3 (PST)",
    version = "1.0",
    url = ""
};

public void OnMapStart()
{
    // Allocate buffer for date string
    char dateString[32];

    // Adjust UTC time to PST manually: UTC-8 (standard) or UTC-7 (if you want to support DST)
    int currentTime = GetTime() - (7 * 60 * 60); // Adjust to UTC-7; change to 8 for standard time

    // Format date as MM-DD in adjusted time
    FormatTime(dateString, sizeof(dateString), "%m-%d", currentTime);

    if (StrEqual(dateString, "08-03"))
    {
        ConVar tf_birthday = FindConVar("tf_birthday");
        if (tf_birthday != null)
        {
            tf_birthday.SetInt(1);
            PrintToServer("[TF Birthday Setter] tf_birthday set to 1 for August 3 (PST).");
        }
        else
        {
            PrintToServer("[TF Birthday Setter] tf_birthday cvar not found.");
        }
    }
    else
    {
        PrintToServer("[TF Birthday Setter] Today is not August 3 (PST); tf_birthday not set.");
    }
}
