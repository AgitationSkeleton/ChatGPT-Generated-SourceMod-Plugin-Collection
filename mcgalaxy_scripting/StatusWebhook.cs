//reference System.Net.dll
//reference System.dll

// Author: ChatGPT
// StatusWebhook - periodic Discord status webhook for MCGalaxy (ClassiCube)
//
// Sends a Discord webhook embed every 15 minutes (America/Los_Angeles) with server/player status.
// Includes /statuswebhook test and /statuswebhook reload.

using System;
using System.IO;
using System.Net;
using System.Text;

using MCGalaxy;
using MCGalaxy.Commands;
using MCGalaxy.Tasks;

namespace Core
{
    public class StatusWebhook : Plugin
    {
        public override string name { get { return "StatusWebhook"; } }
        public override string creator { get { return "ChatGPT"; } }

        // If this causes issues on your build, you can remove this override.
        public override string MCGalaxy_Version { get { return "1.9.1.2"; } }

        const string FolderPath = "plugins/StatusWebhook";
        const string ConfigPath = "plugins/StatusWebhook/config.txt";

        const string DefaultIconUrl = "https://resources.redchanit.xyz/games/minecraftbeta.png";
        const int EmbedColor = 0x57F287;

        static readonly object SendLock = new object();

        static string webhookUrl = "";
        static string serverName = "My MCGalaxy Server";
        static string serverAddress = "example.com:25565";
        static string serverWebsite = "https://example.com";
        static string serverIconUrl = DefaultIconUrl;
        static int intervalMinutes = 15;

        static int lastTriggerHour = -1;
        static int lastTriggerQuarter = -1;

        public static SchedulerTask task;

        public override void Load(bool startup)
        {
            EnsureFolder();
            EnsureConfig();
            LoadConfig();

            Command.Register(new CmdStatusWebhook());

            // Tick every second, only fires when minute boundary matches interval
            Server.MainScheduler.QueueRepeat(DoTick, null, TimeSpan.FromSeconds(1));

            Logger.Log(LogType.SystemActivity, "[StatusWebhook] Loaded.");
        }

        public override void Unload(bool shutdown)
        {
            Server.MainScheduler.Cancel(task);
            Command.Unregister(Command.Find("StatusWebhook"));

            Logger.Log(LogType.SystemActivity, "[StatusWebhook] Unloaded.");
        }

        public static void DoTick(SchedulerTask t)
        {
            task = t;

            try
            {
                CheckAndSendPeriodicStatus();
            }
			catch (Exception ex)
			{
				Logger.Log(LogType.Error, "[StatusWebhook] Tick error:");
				Logger.LogError(ex);
			}			
        }

        static void CheckAndSendPeriodicStatus()
        {
            if (string.IsNullOrEmpty(webhookUrl) ||
                webhookUrl.IndexOf("REPLACE_ME", StringComparison.OrdinalIgnoreCase) >= 0)
                return;

            DateTime nowLocal = GetLosAngelesNow();

            int minute = nowLocal.Minute;
            if ((minute % intervalMinutes) != 0) return;

            int hour = nowLocal.Hour;
            int quarter = minute / intervalMinutes;

            if (hour == lastTriggerHour && quarter == lastTriggerQuarter) return;

            // Only post right at the start of the minute (reduces duplicates on lag)
            if (nowLocal.Second > 3) return;

            lastTriggerHour = hour;
            lastTriggerQuarter = quarter;

            string description = BuildDescription(nowLocal);
            PostDiscordWebhook(description);
        }

        static string BuildDescription(DateTime nowLocal)
        {
            Player[] onlinePlayers = PlayerInfo.Online.Items;
            int onlineCount = PlayerInfo.Online.Count;
            int maxPlayers = Server.Config.MaxPlayers;

            StringBuilder sb = new StringBuilder();

            sb.Append(serverName).Append("\n");
            sb.Append(serverAddress).Append("\n");
            sb.Append(serverWebsite).Append("\n\n");

            sb.Append("Online players: ").Append(onlineCount).Append("/").Append(maxPlayers).Append("\n\n");

            if (onlineCount > 0)
            {
                sb.Append("Online players:\n");
                for (int i = 0; i < onlinePlayers.Length; i++)
                {
                    Player p = onlinePlayers[i];
                    if (p == null) continue;
                    sb.Append(" - ").Append(p.name).Append("\n");
                }
            }
            else
            {
                sb.Append("No players are currently online.\n");
            }

            sb.Append("\nLast updated: ")
              .Append(nowLocal.ToString("yyyy-MM-dd HH:mm:ss"))
              .Append(" America/Los_Angeles");

            return sb.ToString();
        }

