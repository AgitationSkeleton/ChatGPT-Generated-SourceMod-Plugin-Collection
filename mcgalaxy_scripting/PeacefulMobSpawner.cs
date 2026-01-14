//reference System.Core.dll
// Author: ChatGPT
//
// PeacefulMobSpawner - ambient “peaceful mobs” via bots (MCGalaxy / ClassiCube)
//
// - Spawns bots with mob models in random safe spots in LOADED worlds only
// - Per-world cap by default (configurable); optional global cap too
// - Bots wander using BotAI walk/wait/look loop (non-hostile, decorative)
// - World allow/deny lists, pause/resume, reload, cull, per-world cap commands
//
// Compile/load:
//   /pcompile PeacefulMobSpawner
//   /pload PeacefulMobSpawner
//
// Config:
//   plugins/PeacefulMobSpawner/config.txt

using System;
using System.Collections.Generic;
using System.IO;

using MCGalaxy;
using MCGalaxy.Bots;
using MCGalaxy.Commands;
using MCGalaxy.Tasks;

namespace Core
{
    public sealed class PeacefulMobSpawner : Plugin
    {
        public override string name { get { return "PeacefulMobSpawner"; } }
        public override string creator { get { return "ChatGPT"; } }
        public override string MCGalaxy_Version { get { return "1.9.1.2"; } }

        const string FolderPath = "plugins/PeacefulMobSpawner";
        const string ConfigPath = "plugins/PeacefulMobSpawner/config.txt";

        static readonly object Sync = new object();
        static readonly Random Rng = new Random();

        // ---- Config (defaults) ----
        static bool enabled = true;
        static bool paused = false;

        // Caps
        static int defaultPerWorldCap = 30;  // DEFAULT behavior: per-world cap
        static int globalCap = 0;            // 0 = disabled (no global cap), else total cap across all worlds

        // Tick/spawn pacing
        static int tickSeconds = 2;          // how often to try spawning
        static int spawnBurstPerWorld = 1;   // max spawns per world per tick (until it hits cap)
        static int spawnSafeTries = 40;      // attempts to find safe surface spot

        // Turnover / culling
        static bool turnoverEnabled = true;
        static int cullChancePercent = 15;  // chance each tick to cull 1 bot in a world at/over cap (for “new faces”)

        // Models
        static List<string> modelPool = new List<string>() { "creeper", "zombie", "skeleton", "spider", "pig", "sheep", "player" };

        // World filtering
        static List<string> allowWorlds = new List<string>();
        static List<string> denyWorlds  = new List<string>();

        // Per-world cap overrides: "worldName" -> cap
        static Dictionary<string, int> perWorldCaps = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        // Track bots we spawned: botName -> worldName
        static Dictionary<string, string> spawnedBots = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public static SchedulerTask task;

        public override void Load(bool startup)
        {
            EnsureFolder();
            EnsureConfig();
            LoadConfig();

            Command.Register(new CmdPeacefulMobSpawner());

            // Tick loop
            Server.MainScheduler.QueueRepeat(DoTick, null, TimeSpan.FromSeconds(1));

            Logger.Log(LogType.SystemActivity, "[PeacefulMobSpawner] Loaded.");
        }

        public override void Unload(bool shutdown)
        {
            try { Server.MainScheduler.Cancel(task); } catch { }

            Command.Unregister(Command.Find("PeacefulMobSpawner"));

            // Remove all spawned bots
            try { DespawnAll(); } catch { }

            Logger.Log(LogType.SystemActivity, "[PeacefulMobSpawner] Unloaded.");
        }

