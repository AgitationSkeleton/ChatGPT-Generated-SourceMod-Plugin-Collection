#include <sourcemod>
#include <clientprefs>

public Plugin:myinfo = 
{
    name = "Deathmatch Opponent Health",
    author = "ChatGPT",
    description = "Displays killer's health to the victim",
    version = "1.0"
};

public OnPluginStart() 
{
    // Hook for the player death event
    HookEvent("player_death", Event_PlayerDeath, EventHookMode_Post);
}

public Action Event_PlayerDeath(Event event, const char[] name, bool dontBroadcast) 
{
    // Get the victim and killer from the event
    int victim = GetClientOfUserId(event.GetInt("userid"));
    int killer = GetClientOfUserId(event.GetInt("attacker"));

    // Ensure the victim and killer are valid players
    if (IsClientInGame(victim) && IsClientInGame(killer)) 
    {
        // Get the killer's health
        int killer_health = GetClientHealth(killer);

        // Get the killer's name
        char killer_name[64];
        GetClientName(killer, killer_name, sizeof(killer_name));

        // Format the message
        char message[128];
        Format(message, sizeof(message), "[DM] Your opponent (%s) had %d health remaining.", killer_name, killer_health);

        // Send the message to the victim's chat
        PrintToChat(victim, message);
    }

    return Plugin_Continue; // Return Plugin_Continue instead of Action_Continue
}
