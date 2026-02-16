/**
 * Bot Join/Leave/Namechange Message Suppressor
 * Author: ChatGPT
 *
 * TF2C-oriented: suppresses system join/leave/kick/namechange lines for bots only.
 */

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>

int g_TextMsgId = -1;
int g_SayText2Id = -1;

public Plugin myinfo =
{
    name        = "Bot Message Suppressor",
    author      = "ChatGPT",
    description = "Suppress join/leave/kick/namechange chat lines for bots",
    version     = "1.0.2",
    url         = ""
};

public void OnPluginStart()
{
    g_TextMsgId = GetUserMessageId("TextMsg");
    if (g_TextMsgId != -1)
    {
        HookUserMessage(view_as<UserMsg>(g_TextMsgId), OnTextMsg, true);
    }

    g_SayText2Id = GetUserMessageId("SayText2");
    if (g_SayText2Id != -1)
    {
        HookUserMessage(view_as<UserMsg>(g_SayText2Id), OnSayText2, true);
    }

    // These are the big ones for TF2C-style join/leave/namechange prints.
    HookEvent("player_connect", Event_PlayerConnect_Pre, EventHookMode_Pre);
    HookEvent("player_connect_client", Event_PlayerConnectClient_Pre, EventHookMode_Pre);
    HookEvent("player_disconnect", Event_PlayerDisconnect_Pre, EventHookMode_Pre);
    HookEvent("player_changename", Event_PlayerChangeName_Pre, EventHookMode_Pre);
}

/* ----------------------------------------------------------- */

static void StringToLower(char[] text)
{
    int len = strlen(text);
    for (int i = 0; i < len; i++)
    {
        if (text[i] >= 'A' && text[i] <= 'Z')
        {
            text[i] += ('a' - 'A');
        }
    }
}

static bool LooksLikeSystemKey(const char[] keyOrMsg)
{
    char lower[256];
    strcopy(lower, sizeof(lower), keyOrMsg);
    StringToLower(lower);

    // Loose matching: translation keys or plain strings.
    if (StrContains(lower, "join") != -1) return true;
    if (StrContains(lower, "connect") != -1) return true;
    if (StrContains(lower, "disconnect") != -1) return true;
    if (StrContains(lower, "left") != -1) return true;
    if (StrContains(lower, "kicked") != -1) return true;
    if (StrContains(lower, "name") != -1 && StrContains(lower, "change") != -1) return true;

    // TF2-ish keys sometimes look like #game_join, #Game_connected, etc.
    if (lower[0] == '#') return true;

    return false;
}

static bool IsAnyInGameBotNamed(const char[] nameToMatch)
{
    for (int client = 1; client <= MaxClients; client++)
    {
        if (!IsClientInGame(client)) continue;
        if (!IsFakeClient(client)) continue;

        char botName[MAX_NAME_LENGTH];
        GetClientName(client, botName, sizeof(botName));

        if (StrEqual(botName, nameToMatch, false))
        {
            return true;
        }
    }
    return false;
}

static bool AnyParamMatchesBotName(const char[] p1, const char[] p2, const char[] p3, const char[] p4)
{
    if (p1[0] != '\0' && IsAnyInGameBotNamed(p1)) return true;
    if (p2[0] != '\0' && IsAnyInGameBotNamed(p2)) return true;
    if (p3[0] != '\0' && IsAnyInGameBotNamed(p3)) return true;
    if (p4[0] != '\0' && IsAnyInGameBotNamed(p4)) return true;
    return false;
}

/* ----------------------------------------------------------- */
/* Events: suppress broadcast for bots */

public Action Event_PlayerConnect_Pre(Event event, const char[] name, bool dontBroadcast)
{
    // player_connect commonly has "networkid" == "BOT" for bots (even before they fully exist as clients)
    char networkid[64];
    networkid[0] = '\0';
    event.GetString("networkid", networkid, sizeof(networkid));

    if (StrEqual(networkid, "BOT", false))
    {
        SetEventBroadcast(event, true);
        return Plugin_Changed;
    }
    return Plugin_Continue;
}

public Action Event_PlayerConnectClient_Pre(Event event, const char[] name, bool dontBroadcast)
{
    // Another connect variant; also tends to have networkid.
    char networkid[64];
    networkid[0] = '\0';
    event.GetString("networkid", networkid, sizeof(networkid));

    if (StrEqual(networkid, "BOT", false))
    {
        SetEventBroadcast(event, true);
        return Plugin_Changed;
    }
    return Plugin_Continue;
}

public Action Event_PlayerDisconnect_Pre(Event event, const char[] name, bool dontBroadcast)
{
    int userid = event.GetInt("userid");
    int client = (userid > 0) ? GetClientOfUserId(userid) : 0;

    if (client > 0 && IsClientInGame(client) && IsFakeClient(client))
    {
        SetEventBroadcast(event, true);
        return Plugin_Changed;
    }
    return Plugin_Continue;
}

public Action Event_PlayerChangeName_Pre(Event event, const char[] name, bool dontBroadcast)
{
    int userid = event.GetInt("userid");
    int client = (userid > 0) ? GetClientOfUserId(userid) : 0;

    if (client > 0 && IsClientInGame(client) && IsFakeClient(client))
    {
        SetEventBroadcast(event, true);
        return Plugin_Changed;
    }
    return Plugin_Continue;
}

/* ----------------------------------------------------------- */
/* Usermessages: suppress if message looks like a system line and references a bot name */

public Action OnTextMsg(UserMsg msgId, BfRead bf, const int[] recipients, int recipientsCount, bool reliable, bool init)
{
    if (bf == null) return Plugin_Continue;

    BfReadByte(bf); // dest

    char key[256];
    key[0] = '\0';
    BfReadString(bf, key, sizeof(key));

    if (!LooksLikeSystemKey(key))
    {
        return Plugin_Continue;
    }

    // Read up to 4 params if present.
    char p1[192], p2[192], p3[192], p4[192];
    p1[0] = p2[0] = p3[0] = p4[0] = '\0';

    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p1, sizeof(p1));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p2, sizeof(p2));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p3, sizeof(p3));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p4, sizeof(p4));

    if (AnyParamMatchesBotName(p1, p2, p3, p4))
    {
        return Plugin_Handled;
    }

    return Plugin_Continue;
}

public Action OnSayText2(UserMsg msgId, BfRead bf, const int[] recipients, int recipientsCount, bool reliable, bool init)
{
    if (bf == null) return Plugin_Continue;

    int fromClient = BfReadByte(bf);
    BfReadByte(bf); // chat flag

    char keyOrMsg[256];
    keyOrMsg[0] = '\0';
    BfReadString(bf, keyOrMsg, sizeof(keyOrMsg));

    if (!LooksLikeSystemKey(keyOrMsg))
    {
        return Plugin_Continue;
    }

    // Read up to 4 params if present.
    char p1[192], p2[192], p3[192], p4[192];
    p1[0] = p2[0] = p3[0] = p4[0] = '\0';

    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p1, sizeof(p1));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p2, sizeof(p2));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p3, sizeof(p3));
    if (BfGetNumBytesLeft(bf) > 0) BfReadString(bf, p4, sizeof(p4));

    // If a bot is “speaking” the system line OR the params mention a bot, suppress it.
    if (fromClient >= 1 && fromClient <= MaxClients)
    {
        if (IsClientInGame(fromClient) && IsFakeClient(fromClient))
        {
            return Plugin_Handled;
        }
    }

    if (AnyParamMatchesBotName(p1, p2, p3, p4))
    {
        return Plugin_Handled;
    }

    return Plugin_Continue;
}
