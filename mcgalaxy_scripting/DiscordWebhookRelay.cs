//reference System.Net.dll
//reference System.dll

// Author: ChatGPT
// DiscordWebhookRelay - two-way Discord relay for MCGalaxy (ClassiCube)
// OUTBOUND: Server -> Discord via webhook (per-player avatar using CC face PNG)
// INBOUND : Discord -> Server via bot token + channel ID (REST polling)
//
// Config (auto-created on first load):
// ./plugins/DiscordWebhookRelay/config.txt
// State:
// ./plugins/DiscordWebhookRelay/state_last_seen.txt
//
// Commands:
// /dwh status
// /dwh reload
// /dwh test [text]
// /dwh on|off

using System;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Net.Security;
using System.Security.Authentication;


using MCGalaxy;
using MCGalaxy.Commands;
using MCGalaxy.Events.PlayerEvents;

namespace Core
{
    public sealed class DiscordWebhookRelay : Plugin
    {
        public override string name { get { return "DiscordWebhookRelay"; } }
        public override string creator { get { return "ChatGPT"; } }
        public override string MCGalaxy_Version { get { return "1.9.1.2"; } }

        const string FolderPath = "plugins/DiscordWebhookRelay";
        const string ConfigPath = "plugins/DiscordWebhookRelay/config.txt";
        const string StatePath  = "plugins/DiscordWebhookRelay/state_last_seen.txt";

        static readonly object SendLock = new object();
        static DateTime lastSendUtc = DateTime.MinValue;

        // ---------------------------
        // OUTBOUND CONFIG (webhook)
        // ---------------------------
        static bool enabled = true;
        static string webhookUrl = "REPLACE_ME";

        static bool sendChat = true;
        static bool sendJoinLeave = true;
        static bool logCommands = true;

        static bool censorPassArgs = true;

        static int maxContentLen = 1900;
        static int minDelayMs = 200;

        static bool usePlayerAvatar = true;
        static string avatarTemplate = "https://cdn.classicube.net/face/{0}.png";

        // usernameMode=player|static
        static string usernameMode = "player";
        static string staticUsername = "MCGalaxy";

        // Formats: {0}=player, {1}=message/command, {2}=world
        static string chatFormat = "[{2}] <{0}> {1}";
        static string joinFormat = "[{1}] {0} joined.";
        static string leaveFormat = "[{1}] {0} left.";
        static string commandFormat = "[{2}] {0} ran: {1}";

        // ---------------------------
        // INBOUND CONFIG (bot polling)
        // ---------------------------
        static bool inboundEnabled = false;

        // NOTE: This is a Discord BOT token, format "Bot <token>" is applied automatically.
        static string discordBotToken = "REPLACE_ME";
        static string discordChannelId = "REPLACE_ME";

        static int pollSeconds = 3; // >= 2 recommended to avoid rate limits
        static int inboundFetchLimit = 10;

        static bool inboundIgnoreBots = true;
        static bool inboundIgnoreWebhooks = true;

        // Optional safety: prevents discord mentions pinging people in MC logs/etc.
        static bool inboundSanitizeMentions = true;

        // Optional: if true, ignores messages that start with '/'
        static bool inboundIgnoreSlashCommands = false;

        // Inbound format requested:
        // "&5(Discord) <Username>&e : <message>"
        static string inboundFormat = "&5(Discord) <{0}>&e : {1}";

        // ---------------------------
        // INBOUND runtime state
        // ---------------------------
        static Timer inboundTimer;
        static volatile int inboundBusy = 0;

        // store last seen snowflake
        static ulong lastSeenMessageId = 0;

        public override void Load(bool startup)
        {
            EnsureFolder();
            EnsureConfig();
            LoadConfig();
            LoadState();

            Command.Register(new CmdDiscordWebhookRelay());

            OnPlayerChatEvent.Register(HandleChat, Priority.Low);
            OnPlayerConnectEvent.Register(HandleConnect, Priority.Low);
            OnPlayerDisconnectEvent.Register(HandleDisconnect, Priority.Low);
            OnPlayerCommandEvent.Register(HandleCommand, Priority.Low);
			
			// Force TLS 1.2 for Discord API (important on older .NET defaults)
			try {
				ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072; // Tls12
				ServicePointManager.Expect100Continue = false;
			} catch { }
			

            StartInboundIfEnabled();

            Logger.Log(LogType.SystemActivity, "[DiscordWebhookRelay] Loaded.");
        }

        public override void Unload(bool shutdown)
        {
            StopInbound();

            OnPlayerChatEvent.Unregister(HandleChat);
            OnPlayerConnectEvent.Unregister(HandleConnect);
            OnPlayerDisconnectEvent.Unregister(HandleDisconnect);
            OnPlayerCommandEvent.Unregister(HandleCommand);

            Command.Unregister(Command.Find("dwh"));

            Logger.Log(LogType.SystemActivity, "[DiscordWebhookRelay] Unloaded.");
        }

