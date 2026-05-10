#pragma semicolon 1
#pragma newdecls required

#include <sourcemod>

public Plugin myinfo =
{
    name = "Map-Based FastDL Rotator",
    author = "ChatGPT",
    description = "Changes sv_downloadurl based on the current map name.",
    version = "1.0.0",
    url = ""
};

#define URL_4DM  "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_4dm/tf2classified/"
#define URL_MCTF "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_mctf/tf2classified/"
#define URL_INF  "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_inf/tf2classified/"
#define URL_4RDM "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_4rdm/tf2classified/"
#define URL_MDOM "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_mdom/tf2classified/"
#define URL_4IG  "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_4ig/tf2classified/"
#define URL_4GG  "https://agitationskeleton.github.io/viosarcadefastdl_tf2c_4gg/tf2classified/"

public void OnPluginStart()
{
    ApplyFastDLForCurrentMap();
}

public void OnMapStart()
{
    ApplyFastDLForCurrentMap();
}

void ApplyFastDLForCurrentMap()
{
    char currentMap[PLATFORM_MAX_PATH];
    GetCurrentMap(currentMap, sizeof(currentMap));

    char downloadUrl[256];
    downloadUrl[0] = '\0';

    if (StrContains(currentMap, "4dm_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_4DM);
    }
    else if (StrContains(currentMap, "mctf_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_MCTF);
    }
    else if (StrContains(currentMap, "inf_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_INF);
    }
    else if (StrContains(currentMap, "4rdm_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_4RDM);
    }
    else if (StrContains(currentMap, "mdom_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_MDOM);
    }
    else if (StrContains(currentMap, "4ig_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_4IG);
    }
    else if (StrContains(currentMap, "4gg_", false) != -1)
    {
        strcopy(downloadUrl, sizeof(downloadUrl), URL_4GG);
    }

    if (downloadUrl[0] == '\0')
    {
        LogMessage("[FastDL Rotator] No matching FastDL rule for map: %s", currentMap);
        return;
    }

    ServerCommand("sv_downloadurl \"%s\"", downloadUrl);
    ServerExecute();

    LogMessage("[FastDL Rotator] Map: %s | sv_downloadurl set to: %s", currentMap, downloadUrl);
}