        static void PostDiscordWebhook(string descriptionText)
        {
            // Skip posting when server is empty
            if (PlayerInfo.Online.Count <= 0) return;

            lock (SendLock)
            {
                try
                {
                    string username = serverName + " Status";

                    // Minimal Discord webhook JSON (no external libs)
                    // { "username":"...", "embeds":[{ "author":{...}, "description":"...", "color":... }] }
                    string json =
                        "{"
                        + "\"username\":\"" + EscapeJson(username) + "\","
                        + "\"embeds\":[{"
                            + "\"author\":{"
                                + "\"name\":\"" + EscapeJson(serverName) + "\","
                                + "\"icon_url\":\"" + EscapeJson(serverIconUrl) + "\""
                            + "},"
                            + "\"description\":\"" + EscapeJson(descriptionText) + "\","
                            + "\"color\":" + EmbedColor
                        + "}]"
                        + "}";

					using (WebClient wc = new WebClient())
					{
						wc.Headers[HttpRequestHeader.ContentType] = "application/json; charset=utf-8";
					
						byte[] payload = Encoding.UTF8.GetBytes(json);
						wc.UploadData(webhookUrl, "POST", payload);
					}
					

                    Logger.Log(LogType.SystemActivity, "[StatusWebhook] Posted status webhook.");
                }
                catch (WebException ex)
                {
                    // Optional: log Discord error body for debugging
                    try
                    {
                        if (ex.Response != null)
                        {
                            using (StreamReader sr = new StreamReader(ex.Response.GetResponseStream()))
                            {
                                string body = sr.ReadToEnd();
                                Logger.Log(LogType.Warning, "[StatusWebhook] Discord error: " + body);
                            }
                        }
                    }
                    catch { }

                    Logger.Log(LogType.Error, "[StatusWebhook] Failed to post webhook:");
					Logger.LogError(ex);
                }
				catch (Exception ex)
				{
					Logger.Log(LogType.Error, "[StatusWebhook] Failed to post webhook:");
					Logger.LogError(ex);
				}				
            }
        }

        static string EscapeJson(string value)
        {
            if (value == null) return "";
            StringBuilder sb = new StringBuilder(value.Length + 16);

            for (int i = 0; i < value.Length; i++)
            {
                char c = value[i];
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 32) sb.Append(' ');
                        else sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        static void EnsureFolder()
        {
            if (!Directory.Exists(FolderPath))
                Directory.CreateDirectory(FolderPath);
        }

        static void EnsureConfig()
        {
            if (File.Exists(ConfigPath)) return;

            StringBuilder sb = new StringBuilder();
            sb.AppendLine("# StatusWebhook configuration (key=value)");
            sb.AppendLine("webhookUrl=REPLACE_ME");
            sb.AppendLine();
            sb.AppendLine("serverName=My MCGalaxy Server");
            sb.AppendLine("serverAddress=example.com:25565");
            sb.AppendLine("serverWebsite=https://example.com");
            sb.AppendLine("serverIconUrl=" + DefaultIconUrl);
            sb.AppendLine();
            sb.AppendLine("intervalMinutes=15");

            File.WriteAllText(ConfigPath, sb.ToString());
        }

        static void LoadConfig()
        {
            string[] lines = File.ReadAllLines(ConfigPath);

            foreach (string rawLine in lines)
            {
                string line = rawLine.Trim();
                if (line.Length == 0) continue;
                if (line.StartsWith("#")) continue;

                int eq = line.IndexOf('=');
                if (eq <= 0) continue;

                string key = line.Substring(0, eq).Trim();
                string val = line.Substring(eq + 1).Trim();

                if (key.Equals("webhookUrl", StringComparison.OrdinalIgnoreCase)) webhookUrl = val;
                else if (key.Equals("serverName", StringComparison.OrdinalIgnoreCase)) serverName = val;
                else if (key.Equals("serverAddress", StringComparison.OrdinalIgnoreCase)) serverAddress = val;
                else if (key.Equals("serverWebsite", StringComparison.OrdinalIgnoreCase)) serverWebsite = val;
                else if (key.Equals("serverIconUrl", StringComparison.OrdinalIgnoreCase)) serverIconUrl = val;
                else if (key.Equals("intervalMinutes", StringComparison.OrdinalIgnoreCase))
                {
                    int parsed;
                    if (int.TryParse(val, out parsed) && parsed > 0 && parsed <= 1440)
                        intervalMinutes = parsed;
                }
            }

            if (string.IsNullOrEmpty(serverIconUrl)) serverIconUrl = DefaultIconUrl;

            Logger.Log(LogType.SystemActivity,
                "[StatusWebhook] Config loaded. intervalMinutes=" + intervalMinutes + ", serverName=" + serverName);
        }

        static DateTime GetLosAngelesNow()
        {
            // Try common timezone IDs; fallback to local time if not available.
            try
            {
                TimeZoneInfo tz = null;

                try { tz = TimeZoneInfo.FindSystemTimeZoneById("America/Los_Angeles"); }
                catch { }

                if (tz == null)
                {
                    try { tz = TimeZoneInfo.FindSystemTimeZoneById("Pacific Standard Time"); }
                    catch { }
                }

                if (tz != null)
                    return TimeZoneInfo.ConvertTimeFromUtc(DateTime.UtcNow, tz);
            }
            catch { }

            return DateTime.Now;
        }
    }