        // ---------------------------
        // OUTBOUND EVENTS
        // ---------------------------
        static void HandleChat(Player p, string message)
        {
            if (!enabled || !sendChat) return;
            if (p == null || p.IsConsole) return;
            if (!IsWebhookReady()) return;

            string cleanMsg = StripColorCodes(message);
            if (string.IsNullOrEmpty(cleanMsg)) return;

            string world = p.level != null ? p.level.name : "unknown";
            string content = string.Format(chatFormat, p.name, cleanMsg, world);
            SendWebhook(p.name, content);
        }

        static void HandleConnect(Player p)
        {
            if (!enabled || !sendJoinLeave) return;
            if (p == null || p.IsConsole) return;
            if (!IsWebhookReady()) return;

            string world = p.level != null ? p.level.name : "unknown";
            string content = string.Format(joinFormat, p.name, world);
            SendWebhook(p.name, content);
        }

        static void HandleDisconnect(Player p, string discmsg)
        {
            if (!enabled || !sendJoinLeave) return;
            if (p == null || p.IsConsole) return;
            if (!IsWebhookReady()) return;

            string world = p.level != null ? p.level.name : "unknown";
            string content = string.Format(leaveFormat, p.name, world);
            SendWebhook(p.name, content);
        }

        static void HandleCommand(Player p, string cmd, string args, CommandData data)
        {
            if (!enabled || !logCommands) return;
            if (p == null || p.IsConsole) return;
            if (!IsWebhookReady()) return;

            string commandName = (cmd ?? "").Trim();
            if (commandName.Length == 0) return;

            string argText = (args ?? "");

            if (censorPassArgs)
            {
                string lower = commandName.ToLowerInvariant();
                if (lower == "pass" || lower == "setpass")
                {
                    if (argText.Length > 0) argText = "<censored>";
                }
            }

            string full = (argText.Length == 0) ? ("/" + commandName) : ("/" + commandName + " " + argText);
            full = StripColorCodes(full);

            string world = p.level != null ? p.level.name : "unknown";
            string content = string.Format(commandFormat, p.name, full, world);
            SendWebhook(p.name, content);
        }

        static bool IsWebhookReady()
        {
            if (string.IsNullOrEmpty(webhookUrl)) return false;
            if (webhookUrl.IndexOf("REPLACE_ME", StringComparison.OrdinalIgnoreCase) >= 0) return false;
            return true;
        }

        static void SendWebhook(string playerName, string content)
        {
            if (content == null) content = "";
            if (content.Length > maxContentLen) content = content.Substring(0, maxContentLen) + "...";

            lock (SendLock)
            {
                // soft delay between sends to reduce 429s
                TimeSpan since = DateTime.UtcNow - lastSendUtc;
                if (since.TotalMilliseconds < minDelayMs)
                {
                    int sleepMs = (int)(minDelayMs - since.TotalMilliseconds);
                    if (sleepMs > 0 && sleepMs <= 2000)
                        Thread.Sleep(sleepMs);
                }
                lastSendUtc = DateTime.UtcNow;

                try
                {
                    string username = usernameMode.Equals("player", StringComparison.OrdinalIgnoreCase)
                        ? playerName
                        : staticUsername;

                    string avatarUrl = "";
                    if (usePlayerAvatar)
                    {
                        string escaped = Uri.EscapeDataString((playerName ?? "").Trim());
                        if (escaped.Length > 0) avatarUrl = string.Format(avatarTemplate, escaped);
                    }

                    string json = BuildWebhookJson(username, avatarUrl, content);

                    using (WebClient wc = new WebClient())
                    {
                        wc.Headers[HttpRequestHeader.ContentType] = "application/json; charset=utf-8";
                        byte[] payload = Encoding.UTF8.GetBytes(json);
                        wc.UploadData(webhookUrl, "POST", payload);
                    }
                }
				catch (WebException wex)
				{
					HttpWebResponse resp = wex.Response as HttpWebResponse;
					if (resp != null && (int)resp.StatusCode == 429)
					{
						double retrySec = ExtractRetryAfterSeconds(wex);
						if (retrySec < 1) retrySec = pollSeconds;
						Thread.Sleep((int)(retrySec * 1000));
						return;
					}
				
					Logger.Log(LogType.Warning, "[DiscordWebhookRelay] Outbound WebException: " + wex.Message);
				
					// Try to read body (Discord returns helpful JSON)
					TryLogDiscordErrorBody(wex, "[DiscordWebhookRelay] Outbound response: ");
				
					// Also log HTTP code if we have one
					if (resp != null)
						Logger.Log(LogType.Warning, "[DiscordWebhookRelay] Outbound HTTP status: " + (int)resp.StatusCode + " " + resp.StatusCode);
				
					return;
				}
                catch (Exception ex)
                {
                    Logger.Log(LogType.Error, "[DiscordWebhookRelay] Webhook send failed:");
                    Logger.LogError(ex);
                }
            }
        }

