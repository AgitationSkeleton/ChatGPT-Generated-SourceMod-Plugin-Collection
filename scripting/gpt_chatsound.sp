#include <sdktools_sound.inc>

public void OnPluginStart()
{
    // Listen for say and say_team chat messages
    AddCommandListener(PlayTalkSound, "say");
    AddCommandListener(PlayTalkSound, "say_team");
}

public Action:PlayTalkSound(int client, const char[] command, int argc)
{

    // Precache and play the misc/talk.wav sound for all players
    PrecacheSound("misc/talk.wav");
    EmitSoundToAll("misc/talk.wav", _, _, _, _, _, GetRandomInt(99, 101), _, _, _, _, _);

    return Plugin_Continue; // Continue to handle other messages normally
}
