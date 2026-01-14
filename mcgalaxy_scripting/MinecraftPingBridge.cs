//reference System.dll
//reference System.Core.dll
//reference System.Net.dll

// MinecraftPingBridge.cs
// Author: ChatGPT
//
// MCGalaxy plugin that emulates Minecraft's GameSpy4 Query protocol (UDP),
// so external services/tools (GameTracker, custom scripts) can read:
// hostname, map, numplayers, maxplayers, hostport, hostip, plugins, etc.
//
// Usage:
// 1) /Compile plugin MinecraftPingBridge
// 2) Edit plugins/MinecraftPingBridge/config.properties
// 3) Restart server (recommended) or reload plugin if your setup supports it
// 4) Test: mc_query_cc.ps1 against your configured port

using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

using MCGalaxy;

namespace Core
{
    public sealed class MinecraftPingBridge : Plugin
    {
        public override string name { get { return "MinecraftPingBridge"; } }
        public override string MCGalaxy_Version { get { return "1.9.5.0"; } }
        public override string creator { get { return "ChatGPT"; } }

        const string ConfigDirName  = "plugins/MinecraftPingBridge";
        const string ConfigFileName = "plugins/MinecraftPingBridge/config.properties";

        volatile bool isRunning;
        Thread serverThread;
        UdpClient udp;

        readonly object tokenLock = new object();
        readonly Dictionary<string, TokenEntry> tokensByEndpoint = new Dictionary<string, TokenEntry>();

        ConfigValues config;

        public override void Load(bool startup)
        {
            try
            {
                EnsureConfigExists();
                config = LoadConfig();

                if (!config.enabled)
                {
                    Logger.Log(LogType.SystemActivity, "[MinecraftPingBridge] Disabled by config.");
                    return;
                }

                StartUdpServer(config.listenPort);
                Logger.Log(LogType.SystemActivity, "[MinecraftPingBridge] Started UDP Query on port " + config.listenPort);
            }
            catch (Exception ex)
            {
                Logger.LogError(ex);
            }
        }

        public override void Unload(bool shutdown)
        {
            StopUdpServer();
            Logger.Log(LogType.SystemActivity, "[MinecraftPingBridge] Stopped.");
        }

        // -------------------------
        // UDP Server
        // -------------------------

        void StartUdpServer(int port)
        {
            StopUdpServer();

            isRunning = true;
            udp = new UdpClient(port);
            udp.Client.ReceiveTimeout = 2000;

            serverThread = new Thread(UdpLoop);
            serverThread.IsBackground = true;
            serverThread.Name = "MinecraftPingBridge-UdpLoop";
            serverThread.Start();
        }

        void StopUdpServer()
        {
            isRunning = false;

            if (udp != null)
            {
                try { udp.Close(); } catch { }
                udp = null;
            }

            if (serverThread != null)
            {
                try
                {
                    if (!serverThread.Join(800))
                    {
                        try { serverThread.Interrupt(); } catch { }
                    }
                }
                catch { }
                serverThread = null;
            }

            lock (tokenLock) { tokensByEndpoint.Clear(); }
        }

        void UdpLoop()
        {
            IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);

            while (isRunning && udp != null)
            {
                try
                {
                    byte[] data = udp.Receive(ref remote);
                    if (data == null || data.Length < 7) continue;

                    // Packet format:
                    //  FE FD <type> <sessionID:4>
                    if (data[0] != 0xFE || data[1] != 0xFD) continue;

                    byte type = data[2];
                    int sessionId = ReadInt32BE(data, 3);

                    if (type == 0x09)
                    {
                        // Handshake: respond with token ASCII + null terminator
                        HandleHandshake(remote, sessionId);
                    }
                    else if (type == 0x00)
                    {
                        // Full stat request: must include token and 0xFFFFFFFF padding
                        if (data.Length < 15) continue;
                        int token = ReadInt32BE(data, 7);
                        HandleFullStat(remote, sessionId, token);
                    }
                }
                catch (SocketException)
                {
                    // Ignore transient socket errors
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (ThreadInterruptedException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    Logger.LogError(ex);
                }
            }
        }