        static string BuildWebhookJson(string username, string avatarUrl, string content)
        {
            // { "username":"...", "avatar_url":"...", "content":"..." }
            StringBuilder sb = new StringBuilder();
            sb.Append("{");

            bool first = true;

            if (!string.IsNullOrEmpty(username))
                AppendJsonField(sb, "username", username, ref first);

            if (!string.IsNullOrEmpty(avatarUrl))
                AppendJsonField(sb, "avatar_url", avatarUrl, ref first);

            AppendJsonField(sb, "content", content ?? "", ref first);

            sb.Append("}");
            return sb.ToString();
        }

        // ---------------------------
        // INBOUND (Discord -> Server)
        // ---------------------------
        static void StartInboundIfEnabled()
        {
            StopInbound();

            if (!enabled) return;
            if (!inboundEnabled) return;

            if (string.IsNullOrEmpty(discordBotToken) || discordBotToken.IndexOf("REPLACE_ME", StringComparison.OrdinalIgnoreCase) >= 0) return;
            if (string.IsNullOrEmpty(discordChannelId) || discordChannelId.IndexOf("REPLACE_ME", StringComparison.OrdinalIgnoreCase) >= 0) return;

            if (pollSeconds < 2) pollSeconds = 2;
            if (inboundFetchLimit < 1) inboundFetchLimit = 1;
            if (inboundFetchLimit > 50) inboundFetchLimit = 50;

            inboundTimer = new Timer(InboundPollTick, null, 1000, pollSeconds * 1000);
            Logger.Log(LogType.SystemActivity, "[DiscordWebhookRelay] Inbound polling started (every " + pollSeconds + "s).");
        }

        static void StopInbound()
        {
            try
            {
                if (inboundTimer != null)
                {
                    inboundTimer.Dispose();
                    inboundTimer = null;
                }
            }
            catch { }
        }

        static void InboundPollTick(object state)
        {
            // prevent overlap if a poll takes longer than interval
            if (Interlocked.Exchange(ref inboundBusy, 1) == 1) return;

            try
            {
                if (!enabled || !inboundEnabled) return;
                if (string.IsNullOrEmpty(discordBotToken) || string.IsNullOrEmpty(discordChannelId)) return;

                string url = "https://discord.com/api/v10/channels/" + discordChannelId + "/messages?limit=" + inboundFetchLimit;

                string json;
                using (WebClient wc = new WebClient())
                {
                    string token = (discordBotToken ?? "").Trim();
					if (token.StartsWith("Bot ", StringComparison.OrdinalIgnoreCase))
						token = token.Substring(4).Trim();
					
					wc.Headers[HttpRequestHeader.Authorization] = "Bot " + token;
					
                    wc.Headers[HttpRequestHeader.UserAgent] = "MCGalaxy-DiscordWebhookRelay";

                    try
                    {
                        json = wc.DownloadString(url);
                    }
                    catch (WebException wex)
                    {
                        // Handle rate limits (429) gently
                        HttpWebResponse resp = wex.Response as HttpWebResponse;
                        if (resp != null && (int)resp.StatusCode == 429)
                        {
                            double retrySec = ExtractRetryAfterSeconds(wex);
                            if (retrySec < 1) retrySec = pollSeconds;
                            Thread.Sleep((int)(retrySec * 1000));
                            return;
                        }

                        // Other errors: log sometimes but don’t spam hard
                        TryLogDiscordErrorBody(wex, "[DiscordWebhookRelay] Inbound error: ");
                        return;
                    }
                }

                if (string.IsNullOrEmpty(json)) return;

                // Discord returns newest-first array. We parse multiple messages and then
                // process them oldest->newest for correct order.
                DiscordMessage[] messages = DiscordMessageParser.ParseMessages(json);
                if (messages == null || messages.Length == 0) return;

                // reverse order (oldest first)
                for (int i = messages.Length - 1; i >= 0; i--)
                {
                    DiscordMessage msg = messages[i];
                    if (msg == null) continue;

                    if (!msg.IdOk) continue;

                    // skip already seen
                    if (lastSeenMessageId != 0 && msg.Id <= lastSeenMessageId) continue;

                    // update last seen immediately so we don't replay even if broadcast fails
                    lastSeenMessageId = msg.Id;
                    SaveState();

                    if (inboundIgnoreBots && msg.AuthorBot) continue;
                    if (inboundIgnoreWebhooks && msg.HasWebhookId) continue;

                    string content = msg.Content ?? "";
                    if (content.Length == 0) continue;

                    if (inboundIgnoreSlashCommands && content.StartsWith("/")) continue;

                    content = DecodeDiscordEscapes(content);

                    if (inboundSanitizeMentions)
                        content = SanitizeMentions(content);

                    // Trim to something reasonable for in-game chat
                    if (content.Length > 500) content = content.Substring(0, 500) + "...";

                    string user = msg.AuthorUsername ?? "Unknown";
                    string formatted = FormatInboundMessage(inboundFormat, user, content);

                    // Broadcast to all players
                    Chat.MessageAll(formatted);
                }
            }
            catch (Exception ex)
            {
                Logger.Log(LogType.Warning, "[DiscordWebhookRelay] Inbound tick error: " + ex.Message);
            }
            finally
            {
                Interlocked.Exchange(ref inboundBusy, 0);
            }
        }

