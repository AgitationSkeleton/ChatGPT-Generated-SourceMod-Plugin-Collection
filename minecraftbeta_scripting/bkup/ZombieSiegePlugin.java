// No package; compiled as a single file. Suitable for Beta 1.7.3 / CB 1060-style Bukkit.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creature;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Giant;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import org.bukkit.inventory.ItemStack;

import org.bukkit.plugin.java.JavaPlugin;

public class ZombieSiegePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final int TICKS_PER_SECOND = 20;

    // Real-time timing (in TICKS_PER_SECOND units)
    private static final int DEFAULT_PREP_MINUTES = 20;
    private static final int INTER_WAVE_SECONDS = 30;
    private static final int INTER_WAVE_TICKS = INTER_WAVE_SECONDS * TICKS_PER_SECOND;

    // Settings (loaded from config.txt or defaults)
    private int prepTicks;
    private boolean allowBlockBreaking;
    private boolean allowDoorBreaking;
    private boolean protectChests;
    private int baseWaveSize;
    private int waveSizeIncrement;
    private boolean disableUndeadBurning;
    private boolean debug;
    private String siegeWorldName;

    private boolean subMonsterEnabled;
    private boolean subSlimeEnabled;
    private boolean subSpiderEnabled;
    private boolean subSkeletonEnabled;
    private boolean subCreeperEnabled;
    private boolean subPigZombieEnabled;
    private boolean subGhastEnabled;

    private Set<String> enabledWorlds = new HashSet<String>();

    // worldName -> session
    private Map<String, SiegeSession> sessions = new HashMap<String, SiegeSession>();

    // Approximate block-breaking hardness (seconds)
    private Map<Material, Integer> hardnessSeconds = new HashMap<Material, Integer>();

    // Global “seconds” counter (increments once per scheduler run)
    private int globalSeconds = 0;

    // Per-player best survival (in seconds)
    private Map<String, Integer> bestSurvivalSeconds = new HashMap<String, Integer>();

    // Per-player total siege mob kills (across all sieges)
    private Map<String, Integer> totalKills = new HashMap<String, Integer>();

    // Killstreak tracking (global “3 enemies in <= 3 seconds”)
    private Map<String, Integer> lastKillSecond = new HashMap<String, Integer>();
    private Map<String, Integer> killStreakCount = new HashMap<String, Integer>();

    public void onEnable() {
        loadSettings();
        initHardnessTable();
        loadScores();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zombiesiege").setExecutor(this);

        // Main tick: once per second (20 server ticks)
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                globalSeconds++;
                tickAllSessions();
            }
        }, TICKS_PER_SECOND, TICKS_PER_SECOND);
    }

    public void onDisable() {
        List<SiegeSession> copy = new ArrayList<SiegeSession>(sessions.values());
        for (int i = 0; i < copy.size(); i++) {
            stopSession(copy.get(i), "Zombie Siege plugin disabled.");
        }
        sessions.clear();
        saveScores();
    }

    // -------------------------------------------------
    // Settings & config
    // -------------------------------------------------

    private void loadSettings() {
        // Defaults
        String defaultSiegeWorld = "ZombieSiegeWorld";
        int prepMinutes = DEFAULT_PREP_MINUTES;
        baseWaveSize = 10;
        waveSizeIncrement = 5;
        allowBlockBreaking = true;
        allowDoorBreaking = true;
        protectChests = true;
        disableUndeadBurning = true;
        debug = false;

        subMonsterEnabled = true;
        subSlimeEnabled = true;
        subSpiderEnabled = true;
        subSkeletonEnabled = true;
        subCreeperEnabled = true;
        subPigZombieEnabled = true;
        subGhastEnabled = true;

        // Ensure data folder and config file
        File folder = getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File cfg = new File(folder, "config.txt");

        if (!cfg.exists()) {
            writeDefaultConfig(cfg, defaultSiegeWorld, prepMinutes);
        }

        Map<String, String> kv = new HashMap<String, String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(cfg));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                kv.put(key, val);
            }
        } catch (IOException ioe) {
            System.out.println("[ZombieSiegePlugin] Failed to read config.txt: " + ioe.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignore) {}
        }

        // Parse configuration
        siegeWorldName = getString(kv, "siegeWorld", defaultSiegeWorld);
        prepMinutes = getInt(kv, "prepMinutes", DEFAULT_PREP_MINUTES);
        baseWaveSize = getInt(kv, "baseWaveSize", baseWaveSize);
        waveSizeIncrement = getInt(kv, "waveSizeIncrement", waveSizeIncrement);
        allowBlockBreaking = getBool(kv, "allowBlockBreaking", allowBlockBreaking);
        allowDoorBreaking = getBool(kv, "allowDoorBreaking", allowDoorBreaking);
        protectChests = getBool(kv, "protectChests", protectChests);
        disableUndeadBurning = getBool(kv, "disableUndeadBurning", disableUndeadBurning);
        debug = getBool(kv, "debug", debug);

        subMonsterEnabled = getBool(kv, "enableSubMonster", subMonsterEnabled);
        subSlimeEnabled = getBool(kv, "enableSubSlime", subSlimeEnabled);
        subSpiderEnabled = getBool(kv, "enableSubSpider", subSpiderEnabled);
        subSkeletonEnabled = getBool(kv, "enableSubSkeleton", subSkeletonEnabled);
        subCreeperEnabled = getBool(kv, "enableSubCreeper", subCreeperEnabled);
        subPigZombieEnabled = getBool(kv, "enableSubPigZombie", subPigZombieEnabled);
        subGhastEnabled = getBool(kv, "enableSubGhast", subGhastEnabled);

        // Derived
        prepTicks = prepMinutes * 60 * TICKS_PER_SECOND;

        enabledWorlds.clear();
        if (siegeWorldName != null && siegeWorldName.length() > 0) {
            enabledWorlds.add(siegeWorldName);
        }

        System.out.println("[ZombieSiegePlugin] Loaded config: siegeWorld=" + siegeWorldName +
                           ", prepMinutes=" + prepMinutes);
    }

    private void writeDefaultConfig(File cfg, String siegeWorld, int prepMinutes) {
        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileWriter(cfg));
            out.println("# ZombieSiegePlugin configuration");
            out.println("# Generated on first run; edit and restart server to apply changes.");
            out.println();
            out.println("# Name of the world used for zombie sieges");
            out.println("siegeWorld=" + siegeWorld);
            out.println();
            out.println("# Preparation time in minutes before the first wave");
            out.println("prepMinutes=" + prepMinutes);
            out.println();
            out.println("# Base wave size and increment per wave");
            out.println("baseWaveSize=10");
            out.println("waveSizeIncrement=5");
            out.println();
            out.println("# Allow zombies/giants to break blocks and doors");
            out.println("allowBlockBreaking=true");
            out.println("allowDoorBreaking=true");
            out.println();
            out.println("# Prevent undead from burning in sunlight during sieges");
            out.println("disableUndeadBurning=true");
            out.println();
            out.println("# Protect chests from being broken by siege mobs");
            out.println("protectChests=true");
            out.println();
            out.println("# Enable debug messages in console and to siege players");
            out.println("debug=false");
            out.println();
            out.println("# Enable or disable sub-wave mobs (added every 5 waves)");
            out.println("# Wave 5+: Monster; 10+: Slime; 15+: Spider; 20+: Skeleton;");
            out.println("# 25+: Creeper; 30+: Zombie Pigman; 35+: Ghast");
            out.println("enableSubMonster=true");
            out.println("enableSubSlime=true");
            out.println("enableSubSpider=true");
            out.println("enableSubSkeleton=true");
            out.println("enableSubCreeper=true");
            out.println("enableSubPigZombie=true");
            out.println("enableSubGhast=true");
        } catch (IOException ioe) {
            System.out.println("[ZombieSiegePlugin] Failed to write default config: " + ioe.getMessage());
        } finally {
            if (out != null) out.close();
        }
    }

    private String getString(Map<String, String> kv, String key, String def) {
        String v = kv.get(key);
        if (v == null || v.length() == 0) return def;
        return v;
    }

    private int getInt(Map<String, String> kv, String key, int def) {
        String v = kv.get(key);
        if (v == null || v.length() == 0) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException nfe) {
            return def;
        }
    }

    private boolean getBool(Map<String, String> kv, String key, boolean def) {
        String v = kv.get(key);
        if (v == null || v.length() == 0) return def;
        v = v.toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("y") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("n") || v.equals("0")) return false;
        return def;
    }

    private void initHardnessTable() {
        hardnessSeconds.put(Material.WOOD, 4);
        hardnessSeconds.put(Material.LOG, 4);
        hardnessSeconds.put(Material.COBBLESTONE, 10);
        hardnessSeconds.put(Material.STONE, 12);
        hardnessSeconds.put(Material.GLASS, 1);
        hardnessSeconds.put(Material.SAND, 2);
        hardnessSeconds.put(Material.GRAVEL, 2);
        hardnessSeconds.put(Material.DIRT, 3);
        hardnessSeconds.put(Material.GRASS, 3);
        hardnessSeconds.put(Material.OBSIDIAN, 60);

        try {
            hardnessSeconds.put(Material.WOODEN_DOOR, 4);
        } catch (Throwable t) {
            try {
                hardnessSeconds.put(Material.WOOD_DOOR, 4);
            } catch (Throwable t2) {
                // ignore
            }
        }
    }

    // -------------------------------------------------
    // Scores (scores.txt)
    // -------------------------------------------------

    private void loadScores() {
        bestSurvivalSeconds.clear();
        totalKills.clear();

        File folder = getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File scoresFile = new File(folder, "scores.txt");
        if (!scoresFile.exists()) {
            return;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(scoresFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#")) continue;

                String[] parts = line.split(";");
                if (parts.length < 2) continue;

                String name = parts[0].trim();
                if (name.length() == 0) continue;

                int best = 0;
                int kills = 0;

                try {
                    best = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException nfe) {
                    best = 0;
                }

                if (parts.length >= 3) {
                    try {
                        kills = Integer.parseInt(parts[2].trim());
                    } catch (NumberFormatException nfe) {
                        kills = 0;
                    }
                }

                if (best > 0) {
                    bestSurvivalSeconds.put(name, Integer.valueOf(best));
                }
                if (kills > 0) {
                    totalKills.put(name, Integer.valueOf(kills));
                }
            }
            System.out.println("[ZombieSiegePlugin] Loaded scores from scores.txt");
        } catch (IOException ioe) {
            System.out.println("[ZombieSiegePlugin] Failed to read scores.txt: " + ioe.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignore) {}
        }
    }

    private void saveScores() {
        File folder = getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File scoresFile = new File(folder, "scores.txt");

        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileWriter(scoresFile));
            out.println("# ZombieSiegePlugin scores");
            out.println("# Format: playerName;bestSurvivalSeconds;totalKills");

            Set<String> names = new HashSet<String>();
            names.addAll(bestSurvivalSeconds.keySet());
            names.addAll(totalKills.keySet());

            for (String name : names) {
                int best = 0;
                int kills = 0;
                Integer b = bestSurvivalSeconds.get(name);
                Integer k = totalKills.get(name);
                if (b != null) best = b.intValue();
                if (k != null) kills = k.intValue();

                out.println(name + ";" + best + ";" + kills);
            }
        } catch (IOException ioe) {
            System.out.println("[ZombieSiegePlugin] Failed to write scores.txt: " + ioe.getMessage());
        } finally {
            if (out != null) out.close();
        }
    }

    private void addKill(String name) {
        int old = 0;
        Integer cur = totalKills.get(name);
        if (cur != null) old = cur.intValue();
        totalKills.put(name, Integer.valueOf(old + 1));
    }

    private void addSessionKill(SiegeSession session, String name) {
        int old = 0;
        Integer cur = session.sessionKills.get(name);
        if (cur != null) old = cur.intValue();
        session.sessionKills.put(name, Integer.valueOf(old + 1));
    }

    // -------------------------------------------------
    // Utility
    // -------------------------------------------------

    private boolean isSiegeWorld(World world) {
        return world != null && enabledWorlds.contains(world.getName());
    }

    private void debug(SiegeSession session, String message) {
        if (!debug) return;
        String worldName = (session != null && session.world != null) ? session.world.getName() : "null";
        System.out.println("[ZombieSiege DEBUG " + worldName + "] " + message);
        if (session != null) {
            broadcastToSession(session, "[DEBUG] " + message);
        }
    }

    private void broadcastToSession(SiegeSession session, String message) {
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                p.sendMessage(message);
            }
        }
    }

    private void stopSession(SiegeSession session, String reason) {
        if (session == null) return;

        // For non-player-driven stops (plugin disable, world becoming invalid),
        // we still give surviving players a survival record.
        if (session.siegeStartSecond >= 0 && !session.playersInSiege.isEmpty()) {
            int elapsedSec = Math.max(0, globalSeconds - session.siegeStartSecond);
            int elapsedMin = elapsedSec / 60;
            for (String name : session.playersInSiege) {
                int best = 0;
                if (bestSurvivalSeconds.containsKey(name)) {
                    best = bestSurvivalSeconds.get(name).intValue();
                }
                Player p = Bukkit.getPlayer(name);
                if (elapsedSec > best) {
                    bestSurvivalSeconds.put(name, Integer.valueOf(elapsedSec));
                    int oldMin = best / 60;
                    if (p != null && p.isOnline()) {
                        p.sendMessage("You have survived the zombie siege for " +
                                elapsedMin + " minutes, beating your record of " +
                                oldMin + " minutes, a new record!");
                    }
                } else {
                    if (p != null && p.isOnline()) {
                        int bestMin = best / 60;
                        p.sendMessage("You have survived the zombie siege for " +
                                elapsedMin + " minutes. Your record is " +
                                bestMin + " minutes.");
                    }
                }
            }
        }

        // Persist scores after this siege ends
        saveScores();

        broadcastToSession(session, "Zombie Siege ended: " + reason);
        debug(session, "Session stopped: " + reason);

        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && !le.isDead()) {
                le.remove();
            }
        }
        session.activeMobs.clear();
        session.breakTasks.clear();
        session.belowPlayerSinceSecond.clear();

        sessions.remove(session.world.getName());
    }

    private void healPlayers(SiegeSession session) {
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                try {
                    p.setHealth(20);
                } catch (Throwable t) {
                    // ignore if health API differs
                }
            }
        }
    }

    private void removeAllHostileMobsInWorld(World world) {
        List<Entity> list = world.getEntities();
        for (int i = 0; i < list.size(); i++) {
            Entity e = list.get(i);
            if (!(e instanceof LivingEntity)) continue;
            if (e instanceof Player) continue;
            if (isSiegeMob(e)) {
                ((LivingEntity) e).remove();
            }
        }
    }

    // Unified "player is out of this siege" handling (death, quit, world change)
    private void playerLeavesSiege(SiegeSession session, Player player, boolean died) {
        if (session == null || player == null) return;
        String name = player.getName();
        if (!session.playersInSiege.contains(name)) return;

        // Per-player survival time for this siege
        if (session.siegeStartSecond >= 0) {
            int elapsedSec = Math.max(0, globalSeconds - session.siegeStartSecond);
            int minutes = elapsedSec / 60;
            int seconds = elapsedSec % 60;

            // Update best survival record for this player
            int best = 0;
            if (bestSurvivalSeconds.containsKey(name)) {
                best = bestSurvivalSeconds.get(name).intValue();
            }
            if (elapsedSec > best) {
                bestSurvivalSeconds.put(name, Integer.valueOf(elapsedSec));
            }

            Integer sk = session.sessionKills.get(name);
            int kills = (sk != null) ? sk.intValue() : 0;

            if (player.isOnline()) {
                player.sendMessage("You survived this siege for " +
                        minutes + " minutes and " + seconds +
                        " seconds and got " + kills + " kills.");
                if (elapsedSec > best) {
                    int oldMin = best / 60;
                    player.sendMessage("You beat your previous survival record of " +
                            oldMin + " minutes. New record!");
                }
            }
        }

        // Remove them from siege
        session.playersInSiege.remove(name);

        // Leader reassignment (if any)
        if (session.leader != null && session.leader.equals(name)) {
            session.pickNewLeader();
            if (session.leader != null) {
                Player newLeader = Bukkit.getPlayer(session.leader);
                if (newLeader != null && newLeader.isOnline() &&
                    newLeader.getWorld().equals(session.world)) {
                    broadcastToSession(session, "New siege leader is " + newLeader.getName() + ".");
                }
            }
        }

        // If this was the last player, it's full Game Over for the run
        if (session.playersInSiege.isEmpty()) {
            int elapsedSecTotal = 0;
            if (session.siegeStartSecond >= 0) {
                elapsedSecTotal = Math.max(0, globalSeconds - session.siegeStartSecond);
            }
            int minutes = elapsedSecTotal / 60;
            int seconds = elapsedSecTotal % 60;

            int totalSiegeKills = 0;
            for (Integer val : session.sessionKills.values()) {
                if (val != null) totalSiegeKills += val.intValue();
            }

            Bukkit.broadcastMessage("Game over! The siege lasted " +
                    minutes + " minutes and " + seconds +
                    " seconds and the group got " + totalSiegeKills + " kills.");

            // Clean hostiles in the siege world
            removeAllHostileMobsInWorld(session.world);

            // Stop the session
            stopSession(session, "All siege players are out.");
        }

        // Save scores after a player leaves
        saveScores();
    }

    // -------------------------------------------------
    // Commands
    // -------------------------------------------------

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("zombiesiege")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        if (!isSiegeWorld(world)) {
            player.sendMessage("Zombie Siege is not enabled in this world.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("/zombiesiege start|stop|status|startwave|stats [player]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("start")) {
            handleStart(player, world);
            return true;
        } else if (sub.equals("stop")) {
            handleStop(player, world);
            return true;
        } else if (sub.equals("status")) {
            handleStatus(player, world);
            return true;
        } else if (sub.equals("startwave")) {
            handleStartWave(player, world);
            return true;
        } else if (sub.equals("stats")) {
            String targetName;
            if (args.length >= 2) {
                targetName = args[1];
            } else {
                targetName = player.getName();
            }
            handleStats(player, targetName);
            return true;
        }

        player.sendMessage("/zombiesiege start|stop|status|startwave|stats [player]");
        return true;
    }

    private void handleStart(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            session = new SiegeSession(world);
            sessions.put(world.getName(), session);
        }

        // Already part of this siege? Don't rejoin spam.
        if (session.playersInSiege.contains(player.getName())) {
            player.sendMessage("You are already part of this Zombie Siege.");
            return;
        }

        // If something is already running/prepping, just join it
        if (session.prepTicksRemaining > 0 || session.siegeActive || session.awaitingNextWave) {
            session.addPlayer(player);
            player.sendMessage("You join the existing Zombie Siege in this world.");
            return;
        }

        // Fresh siege
        session.addPlayer(player);
        session.leader = player.getName();
        session.prepTicksRemaining = prepTicks;
        session.siegeActive = false;
        session.awaitingNextWave = false;
        session.currentWave = 0;
        session.interWaveTicksRemaining = 0;
        session.wavesSurvived = 0;
        session.deathThisWave = false;
        session.siegeStartSecond = -1;
        session.lastSurvivalBroadcastMinutes = 0;
        session.sessionKills.clear();
        session.belowPlayerSinceSecond.clear();

        broadcastToSession(session, player.getName() + " has started a Zombie Siege in this world.");
        int minutes = prepTicks / (60 * TICKS_PER_SECOND);
        player.sendMessage("Zombie Siege preparation started. You have about " + minutes + " minutes to prepare.");
        debug(session, "Preparation started: " + session.prepTicksRemaining + " ticks.");
    }

    private void handleStop(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        if (session.leader != null && !session.leader.equals(player.getName()) && !player.isOp()) {
            player.sendMessage("Only the siege leader or an OP can stop this siege.");
            return;
        }

        stopSession(session, "Stopped by " + player.getName());
    }

    private void handleStatus(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        if (!session.playersInSiege.contains(player.getName()) && !player.isOp()) {
            player.sendMessage("You are not participating in this siege.");
            return;
        }

        String phase;
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            phase = "PREPARATION";
        } else if (session.siegeActive) {
            phase = "WAVE_ACTIVE";
        } else if (session.awaitingNextWave) {
            phase = "BETWEEN_WAVES";
        } else {
            phase = "IDLE";
        }

        player.sendMessage("Zombie Siege status:");
        player.sendMessage("  Phase: " + phase);
        if (session.prepTicksRemaining > 0) {
            int secondsLeft = session.prepTicksRemaining / TICKS_PER_SECOND;
            player.sendMessage("  Prep time remaining: " + secondsLeft + " seconds.");
        }
        if (session.siegeActive || session.awaitingNextWave) {
            player.sendMessage("  Current wave: " + session.currentWave);
            player.sendMessage("  Siege mobs alive: " + session.activeMobs.size());
            player.sendMessage("  Waves survived: " + session.wavesSurvived + ".");
        }

        // Current survival time if active
        if (session.siegeStartSecond >= 0 && (session.siegeActive || session.awaitingNextWave)) {
            int elapsedSec = Math.max(0, globalSeconds - session.siegeStartSecond);
            int elapsedMin = elapsedSec / 60;
            player.sendMessage("  Current survival time: " + elapsedMin + " minutes.");
        }

        // Best record
        Integer best = bestSurvivalSeconds.get(player.getName());
        if (best != null && best.intValue() > 0) {
            int bestMin = best.intValue() / 60;
            player.sendMessage("  Your best survival time: " + bestMin + " minutes.");
        }

        // Total kills
        Integer kills = totalKills.get(player.getName());
        if (kills != null) {
            player.sendMessage("  Your total siege kills: " + kills.intValue() + ".");
        }
    }

    private void handleStartWave(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        // Must be part of the siege or OP
        if (!session.playersInSiege.contains(player.getName()) && !player.isOp()) {
            player.sendMessage("You are not participating in this siege.");
            return;
        }

        // Only leader or OP can skip timers
        if (session.leader != null && !session.leader.equals(player.getName()) && !player.isOp()) {
            player.sendMessage("Only the siege leader or an OP can start waves early.");
            return;
        }

        // Case 1: still in prep, no waves have started
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            session.prepTicksRemaining = 0;
            broadcastToSession(session, "Preparation time skipped. Starting the first wave now.");
            debug(session, "Prep skipped by " + player.getName() + ", starting first wave.");
            startWave(session);
            return;
        }

        // Case 2: between waves, waiting for next wave timer
        if (session.awaitingNextWave && !session.siegeActive) {
            session.interWaveTicksRemaining = 0;
            broadcastToSession(session, "Next wave is starting early!");
            debug(session, "Inter-wave delay skipped by " + player.getName() + ".");
            startWave(session);
            return;
        }

        // Otherwise, nothing to fast-forward
        player.sendMessage("There is no pending wave to start early.");
    }

    private void handleStats(Player viewer, String targetName) {
        Integer best = bestSurvivalSeconds.get(targetName);
        Integer kills = totalKills.get(targetName);

        if (best == null && kills == null) {
            viewer.sendMessage("No Zombie Siege stats recorded for " + targetName + ".");
            return;
        }

        int bestSec = (best != null) ? best.intValue() : 0;
        int bestMin = bestSec / 60;
        int totalKillsVal = (kills != null) ? kills.intValue() : 0;

        if (viewer.getName().equalsIgnoreCase(targetName)) {
            viewer.sendMessage("Your Zombie Siege stats:");
        } else {
            viewer.sendMessage("Zombie Siege stats for " + targetName + ":");
        }

        viewer.sendMessage("  Best survival: " + bestMin + " minutes (" + bestSec + " seconds).");
        viewer.sendMessage("  Total siege mob kills: " + totalKillsVal + ".");
    }

    // -------------------------------------------------
    // Event Handlers
    // -------------------------------------------------

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World from = event.getFrom();
        World to = player.getWorld();

        handlePlayerLeave(player, from);
        handlePlayerEnter(player, to);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        handlePlayerLeave(player, player.getWorld());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;
        if (!session.playersInSiege.contains(player.getName())) return;

        // Treat respawn as that player leaving the siege; siege only ends if they were last
        playerLeavesSiege(session, player, true);
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!disableUndeadBurning) return;

        Entity ent = event.getEntity();
        World world = ent.getWorld();
        if (!isSiegeWorld(world)) return;

        if (ent instanceof org.bukkit.entity.Zombie ||
            ent instanceof org.bukkit.entity.Skeleton ||
            ent instanceof org.bukkit.entity.PigZombie ||
            ent instanceof org.bukkit.entity.Giant) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!isSiegeWorld(world)) return;

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (session.prepTicksRemaining > 0 || session.siegeActive || session.awaitingNextWave) {
            event.setCancelled(true);
            player.sendMessage("You cannot sleep during a zombie siege.");
        }
    }

    // Cancel unrelated hostile spawns during active/between-waves siege
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (!isSiegeWorld(world)) return;

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (!session.siegeActive && !session.awaitingNextWave) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL ||
            reason == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.setCancelled(true);
        }
    }

    // Killstreak logic (3 siege mobs in <= 3 seconds) + kill count + special drops
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        World world = entity.getWorld();
        if (!isSiegeWorld(world)) return;

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (!(entity instanceof LivingEntity)) return;

        // Older Bukkit: no getKiller(), so infer from last damage cause
        LivingEntity living = (LivingEntity) entity;
        EntityDamageEvent dmg = living.getLastDamageCause();
        if (!(dmg instanceof EntityDamageByEntityEvent)) return;

        Entity damager = ((EntityDamageByEntityEvent) dmg).getDamager();
        Player killer = null;

        if (damager instanceof Player) {
            killer = (Player) damager;
        } else if (damager instanceof Arrow) {
            Arrow arrow = (Arrow) damager;
            Object shooter = arrow.getShooter();
            if (shooter instanceof Player) {
                killer = (Player) shooter;
            }
        }

        if (killer == null) return;
        if (!session.playersInSiege.contains(killer.getName())) return;

        // Only count siege mobs for the streak and kill tally
        if (!isSiegeMob(entity)) return;

        // Special drops for Giant
        if (entity instanceof Giant) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Material.GOLDEN_APPLE, 1));
            event.getDrops().add(new ItemStack(Material.SPONGE, 16));
        } else if (isMonsterHuman(entity)) {
            // Special drops for "Monster" (mob 49 human)
            List<ItemStack> drops = event.getDrops();

            int stringCount = randomInt(0, 2);
            int gunpowderCount = randomInt(0, 2);
            int featherCount = randomInt(0, 2);

            if (stringCount > 0) {
                drops.add(new ItemStack(Material.STRING, stringCount));
            }
            if (gunpowderCount > 0) {
                drops.add(new ItemStack(Material.SULPHUR, gunpowderCount));
            }
            if (featherCount > 0) {
                drops.add(new ItemStack(Material.FEATHER, featherCount));
            }

            // Rare flint and steel (~10% chance)
            if (Math.random() < 0.10) {
                drops.add(new ItemStack(Material.FLINT_AND_STEEL, 1));
            }
        }

        addKill(killer.getName());
        addSessionKill(session, killer.getName());
        handleKillStreak(killer, entity.getLocation());
    }

    private void handlePlayerEnter(Player player, World world) {
        if (!isSiegeWorld(world)) return;
        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;
        if (!session.playersInSiege.contains(player.getName())) return;

        player.sendMessage("You have returned to an active Zombie Siege world.");
    }

    private void handlePlayerLeave(Player player, World world) {
        if (world == null) return;
        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (!session.playersInSiege.contains(player.getName())) return;

        playerLeavesSiege(session, player, false);
    }

    // -------------------------------------------------
    // Killstreak handling
    // -------------------------------------------------

    private boolean isSiegeMob(Entity ent) {
        if (ent instanceof org.bukkit.entity.Zombie) return true;
        if (ent instanceof Giant) return true;
        if (ent instanceof org.bukkit.entity.Slime) return true;
        if (ent instanceof org.bukkit.entity.Spider) return true;
        if (ent instanceof org.bukkit.entity.Skeleton) return true;
        if (ent instanceof org.bukkit.entity.Creeper) return true;
        if (ent instanceof org.bukkit.entity.PigZombie) return true;
        if (ent instanceof org.bukkit.entity.Ghast) return true;
        if (ent instanceof Monster) return true; // covers MONSTER/hostile human
        return false;
    }

    private boolean isMonsterHuman(Entity ent) {
        if (!(ent instanceof Monster)) return false;
        if (ent instanceof org.bukkit.entity.Zombie) return false;
        if (ent instanceof org.bukkit.entity.Skeleton) return false;
        if (ent instanceof org.bukkit.entity.Creeper) return false;
        if (ent instanceof org.bukkit.entity.Spider) return false;
        if (ent instanceof org.bukkit.entity.Slime) return false;
        if (ent instanceof org.bukkit.entity.PigZombie) return false;
        if (ent instanceof Giant) return false;
        if (ent instanceof org.bukkit.entity.Ghast) return false;
        return true; // leftover Monster = mob 49 human
    }

    private void handleKillStreak(Player killer, Location loc) {
        String name = killer.getName();
        int now = globalSeconds;

        Integer last = lastKillSecond.get(name);
        int streak = 1;
        if (last != null && (now - last.intValue()) <= 3) {
            Integer oldCount = killStreakCount.get(name);
            if (oldCount != null) {
                streak = oldCount.intValue() + 1;
            } else {
                streak = 2;
            }
        }

        lastKillSecond.put(name, Integer.valueOf(now));
        killStreakCount.put(name, Integer.valueOf(streak));

        if (streak >= 3) {
            // Reward and reset streak
            giveKillStreakReward(killer, loc);
            killStreakCount.put(name, Integer.valueOf(0));
        }
    }

    private void giveKillStreakReward(Player killer, Location loc) {
        double roll = Math.random();
        Material rewardMat;
        String rewardName;
        int amount = 1;

        if (roll < 0.10) {
            // 10% chance: random sword
            double r2 = Math.random();
            if (r2 < 0.33) {
                rewardMat = Material.GOLD_SWORD;
                rewardName = "a Golden Sword";
            } else if (r2 < 0.66) {
                rewardMat = Material.IRON_SWORD;
                rewardName = "an Iron Sword";
            } else {
                rewardMat = Material.DIAMOND_SWORD;
                rewardName = "a Diamond Sword";
            }
        } else if (roll < 0.45) {
            // 35% chance: ingot/diamond
            double r2 = Math.random();
            if (r2 < 0.33) {
                rewardMat = Material.GOLD_INGOT;
                rewardName = "a Gold Ingot";
            } else if (r2 < 0.66) {
                rewardMat = Material.IRON_INGOT;
                rewardName = "an Iron Ingot";
            } else {
                rewardMat = Material.DIAMOND;
                rewardName = "a Diamond";
            }
        } else if (roll < 0.75) {
            // 30% chance: arrows (bundle of 16)
            rewardMat = Material.ARROW;
            amount = 16;
            rewardName = "16 Arrows";
        } else {
            // 25% chance: food (apples, bread, pork, golden apple)
            double r2 = Math.random();
            if (r2 < 0.20) {
                rewardMat = Material.APPLE;
                rewardName = "an Apple";
            } else if (r2 < 0.40) {
                rewardMat = Material.BREAD;
                rewardName = "Bread";
            } else if (r2 < 0.70) {
                rewardMat = Material.PORK;
                rewardName = "Raw Porkchop";
            } else if (r2 < 0.90) {
                rewardMat = Material.GRILLED_PORK;
                rewardName = "Cooked Porkchop";
            } else {
                rewardMat = Material.GOLDEN_APPLE;
                rewardName = "a Golden Apple";
            }
        }

        loc.getWorld().dropItemNaturally(loc.add(0.5, 0.5, 0.5), new ItemStack(rewardMat, amount));
        killer.sendMessage("You killed 3 enemies in 3 seconds! You earned " + rewardName + ".");
    }

    private int randomInt(int min, int max) {
        if (max < min) return min;
        return min + (int) Math.floor(Math.random() * (max - min + 1));
    }

    // -------------------------------------------------
    // Main ticking
    // -------------------------------------------------

    private void tickAllSessions() {
        List<SiegeSession> copy = new ArrayList<SiegeSession>(sessions.values());
        for (int i = 0; i < copy.size(); i++) {
            tickSession(copy.get(i));
        }
    }

    private void tickSession(SiegeSession session) {
        World world = session.world;
        if (world == null) {
            stopSession(session, "World unloaded.");
            return;
        }

        if (!isSiegeWorld(world)) {
            stopSession(session, "World no longer configured for sieges.");
            return;
        }

        // If no siege players online in this world, reset to idle
        if (!hasOnlineSiegePlayersInWorld(session)) {
            session.prepTicksRemaining = 0;
            session.siegeActive = false;
            session.awaitingNextWave = false;
            session.currentWave = 0;
            session.interWaveTicksRemaining = 0;
            return;
        }

        // Prep phase
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            session.prepTicksRemaining -= TICKS_PER_SECOND;
            if (session.prepTicksRemaining < 0) {
                session.prepTicksRemaining = 0;
            }

            int secondsLeft = session.prepTicksRemaining / TICKS_PER_SECOND;
            handlePrepCountdown(session, secondsLeft);

            if (session.prepTicksRemaining <= 0) {
                session.prepTicksRemaining = 0;
                broadcastToSession(session, "Preparation time is over. The first wave is starting.");
                debug(session, "Prep complete, starting first wave.");
                startWave(session);
            }
            return;
        }

        // Between waves
        if (session.awaitingNextWave && !session.siegeActive) {
            if (session.interWaveTicksRemaining > 0) {
                session.interWaveTicksRemaining -= TICKS_PER_SECOND;
                if (session.interWaveTicksRemaining < 0) {
                    session.interWaveTicksRemaining = 0;
                }
            }

            if (session.interWaveTicksRemaining <= 0) {
                broadcastToSession(session, "Next wave is starting.");
                startWave(session);
            }
            return;
        }

        // Active wave
        if (session.siegeActive) {
            cleanMobList(session);
            aggroMobs(session);
            if (allowBlockBreaking || allowDoorBreaking) {
                tickBlockBreaking(session);
            }

            // Survival milestone messages every 5 minutes
            handleSurvivalMilestones(session);

            if (session.activeMobs.isEmpty()) {
                // Wave cleared
                session.siegeActive = false;
                session.awaitingNextWave = true;
                if (!session.deathThisWave) {
                    session.wavesSurvived++;
                }
                session.interWaveTicksRemaining = INTER_WAVE_TICKS;

                // Heal players between waves
                healPlayers(session);

                broadcastToSession(session, "Wave " + session.currentWave +
                        " cleared. Waves survived: " + session.wavesSurvived + ".");
                broadcastToSession(session, "Next wave will begin in about " +
                        INTER_WAVE_SECONDS + " seconds.");
                debug(session, "Wave " + session.currentWave + " cleared, waiting " +
                        INTER_WAVE_SECONDS + " seconds.");
            }
        }
    }

    private void handlePrepCountdown(SiegeSession session, int secondsLeft) {
        if (secondsLeft <= 0) return;

        if (secondsLeft == 900 || secondsLeft == 600 || secondsLeft == 300) {
            int mins = secondsLeft / 60;
            broadcastToSession(session, "The zombie siege begins in " + mins + " minutes.");
        } else if (secondsLeft == 60 || secondsLeft == 45 || secondsLeft == 30 ||
                   secondsLeft == 15 || secondsLeft == 10 || secondsLeft == 5) {
            broadcastToSession(session, "The zombie siege begins in " + secondsLeft + " seconds.");
        }
    }

    private void handleSurvivalMilestones(SiegeSession session) {
        if (session.siegeStartSecond < 0) return;
        int elapsedSec = Math.max(0, globalSeconds - session.siegeStartSecond);
        int elapsedMin = elapsedSec / 60;
        if (elapsedMin >= 5 &&
            elapsedMin % 5 == 0 &&
            elapsedMin > session.lastSurvivalBroadcastMinutes) {
            broadcastToSession(session, "You have survived the zombie siege for " +
                    elapsedMin + " minutes.");
            session.lastSurvivalBroadcastMinutes = elapsedMin;
        }
    }

    private boolean hasOnlineSiegePlayersInWorld(SiegeSession session) {
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                return true;
            }
        }
        return false;
    }

    private void cleanMobList(SiegeSession session) {
        List<LivingEntity> alive = new ArrayList<LivingEntity>();
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && !le.isDead() && le.getWorld().equals(session.world)) {
                alive.add(le);
            }
        }
        session.activeMobs = alive;

        List<Integer> toRemove = new ArrayList<Integer>();
        for (Integer id : session.breakTasks.keySet()) {
            LivingEntity e = findMobById(session, id.intValue());
            if (e == null || e.isDead()) {
                toRemove.add(id);
            }
        }
        for (int i = 0; i < toRemove.size(); i++) {
            session.breakTasks.remove(toRemove.get(i));
            session.belowPlayerSinceSecond.remove(toRemove.get(i));
        }
    }

    private LivingEntity findMobById(SiegeSession session, int id) {
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && le.getEntityId() == id) {
                return le;
            }
        }
        return null;
    }

    private void startWave(SiegeSession session) {
        session.siegeActive = true;
        session.awaitingNextWave = false;
        session.deathThisWave = false;
        session.currentWave++;
        if (session.siegeStartSecond < 0) {
            session.siegeStartSecond = globalSeconds;
            session.lastSurvivalBroadcastMinutes = 0;
        }
        spawnWave(session);
    }

    private CreatureType getSubMobTypeForWave(int wave) {
        // From hardest to softest, respecting enable flags
        if (wave >= 35 && subGhastEnabled) return CreatureType.GHAST;
        if (wave >= 30 && subPigZombieEnabled) return CreatureType.PIG_ZOMBIE;
        if (wave >= 25 && subCreeperEnabled) return CreatureType.CREEPER;
        if (wave >= 20 && subSkeletonEnabled) return CreatureType.SKELETON;
        if (wave >= 15 && subSpiderEnabled) return CreatureType.SPIDER;
        if (wave >= 10 && subSlimeEnabled) return CreatureType.SLIME;
        if (wave >= 5 && subMonsterEnabled) return CreatureType.MONSTER;
        return null;
    }

    private void spawnWave(SiegeSession session) {
        // Purge unrelated hostiles before spawning wave
        purgeUnrelatedHostiles(session);

        int playerCount = Math.max(1, session.playersInSiege.size());
        int wave = Math.max(1, session.currentWave);

        int zombiesToSpawn = baseWaveSize + (wave - 1) * waveSizeIncrement;
        zombiesToSpawn *= playerCount;

        CreatureType subType = getSubMobTypeForWave(wave);
        int subCount = 0;
        if (subType != null) {
            if ((zombiesToSpawn % 2) == 1) {
                subCount = zombiesToSpawn / 5;
            } else {
                subCount = zombiesToSpawn / 4;
            }
            if (subCount < 1) subCount = 1;
        }
        if (subCount > zombiesToSpawn) {
            subCount = zombiesToSpawn;
        }
        int normalZombies = zombiesToSpawn - subCount;
        if (normalZombies < 0) normalZombies = 0;

        debug(session, "Spawning wave " + wave + " with " +
                zombiesToSpawn + " total mobs for " + playerCount + " players. " +
                "Zombies=" + normalZombies + ", subwave=" + subCount +
                (subType != null ? (" (" + subType.toString() + ")") : ""));

        List<Player> targets = new ArrayList<Player>();
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) return;

        World world = session.world;

        // Spawn normal zombies, with a very rare chance to be a Giant instead
        for (int i = 0; i < normalZombies; i++) {
            Player target = targets.get(i % targets.size());
            Location base = target.getLocation();

            double angle = (2 * Math.PI) * (i / (double) Math.max(1, zombiesToSpawn));
            double radius = 20 + (i % 12);
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;

            Location spawnLoc = findSafeSpawn(world, base.getX() + dx, base.getZ() + dz);
            if (spawnLoc == null) continue;

            CreatureType spawnType;
            // Very rare giant (e.g., ~1% chance)
            if (Math.random() < 0.01) {
                spawnType = CreatureType.GIANT;
            } else {
                spawnType = CreatureType.ZOMBIE;
            }

            LivingEntity mob = (LivingEntity) world.spawnCreature(spawnLoc, spawnType);
            session.activeMobs.add(mob);
        }

        // Spawn subwave mobs
        if (subType != null && subCount > 0) {
            for (int i = 0; i < subCount; i++) {
                Player target = targets.get(i % targets.size());
                Location base = target.getLocation();

                double angle = (2 * Math.PI) * (i / (double) Math.max(1, subCount));
                double radius = 25 + (i % 10);
                double dx = Math.cos(angle) * radius;
                double dz = Math.sin(angle) * radius;

                Location spawnLoc = findSafeSpawn(world, base.getX() + dx, base.getZ() + dz);
                if (spawnLoc == null) continue;

                LivingEntity mob = (LivingEntity) world.spawnCreature(spawnLoc, subType);
                session.activeMobs.add(mob);
            }
        }

        String msg = "Wave " + session.currentWave +
                " has begun! Horde size: " + zombiesToSpawn + ".";
        if (subType != null && subCount > 0) {
            msg += " This wave also includes " + subType.toString().toLowerCase().replace('_', ' ') + ".";
        }

        broadcastToSession(session, msg);
        debug(session, "Wave spawn complete. Active mobs: " + session.activeMobs.size());
    }

    private void purgeUnrelatedHostiles(SiegeSession session) {
        World world = session.world;
        if (world == null) return;

        List<Entity> entities = world.getEntities();
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (!(e instanceof LivingEntity)) continue;
            if (e instanceof Player) continue;

            // Only care about hostile-ish mobs
            if (!isSiegeMob(e)) continue;

            LivingEntity le = (LivingEntity) e;
            if (!isTrackedSiegeMob(session, le)) {
                le.remove();
            }
        }

        debug(session, "Purged unrelated hostile mobs before spawning wave " + session.currentWave + ".");
    }

    private boolean isTrackedSiegeMob(SiegeSession session, LivingEntity mob) {
        int id = mob.getEntityId();
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity tracked = session.activeMobs.get(i);
            if (tracked != null && tracked.getEntityId() == id) {
                return true;
            }
        }
        return false;
    }

    private Location findSafeSpawn(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        for (int y = 80; y > 5; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            Block above = world.getBlockAt(bx, y + 1, bz);
            Block below = world.getBlockAt(bx, y - 1, bz);

            if (!block.isEmpty() || !above.isEmpty()) continue;
            if (below.isEmpty()) continue;

            return new Location(world, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    // -------------------------------------------------
    // Aggro + teleport assistance (including anti-tunneling)
    // -------------------------------------------------

    private void aggroMobs(SiegeSession session) {
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le == null || le.isDead()) continue;
            if (!(le instanceof Creature)) continue;

            Player target = findNearestSiegePlayer(session, le.getLocation());
            if (target == null || !target.isOnline()) continue;

            Location mobLoc = le.getLocation();
            Location targetLoc = target.getLocation();
            double distSq = mobLoc.distanceSquared(targetLoc);

            // Zombies that wander too far will be repositioned behind the player
            double teleportRangeSq = 40 * 40; // beyond ~40 blocks: reposition
            if (distSq > teleportRangeSq) {
                mobLoc = teleportBehindPlayer(le, target);
            }

            // Anti-tunneling: if mob is below the player for >30 seconds, teleport them up
            int mobId = le.getEntityId();
            double mobY = mobLoc.getY();
            double playerY = targetLoc.getY();

            if (mobY < playerY - 1.0) {
                Integer since = session.belowPlayerSinceSecond.get(Integer.valueOf(mobId));
                if (since == null) {
                    session.belowPlayerSinceSecond.put(Integer.valueOf(mobId), Integer.valueOf(globalSeconds));
                } else {
                    int elapsed = globalSeconds - since.intValue();
                    if (elapsed >= 30) {
                        teleportBehindPlayer(le, target);
                        session.belowPlayerSinceSecond.remove(Integer.valueOf(mobId));
                    }
                }
            } else {
                // No longer below player; reset timer
                session.belowPlayerSinceSecond.remove(Integer.valueOf(mobId));
            }

            ((Creature) le).setTarget(target);
        }
    }

    private Location teleportBehindPlayer(LivingEntity mob, Player target) {
        Location targetLoc = target.getLocation();
        float yaw = targetLoc.getYaw();
        double yawRad = Math.toRadians(yaw + 90.0);

        double facingX = Math.cos(yawRad);
        double facingZ = Math.sin(yawRad);

        // Place mob 10–16 blocks behind the player
        double backDistance = 10.0 + (Math.random() * 6.0);

        double baseX = targetLoc.getX() - facingX * backDistance;
        double baseZ = targetLoc.getZ() - facingZ * backDistance;

        double sideX = -facingZ;
        double sideZ = facingX;
        double sideOffset = (Math.random() * 6.0) - 3.0; // -3..+3
        baseX += sideX * sideOffset;
        baseZ += sideZ * sideOffset;

        Location newLoc = findSafeSpawn(target.getWorld(), baseX, baseZ);
        if (newLoc == null) {
            // Fallback: just use the player's current feet position offset slightly
            newLoc = targetLoc.clone().add(-facingX * 2.0, 0.0, -facingZ * 2.0);
        }

        // Try to keep roughly same Y level as player if possible
        newLoc.setY(targetLoc.getY());

        mob.teleport(newLoc);
        return newLoc;
    }

    private Player findNearestSiegePlayer(SiegeSession session, Location from) {
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p == null || !p.isOnline()) continue;
            if (!p.getWorld().equals(session.world)) continue;

            double distSq = p.getLocation().distanceSquared(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best;
    }

    // -------------------------------------------------
    // Block / door breaking
    // -------------------------------------------------

    private boolean canMobBreakBlocks(LivingEntity mob) {
        if (mob instanceof org.bukkit.entity.Creeper) return false;
        if (mob instanceof org.bukkit.entity.Ghast) return false;
        if (mob instanceof org.bukkit.entity.Spider) return false;
        // Everything else (zombies, giants, skeletons, pigmen, slimes, Monster, etc.) can
        return true;
    }

    private void tickBlockBreaking(SiegeSession session) {
        // Advance existing tasks
        List<Integer> completed = new ArrayList<Integer>();

        for (Map.Entry<Integer, BreakTask> entry : session.breakTasks.entrySet()) {
            BreakTask task = entry.getValue();
            LivingEntity le = findMobById(session, task.entityId);

            if (le == null || le.isDead()) {
                completed.add(Integer.valueOf(task.entityId));
                continue;
            }

            if (!isNearBlock(le.getLocation(), task.targetBlock)) {
                completed.add(Integer.valueOf(task.entityId));
                continue;
            }

            task.ticksLeft -= TICKS_PER_SECOND;
            if (task.ticksLeft <= 0) {
                Block b = task.targetBlock;
                if (b != null && b.getType() != Material.AIR) {
                    Material type = b.getType();

                    if (isDoorMaterial(type)) {
                        // Special handling for doors: break both halves, drop exactly one door item
                        breakDoorAndDrop(b);
                        debug(session, "Door broken by mob " + task.entityId + " at " +
                                b.getX() + "," + b.getY() + "," + b.getZ());
                    } else {
                        // Normal block: break and optionally break block above for 1x2 doorway
                        Material dropType = normalizeBrokenMaterial(type);
                        b.setType(Material.AIR);
                        if (dropType != null) {
                            b.getWorld().dropItemNaturally(
                                    b.getLocation().add(0.5, 0.5, 0.5),
                                    new ItemStack(dropType, 1));
                        }
                        debug(session, "Block broken by mob " + task.entityId + " at " +
                                b.getX() + "," + b.getY() + "," + b.getZ() +
                                " (" + type.name() + ")");

                        // Try to break the block above as well to create a 1x2 doorway
                        Block above = b.getWorld().getBlockAt(b.getX(), b.getY() + 1, b.getZ());
                        Material aboveType = above.getType();
                        if (aboveType != Material.AIR && isBreakableMaterial(aboveType)) {
                            Material aboveDrop = normalizeBrokenMaterial(aboveType);
                            above.setType(Material.AIR);
                            if (aboveDrop != null) {
                                above.getWorld().dropItemNaturally(
                                        above.getLocation().add(0.5, 0.5, 0.5),
                                        new ItemStack(aboveDrop, 1));
                            }
                            debug(session, "Upper block also broken at " +
                                    above.getX() + "," + above.getY() + "," + above.getZ() +
                                    " (" + aboveType.name() + ")");
                        }
                    }
                }
                completed.add(Integer.valueOf(task.entityId));
            }
        }

        for (int i = 0; i < completed.size(); i++) {
            session.breakTasks.remove(completed.get(i));
        }

        // Start new tasks
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le == null || le.isDead()) continue;
            if (!canMobBreakBlocks(le)) continue; // skip creepers/ghasts/spiders
            if (session.breakTasks.containsKey(Integer.valueOf(le.getEntityId()))) continue;

            Block target = findBreakableBlockNear(le.getLocation());
            if (target == null) continue;

            Material mat = target.getType();
            if (!allowDoorBreaking && isDoorMaterial(mat)) {
                continue;
            }

            int secs = getHardnessSeconds(mat);
            // Double the time it takes to break any block
            secs = secs * 2;

            // Giants break blocks twice as fast
            if (le instanceof Giant) {
                secs = (secs + 1) / 2;
            }

            if (secs <= 0) continue;

            BreakTask task = new BreakTask(le.getEntityId(), target, secs * TICKS_PER_SECOND);
            session.breakTasks.put(Integer.valueOf(le.getEntityId()), task);
            debug(session, "Mob " + le.getEntityId() + " started breaking " + mat.name() +
                    " at " + target.getX() + "," + target.getY() + "," + target.getZ() +
                    " (" + secs + "s)");
        }
    }

    private void breakDoorAndDrop(Block b) {
        Material type = b.getType();
        if (!isDoorMaterial(type)) return;

        World world = b.getWorld();
        Block otherHalf = null;
        Block above = b.getRelative(0, 1, 0);
        Block below = b.getRelative(0, -1, 0);

        if (above.getType() == type) {
            otherHalf = above;
        } else if (below.getType() == type) {
            otherHalf = below;
        }

        b.setType(Material.AIR);
        if (otherHalf != null) {
            otherHalf.setType(Material.AIR);
        }

        // Drop exactly one door item (324 or 330 equivalent)
        Material itemType = doorItemFromBlock(type);
        world.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(itemType, 1));
    }

    private Material doorItemFromBlock(Material blockMat) {
        // Beta 1.7.3: wooden door item is 324, iron door item is 330.
        // Bukkit usually exposes WOODEN_DOOR as item, IRON_DOOR as item, and IRON_DOOR_BLOCK as the block.
        if (blockMat == Material.IRON_DOOR_BLOCK) {
            return Material.IRON_DOOR;
        }
        // Everything else with "DOOR" we treat as wooden
        return Material.WOODEN_DOOR;
    }

    private Material normalizeBrokenMaterial(Material mat) {
        // Convert grass to dirt so we don't get unobtainable grass without Silk Touch
        if (mat == Material.GRASS) return Material.DIRT;
        return mat;
    }

    private boolean isNearBlock(Location loc, Block block) {
        if (block == null) return false;
        if (!block.getWorld().equals(loc.getWorld())) return false;
        Location bl = block.getLocation().add(0.5, 0.5, 0.5);
        double distSq = loc.distanceSquared(bl);
        return distSq <= 2.25; // within ~1.5 blocks
    }

    private Block findBreakableBlockNear(Location loc) {
        World world = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        // Check a 3x3 around feet; for each column, look at feet and eye level.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block lower = world.getBlockAt(bx + dx, by, bz + dz);
                Block upper = world.getBlockAt(bx + dx, by + 1, bz + dz);

                Material lowerMat = lower.getType();
                Material upperMat = upper.getType();

                boolean lowerBreak = isBreakableCandidate(lowerMat);
                boolean upperBreak = isBreakableCandidate(upperMat);

                if (lowerBreak || upperBreak) {
                    if (lowerBreak) return lower;
                    return upper;
                }
            }
        }

        return null;
    }

    private boolean isBreakableCandidate(Material mat) {
        if (mat == Material.AIR) return false;
        if (protectChests && mat == Material.CHEST) return false;
        if (!allowBlockBreaking && !isDoorMaterial(mat)) return false;
        return isBreakableMaterial(mat);
    }

    private boolean isBreakableMaterial(Material mat) {
        if (isDoorMaterial(mat)) return true;
        return hardnessSeconds.containsKey(mat);
    }

    private boolean isDoorMaterial(Material mat) {
        String name = mat.name().toUpperCase();
        return (name.indexOf("DOOR") != -1);
    }

    private int getHardnessSeconds(Material mat) {
        Integer secs = hardnessSeconds.get(mat);
        if (secs == null) return 5;
        return secs.intValue();
    }

    // -------------------------------------------------
    // Inner data classes
    // -------------------------------------------------

    private static class SiegeSession {
        final World world;
        final Set<String> playersInSiege = new HashSet<String>();
        String leader;

        int prepTicksRemaining = 0;
        boolean siegeActive = false;
        boolean awaitingNextWave = false;

        int currentWave = 0;
        int wavesSurvived = 0;
        boolean deathThisWave = false;

        int interWaveTicksRemaining = 0;

        int siegeStartSecond = -1;
        int lastSurvivalBroadcastMinutes = 0;

        List<LivingEntity> activeMobs = new ArrayList<LivingEntity>();
        Map<Integer, BreakTask> breakTasks = new HashMap<Integer, BreakTask>();

        // Per-siege kills per player
        Map<String, Integer> sessionKills = new HashMap<String, Integer>();

        // Anti-tunneling: mob ID -> second when it was first noticed below player
        Map<Integer, Integer> belowPlayerSinceSecond = new HashMap<Integer, Integer>();

        SiegeSession(World world) {
            this.world = world;
        }

        void addPlayer(Player player) {
            if (player == null) return;
            String name = player.getName();
            playersInSiege.add(name);
            if (leader == null) {
                leader = name;
            }
        }

        void pickNewLeader() {
            for (String name : playersInSiege) {
                leader = name;
                return;
            }
            leader = null;
        }
    }

    private static class BreakTask {
        final int entityId;
        final Block targetBlock;
        int ticksLeft;

        BreakTask(int entityId, Block targetBlock, int ticksLeft) {
            this.entityId = entityId;
            this.targetBlock = targetBlock;
            this.ticksLeft = ticksLeft;
        }
    }
}