        // -------------------------
        // Query Handlers
        // -------------------------

        void HandleHandshake(IPEndPoint remote, int sessionId)
        {
            int token = EnsureToken(remote);

            // Response:
            //  09 <sessionID:4> <token-ascii> 00
            string tokenStr = token.ToString();
            byte[] tokenAscii = Encoding.ASCII.GetBytes(tokenStr);

            byte[] outData = new byte[1 + 4 + tokenAscii.Length + 1];
            outData[0] = 0x09;
            WriteInt32BE(outData, 1, sessionId);
            Buffer.BlockCopy(tokenAscii, 0, outData, 5, tokenAscii.Length);
            outData[outData.Length - 1] = 0x00;

            SafeSend(outData, remote);
        }

        void HandleFullStat(IPEndPoint remote, int sessionId, int tokenFromClient)
        {
            if (!ValidateToken(remote, tokenFromClient)) return;

            // Build:
            //  00 <sessionID:4> "splitnum\0\x80\0" <k\0v\0...> \0\0 01 "player_\0" <names...> \0\0
            byte[] payload = BuildFullStatPayload(sessionId);

            SafeSend(payload, remote);
        }

        byte[] BuildFullStatPayload(int sessionId)
        {
            // Pull dynamic server info
            string hostName = GetHostName();
            string mapName = GetMapName();

            List<string> playerNames = null;
            if (config.includePlayerList) playerNames = GetVisiblePlayerNames(config.maxPlayerList);
            int online = playerNames != null ? playerNames.Count : GetOnlineCount();

            int maxPlayers = GetMaxPlayers();
            int hostPort = config.reportPort > 0 ? config.reportPort : config.listenPort;
            string hostIp = config.reportIp != null ? config.reportIp.Trim() : "";

            string plugins = GetPluginsString();

            // Assemble k/v
            Dictionary<string, string> kv = new Dictionary<string, string>();
            kv["hostname"] = hostName;
            kv["gametype"] = config.gameType;
            kv["game_id"] = "MINECRAFT";
            kv["version"] = config.versionString;
            kv["plugins"] = plugins;
            kv["map"] = mapName;
            kv["numplayers"] = online.ToString();
            kv["maxplayers"] = maxPlayers.ToString();
            kv["hostport"] = hostPort.ToString();
            kv["hostip"] = hostIp;

            using (MemoryStream ms = new MemoryStream())
            {
                ms.WriteByte(0x00);
                WriteInt32BE(ms, sessionId);

                // "splitnum\0\x80\0"
                WriteAsciiRaw(ms, "splitnum");
                ms.WriteByte(0x00);
                ms.WriteByte(0x80);
                ms.WriteByte(0x00);

                foreach (KeyValuePair<string, string> pair in kv)
                {
                    WriteAsciiZ(ms, pair.Key);
                    WriteAsciiZ(ms, pair.Value);
                }

                // \0\0 then 01 then "player_\0" then list of names (\0-terminated), then \0\0
                // End of key/value section (the last value already ended with 0x00)
                // so we only need one more 0x00 here to produce the required \0\0 terminator.
                ms.WriteByte(0x00);
                ms.WriteByte(0x01);
                WriteAsciiZ(ms, "player_");


                if (playerNames != null)
                {
                    for (int i = 0; i < playerNames.Count; i++)
                    {
                        WriteAsciiZ(ms, playerNames[i]);
                    }
                }

                ms.WriteByte(0x00);
                ms.WriteByte(0x00);

                return ms.ToArray();
            }
        }

        // -------------------------
        // Config
        // -------------------------

        struct ConfigValues
        {
            public bool enabled;
            public int listenPort;
            public int reportPort;
            public string reportIp;
            public string hostNameOverride;
            public string mapOverride;
            public string gameType;
            public string versionString;
            public bool includePlugins;
            public bool includePlayerList;
            public int maxPlayerList;
        }