        static double ExtractRetryAfterSeconds(WebException wex)
        {
            try
            {
                if (wex.Response == null) return 0;

                using (StreamReader sr = new StreamReader(wex.Response.GetResponseStream()))
                {
                    string body = sr.ReadToEnd();
                    // Discord rate limit body often contains: "retry_after": <number>
                    int idx = body.IndexOf("\"retry_after\"", StringComparison.OrdinalIgnoreCase);
                    if (idx < 0) return 0;

                    int colon = body.IndexOf(':', idx);
                    if (colon < 0) return 0;

                    int start = colon + 1;
                    while (start < body.Length && (body[start] == ' ' || body[start] == '\t')) start++;

                    int end = start;
                    while (end < body.Length && (char.IsDigit(body[end]) || body[end] == '.')) end++;

                    string num = body.Substring(start, end - start);
                    double seconds;
                    if (double.TryParse(num, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out seconds))
                        return seconds;
                }
            }
            catch { }
            return 0;
        }

        static string SanitizeMentions(string s)
        {
            // Prevent @everyone / @here / user mentions from looking like pings in logs/clients
            // Replace '@' with a visually similar fullwidth variant
            return s.Replace("@", "＠");
        }

        
		static string FormatInboundMessage(string format, string username, string message)
		{
			// Support legacy token "{purple}" by translating it to MCGalaxy color code.
			// Then ensure only (Discord) + <name> are purple, and message portion is yellow.
			if (string.IsNullOrEmpty(format))
				format = "&5(Discord) <{0}>&e : {1}";
		
			string result = format;
		
			// Translate "{purple}" -> "&5" (purple/magenta)
			result = result.Replace("{purple}", "&5");
		
			// Replace placeholders
			result = result.Replace("{0}", username ?? "Unknown");
		
			// If the format contains "{1}" and does not already put a color right before it,
			// we force the message portion to yellow by prefixing "&e".
			// This matches your desired: purple tag+name, rest normal/yellow.
			result = result.Replace("{1}", "&e" + (message ?? ""));
		
			// Additionally, if the format includes the username and does NOT reset after it,
			// inject "&e" right after the closing ">" to stop the purple from bleeding.
			// (Only do this when the exact "<{0}>" pattern exists.)
			if (result.Contains("<" + (username ?? "Unknown") + ">") && !result.Contains(">&"))
			{
				result = result.Replace(">", ">&e");
			}
		
			return result;
		}
		
static string DecodeDiscordEscapes(string s)
        {
            // Discord JSON gives us escaped sequences; our parser extracts raw JSON string values.
            // This converts minimal escapes we care about.
            if (string.IsNullOrEmpty(s)) return "";

            StringBuilder sb = new StringBuilder(s.Length);
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                if (c != '\\' || i + 1 >= s.Length)
                {
                    sb.Append(c);
                    continue;
                }

                char n = s[i + 1];
                if (n == 'n') { sb.Append('\n'); i++; }
                else if (n == 'r') { sb.Append('\r'); i++; }
                else if (n == 't') { sb.Append('\t'); i++; }
                else if (n == '\\') { sb.Append('\\'); i++; }
                else if (n == '"') { sb.Append('\"'); i++; }
                else if (n == 'u' && i + 5 < s.Length)
                {
                    // \uXXXX
                    string hex = s.Substring(i + 2, 4);
                    int code;
                    if (int.TryParse(hex, System.Globalization.NumberStyles.HexNumber, null, out code))
                    {
                        sb.Append((char)code);
                        i += 5;
                    }
                    else
                    {
                        sb.Append(c);
                    }
                }
                else
                {
                    // unknown escape, keep as-is
                    sb.Append(n);
                    i++;
                }
            }
            return sb.ToString();
        }

