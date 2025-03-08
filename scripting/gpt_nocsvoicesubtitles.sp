#include <sourcemod>

#pragma semicolon 1

public Plugin myinfo = 
{
    name = "No Voice Subtitles CS:S",
    author = "ChatGPT",
    description = "Removes all in-chat messages when using Voice commands in CS:S.",
    version = "1.1",
    url = ""
};

public void OnPluginStart()
{
    int msgId = GetUserMessageId("RadioText");
    if (msgId != INVALID_MESSAGE_ID)
    {
        HookUserMessage(msgId, OnRadioText, true);
    }
    else
    {
        PrintToServer("[SM] Warning: 'RadioText' message not found. Plugin may not work.");
    }
}

public Action OnRadioText(UserMsg msg_id, Handle bf, const int[] players, int playersNum, bool reliable, bool init)
{
    int clientid = BfReadByte(bf); // Read the client sending the radio message

    if (IsClientInGame(clientid) && IsPlayerAlive(clientid))
    {
        return Plugin_Handled; // Block the message from appearing in chat
    }

    return Plugin_Continue;
}