        void EnsureConfigExists()
        {
            if (File.Exists(ConfigFileName)) return;

            try { Directory.CreateDirectory(ConfigDirName); } catch { }

            string defaultText =
@"# MinecraftPingBridge config
# Implements the Minecraft Query (GameSpy4) UDP protocol

Enabled=true
ListenPort=45566

# These are what get reported in the response:
ReportPort=45566
ReportIp=

# If HostNameOverride is blank, uses Server.Config.Name
HostNameOverride=

# If MapOverride is blank, uses the loaded world name (Server.mainLevel)
MapOverride=

GameType=SMP
VersionString=MCGalaxy-Classicube
IncludePlugins=true

# If IncludePlayerList is true, the response includes a player list section
# like CraftBukkit does. Player names are taken from visible online players.
IncludePlayerList=true

# Maximum number of player names to include in the player list section.
MaxPlayerList=100
";
            File.WriteAllText(ConfigFileName, defaultText, Encoding.UTF8);
        }

        ConfigValues LoadConfig()
        {
            Dictionary<string, string> kv = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            foreach (string raw in File.ReadAllLines(ConfigFileName))
            {
                string line = raw.Trim();
                if (line.Length == 0) continue;
                if (line.StartsWith("#")) continue;

                int idx = line.IndexOf('=');
                if (idx <= 0) continue;

                string k = line.Substring(0, idx).Trim();
                string v = line.Substring(idx + 1).Trim();
                kv[k] = v;
            }

            ConfigValues c = new ConfigValues();
            c.enabled = ReadBool(kv, "Enabled", true);
            c.listenPort = ReadInt(kv, "ListenPort", 45566, 1, 65535);
            c.reportPort = ReadInt(kv, "ReportPort", c.listenPort, 1, 65535);
            c.reportIp = ReadString(kv, "ReportIp", "");
            c.hostNameOverride = ReadString(kv, "HostNameOverride", "");
            c.mapOverride = ReadString(kv, "MapOverride", "");
            c.gameType = ReadString(kv, "GameType", "SMP");
            c.versionString = ReadString(kv, "VersionString", "MCGalaxy-Classicube");
            c.includePlugins = ReadBool(kv, "IncludePlugins", true);
            c.includePlayerList = ReadBool(kv, "IncludePlayerList", true);
            c.maxPlayerList = ReadInt(kv, "MaxPlayerList", 100, 0, 2000);
            return c;
        }

        static int ReadInt(Dictionary<string, string> kv, string key, int def, int min, int max)
        {
            string v;
            if (!kv.TryGetValue(key, out v)) return def;

            int parsed;
            if (!int.TryParse(v, out parsed)) return def;
            if (parsed < min) return min;
            if (parsed > max) return max;
            return parsed;
        }

        static bool ReadBool(Dictionary<string, string> kv, string key, bool def)
        {
            string v;
            if (!kv.TryGetValue(key, out v)) return def;
            if (v.Equals("true", StringComparison.OrdinalIgnoreCase)) return true;
            if (v.Equals("false", StringComparison.OrdinalIgnoreCase)) return false;
            return def;
        }

        static string ReadString(Dictionary<string, string> kv, string key, string def)
        {
            string v;
            if (!kv.TryGetValue(key, out v)) return def;
            return v ?? def;
        }

        // -------------------------
        // Server info helpers
        // -------------------------

        string GetHostName()
        {
            string s = config.hostNameOverride ?? "";
            if (s.Length > 0) return s;

            try { return Server.Config.Name; }
            catch { return "MCGalaxy Server"; }
        }

        string GetMapName()
        {
            string s = config.mapOverride ?? "";
            if (s.Length > 0) return s;

            try
            {
                if (Server.mainLevel != null && !string.IsNullOrEmpty(Server.mainLevel.name))
                    return Server.mainLevel.name;
            }
            catch { }

            return "world";
        }

        int GetOnlineCount()
        {
            try
            {
                Player[] players = PlayerInfo.Online.Items;
                return players == null ? 0 : players.Length;
            }
            catch { return 0; }
        }