        // ---------------------------
        // JSON helpers
        // ---------------------------
        static void AppendJsonField(StringBuilder sb, string key, string value, ref bool first)
        {
            if (!first) sb.Append(",");
            first = false;

            sb.Append("\"").Append(EscapeJson(key)).Append("\":\"").Append(EscapeJson(value)).Append("\"");
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

        static string StripColorCodes(string s)
        {
            // removes '&x' style color codes
            if (string.IsNullOrEmpty(s)) return "";
            StringBuilder sb = new StringBuilder(s.Length);

            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                if (c == '&' && i + 1 < s.Length)
                {
                    i++;
                    continue;
                }
                sb.Append(c);
            }
            return sb.ToString();
        }

        static void TryLogDiscordErrorBody(WebException ex, string prefix)
        {
            try
            {
                if (ex == null || ex.Response == null) return;
                using (StreamReader sr = new StreamReader(ex.Response.GetResponseStream()))
                {
                    string body = sr.ReadToEnd();
                    if (!string.IsNullOrEmpty(body))
                        Logger.Log(LogType.Warning, prefix + body);
                }
            }
            catch { }
        }

        // ---------------------------
        // Config + State
        // ---------------------------
        static void EnsureFolder()
        {
            if (!Directory.Exists(FolderPath))
                Directory.CreateDirectory(FolderPath);
        }

        static void EnsureConfig()
        {
            if (File.Exists(ConfigPath)) return;

            StringBuilder sb = new StringBuilder();
            sb.AppendLine("# DiscordWebhookRelay configuration (key=value)");
            sb.AppendLine("enabled=true");
            sb.AppendLine();
            sb.AppendLine("# OUTBOUND (Server -> Discord webhook)");
            sb.AppendLine("webhookUrl=REPLACE_ME");
            sb.AppendLine("sendChat=true");
            sb.AppendLine("sendJoinLeave=true");
            sb.AppendLine("logCommands=true");
            sb.AppendLine("censorPassArgs=true");
            sb.AppendLine("maxContentLen=1900");
            sb.AppendLine("minDelayMs=200");
            sb.AppendLine("usePlayerAvatar=true");
            sb.AppendLine("avatarTemplate=https://cdn.classicube.net/face/{0}.png");
            sb.AppendLine("usernameMode=player");
            sb.AppendLine("staticUsername=MCGalaxy");
            sb.AppendLine("chatFormat=[{2}] <{0}> {1}");
            sb.AppendLine("joinFormat=[{1}] {0} joined.");
            sb.AppendLine("leaveFormat=[{1}] {0} left.");
            sb.AppendLine("commandFormat=[{2}] {0} ran: {1}");
            sb.AppendLine();
            sb.AppendLine("# INBOUND (Discord -> Server via bot token polling)");
            sb.AppendLine("inboundEnabled=false");
            sb.AppendLine("discordBotToken=REPLACE_ME");
            sb.AppendLine("discordChannelId=REPLACE_ME");
            sb.AppendLine("pollSeconds=3");
            sb.AppendLine("inboundFetchLimit=10");
            sb.AppendLine("inboundIgnoreBots=true");
            sb.AppendLine("inboundIgnoreWebhooks=true");
            sb.AppendLine("inboundSanitizeMentions=true");
            sb.AppendLine("inboundIgnoreSlashCommands=false");
            sb.AppendLine("# Format: {0}=username, {1}=message");
            sb.AppendLine("inboundFormat=&5(Discord) <{0}>&e : {1}");

            File.WriteAllText(ConfigPath, sb.ToString());
        }