        public static void DoTick(SchedulerTask t)
        {
            task = t;

            try
            {
                if (!enabled || paused) return;

                // only run on tickSeconds boundaries
                DateTime now = DateTime.UtcNow;
                if ((now.Second % Math.Max(1, tickSeconds)) != 0) return;

                PruneTrackingForUnloadedWorlds();

                // Work only with LOADED worlds
                Level[] loaded = GetLoadedWorlds();
                if (loaded.Length == 0) return;

                // Optional global cap
                if (globalCap > 0)
                {
                    int total = CountTotalSpawned();
                    if (total >= globalCap) return;
                }

                for (int i = 0; i < loaded.Length; i++)
                {
                    Level lvl = loaded[i];
                    if (lvl == null) continue;

                    if (!IsWorldAllowed(lvl.name)) continue;

                    int cap = GetCapForWorld(lvl.name);
                    if (cap <= 0) continue;

                    int current = CountSpawnedInWorld(lvl.name);

                    // Turnover: occasionally cull 1 if at/over cap (so new ones can appear)
                    if (turnoverEnabled && current >= cap && cullChancePercent > 0)
                    {
                        if (Rng.Next(0, 100) < cullChancePercent)
                        {
                            CullOneInWorld(lvl);
                            current = CountSpawnedInWorld(lvl.name);
                        }
                    }

                    // Spawn up to burst, but never exceed cap
                    int spawns = 0;
                    while (spawns < spawnBurstPerWorld && current < cap)
                    {
                        if (globalCap > 0 && CountTotalSpawned() >= globalCap) return;

                        if (TrySpawnOne(lvl))
                        {
                            spawns++;
                            current++;
                        }
                        else
                        {
                            // couldn't find safe spawn / no bot capacity etc
                            break;
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Logger.Log(LogType.Error, "[PeacefulMobSpawner] Tick error:");
                Logger.LogError(ex);
            }
        }

        // -------------------------
        // Spawning / Culling
        // -------------------------

        static bool TrySpawnOne(Level lvl)
        {
            if (lvl == null) return false;
            if (modelPool == null || modelPool.Count == 0) return false;

            if (lvl.Bots.Count >= Server.Config.MaxBotsPerLevel) return false;

            ushort x, y, z;
            if (!TryPickSafeSpawn(lvl, out x, out y, out z)) return false;

            string model = modelPool[Rng.Next(modelPool.Count)];
            string botName = MakeUniqueBotName(model);

            try
            {
                PlayerBot bot = new PlayerBot(botName, lvl);
                bot.Owner = "PeacefulMobSpawner";

                // set initial position
                int spawnY = y + 1;
				ushort below = 0;
				if (y > 0) below = lvl.GetBlock((ushort)x, (ushort)(y - 1), (ushort)z);
				
				// Water / lava → drop 1 full block
				if (below == 8 || below == 9 || below == 10 || below == 11)
					spawnY -= 1;
				
				// Slabs → drop half a block (16 units)
				bool halfBlock = (below == 44 || below == 50);
				
				int posY = (spawnY << 5) + 16;
				if (halfBlock)
					posY -= 16;
				
				Position pos = new Position(
					(x << 5) + 16,
					posY,
					(z << 5) + 16
				);
				
                bot.SetInitialPos(pos);

                // random yaw
                byte yaw = (byte)Rng.Next(0, 256);
                bot.SetYawPitch(yaw, 0);

                PlayerBot.Add(bot);

                // apply model
                bot.UpdateModel(model);

                // program a simple wander AI:
                // Wander behavior disabled for now (was flooding console)
            //TryProgramWanderAI(botName);

                lock (Sync) { spawnedBots[botName] = lvl.name; }
                return true;
            }
            catch (Exception ex)
            {
                Logger.Log(LogType.Error, "[PeacefulMobSpawner] Failed to spawn bot:");
                Logger.LogError(ex);

                // best-effort cleanup
                try
                {
                    PlayerBot existing = FindBotInLevel(lvl, botName);
                    if (existing != null) PlayerBot.Remove(existing);
                }
                catch { }

                lock (Sync) { spawnedBots.Remove(botName); }
                return false;
            }
        }

        static void CullOneInWorld(Level lvl)
        {
            if (lvl == null) return;

            string[] names = GetTrackedBotsInWorld(lvl.name);
            if (names.Length == 0) return;

            string pick = names[Rng.Next(names.Length)];

            try
            {
                PlayerBot bot = FindBotInLevel(lvl, pick);
                if (bot != null) PlayerBot.Remove(bot);
            }
            catch { }

            lock (Sync) { spawnedBots.Remove(pick); }
        }

        static void DespawnAll()
        {
            Level[] loaded = GetLoadedWorlds();
            for (int i = 0; i < loaded.Length; i++)
            {
                Level lvl = loaded[i];
                if (lvl == null) continue;

                string[] names = GetTrackedBotsInWorld(lvl.name);
                for (int j = 0; j < names.Length; j++)
                {
                    try
                    {
                        PlayerBot bot = FindBotInLevel(lvl, names[j]);
                        if (bot != null) PlayerBot.Remove(bot);
                    }
                    catch { }

                    lock (Sync) { spawnedBots.Remove(names[j]); }
                }
            }

            lock (Sync) { spawnedBots.Clear(); }
        }

        static void TryProgramWanderAI(string botName)
        {
            try
            {
                Command cmd = Command.Find("BotAI");
                if (cmd == null) return;

                cmd.Use(Player.Console, botName + " reset");
                cmd.Use(Player.Console, botName + " add walk");
                cmd.Use(Player.Console, botName + " add wait " + Rng.Next(1, 4));
                cmd.Use(Player.Console, botName + " add look");
                cmd.Use(Player.Console, botName + " add reset");
            }
            catch { }
        }

        // -------------------------
        // World + bot helpers
        // -------------------------

        static Level[] GetLoadedWorlds()
        {
            try { return LevelInfo.Loaded.Items; }
            catch
            {
                try { return new Level[] { Server.mainLevel }; } catch { }
                return new Level[0];
            }
        }

        static void PruneTrackingForUnloadedWorlds()
        {
            Level[] loaded = GetLoadedWorlds();
            Dictionary<string, bool> loadedSet = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < loaded.Length; i++)
            {
                if (loaded[i] != null) loadedSet[loaded[i].name] = true;
            }

            List<string> remove = new List<string>();

            lock (Sync)
            {
                foreach (KeyValuePair<string, string> kv in spawnedBots)
                {
                    string botName = kv.Key;
                    string world = kv.Value;

                    if (!loadedSet.ContainsKey(world)) { remove.Add(botName); continue; }

                    Level lvl = LevelInfo.FindExact(world);
                    if (lvl == null) { remove.Add(botName); continue; }

                    if (FindBotInLevel(lvl, botName) == null) { remove.Add(botName); continue; }
                }

                for (int i = 0; i < remove.Count; i++) spawnedBots.Remove(remove[i]);
            }
        }

        static PlayerBot FindBotInLevel(Level lvl, string botName)
        {
            if (lvl == null || botName == null) return null;
            PlayerBot[] bots = lvl.Bots.Items;
            for (int i = 0; i < bots.Length; i++)
            {
                PlayerBot b = bots[i];
                if (b == null) continue;
                if (b.name.CaselessEq(botName)) return b;
            }
            return null;
        }

        static string[] GetTrackedBotsInWorld(string worldName)
        {
            List<string> list = new List<string>();
            lock (Sync)
            {
                foreach (KeyValuePair<string, string> kv in spawnedBots)
                {
                    if (kv.Value.CaselessEq(worldName)) list.Add(kv.Key);
                }
            }
            return list.ToArray();
        }

        static int CountSpawnedInWorld(string worldName)
        {
            int count = 0;
            lock (Sync)
            {
                foreach (KeyValuePair<string, string> kv in spawnedBots)
                {
                    if (kv.Value.CaselessEq(worldName)) count++;
                }
            }
            return count;
        }

        static int CountTotalSpawned()
        {
            lock (Sync) { return spawnedBots.Count; }
        }

        static int GetCapForWorld(string worldName)
        {
            int cap;
            if (perWorldCaps.TryGetValue(worldName, out cap)) return cap;
            return defaultPerWorldCap;
        }

        static bool IsWorldAllowed(string worldName)
        {
            if (denyWorlds.CaselessContains(worldName)) return false;
            if (allowWorlds.Count == 0) return true;
            return allowWorlds.CaselessContains(worldName);
        }

        static string SanitizeModelName(string raw) {
            if (raw == null) return "";
            // Strip directory and extension, keep only [a-z0-9_]
            string name = raw;
            int slash = name.LastIndexOfAny(new char[] { '/', '\\' });
            if (slash >= 0) name = name.Substring(slash + 1);
            int dot = name.LastIndexOf('.');
            if (dot > 0) name = name.Substring(0, dot);

            name = name.Trim().ToLowerInvariant();
            char[] chars = new char[name.Length];
            int count = 0;
            for (int i = 0; i < name.Length; i++) {
                char c = name[i];
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                    chars[count++] = c;
                } else if (c == '-' || c == ' ') {
                    chars[count++] = '_';
                }
            }
            return new string(chars, 0, count);
        }

        static string MakeUniqueBotName(string modelName)
        {
            // Classic protocol limits player names to 16 characters, so keep this short.
            string safeModel = SanitizeNamePart(modelName);
            if (safeModel.Length == 0) safeModel = "mob";

            const int digits = 5;
            // "npc_" + model + "_" + 5 digits <= 16 -> model <= 6
            int maxModelLen = 16 - 4 - 1 - digits;
            if (maxModelLen < 1) maxModelLen = 1;
            if (safeModel.Length > maxModelLen) safeModel = safeModel.Substring(0, maxModelLen);

            for (int tries = 0; tries < 5000; tries++)
            {
                int num = Rng.Next(0, (int)Math.Pow(10, digits));
                string candidate = "npc_" + safeModel + "_" + num.ToString("D" + digits);
                if (!spawnedBots.ContainsKey(candidate)) return candidate;
            }
            // Extremely unlikely fallback
            return "npc_" + safeModel + "_" + Guid.NewGuid().ToString("N").Substring(0, 4);
        }

        static string SanitizeNamePart(string input)
        {
            if (input == null) return "";
            var sb = new System.Text.StringBuilder(input.Length);
            for (int i = 0; i < input.Length; i++)
            {
                char c = char.ToLowerInvariant(input[i]);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.Append(c);
            }
            return sb.ToString();
        }
        // FIX: GetBlock returns ushort on your build, so store as ushort
        static bool TryPickSafeSpawn(Level lvl, out ushort x, out ushort y, out ushort z)
        {
            x = y = z = 0;

            int w = lvl.Width, h = lvl.Height, l = lvl.Length;
            if (w < 3 || h < 3 || l < 3) return false;

            for (int attempt = 0; attempt < Math.Max(1, spawnSafeTries); attempt++)
            {
                ushort rx = (ushort)Rng.Next(1, w - 1);
                ushort rz = (ushort)Rng.Next(1, l - 1);

                int startY = h - 2;
                int found = -1;

                for (int yy = startY; yy >= 1; yy--)
                {
                    ushort blk = lvl.GetBlock(rx, (ushort)yy, rz);
                    if (blk == Block.Air) continue;

                    int standY = yy + 1;
                    if (standY >= h - 1) break;

                    ushort feet = lvl.GetBlock(rx, (ushort)standY, rz);
                    ushort head = lvl.GetBlock(rx, (ushort)(standY + 1), rz);
                    if (feet != Block.Air || head != Block.Air) break;

                    found = standY;
                    break;
                }

                if (found < 0) continue;

                x = rx;
                y = (ushort)found;
                z = rz;
                return true;
            }

            return false;
        }

        // -------------------------
        // Config IO
        // -------------------------

        static void EnsureFolder()
        {
            if (!Directory.Exists(FolderPath)) Directory.CreateDirectory(FolderPath);
        }

        static void EnsureConfig()
        {
            if (File.Exists(ConfigPath)) return;

            List<string> lines = new List<string>();
            lines.Add("# PeacefulMobSpawner config (key=value)");
            lines.Add("enabled=true");
            lines.Add("paused=false");
            lines.Add("");
            lines.Add("# Caps");
            lines.Add("defaultPerWorldCap=30");
            lines.Add("globalCap=0");
            lines.Add("");
            lines.Add("# Tick & spawning");
            lines.Add("tickSeconds=2");
            lines.Add("spawnBurstPerWorld=1");
            lines.Add("spawnSafeTries=40");
            lines.Add("");
            lines.Add("# Turnover");
            lines.Add("turnoverEnabled=true");
            lines.Add("cullChancePercent=15");
            lines.Add("");
            lines.Add("# Models (comma-separated)");
            lines.Add("models=creeper,zombie,skeleton,spider,pig,sheep,player");
            lines.Add("");
            lines.Add("# World gating");
            lines.Add("allowWorlds=");
            lines.Add("denyWorlds=");
            lines.Add("");
            lines.Add("# Per-world cap overrides (comma-separated: world:cap)");
            lines.Add("perWorldCaps=");

            File.WriteAllLines(ConfigPath, lines.ToArray());
        }

        static void LoadConfig()
        {
            try
            {
                string[] lines = File.ReadAllLines(ConfigPath);

                enabled = true;
                paused = false;

                defaultPerWorldCap = 30;
                globalCap = 0;

                tickSeconds = 2;
                spawnBurstPerWorld = 1;
                spawnSafeTries = 40;

                turnoverEnabled = true;
                cullChancePercent = 15;

                modelPool = new List<string>() { "creeper", "zombie", "skeleton", "spider", "pig", "sheep", "player" };
                allowWorlds = new List<string>();
                denyWorlds = new List<string>();
                perWorldCaps = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

                for (int i = 0; i < lines.Length; i++)
                {
                    string line = lines[i].Trim();
                    if (line.Length == 0) continue;
                    if (line.StartsWith("#")) continue;

                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;

                    string key = line.Substring(0, eq).Trim();
                    string val = line.Substring(eq + 1).Trim();

                    if (key.CaselessEq("enabled")) enabled = ParseBool(val, enabled);
                    else if (key.CaselessEq("paused")) paused = ParseBool(val, paused);

                    else if (key.CaselessEq("defaultPerWorldCap")) defaultPerWorldCap = ParseInt(val, defaultPerWorldCap, 0, 500);
                    else if (key.CaselessEq("globalCap")) globalCap = ParseInt(val, globalCap, 0, 5000);

                    else if (key.CaselessEq("tickSeconds")) tickSeconds = ParseInt(val, tickSeconds, 1, 60);
                    else if (key.CaselessEq("spawnBurstPerWorld")) spawnBurstPerWorld = ParseInt(val, spawnBurstPerWorld, 1, 50);
                    else if (key.CaselessEq("spawnSafeTries")) spawnSafeTries = ParseInt(val, spawnSafeTries, 1, 500);

                    else if (key.CaselessEq("turnoverEnabled")) turnoverEnabled = ParseBool(val, turnoverEnabled);
                    else if (key.CaselessEq("cullChancePercent")) cullChancePercent = ParseInt(val, cullChancePercent, 0, 100);

                    else if (key.CaselessEq("models")) modelPool = ParseCsv(val);
                    else if (key.CaselessEq("allowWorlds")) allowWorlds = ParseCsv(val);
                    else if (key.CaselessEq("denyWorlds")) denyWorlds = ParseCsv(val);
                    else if (key.CaselessEq("perWorldCaps")) ParseWorldCaps(val, perWorldCaps);
                }

                NormalizeList(modelPool);
                NormalizeList(allowWorlds);
                NormalizeList(denyWorlds);

                if (modelPool.Count == 0) modelPool.Add("creeper");

                Logger.Log(LogType.SystemActivity, "[PeacefulMobSpawner] Config loaded.");
            }
            catch (Exception ex)
            {
                Logger.Log(LogType.Error, "[PeacefulMobSpawner] Failed to load config:");
                Logger.LogError(ex);
            }
        }

        static bool ParseBool(string val, bool def)
        {
            if (val == null) return def;
            val = val.Trim();
            if (val.CaselessEq("true") || val == "1" || val.CaselessEq("yes")) return true;
            if (val.CaselessEq("false") || val == "0" || val.CaselessEq("no")) return false;
            return def;
        }

        static int ParseInt(string val, int def, int min, int max)
        {
            int x;
            if (!int.TryParse(val, out x)) return def;
            if (x < min) x = min;
            if (x > max) x = max;
            return x;
        }

        static List<string> ParseCsv(string val)
        {
            List<string> list = new List<string>();
            if (string.IsNullOrEmpty(val)) return list;

            string[] parts = val.Split(new char[] { ',' }, StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < parts.Length; i++)
            {
                string s = parts[i].Trim();
                if (s.Length == 0) continue;
                list.Add(s);
            }
            return list;
        }

        static void ParseWorldCaps(string val, Dictionary<string, int> dict)
        {
            if (dict == null) return;
            dict.Clear();

            if (string.IsNullOrEmpty(val)) return;

            string[] parts = val.Split(new char[] { ',' }, StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < parts.Length; i++)
            {
                string p = parts[i].Trim();
                if (p.Length == 0) continue;

                int colon = p.IndexOf(':');
                if (colon <= 0) continue;

                string world = p.Substring(0, colon).Trim();
                string capS = p.Substring(colon + 1).Trim();

                int cap;
                if (!int.TryParse(capS, out cap)) continue;

                if (cap < 0) cap = 0;
                if (cap > 500) cap = 500;

                if (world.Length == 0) continue;
                dict[world] = cap;
            }
        }

        static void NormalizeList(List<string> list)
        {
            if (list == null) return;

            for (int i = list.Count - 1; i >= 0; i--)
            {
                if (list[i] == null) { list.RemoveAt(i); continue; }
                string t = list[i].Trim();
                if (t.Length == 0) { list.RemoveAt(i); continue; }
                list[i] = t;
            }

            for (int i = 0; i < list.Count; i++)
            {
                for (int j = list.Count - 1; j > i; j--)
                {
                    if (list[i].CaselessEq(list[j])) list.RemoveAt(j);
                }
            }
        }

        // -------------------------
        // Commands
        // -------------------------

        public sealed class CmdPeacefulMobSpawner : Command
        {
            public override string name { get { return "PeacefulMobSpawner"; } }
            public override string type { get { return "mod"; } }
            public override LevelPermission defaultRank { get { return LevelPermission.Operator; } }

            public override void Use(Player p, string message)
            {
                string msg = (message ?? "").Trim();
                string[] args = msg.SplitSpaces();

                if (args.Length == 0 || args[0].Length == 0)
                {
                    Help(p);
                    return;
                }

                string sub = args[0].ToLower();

                if (sub == "status")
                {
                    p.Message("&S[MobSpawner] enabled=&f{0}&S paused=&f{1}", enabled, paused);
                    p.Message("&S[MobSpawner] defaultPerWorldCap=&f{0}&S globalCap=&f{1}", defaultPerWorldCap, globalCap);
                    p.Message("&S[MobSpawner] tickSeconds=&f{0}&S burstPerWorld=&f{1}", tickSeconds, spawnBurstPerWorld);
                    p.Message("&S[MobSpawner] models=&f{0}", string.Join(",", modelPool.ToArray()));
                    p.Message("&S[MobSpawner] allowWorlds=&f{0}", allowWorlds.Count == 0 ? "(all)" : string.Join(",", allowWorlds.ToArray()));
                    p.Message("&S[MobSpawner] denyWorlds=&f{0}", denyWorlds.Count == 0 ? "(none)" : string.Join(",", denyWorlds.ToArray()));
                    p.Message("&S[MobSpawner] totalSpawned=&f{0}", CountTotalSpawned());
                    return;
                }

                if (sub == "on") { enabled = true; p.Message("&S[MobSpawner] enabled."); return; }
                if (sub == "off") { enabled = false; p.Message("&S[MobSpawner] disabled."); return; }
                if (sub == "pause") { paused = true; p.Message("&S[MobSpawner] paused."); return; }
                if (sub == "resume") { paused = false; p.Message("&S[MobSpawner] resumed."); return; }

                if (sub == "reload") { LoadConfig(); p.Message("&S[MobSpawner] reloaded config."); return; }

                if (sub == "despawnall") { DespawnAll(); p.Message("&S[MobSpawner] despawned all spawned bots."); return; }

                if (sub == "cap" && args.Length >= 2)
                {
                    int cap;
                    if (args.Length == 2)
                    {
                        if (!int.TryParse(args[1], out cap)) { p.Message("&WNot a number."); return; }
                        if (cap < 0) cap = 0;
                        if (cap > 500) cap = 500;

                        defaultPerWorldCap = cap;
                        p.Message("&S[MobSpawner] default per-world cap set to &f{0}", cap);
                        return;
                    }
                    else
                    {
                        string world = args[1];
                        if (!int.TryParse(args[2], out cap)) { p.Message("&WNot a number."); return; }
                        if (cap < 0) cap = 0;
                        if (cap > 500) cap = 500;

                        perWorldCaps[world] = cap;
                        p.Message("&S[MobSpawner] cap for &f{0}&S set to &f{1}", world, cap);
                        return;
                    }
                }

                if (sub == "globalcap" && args.Length >= 2)
                {
                    int cap;
                    if (!int.TryParse(args[1], out cap)) { p.Message("&WNot a number."); return; }
                    if (cap < 0) cap = 0;
                    if (cap > 5000) cap = 5000;
                    globalCap = cap;
                    p.Message("&S[MobSpawner] global cap set to &f{0}&S (0 disables)", cap);
                    return;
                }

                if (sub == "allow" && args.Length >= 2)
                {
                    string world = args[1];
                    if (!allowWorlds.CaselessContains(world)) allowWorlds.Add(world);
                    NormalizeList(allowWorlds);
                    p.Message("&S[MobSpawner] allowed world: &f{0}", world);
                    return;
                }

                if (sub == "deny" && args.Length >= 2)
                {
                    string world = args[1];
                    if (!denyWorlds.CaselessContains(world)) denyWorlds.Add(world);
                    NormalizeList(denyWorlds);
                    p.Message("&S[MobSpawner] denied world: &f{0}", world);
                    return;
                }

                if (sub == "unallow" && args.Length >= 2)
                {
                    string world = args[1];
                    allowWorlds.RemoveAll(w => w.CaselessEq(world));
                    p.Message("&S[MobSpawner] removed from allow list: &f{0}", world);
                    return;
                }

                if (sub == "undeny" && args.Length >= 2)
                {
                    string world = args[1];
                    denyWorlds.RemoveAll(w => w.CaselessEq(world));
                    p.Message("&S[MobSpawner] removed from deny list: &f{0}", world);
                    return;
                }

                if (sub == "cull" && args.Length >= 2)
                {
                    int n;
                    if (!int.TryParse(args[1], out n)) { p.Message("&WNot a number."); return; }
                    if (n < 1) n = 1;
                    if (n > 200) n = 200;

                    int removed = 0;
                    Level[] loaded = GetLoadedWorlds();
                    for (int i = 0; i < loaded.Length && removed < n; i++)
                    {
                        Level lvl = loaded[i];
                        if (lvl == null) continue;

                        while (removed < n)
                        {
                            string[] bots = GetTrackedBotsInWorld(lvl.name);
                            if (bots.Length == 0) break;

                            string pick = bots[Rng.Next(bots.Length)];
                            try
                            {
                                PlayerBot bot = FindBotInLevel(lvl, pick);
                                if (bot != null) PlayerBot.Remove(bot);
                            }
                            catch { }

                            lock (Sync) { spawnedBots.Remove(pick); }
                            removed++;
                        }
                    }

                    p.Message("&S[MobSpawner] culled &f{0}&S bots.", removed);
                    return;
                }

                Help(p);
            }

            public override void Help(Player p)
            {
                p.Message("&T/peacefulmobspawner status");
                p.Message("&T/peacefulmobspawner on|off");
                p.Message("&T/peacefulmobspawner pause|resume");
                p.Message("&T/peacefulmobspawner reload");
                p.Message("&T/peacefulmobspawner despawnall");
                p.Message("&T/peacefulmobspawner cap <n>");
                p.Message("&T/peacefulmobspawner cap <world> <n>");
                p.Message("&T/peacefulmobspawner globalcap <n>  &H(0 disables)");
                p.Message("&T/peacefulmobspawner allow|deny <world>");
                p.Message("&T/peacefulmobspawner unallow|undeny <world>");
                p.Message("&T/peacefulmobspawner cull <n>");
            }
        }
    }
}