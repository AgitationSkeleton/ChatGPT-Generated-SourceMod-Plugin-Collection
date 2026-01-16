/**
 * gpt_pingreminder.sp
 * Author: ChatGPT
 *
 * If a player says "ping" (whole word) or "lag"/"lagggg"/"laggy"/"lagging" in chat,
 * sends a private message reminding them the server is in California, USA.
 */

#pragma semicolon 1

#include <sourcemod>

public Plugin myinfo =
{
    name        = "Ping/Lag Location Reminder",
    author      = "ChatGPT",
    description = "Privately reminds players the server is in California when they mention ping/lag.",
    version     = "1.0.3",
    url         = ""
};

ConVar gCvarEnabled;
ConVar gCvarCooldown;
ConVar gCvarMessage;

float gNextAllowed[MAXPLAYERS + 1];

static void SendSayText2ToClient(int client, const char[] message)
{
    // In TF2, color codes are most reliable via SayText2.
    // Supports: \x01 (default) and \x07RRGGBB (custom color).
    Handle msg = StartMessageOne("SayText2", client, USERMSG_RELIABLE | USERMSG_BLOCKHOOKS);
    if (msg == null)
    {
        // Fallback (may strip colors depending on engine/game)
        PrintToChat(client, "%s", message);
        return;
    }

    BfWriteByte(msg, client); // author index
    BfWriteByte(msg, 1);      // chat = true
    BfWriteString(msg, message);
    EndMessage();
}

public void OnPluginStart()
{
    gCvarEnabled  = CreateConVar("sm_pinglagreminder_enabled", "1", "Enable ping/lag reminder plugin.", FCVAR_NOTIFY, true, 0.0, true, 1.0);
    gCvarCooldown = CreateConVar("sm_pinglagreminder_cooldown", "10.0", "Per-player cooldown (seconds) between reminders.", FCVAR_NOTIFY, true, 0.0, true, 300.0);
    // TF2 chat color codes (most reliable via SayText2):
    //   \x01 = default, \x07RRGGBB = custom color
    gCvarMessage  = CreateConVar(
        "sm_pinglagreminder_message",
        "\x01\x0700FF00Reminder\x01: This server is hosted in \x0700FF00California, United States\x01.",
        "Message sent to triggering player. (Supports TF2 chat color codes: \\x01 default, \\x07RRGGBB custom color)",
        FCVAR_NOTIFY
    );

    AddCommandListener(Command_Say, "say");
    AddCommandListener(Command_Say, "say_team");

    for (int i = 1; i <= MaxClients; i++)
    {
        gNextAllowed[i] = 0.0;
    }
}

public void OnClientPutInServer(int client)
{
    if (client > 0 && client <= MaxClients)
    {
        gNextAllowed[client] = 0.0;
    }
}

public void OnClientDisconnect(int client)
{
    if (client > 0 && client <= MaxClients)
    {
        gNextAllowed[client] = 0.0;
    }
}

static bool IsWordChar(int c)
{
    // Consider letters, numbers, underscore as "word" characters.
    return (c >= '0' && c <= '9')
        || (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c == '_');
}

static bool IsBoundary(const char[] text, int index)
{
    // Boundary is start/end or non-word char.
    int length = strlen(text);
    if (index < 0 || index >= length)
    {
        return true;
    }
    return !IsWordChar(text[index]);
}

static bool ContainsPingDerivative(const char[] lowerText)
{
    int len = strlen(lowerText);

    for (int i = 0; i < len; i++)
    {
        if (lowerText[i] != 'p')
            continue;

        // Need at least "ping"
        if (lowerText[i + 1] != 'i' || lowerText[i + 2] != 'n' || lowerText[i + 3] != 'g')
            continue;

        // Must start on a word boundary
        if (!IsBoundary(lowerText, i - 1))
            continue;

        int j = i + 4;

        // Allow stretched g: pinggggg
        while (lowerText[j] == 'g')
        {
            j++;
        }

        // Must end on a word boundary
        if (IsBoundary(lowerText, j))
        {
            return true;
        }
    }

    return false;
}


static bool ContainsLagDerivative(const char[] lowerText)
{
    int len = strlen(lowerText);

    for (int i = 0; i < len; i++)
    {
        if (lowerText[i] != 'l')
            continue;

        // Look for "lag" starting at i
        if (lowerText[i + 1] != 'a' || lowerText[i + 2] != 'g')
            continue;

        // Must start on a word boundary
        if (!IsBoundary(lowerText, i - 1))
            continue;

        int j = i + 3;

        // Allow stretched g: laggggg
        while (lowerText[j] == 'g')
        {
            j++;
        }

        // Optional derivatives: laggy, lagging
        // After lag(g*), allow:
        // - 'y' (laggy)
        // - "ing" (lagging)
        if (lowerText[j] == 'y')
        {
            j++;
        }
        else if (lowerText[j] == 'i' && lowerText[j + 1] == 'n' && lowerText[j + 2] == 'g')
        {
            j += 3;
        }

        // Must end on a word boundary
        if (IsBoundary(lowerText, j))
        {
            return true;
        }
    }

    return false;
}

public Action Command_Say(int client, const char[] command, int argc)
{
    if (!gCvarEnabled.BoolValue)
        return Plugin_Continue;

    if (client <= 0 || client > MaxClients || !IsClientInGame(client) || IsFakeClient(client))
        return Plugin_Continue;

    char msg[256];
    GetCmdArgString(msg, sizeof(msg));
    StripQuotes(msg);
    TrimString(msg);

    if (msg[0] == '\0')
        return Plugin_Continue;

    // Ignore common chat command prefixes
    if (msg[0] == '!' || msg[0] == '/' || msg[0] == '@')
        return Plugin_Continue;

    float now = GetGameTime();
    if (now < gNextAllowed[client])
        return Plugin_Continue;

    // Lowercase copy for case-insensitive matching
    char lowerMsg[256];
    strcopy(lowerMsg, sizeof(lowerMsg), msg);
    for (int i = 0; lowerMsg[i] != '\0'; i++)
    {
        if (lowerMsg[i] >= 'A' && lowerMsg[i] <= 'Z')
        {
            lowerMsg[i] = lowerMsg[i] + ('a' - 'A');
        }
    }

    bool matched = false;

    if (ContainsPingDerivative(lowerMsg))
		matched = true;
    else if (ContainsLagDerivative(lowerMsg))
        matched = true;

    if (!matched)
        return Plugin_Continue;

    char reply[256];
    gCvarMessage.GetString(reply, sizeof(reply));
    SendSayText2ToClient(client, reply);

    float cd = gCvarCooldown.FloatValue;
    gNextAllowed[client] = now + cd;

    return Plugin_Continue;
}