        List<string> GetVisiblePlayerNames(int maxNames)
        {
            List<string> names = new List<string>();
            if (maxNames <= 0) return names;

            try
            {
                Player[] players = PlayerInfo.Online.Items;
                if (players == null) return names;

                for (int i = 0; i < players.Length && names.Count < maxNames; i++)
                {
                    Player p = players[i];
                    if (p == null) continue;
                    if (p.hidden) continue;
                    if (string.IsNullOrEmpty(p.name)) continue;
                    names.Add(p.name);
                }
            }
            catch { }

            return names;
        }

        int GetMaxPlayers()
        {
            try { return Server.Config.MaxPlayers; }
            catch { return 32; }
        }

        string GetPluginsString()
        {
            if (!config.includePlugins) return "MCGalaxy";

            try
            {
                return "MCGalaxy";
            }
            catch
            {
                return "MCGalaxy";
            }
        }

        // -------------------------
        // Token management
        // -------------------------

        int EnsureToken(IPEndPoint ep)
        {
            string key = ep.Address + ":" + ep.Port;

            lock (tokenLock)
            {
                TokenEntry entry;
                if (tokensByEndpoint.TryGetValue(key, out entry))
                {
                    // refresh
                    entry.lastSeenUtc = DateTime.UtcNow;
                    tokensByEndpoint[key] = entry;
                    return entry.token;
                }

                int token = MakeToken();
                tokensByEndpoint[key] = new TokenEntry { token = token, lastSeenUtc = DateTime.UtcNow };
                return token;
            }
        }

        bool ValidateToken(IPEndPoint ep, int tokenFromClient)
        {
            string key = ep.Address + ":" + ep.Port;

            lock (tokenLock)
            {
                TokenEntry entry;
                if (!tokensByEndpoint.TryGetValue(key, out entry)) return false;
                if (entry.token != tokenFromClient) return false;

                entry.lastSeenUtc = DateTime.UtcNow;
                tokensByEndpoint[key] = entry;
                return true;
            }
        }

        static int MakeToken()
        {
            int t = (int)(DateTime.UtcNow.Ticks & 0x7FFFFFFF);
            if (t == 0) t = 1;
            return t;
        }

        struct TokenEntry
        {
            public int token;
            public DateTime lastSeenUtc;
        }

        // -------------------------
        // Byte helpers
        // -------------------------

        static int ReadInt32BE(byte[] data, int offset)
        {
            return (data[offset] << 24)
                 | (data[offset + 1] << 16)
                 | (data[offset + 2] << 8)
                 | (data[offset + 3]);
        }

        static void WriteInt32BE(byte[] data, int offset, int value)
        {
            data[offset]     = (byte)((value >> 24) & 0xFF);
            data[offset + 1] = (byte)((value >> 16) & 0xFF);
            data[offset + 2] = (byte)((value >> 8) & 0xFF);
            data[offset + 3] = (byte)(value & 0xFF);
        }

        static void WriteInt32BE(Stream s, int value)
        {
            s.WriteByte((byte)((value >> 24) & 0xFF));
            s.WriteByte((byte)((value >> 16) & 0xFF));
            s.WriteByte((byte)((value >> 8) & 0xFF));
            s.WriteByte((byte)(value & 0xFF));
        }

        static void WriteAsciiRaw(Stream s, string text)
        {
            if (text == null) text = "";
            byte[] b = Encoding.ASCII.GetBytes(text);
            s.Write(b, 0, b.Length);
        }

        static void WriteAsciiZ(Stream s, string text)
        {
            if (text == null) text = "";
            byte[] b = Encoding.ASCII.GetBytes(text);
            s.Write(b, 0, b.Length);
            s.WriteByte(0x00);
        }

        void SafeSend(byte[] data, IPEndPoint remote)
        {
            try
            {
                if (udp == null) return;
                udp.Send(data, data.Length, remote);
            }
            catch { }
        }
    }
}