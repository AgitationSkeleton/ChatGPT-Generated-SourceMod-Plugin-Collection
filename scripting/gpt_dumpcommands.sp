/**
 * Dump SM Client Commands
 * - Adds: sm_dumpcommands (chat trigger: !dumpcommands)
 * - Dumps all "sm_" commands (SourceMod plugin commands) to a text file, including:
 *   - command name
 *   - description
 *   - effective admin flags (permissions)
 *   - plugin filename/name (if available)
 *
 * Author: ChatGPT
 */

#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>
#include <console>
#include <admin>

public Plugin myinfo =
{
    name = "SM Dump Commands",
    author = "ChatGPT",
    description = "Dumps sm_ commands, their plugin source, and effective admin flags to a file",
    version = "1.0.1",
    url = ""
};

static const char g_outputSubdir[] = "data/command_dumps";

public void OnPluginStart()
{
    // Root-only by default (z). Can be overridden in admin_overrides.cfg if you want.
    RegAdminCmd(
        "sm_dumpcommands",
        Cmd_DumpCommands,
        ADMFLAG_ROOT,
        "Dumps all client-usable sm_ commands to a file (chat trigger: !dumpcommands)"
    );
}

public Action Cmd_DumpCommands(int client, int args)
{
    if (client <= 0 || !IsClientInGame(client))
    {
        ReplyToCommand(client, "[SM] This command must be run by an in-game admin client.");
        return Plugin_Handled;
    }

    // Build output directory: addons/sourcemod/data/command_dumps
    char outputDir[PLATFORM_MAX_PATH];
    BuildPath(Path_SM, outputDir, sizeof(outputDir), "%s", g_outputSubdir);

    // Ensure directory exists (511 = 0777)
    CreateDirectory(outputDir, 511);

    // Timestamped filename
    char timeStamp[32];
    FormatTime(timeStamp, sizeof(timeStamp), "%Y%m%d-%H%M%S", GetTime());

    char mapName[64];
    GetCurrentMap(mapName, sizeof(mapName));

    char outputFile[PLATFORM_MAX_PATH];
    BuildPath(Path_SM, outputFile, sizeof(outputFile), "%s/sm_commands_%s_%s.txt", g_outputSubdir, mapName, timeStamp);

    File fileHandle = OpenFile(outputFile, "w");
    if (fileHandle == null)
    {
        ReplyToCommand(client, "[SM] Failed to open output file for writing: %s", outputFile);
        return Plugin_Handled;
    }

    // Header
    ConVar hostnameCvar = FindConVar("hostname");
    char serverName[256];
    serverName[0] = '\0';
    if (hostnameCvar != null)
    {
        hostnameCvar.GetString(serverName, sizeof(serverName));
    }
    if (serverName[0] == '\0')
    {
        strcopy(serverName, sizeof(serverName), "(unknown hostname)");
    }

    fileHandle.WriteLine("SourceMod sm_ Command Dump");
    fileHandle.WriteLine("Server: %s", serverName);
    fileHandle.WriteLine("Map: %s", mapName);
    fileHandle.WriteLine("Time: %s", timeStamp);
    fileHandle.WriteLine("");
    fileHandle.WriteLine("Format:");
    fileHandle.WriteLine("  <command> | flags=<flagstring or (none)> | plugin=<file> | name=<plugin name> | desc=<description>");
    fileHandle.WriteLine("");

    int dumpedCount = 0;

    CommandIterator commandIter = new CommandIterator();
    while (commandIter.Next())
    {
        char commandName[128];
        commandIter.GetName(commandName, sizeof(commandName));

        // Only sm_ commands
        if (strncmp(commandName, "sm_", 3, false) != 0)
        {
            continue;
        }

        // Description
        char commandDesc[256];
        commandIter.GetDescription(commandDesc, sizeof(commandDesc));

        // Effective admin flags
        int adminFlagBits = commandIter.AdminFlags;

        char flagsString[64];
        if (adminFlagBits == 0)
        {
            strcopy(flagsString, sizeof(flagsString), "(none)");
        }
        else
        {
            FlagBitsToString(adminFlagBits, flagsString, sizeof(flagsString));
            if (flagsString[0] == '\0')
            {
                strcopy(flagsString, sizeof(flagsString), "(unknown)");
            }
        }

        // Plugin attribution (creator)
        char pluginFile[PLATFORM_MAX_PATH];
        char pluginName[256];
        strcopy(pluginFile, sizeof(pluginFile), "(unknown)");
        strcopy(pluginName, sizeof(pluginName), "(unknown)");

        Handle creatorPlugin = commandIter.Plugin;
        if (creatorPlugin != null)
        {
            // Older SM: filename via GetPluginFilename(), metadata via GetPluginInfo() (PlInfo_Name exists)
            GetPluginFilename(creatorPlugin, pluginFile, sizeof(pluginFile));
            GetPluginInfo(creatorPlugin, PlInfo_Name, pluginName, sizeof(pluginName));

            if (pluginFile[0] == '\0')
            {
                strcopy(pluginFile, sizeof(pluginFile), "(unknown)");
            }
            if (pluginName[0] == '\0')
            {
                strcopy(pluginName, sizeof(pluginName), "(unknown)");
            }
        }

        fileHandle.WriteLine(
            "%s | flags=%s | plugin=%s | name=%s | desc=%s",
            commandName,
            flagsString,
            pluginFile,
            pluginName,
            (commandDesc[0] != '\0') ? commandDesc : "(no description)"
        );

        dumpedCount++;
    }

    delete commandIter;
    delete fileHandle;

    ReplyToCommand(client, "[SM] Dumped %d sm_ commands to: %s", dumpedCount, outputFile);
    return Plugin_Handled;
}