    public class CmdStatusWebhook : Command
    {
        public override string name { get { return "StatusWebhook"; } }
        public override string type { get { return "other"; } }
        public override LevelPermission defaultRank { get { return LevelPermission.Operator; } }

        public override void Use(Player p, string message)
        {
            string arg = (message ?? "").Trim();

            if (arg.Equals("reload", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    // Reload config and reset de-dupe
                    typeof(StatusWebhook).GetMethod("EnsureFolder", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).Invoke(null, null);
                    typeof(StatusWebhook).GetMethod("EnsureConfig", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).Invoke(null, null);
                    typeof(StatusWebhook).GetMethod("LoadConfig", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).Invoke(null, null);

                    // reset dedupe fields
                    typeof(StatusWebhook).GetField("lastTriggerHour", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).SetValue(null, -1);
                    typeof(StatusWebhook).GetField("lastTriggerQuarter", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static).SetValue(null, -1);

                    p.Message("&a[StatusWebhook]&S Reloaded config.");
                }
                catch (Exception ex)
                {
                    p.Message("&c[StatusWebhook]&S Reload failed. See server log.");
                    Logger.Log(LogType.Error, "[StatusWebhook] Reload failed:");
					Logger.LogError(ex);
                }
                return;
            }

            if (arg.Equals("test", StringComparison.OrdinalIgnoreCase) || arg.Length == 0)
            {
                try
                {
                    // Call the private helpers directly via reflection to keep code in one file
                    DateTime nowLocal = (DateTime)typeof(StatusWebhook)
                        .GetMethod("GetLosAngelesNow", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)
                        .Invoke(null, null);

                    string description = (string)typeof(StatusWebhook)
                        .GetMethod("BuildDescription", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)
                        .Invoke(null, new object[] { nowLocal });

                    typeof(StatusWebhook)
                        .GetMethod("PostDiscordWebhook", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)
                        .Invoke(null, new object[] { description });

                    p.Message("&a[StatusWebhook]&S Sent a test status webhook.");
                }
                catch (Exception ex)
                {
                    p.Message("&c[StatusWebhook]&S Test send failed. See server log.");
                    Logger.Log(LogType.Error, "[StatusWebhook] Test send failed:");
					Logger.LogError(ex);
                }
                return;
            }

            Help(p);
        }

        public override void Help(Player p)
        {
            p.Message("&T/statuswebhook test");
            p.Message("&H Sends a status webhook immediately.");
            p.Message("&T/statuswebhook reload");
            p.Message("&H Reloads plugins/StatusWebhook/config.txt");
        }
    }
}