        static void LoadConfig()
        {
            try
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

                    // General
                    if (key.Equals("enabled", StringComparison.OrdinalIgnoreCase)) enabled = ParseBool(val, enabled);

                    // Outbound
                    else if (key.Equals("webhookUrl", StringComparison.OrdinalIgnoreCase)) webhookUrl = val;
                    else if (key.Equals("sendChat", StringComparison.OrdinalIgnoreCase)) sendChat = ParseBool(val, sendChat);
                    else if (key.Equals("sendJoinLeave", StringComparison.OrdinalIgnoreCase)) sendJoinLeave = ParseBool(val, sendJoinLeave);
                    else if (key.Equals("logCommands", StringComparison.OrdinalIgnoreCase)) logCommands = ParseBool(val, logCommands);
                    else if (key.Equals("censorPassArgs", StringComparison.OrdinalIgnoreCase)) censorPassArgs = ParseBool(val, censorPassArgs);
                    else if (key.Equals("maxContentLen", StringComparison.OrdinalIgnoreCase)) maxContentLen = ParseInt(val, maxContentLen, 200, 2000);
                    else if (key.Equals("minDelayMs", StringComparison.OrdinalIgnoreCase)) minDelayMs = ParseInt(val, minDelayMs, 0, 5000);
                    else if (key.Equals("usePlayerAvatar", StringComparison.OrdinalIgnoreCase)) usePlayerAvatar = ParseBool(val, usePlayerAvatar);
                    else if (key.Equals("avatarTemplate", StringComparison.OrdinalIgnoreCase)) avatarTemplate = val;
                    else if (key.Equals("usernameMode", StringComparison.OrdinalIgnoreCase)) usernameMode = val;
                    else if (key.Equals("staticUsername", StringComparison.OrdinalIgnoreCase)) staticUsername = val;
                    else if (key.Equals("chatFormat", StringComparison.OrdinalIgnoreCase)) chatFormat = val;
                    else if (key.Equals("joinFormat", StringComparison.OrdinalIgnoreCase)) joinFormat = val;
                    else if (key.Equals("leaveFormat", StringComparison.OrdinalIgnoreCase)) leaveFormat = val;
                    else if (key.Equals("commandFormat", StringComparison.OrdinalIgnoreCase)) commandFormat = val;

                    // Inbound
                    else if (key.Equals("inboundEnabled", StringComparison.OrdinalIgnoreCase)) inboundEnabled = ParseBool(val, inboundEnabled);
                    else if (key.Equals("discordBotToken", StringComparison.OrdinalIgnoreCase)) discordBotToken = val;
                    else if (key.Equals("discordChannelId", StringComparison.OrdinalIgnoreCase)) discordChannelId = val;
                    else if (key.Equals("pollSeconds", StringComparison.OrdinalIgnoreCase)) pollSeconds = ParseInt(val, pollSeconds, 2, 60);
                    else if (key.Equals("inboundFetchLimit", StringComparison.OrdinalIgnoreCase)) inboundFetchLimit = ParseInt(val, inboundFetchLimit, 1, 50);
                    else if (key.Equals("inboundIgnoreBots", StringComparison.OrdinalIgnoreCase)) inboundIgnoreBots = ParseBool(val, inboundIgnoreBots);
                    else if (key.Equals("inboundIgnoreWebhooks", StringComparison.OrdinalIgnoreCase)) inboundIgnoreWebhooks = ParseBool(val, inboundIgnoreWebhooks);
                    else if (key.Equals("inboundSanitizeMentions", StringComparison.OrdinalIgnoreCase)) inboundSanitizeMentions = ParseBool(val, inboundSanitizeMentions);
                    else if (key.Equals("inboundIgnoreSlashCommands", StringComparison.OrdinalIgnoreCase)) inboundIgnoreSlashCommands = ParseBool(val, inboundIgnoreSlashCommands);
                    else if (key.Equals("inboundFormat", StringComparison.OrdinalIgnoreCase)) inboundFormat = val;
                }

                if (string.IsNullOrEmpty(avatarTemplate)) avatarTemplate = "https://cdn.classicube.net/face/{0}.png";
                if (string.IsNullOrEmpty(usernameMode)) usernameMode = "player";
                if (string.IsNullOrEmpty(staticUsername)) staticUsername = "MCGalaxy";
                if (string.IsNullOrEmpty(inboundFormat)) inboundFormat = "&5(Discord) <{0}>&e : {1}";

                Logger.Log(LogType.SystemActivity,
                    "[DiscordWebhookRelay] Config loaded. enabled=" + enabled +
                    ", outbound(chat=" + sendChat + ", joinLeave=" + sendJoinLeave + ", commands=" + logCommands + ")" +
                    ", inbound=" + inboundEnabled);
            }
            catch (Exception ex)
            {
                Logger.Log(LogType.Error, "[DiscordWebhookRelay] Failed to load config, using defaults:");
                Logger.LogError(ex);
            }
        }

        static void LoadState()
        {
            try
            {
                if (!File.Exists(StatePath)) return;
                string txt = File.ReadAllText(StatePath).Trim();
                if (txt.Length == 0) return;

                ulong val;
                if (ulong.TryParse(txt, out val))
                    lastSeenMessageId = val;
            }
            catch { }
        }

        static void SaveState()
        {
            try
            {
                File.WriteAllText(StatePath, lastSeenMessageId.ToString());
            }
            catch { }
        }

        static bool ParseBool(string v, bool def)
        {
            if (v == null) return def;
            v = v.Trim().ToLowerInvariant();
            return (v == "1" || v == "true" || v == "yes" || v == "y" || v == "on");
        }

        static int ParseInt(string v, int def, int min, int max)
        {
            int n;
            if (!int.TryParse(v, out n)) return def;
            if (n < min) return min;
            if (n > max) return max;
            return n;
        }

        // ---------------------------
        // Minimal Discord JSON parsing
        // ---------------------------
        sealed class DiscordMessage
        {
            public ulong Id;
            public bool IdOk;

            public string Content;

            public string AuthorUsername;
            public bool AuthorBot;

            public bool HasWebhookId;
        }

        static class DiscordMessageParser
        {
            public static DiscordMessage[] ParseMessages(string json)
            {
                // Expect: [ {message1}, {message2}, ... ]
                // We do a light scan for message objects by top-level braces.
                // This is intentionally minimal (no external JSON libs).
                if (string.IsNullOrEmpty(json)) return null;

                int len = json.Length;
                int i = 0;

                // find first '['
                while (i < len && json[i] != '[') i++;
                if (i >= len) return null;
                i++;

                // collect up to 50
                DiscordMessage[] temp = new DiscordMessage[50];
                int count = 0;

                while (i < len && count < temp.Length)
                {
                    // skip whitespace/commas
                    while (i < len && (json[i] == ' ' || json[i] == '\n' || json[i] == '\r' || json[i] == '\t' || json[i] == ',')) i++;
                    if (i >= len) break;
                    if (json[i] == ']') break;

                    if (json[i] != '{')
                    {
                        i++;
                        continue;
                    }

                    int objStart = i;
                    int depth = 0;
                    bool inString = false;

                    while (i < len)
                    {
                        char c = json[i];

                        if (c == '"' && (i == 0 || json[i - 1] != '\\'))
                        {
                            inString = !inString;
                        }

                        if (!inString)
                        {
                            if (c == '{') depth++;
                            else if (c == '}')
                            {
                                depth--;
                                if (depth == 0)
                                {
                                    int objEnd = i;
                                    string obj = json.Substring(objStart, objEnd - objStart + 1);
                                    temp[count++] = ParseOne(obj);
                                    i++;
                                    break;
                                }
                            }
                        }

                        i++;
                    }
                }

                if (count == 0) return null;

                DiscordMessage[] result = new DiscordMessage[count];
                for (int k = 0; k < count; k++) result[k] = temp[k];
                return result;
            }

            static DiscordMessage ParseOne(string obj)
            {
                DiscordMessage m = new DiscordMessage();

                string idStr = FindJsonStringValue(obj, "\"id\"");
                ulong id;
                if (!string.IsNullOrEmpty(idStr) && ulong.TryParse(idStr, out id))
                {
                    m.Id = id;
                    m.IdOk = true;
                }

                m.Content = FindJsonStringValue(obj, "\"content\"");

                // webhook_id may be null or string; we only care if it's present and non-empty
                string webhookId = FindJsonStringValue(obj, "\"webhook_id\"");
                if (!string.IsNullOrEmpty(webhookId)) m.HasWebhookId = true;

                // author subobject
                int authorIdx = obj.IndexOf("\"author\"", StringComparison.OrdinalIgnoreCase);
                if (authorIdx >= 0)
                {
                    int brace = obj.IndexOf('{', authorIdx);
                    if (brace >= 0)
                    {
                        string authorObj = ExtractObject(obj, brace);
                        if (!string.IsNullOrEmpty(authorObj))
                        {
                            m.AuthorUsername = FindJsonStringValue(authorObj, "\"username\"");
                            string botVal = FindJsonBoolValue(authorObj, "\"bot\"");
                            m.AuthorBot = (botVal == "true");
                        }
                    }
                }

                return m;
            }

            static string ExtractObject(string s, int startBrace)
            {
                int len = s.Length;
                int i = startBrace;
                int depth = 0;
                bool inString = false;

                while (i < len)
                {
                    char c = s[i];

                    if (c == '"' && (i == 0 || s[i - 1] != '\\'))
                        inString = !inString;

                    if (!inString)
                    {
                        if (c == '{') depth++;
                        else if (c == '}')
                        {
                            depth--;
                            if (depth == 0)
                                return s.Substring(startBrace, i - startBrace + 1);
                        }
                    }
                    i++;
                }
                return null;
            }

            static string FindJsonBoolValue(string obj, string key)
            {
                int idx = obj.IndexOf(key, StringComparison.OrdinalIgnoreCase);
                if (idx < 0) return null;

                int colon = obj.IndexOf(':', idx + key.Length);
                if (colon < 0) return null;

                int i = colon + 1;
                while (i < obj.Length && (obj[i] == ' ' || obj[i] == '\t' || obj[i] == '\r' || obj[i] == '\n')) i++;

                if (i + 4 <= obj.Length && obj.Substring(i, 4) == "true") return "true";
                if (i + 5 <= obj.Length && obj.Substring(i, 5) == "false") return "false";
                return null;
            }

            static string FindJsonStringValue(string obj, string key)
            {
                int idx = obj.IndexOf(key, StringComparison.OrdinalIgnoreCase);
                if (idx < 0) return null;

                int colon = obj.IndexOf(':', idx + key.Length);
                if (colon < 0) return null;

                int i = colon + 1;
                while (i < obj.Length && (obj[i] == ' ' || obj[i] == '\t' || obj[i] == '\r' || obj[i] == '\n')) i++;

                // null?
                if (i + 4 <= obj.Length && obj.Substring(i, 4) == "null") return null;

                if (i >= obj.Length || obj[i] != '"') return null;
                i++;

                StringBuilder sb = new StringBuilder();
                while (i < obj.Length)
                {
                    char c = obj[i];

                    if (c == '"' && obj[i - 1] != '\\')
                        break;

                    sb.Append(c);
                    i++;
                }

                return sb.ToString();
            }
        }

        // ---------------------------
        // Command
        // ---------------------------
        public sealed class CmdDiscordWebhookRelay : Command
        {
            public override string name { get { return "dwh"; } }
            public override string type { get { return "other"; } }
            public override LevelPermission defaultRank { get { return LevelPermission.Operator; } }

            public override void Use(Player p, string message)
            {
                string arg = (message ?? "").Trim();

                if (arg.Length == 0 || arg.Equals("status", StringComparison.OrdinalIgnoreCase))
                {
                    p.Message("&e[DiscordWebhookRelay]&S enabled=&f" + enabled +
                              " &SwebhookSet=&f" + IsWebhookReady());
                    p.Message("&eoutbound:&S chat=&f" + sendChat +
                              " &SjoinLeave=&f" + sendJoinLeave +
                              " &Scommands=&f" + logCommands +
                              " &ScensorPassArgs=&f" + censorPassArgs);
                    p.Message("&einbound:&S enabled=&f" + inboundEnabled +
                              " &SpollSeconds=&f" + pollSeconds +
                              " &SchannelIdSet=&f" + (discordChannelId != null && discordChannelId.IndexOf("REPLACE_ME", StringComparison.OrdinalIgnoreCase) < 0));
                    p.Message("&elastSeenId:&f " + lastSeenMessageId);
                    return;
                }

                if (arg.Equals("reload", StringComparison.OrdinalIgnoreCase))
                {
                    try
                    {
                        LoadConfig();
                        LoadState();
                        StartInboundIfEnabled();
                        p.Message("&a[DiscordWebhookRelay]&S Reloaded config.");
                    }
                    catch (Exception ex)
                    {
                        p.Message("&c[DiscordWebhookRelay]&S Reload failed. See server log.");
                        Logger.Log(LogType.Error, "[DiscordWebhookRelay] Reload failed:");
                        Logger.LogError(ex);
                    }
                    return;
                }

                if (arg.StartsWith("test", StringComparison.OrdinalIgnoreCase))
                {
                    string text = "Test message from DiscordWebhookRelay.";
                    if (arg.Length > 4) text = arg.Substring(4).Trim();

                    SendWebhook(p != null ? p.name : "Server", text);
                    p.Message("&a[DiscordWebhookRelay]&S Sent test message.");
                    return;
                }

                if (arg.Equals("on", StringComparison.OrdinalIgnoreCase) || arg.Equals("off", StringComparison.OrdinalIgnoreCase))
                {
                    bool on = arg.Equals("on", StringComparison.OrdinalIgnoreCase);
                    enabled = on;
                    StartInboundIfEnabled();
                    p.Message(on ? "&a[DiscordWebhookRelay]&S Enabled." : "&c[DiscordWebhookRelay]&S Disabled.");
                    return;
                }
				
				if (arg.Equals("inboundtest", StringComparison.OrdinalIgnoreCase))
				{
					try
					{
						DiscordWebhookRelay.InboundPollTick(null);
						p.Message("&a[DiscordWebhookRelay]&S Ran inbound poll tick once. Check server logs for details.");
					}
					catch (Exception ex)
					{
						p.Message("&c[DiscordWebhookRelay]&S inboundtest failed. See server logs.");
						Logger.LogError(ex);
					}
					return;
				}
								

                Help(p);
            }

            public override void Help(Player p)
            {
                p.Message("&T/dwh status");
                p.Message("&H Shows current relay settings.");
                p.Message("&T/dwh reload");
                p.Message("&H Reloads config + inbound state.");
                p.Message("&T/dwh test [text]");
                p.Message("&H Sends a test webhook message.");
                p.Message("&T/dwh on|off");
                p.Message("&H Toggles relay (both directions).");
				p.Message("&T/dwh inboundtest");
				p.Message("&H Runs one inbound fetch and logs results.");
            }
        }
    }
}
