// ViridianTownsFolk.java
// Spigot 1.21.10 (single-file plugin)
// Author: ChatGPT

package com.redchanit.viridiantownsfolk;

import org.bukkit.*;
import org.bukkit.Tag;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ViridianTownsFolk
 *
 * Purpose: Populate a predefined set of Dynmap marker sites with Citizens humanoid NPCs that feel "vanilla",
 *          drift around like townsfolk/guards, and speak in varied procedural dialogue.
 *
 * Soft dependencies:
 *  - Citizens (for NPCs)
 *  - dynmap (optional map markers + skin-face icons)
 *
 * Safety:
 *  - No world edits
 *  - No griefing
 *  - Does not delete unrelated Citizens NPCs (only those tagged as managed by THIS plugin).
 */
public class ViridianTownsFolk extends JavaPlugin implements Listener {

    // -----------------------------
    // Config + state
    // -----------------------------
    private File configFile;
    private boolean enabled = true;
    private boolean debugLog = false;

    private boolean integrateCitizens = true;
    private boolean integrateDynmap = true;

    // Consolidated dynmap markers
    private static final String DYNMAP_MARKERSET_ID = "npcs"; // consolidated
    private static final String DYNMAP_MARKERSET_LABEL = "NPCs";

    // NPC marker prefix so we can safely update/cleanup our own markers
    private static final String DYNMAP_MARKER_PREFIX = "vtf_";

    // Managed NPC tags for safe cleanup
    private static final String SCORE_TAG_MANAGED = "vtf_managed";
    private static final String SCORE_TAG_VERSION_PREFIX = "vtf_ver:";

    // When this plugin updates, old managed NPCs are removed
    private static final String PLUGIN_VERSION_TAG = SCORE_TAG_VERSION_PREFIX + "1.3.2";

    // Population behaviour
    private int maintainEverySeconds = 45;
    private int moveEverySeconds = 8;
    private int maxSpawnsPerMaintainTick = 6;
    private int maxMoveTargetsPerTick = 24;

    private int globalMaxNpcs = 120;
    private int minDistanceFromPlayersToSpawn = 10;
    private int spawnAttemptsPerNpc = 24;

    // Per-site defaults
    private int defaultMaxNpcsPerSite = 5;
    private int defaultSpawnRadius = 42;
    private int defaultWanderRadius = 22;

    // Dialogue
    private int conversationTimeoutSeconds = 18;
    private int ambientChatterCooldownSeconds = 30;
    private int ambientChatterChancePercent = 10;

    // NPC identity / metadata
    private final String metaKeyNpc = "vtf_isNpc";
    private final String metaKeySite = "vtf_siteId";
    private final String metaKeyNpcKey = "vtf_npcKey";

    // Registry (entity UUID -> record)
    private final Map<UUID, NpcRecord> npcByEntityId = new ConcurrentHashMap<>();

    // Per-player conversation state
    private final Map<UUID, ConversationState> conversationByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ambientCooldownByPlayer = new ConcurrentHashMap<>();

    // Sites: hardcoded from your dynmap markers paste
    private final List<Site> sites = new ArrayList<>();

    // Random
    private final Random runtimeRandom = new Random();

    // Dynmap bridge (reflection)
    private DynmapBridge dynmapBridge;

    // NPC skin assets folder
    private File npcRootDir;

    // -----------------------------
    // onEnable / onDisable
    // -----------------------------
    @Override
    public void onEnable() {
        configFile = new File(getDataFolder(), "config.yml");
        ensureConfigExists();
        loadConfigValues();

        if (!enabled) {
            getLogger().info("ViridianTownsFolk disabled via config.");
            return;
        }

        npcRootDir = new File(getDataFolder(), "npcs");
        if (!npcRootDir.exists()) npcRootDir.mkdirs();

        buildHardcodedSites();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (integrateCitizens && !isPluginEnabled("Citizens")) {
            getLogger().warning("Citizens not found/enabled. ViridianTownsFolk will not spawn humanoid NPCs.");
            integrateCitizens = false;
        }

        if (integrateDynmap && !isPluginEnabled("dynmap")) {
            if (debugLog) getLogger().info("Dynmap not found/enabled; dynmap integration disabled.");
            integrateDynmap = false;
        }

        if (integrateDynmap) {
            dynmapBridge = new DynmapBridge(this);
            dynmapBridge.init();
        } else {
            dynmapBridge = null;
        }

        // Cleanup: remove managed NPCs from older plugin versions (safe)
        if (integrateCitizens) {
            Bukkit.getScheduler().runTask(this, this::cleanupManagedCitizensNpcsFromOldVersions);
        }

        // Keep populations around sites
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    maintainSitePopulations();
                } catch (Throwable t) {
                    getLogger().warning("Maintain tick failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }.runTaskTimer(this, 40L, 20L * Math.max(10, maintainEverySeconds));

        // NPC movement/behavior tick (set new wander targets)
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    driveNpcMovement();
                } catch (Throwable t) {
                    if (debugLog) getLogger().warning("Movement tick failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }.runTaskTimer(this, 80L, 20L * Math.max(3, moveEverySeconds));

        // Dynmap marker update tick (keeps markers tracking NPC movement)
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    updateDynmapMarkersForTrackedNpcs();
                } catch (Throwable t) {
                    if (debugLog) getLogger().warning("Dynmap update tick failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }.runTaskTimer(this, 120L, 20L * 15L);

        // Registry prune + conversation expiry
        new BukkitRunnable() {
            @Override
            public void run() {
                pruneNpcRegistry();
            }
        }.runTaskTimer(this, 20L * 20L, 20L * 20L);

        getLogger().info("ViridianTownsFolk v1.2 enabled with " + sites.size() + " tailored Sites.");
    }

    @Override
    public void onDisable() {
        npcByEntityId.clear();
        conversationByPlayer.clear();
        ambientCooldownByPlayer.clear();
    }

    // -----------------------------
    // Commands
    // -----------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase(Locale.ROOT);
        if (!cmdName.equals("vtf") && !cmdName.equals("viridiantownsfolk")) return false;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GREEN + "ViridianTownsFolk commands:");
            sender.sendMessage(ChatColor.YELLOW + "/vtf list" + ChatColor.GRAY + " - list sites");
            sender.sendMessage(ChatColor.YELLOW + "/vtf reload" + ChatColor.GRAY + " - reload config");
            sender.sendMessage(ChatColor.YELLOW + "/vtf maintain" + ChatColor.GRAY + " - run population maintain now");
            sender.sendMessage(ChatColor.YELLOW + "/vtf cleanup" + ChatColor.GRAY + " - remove managed NPCs from older versions");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            sender.sendMessage(ChatColor.GREEN + "Sites (" + sites.size() + "):");
            for (Site s : sites) {
                sender.sendMessage(ChatColor.AQUA + s.siteId + ChatColor.GRAY + " -> " + s.label + " [" + s.worldName + " " + s.x + "," + s.y + "," + s.z + "] type=" + s.siteType + " max=" + s.maxNpcs);
            }
            return true;
        }

        if (sub.equals("reload")) {
            loadConfigValues();
            if (integrateDynmap && dynmapBridge != null) dynmapBridge.init();
            sender.sendMessage(ChatColor.GREEN + "ViridianTownsFolk config reloaded.");
            return true;
        }

        if (sub.equals("maintain")) {
            Bukkit.getScheduler().runTask(this, this::maintainSitePopulations);
            sender.sendMessage(ChatColor.GREEN + "Maintain tick executed.");
            return true;
        }

        if (sub.equals("cleanup")) {
            Bukkit.getScheduler().runTask(this, this::cleanupManagedCitizensNpcsFromOldVersions);
            sender.sendMessage(ChatColor.GREEN + "Cleanup executed.");
            return true;
        }

        if (sub.equals("purgeall")) {
            boolean unsafe = args.length >= 2 && args[1].equalsIgnoreCase("unsafe");
            Bukkit.getScheduler().runTask(this, () -> {
                int removed = purgeAllViridianTownsFolkCitizensNpcs(unsafe);
                getLogger().info("Purged " + removed + " Citizens NPC(s) (" + (unsafe ? "unsafe" : "safe") + ").");
            });
            sender.sendMessage(ChatColor.GREEN + "Purge scheduled (" + (unsafe ? "unsafe" : "safe") + ").");
            return true;
        }

        if (sub.equals("reset")) {
            boolean unsafe = args.length >= 2 && args[1].equalsIgnoreCase("unsafe");
            Bukkit.getScheduler().runTask(this, () -> {
                int removed = purgeAllViridianTownsFolkCitizensNpcs(unsafe);
                // repopulate quickly
                for (int i = 0; i < 3; i++) maintainSitePopulations();
                getLogger().info("Reset complete. Purged " + removed + " and repopulated.");
            });
            sender.sendMessage(ChatColor.GREEN + "Reset scheduled (" + (unsafe ? "unsafe" : "safe") + ").");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /vtf");
        return true;
    }

    // -----------------------------
    // Events: interactions + ambient liveliness
    // -----------------------------
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
        if (!enabled) return;

        // Fix: ignore off-hand
        if (e.getHand() != EquipmentSlot.HAND) return;

        Entity ent = e.getRightClicked();
        if (ent == null) return;

        NpcRecord rec = npcByEntityId.get(ent.getUniqueId());
        if (rec == null) return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        if (p == null) return;

        Site site = findSite(rec.siteId);
        if (site == null) site = rec.fallbackSite;

        startOrContinueConversation(p, rec, site);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p == null) return;

        long now = System.currentTimeMillis();
        long nextAllowed = ambientCooldownByPlayer.getOrDefault(p.getUniqueId(), 0L);
        if (now < nextAllowed) return;

        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;

        Site near = findNearestSite(to, 48);
        if (near == null) return;

        if (!near.isChatterHeavy()) return;
        if (runtimeRandom.nextInt(100) >= ambientChatterChancePercent) return;

        String line = pickAmbientLine(near);
        if (line == null || line.isEmpty()) return;

        // Vanilla-ish: subtle gray, no prefix
        p.sendMessage(ChatColor.GRAY + line);
        ambientCooldownByPlayer.put(p.getUniqueId(), now + (ambientChatterCooldownSeconds * 1000L));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncPlayerChatEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p == null) return;

        ConversationState st = conversationByPlayer.get(p.getUniqueId());
        if (st == null) return;

        String msg = e.getMessage();
        if (msg == null) return;
        String m = msg.trim().toLowerCase(Locale.ROOT);

        if (!(m.equals("yes") || m.equals("y") || m.equals("no") || m.equals("n") || m.equals("ok") || m.equals("okay"))) {
            return; // normal chat
        }

        e.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> handleConversationReply(p, st, m));
    }

    // -----------------------------
    // Conversation logic (procedural variety)
    // -----------------------------
    private void startOrContinueConversation(Player p, NpcRecord rec, Site site) {
        if (p == null) return;

        long now = System.currentTimeMillis();
        ConversationState existing = conversationByPlayer.get(p.getUniqueId());
        if (existing != null && now <= existing.expiresAtMs) {
            // already talking: just re-prompt
            p.sendMessage(ChatColor.GRAY + existing.lastPrompt);
            existing.expiresAtMs = now + conversationTimeoutSeconds * 1000L;
            return;
        }

        ConversationState st = new ConversationState(rec, site, now + conversationTimeoutSeconds * 1000L, 0);
        conversationByPlayer.put(p.getUniqueId(), st);

        // Opening line (short, vanilla-ish)
        String opener = pickOpenerLine(rec, site, p);
        if (opener != null && !opener.isEmpty()) {
            p.sendMessage(ChatColor.GRAY + opener);
        }

        // Prompt
        String prompt = pickPrompt(rec, site);
        st.lastPrompt = prompt;
        p.sendMessage(ChatColor.GRAY + prompt);

        // Timeout cleaner
        Bukkit.getScheduler().runTaskLater(this, () -> {
            ConversationState cur = conversationByPlayer.get(p.getUniqueId());
            if (cur == null) return;
            if (System.currentTimeMillis() > cur.expiresAtMs) {
                conversationByPlayer.remove(p.getUniqueId());
            }
        }, 20L * (conversationTimeoutSeconds + 1));
    }

    private void handleConversationReply(Player p, ConversationState st, String reply) {
        if (p == null || st == null) return;

        long now = System.currentTimeMillis();
        if (now > st.expiresAtMs) {
            conversationByPlayer.remove(p.getUniqueId());
            return;
        }

        boolean yes = reply.equals("yes") || reply.equals("y") || reply.equals("ok") || reply.equals("okay");

        // Extend timer each reply
        st.expiresAtMs = now + conversationTimeoutSeconds * 1000L;

        List<String> lines = new ArrayList<>();
        Site site = st.site;
        NpcRecord rec = st.npc;

        // Stage flow:
        // 0: reveal demeanor line -> ask a follow-up
        // 1: either rumor or warning -> ask if want directions
        // 2: give hint or dismiss -> end
        // Variation heavily based on role, temperament, site type, time, weather
        if (st.stage == 0) {
            lines.add(pickResponseLine(rec, site, yes, p));
            lines.add(pickFollowupPrompt(rec, site, yes));
        } else if (st.stage == 1) {
            if (yes) {
                lines.add(pickRumorLine(rec, site));
            } else {
                lines.add(pickWarningLine(rec, site));
            }
            lines.add(pickDirectionPrompt(rec, site));
        } else {
            if (yes) {
                Site hint = pickNearbyInterestingSite(site);
                if (hint != null) {
                    lines.add(pickDirectionLine(rec, site, hint));
                } else {
                    lines.add("No. Not today.");
                }
            } else {
                lines.add(pickDismissLine(rec, site));
            }
            conversationByPlayer.remove(p.getUniqueId());
        }

        st.stage++;

        for (String line : lines) {
            if (line != null && !line.isEmpty()) p.sendMessage(ChatColor.GRAY + line);
        }

        // Update last prompt if last line is a prompt
        if (!conversationByPlayer.containsKey(p.getUniqueId())) return;
        ConversationState cur = conversationByPlayer.get(p.getUniqueId());
        if (cur != null && !lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            cur.lastPrompt = last;
        }
    }

    private String pickOpenerLine(NpcRecord rec, Site site, Player p) {
        String time = timeOfDayBucket(p.getWorld());
        boolean raining = p.getWorld().hasStorm();
        List<String> pool = new ArrayList<>();

        pool.add(rec.displayName + " looks your way.");
        pool.add(rec.displayName + " pauses, as if listening.");
        pool.add(rec.displayName + " seems about to speak, then thinks better of it.");
        if ("night".equals(time)) pool.add(rec.displayName + " keeps their voice low at night.");
        if (raining) pool.add(rec.displayName + " watches the rain like it means something.");

        // Temperament flavor
        switch (rec.temperament) {
            case "wary" -> {
                pool.add(rec.displayName + " watches your hands.");
                pool.add(rec.displayName + " steps back half a pace.");
            }
            case "friendly" -> {
                pool.add(rec.displayName + " gives a small nod.");
                pool.add(rec.displayName + " seems relieved to see someone.");
            }
            case "blunt" -> {
                pool.add(rec.displayName + " sizes you up.");
            }
            case "cryptic" -> {
                pool.add(rec.displayName + " murmurs something you almost understand.");
            }
        }

        // Site flavor
        if ("RUINS".equals(site.siteType)) pool.add("The air tastes old here.");
        if ("CASTLE".equals(site.siteType)) pool.add("Somewhere behind the walls, a chain shifts.");

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickPrompt(NpcRecord rec, Site site) {
        List<String> pool = new ArrayList<>();

        // Role driven prompts
        switch (rec.role) {
            case "Guard" -> {
                pool.add("Passing through? (yes/no)");
                pool.add("Need directions? (yes/no)");
                pool.add("State your business. (yes/no)");
            }
            case "Priest" -> {
                pool.add("Do you seek counsel? (yes/no)");
                pool.add("Will you keep a vow? (yes/no)");
                pool.add("Do you want the safe answer? (yes/no)");
            }
            case "Merchant" -> {
                pool.add("Trade? (yes/no)");
                pool.add("Looking for work? (yes/no)");
                pool.add("Do you want to hear a rumor? (yes/no)");
            }
            case "Ranger" -> {
                pool.add("Did you come alone? (yes/no)");
                pool.add("Want a warning about the roads? (yes/no)");
                pool.add("Do you know how to walk quietly? (yes/no)");
            }
            default -> {
                pool.add("Need something? (yes/no)");
                pool.add("Can I help you? (yes/no)");
                pool.add("Do you want to hear something strange? (yes/no)");
            }
        }

        // Site nudges
        if ("BRIDGE".equals(site.siteType)) pool.add("Do you trust bridges? (yes/no)");
        if ("DOCK".equals(site.siteType)) pool.add("Smell the salt? (yes/no)");
        if ("RUINS".equals(site.siteType)) pool.add("You shouldn't linger here. Still... (yes/no)");

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickResponseLine(NpcRecord rec, Site site, boolean yes, Player p) {
        List<String> pool = new ArrayList<>();

        if (yes) {
            pool.add("Alright.");
            pool.add("Good.");
            pool.add("Then listen.");
            pool.add("Fine. Keep your voice down.");
        } else {
            pool.add("Suit yourself.");
            pool.add("Then keep walking.");
            pool.add("Fair.");
            pool.add("Probably for the best.");
        }

        // Add a content line that feels lore-ish
        if (yes) {
            pool.addAll(loreDripLines(rec, site, p));
        } else {
            pool.addAll(safeDripLines(rec, site, p));
        }

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private List<String> loreDripLines(NpcRecord rec, Site site, Player p) {
        String pl = prettyLabel(site.label);
        List<String> l = new ArrayList<>();

        l.add("Count the lights in " + pl + ". If one goes out, don't look at the shadow it leaves.");
        l.add("Some roads don't show on maps. They show on scars.");
        l.add("If you find a note with a circle where the name should be, keep it.");
        l.add("If you hear footsteps match yours exactly, stop. Let them pass.");
        l.add("People swear they saw someone building at night. No one admits it in daylight.");
        l.add("There are pages missing from the book of this land. Someone tore them out.");

        if ("RUINS".equals(site.siteType)) {
            l.add("Ruins don't stay empty. They just get quieter.");
            l.add("The stones remember hands that never existed.");
        }
        if ("CASTLE".equals(site.siteType)) {
            l.add("A wall can be honest. A gate never is.");
        }
        if ("DOCK".equals(site.siteType)) {
            l.add("Salt keeps more than fish. Some names won't drown.");
        }
        if (p.getWorld().hasStorm()) {
            l.add("Storms make it easier for things to move without being seen.");
        }

        return l;
    }

    private List<String> safeDripLines(NpcRecord rec, Site site, Player p) {
        List<String> l = new ArrayList<>();
        l.add("Don't dig where the ground looks too neat.");
        l.add("If you find a sign with one blank line, leave it blank.");
        l.add("Stay near torches. Not because of monsters.");
        l.add("If you see a door in the open, close it. If it's locked, don't touch it.");
        l.add("Travel by daylight. Even if you don't believe in stories.");

        if ("OUTPOST".equals(site.siteType)) {
            l.add("Frontier rule: if you hear something behind you, don't run. Walk.");
        }
        if ("FOREST".equals(site.siteType)) {
            l.add("The forest trades in echoes. Speak softly.");
        }
        if ("BRIDGE".equals(site.siteType)) {
            l.add("Bridges connect promises more than places.");
        }
        return l;
    }

    private String pickFollowupPrompt(NpcRecord rec, Site site, boolean yes) {
        List<String> pool = new ArrayList<>();
        if (yes) {
            pool.add("Will you keep it to yourself? (yes/no)");
            pool.add("Can you remember something exactly? (yes/no)");
            pool.add("Will you carry a message if you find it? (yes/no)");
        } else {
            pool.add("Do you want the safer warning instead? (yes/no)");
            pool.add("Then at least promise one thing. (yes/no)");
            pool.add("Still... will you listen to one rule? (yes/no)");
        }
        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickRumorLine(NpcRecord rec, Site site) {
        List<String> pool = new ArrayList<>();
        pool.add("They say someone keeps rebuilding the same house, over and over, in different places.");
        pool.add("Someone heard singing under stone. No one went down twice.");
        pool.add("A traveler swore the river ran backward for one night.");
        pool.add("A path appeared after sunset and vanished at dawn. It led somewhere that felt familiar.");
        pool.add("A name is being scratched into signs. Always the same. Always half-finished.");

        if ("CAPITAL".equals(site.siteType)) {
            pool.add("The Register has entries for people who never logged in.");
            pool.add("A clerk writes with ink that dries like ash.");
        }
        if ("DOCK".equals(site.siteType)) {
            pool.add("A boat arrived with no crew. Just a lantern still burning.");
        }
        if ("CASTLE".equals(site.siteType)) {
            pool.add("They found footprints on the battlements. One set ended mid-step.");
        }
        if ("RUINS".equals(site.siteType)) {
            pool.add("A torch lit itself in the ruins. It burned cold.");
        }

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickWarningLine(NpcRecord rec, Site site) {
        List<String> pool = new ArrayList<>();
        pool.add("Don't take from places that look abandoned.");
        pool.add("Don't sleep next to unfinished walls.");
        pool.add("Don't follow a trail that looks too perfect.");
        pool.add("If you see a sign you didn't place, read it once and move on.");
        pool.add("If the animals go quiet, turn back.");

        if ("RUINS".equals(site.siteType)) {
            pool.add("The ruins don't like being watched.");
        }
        if ("FOREST".equals(site.siteType)) {
            pool.add("If a tree looks like it's leaning toward you, walk the other way.");
        }

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickDirectionPrompt(NpcRecord rec, Site site) {
        List<String> pool = new ArrayList<>();
        pool.add("Want a place to go? (yes/no)");
        pool.add("Need a direction? (yes/no)");
        pool.add("Do you want to see something strange? (yes/no)");
        pool.add("Do you want the shortest path? (yes/no)");
        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickDirectionLine(NpcRecord rec, Site from, Site to) {
        List<String> pool = new ArrayList<>();
        String a = prettyLabel(to.label);

        pool.add("Try " + a + ". Don't arrive by the straightest path.");
        pool.add("Go to " + a + ". If you hear steps behind you, stop walking.");
        pool.add("Find " + a + ". Look for a sign with a blank line nearby.");
        pool.add("Head toward " + a + ". If you feel watched, you're on the right road.");
        pool.add(a + ". Daylight is safer, but night is... clearer.");

        if ("RUINS".equals(to.siteType)) pool.add(a + ". Don't take anything from there.");
        if ("CASTLE".equals(to.siteType)) pool.add(a + ". Mind the gates more than the walls.");
        if ("BRIDGE".equals(to.siteType)) pool.add(a + ". Bridges remember who crosses.");

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String pickDismissLine(NpcRecord rec, Site site) {
        List<String> pool = new ArrayList<>();
        pool.add("Then go.");
        pool.add("Fine. Stay safe.");
        pool.add("Don't make it my problem.");
        pool.add("Keep your head down.");
        pool.add("Good luck.");
        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private String timeOfDayBucket(World w) {
        if (w == null) return "day";
        long t = w.getTime() % 24000;
        if (t >= 13000 && t <= 23000) return "night";
        if (t >= 0 && t < 2000) return "dawn";
        if (t >= 12000 && t < 13000) return "dusk";
        return "day";
    }

    // -----------------------------
    // Population maintenance
    // -----------------------------
    private void maintainSitePopulations() {
        if (!enabled) return;
        if (!integrateCitizens) return;

        int existing = countAllTrackedNpcs();
        if (existing >= globalMaxNpcs) {
            if (debugLog) getLogger().info("Global NPC cap reached (" + existing + "/" + globalMaxNpcs + ")");
            return;
        }

        int spawnedThisTick = 0;

        List<Site> shuffled = new ArrayList<>(sites);
        Collections.shuffle(shuffled, runtimeRandom);

        for (Site s : shuffled) {
            if (!s.enabled) continue;

            World w = Bukkit.getWorld(s.worldName);
            if (w == null) continue;

            int aliveHere = countAliveTrackedNpcsForSite(s.siteId);
            int target = Math.max(0, Math.min(s.maxNpcs, defaultMaxNpcsPerSite));

            if (aliveHere >= target) continue;
            if (spawnedThisTick >= maxSpawnsPerMaintainTick) break;
            if (countAllTrackedNpcs() >= globalMaxNpcs) break;

            boolean ok = spawnOneNpcNearSite(s);
            if (ok) spawnedThisTick++;
        }

        if (debugLog && spawnedThisTick > 0) getLogger().info("Maintain tick spawned " + spawnedThisTick + " NPC(s).");
    }

    private int countAllTrackedNpcs() {
        return npcByEntityId.size();
    }

    private int countAliveTrackedNpcsForSite(String siteId) {
        int count = 0;
        for (NpcRecord rec : npcByEntityId.values()) {
            if (!siteId.equalsIgnoreCase(rec.siteId)) continue;
            count++;
        }
        return count;
    }

    private boolean spawnOneNpcNearSite(Site s) {
        World w = Bukkit.getWorld(s.worldName);
        if (w == null) return false;

        for (int attempt = 0; attempt < spawnAttemptsPerNpc; attempt++) {
            int dx = runtimeRandom.nextInt(s.spawnRadius * 2 + 1) - s.spawnRadius;
            int dz = runtimeRandom.nextInt(s.spawnRadius * 2 + 1) - s.spawnRadius;

            int baseX = s.x + dx;
            int baseZ = s.z + dz;

            int topY = w.getHighestBlockYAt(baseX, baseZ);
            Location spawnLoc = new Location(w, baseX + 0.5, topY + 1, baseZ + 0.5);

            if (!isSafeSpawnLocation(spawnLoc)) continue;
            if (isTooCloseToAnyPlayer(spawnLoc, minDistanceFromPlayersToSpawn)) continue;

            NpcSpec spec = pickNpcSpecForSite(s);
            NpcSpawnResult res = spawnCitizensNpc(spawnLoc, s, spec);
            if (res != null && res.entity != null) {
                Entity ent = res.entity;

                String npcKey = buildNpcKey(s, ent.getUniqueId());

                // Tag the entity so we can safely manage/cleanup
                try {
                    ent.addScoreboardTag(SCORE_TAG_MANAGED);
                    ent.addScoreboardTag(PLUGIN_VERSION_TAG);
                } catch (Throwable ignored) {}

                ent.setMetadata(metaKeyNpc, new FixedMetadataValue(this, true));
                ent.setMetadata(metaKeySite, new FixedMetadataValue(this, s.siteId));
                ent.setMetadata(metaKeyNpcKey, new FixedMetadataValue(this, npcKey));

                NpcRecord rec = new NpcRecord(
                        s.siteId,
                        s.worldName,
                        s,
                        spec.displayName,
                        spec.skinName,
                        spec.role,
                        spec.temperament,
                        npcKey,
                        System.currentTimeMillis()
                );
                npcByEntityId.put(ent.getUniqueId(), rec);

                // Persist skin info + download skin PNG (async)
                persistNpcSkinAssets(rec);

                // Dynmap marker upsert
                if (integrateDynmap && dynmapBridge != null) {
                    dynmapBridge.upsertNpc(ent, rec);
                }

                if (debugLog) getLogger().info("Spawned NPC '" + spec.displayName + "' (" + spec.skinName + ") at " + s.siteId);
                return true;
            }
        }

        return false;
    }

    private String buildNpcKey(Site s, UUID entityId) {
        // stable-ish key for file naming; human readable by site + short uuid
        String shortId = entityId.toString().split("-")[0];
        return s.siteId + "_" + shortId;
    }

    private void persistNpcSkinAssets(NpcRecord rec) {
        if (rec == null) return;

        File npcDir = new File(npcRootDir, rec.npcKey);
        if (!npcDir.exists()) npcDir.mkdirs();

        File meta = new File(npcDir, "meta.txt");
        if (!meta.exists()) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(meta), StandardCharsets.UTF_8)) {
                w.write("name=" + rec.displayName + "\n");
                w.write("role=" + rec.role + "\n");
                w.write("temperament=" + rec.temperament + "\n");
                w.write("siteId=" + rec.siteId + "\n");
                w.write("skinName=" + rec.skinName + "\n");

                UUID uuid = safeOfflineUuid(rec.skinName);
                if (uuid != null) w.write("uuid=" + uuid + "\n");
            } catch (Throwable ignored) {}
        }

        // Download skin PNG if missing (async)
        File skinPng = new File(npcDir, "skin.png");
        if (skinPng.exists()) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                byte[] png = httpGetBytes("https://minotar.net/skin/" + urlEncode(rec.skinName), 5000, 8000);
                if (png == null || png.length == 0) return;

                try (FileOutputStream fos = new FileOutputStream(skinPng)) {
                    fos.write(png);
                }
            } catch (Throwable ignored) {}
        });
    }

    private UUID safeOfflineUuid(String name) {
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayer(name);
            return off != null ? off.getUniqueId() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isSafeSpawnLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // Need headroom
        Material at = w.getBlockAt(x, y, z).getType();
        Material head = w.getBlockAt(x, y + 1, z).getType();
        if (!at.isAir() || !head.isAir()) return false;

        // Must stand on solid
        Material below = w.getBlockAt(x, y - 1, z).getType();
        if (!below.isSolid()) return false;

        // Avoid unsafe bases
        if (below == Material.MAGMA_BLOCK || below == Material.CACTUS || below == Material.LAVA) return false;

        // Avoid roofs/trees: no leaves or stairs
        if (Tag.LEAVES.isTagged(below)) return false;
        if (Tag.STAIRS.isTagged(below)) return false;

        // Also avoid slab rooftops (common)
        String belowName = below.name();
        if (belowName.endsWith("_SLAB")) return false;
        if (belowName.contains("LEAVES")) return false;

        // Avoid water surfaces
        if (below == Material.WATER) return false;

        return true;
    }

    private boolean isTooCloseToAnyPlayer(Location loc, int minDistBlocks) {
        if (loc == null || loc.getWorld() == null) return true;
        double minSq = (double) minDistBlocks * (double) minDistBlocks;

        for (Player p : loc.getWorld().getPlayers()) {
            if (p == null || !p.isOnline()) continue;
            if (p.getLocation().distanceSquared(loc) < minSq) return true;
        }

        return false;
    }

    // -----------------------------
    // Movement (make NPCs feel alive)
    // -----------------------------
    private void driveNpcMovement() {
        if (!integrateCitizens) return;
        if (npcByEntityId.isEmpty()) return;

        // Pick a subset to retarget each tick
        List<Map.Entry<UUID, NpcRecord>> list = new ArrayList<>(npcByEntityId.entrySet());
        Collections.shuffle(list, runtimeRandom);

        int moved = 0;
        for (Map.Entry<UUID, NpcRecord> en : list) {
            if (moved >= maxMoveTargetsPerTick) break;

            Entity ent = findEntityByUuid(en.getKey());
            if (ent == null || !ent.isValid()) continue;

            NpcRecord rec = en.getValue();
            Site site = findSite(rec.siteId);
            if (site == null) site = rec.fallbackSite;
            if (site == null) continue;

            // Don't constantly retarget; cool down per NPC
            long now = System.currentTimeMillis();
            if (now < rec.nextMoveAtMs) continue;

            // Guards patrol less randomly; townsfolk wander more
            int wanderRadius = site.wanderRadius > 0 ? site.wanderRadius : defaultWanderRadius;
            if ("Guard".equals(rec.role)) wanderRadius = Math.max(10, wanderRadius - 6);

            Location target = pickWanderTarget(ent.getLocation(), site, wanderRadius);
            if (target == null) continue;

            if (setCitizensNpcTarget(ent, target)) {
                moved++;
                rec.nextMoveAtMs = now + (6000L + runtimeRandom.nextInt(12000)); // 6-18 seconds
            }
        }

        if (debugLog && moved > 0) getLogger().info("Movement tick retargeted " + moved + " NPC(s).");
    }

    private Location pickWanderTarget(Location from, Site site, int radius) {
        if (from == null || from.getWorld() == null) return null;

        World w = from.getWorld();

        for (int i = 0; i < 20; i++) {
            int dx = runtimeRandom.nextInt(radius * 2 + 1) - radius;
            int dz = runtimeRandom.nextInt(radius * 2 + 1) - radius;

            int x = site.x + dx;
            int z = site.z + dz;
            int y = w.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            if (!isSafeSpawnLocation(loc)) continue; // same checks for walking target
            if (isTooCloseToAnyPlayer(loc, 2)) continue; // okay to walk near players, but not inside

            return loc;
        }

        return null;
    }

    private boolean setCitizensNpcTarget(Entity npcEntity, Location target) {
        try {
            Object citizensNpc = getCitizensNpcFromEntity(npcEntity);
            if (citizensNpc == null) return false;

            // NPC.getNavigator().setTarget(Location)
            Object navigator = invokeNoArg(citizensNpc, "getNavigator");
            if (navigator == null) return false;

            // Some Citizens versions use setTarget(Location), others setTarget(Entity/Location, boolean).
            // Try the common one first.
            if (invokeWithSingleArgIfExists(navigator, "setTarget", target)) return true;

            // Fallback: try setTarget(Location, boolean)
            Method m = findMethodByNameAndParamCount(navigator.getClass(), "setTarget", 2);
            if (m != null) {
                m.invoke(navigator, target, false);
                return true;
            }
        } catch (Throwable t) {
            if (debugLog) getLogger().info("Navigator target set failed: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private Object getCitizensNpcFromEntity(Entity ent) {
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
            Method getNpc = findMethodByNameAndParamCount(registry.getClass(), "getNPC", 1);
            if (getNpc != null) {
                return getNpc.invoke(registry, ent);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // -----------------------------
    // Citizens spawn + skin (less random; site rosters)
    // -----------------------------
    private NpcSpec pickNpcSpecForSite(Site s) {
        // Use a deterministic-ish roster per site, but still varied enough.
        // Also allow per-NPC overrides via npcs/<npcKey>/override_skin.txt if present (applies after spawn).
        List<String> skins = s.rosterSkins;
        List<String> roles = s.rosterRoles;
        List<String> temps = Arrays.asList("friendly", "wary", "blunt", "cryptic");

        String role = roles.get(runtimeRandom.nextInt(roles.size()));
        String temperament = temps.get(runtimeRandom.nextInt(temps.size()));

        // Skin: not too varied; pick from site roster
        String skin = skins.get(runtimeRandom.nextInt(skins.size()));

        // Display name: simple vanilla-ish
        String baseName = pickNameForRole(role);
        String display = baseName;

        // Occasionally add "of <place>" for flavor, but not always
        if (runtimeRandom.nextInt(100) < 35 && s.isSettlement()) {
            display = baseName + " of " + prettyLabel(s.label);
        }

        return new NpcSpec(display, skin, role, temperament);
    }

    private String pickNameForRole(String role) {
        List<String> pool = new ArrayList<>();
        switch (role) {
            case "Guard" -> pool.addAll(Arrays.asList("Sentinel", "Gatewatch", "Warden", "Shieldbearer", "Watchman"));
            case "Priest" -> pool.addAll(Arrays.asList("Priest", "Cleric", "Acolyte", "Candlekeeper", "Confessor"));
            case "Merchant" -> pool.addAll(Arrays.asList("Trader", "Merchant", "Baker", "Smith", "Peddler"));
            case "Ranger" -> pool.addAll(Arrays.asList("Ranger", "Scout", "Lookout", "Pathfinder", "Courier"));
            default -> pool.addAll(Arrays.asList("Townsfolk", "Worker", "Traveler", "Citizen", "Stranger"));
        }
        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    private NpcSpawnResult spawnCitizensNpc(Location loc, Site site, NpcSpec spec) {
        if (loc == null || loc.getWorld() == null) return null;

        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);

            // registry.createNPC(EntityType.PLAYER, name)
            Method createNpc = findMethodByNameAndParamCount(registry.getClass(), "createNPC", 2);
            if (createNpc == null) return null;

            Object npc = createNpc.invoke(registry, org.bukkit.entity.EntityType.PLAYER, spec.displayName);

            // npc.spawn(Location)
            Method spawn = findMethodByNameAndParamCount(npc.getClass(), "spawn", 1);
            if (spawn != null) spawn.invoke(npc, loc);

            // npc.getEntity()
            Object entObj = invokeNoArg(npc, "getEntity");
            if (!(entObj instanceof Entity)) return null;
            Entity ent = (Entity) entObj;

            // Safe baseline
            try { ent.setInvulnerable(true); } catch (Throwable ignored) {}
            try { ent.setSilent(runtimeRandom.nextInt(100) < 30); } catch (Throwable ignored) {}

            // Skin via SkinTrait best effort
            applyCitizensSkinTrait(npc, spec.skinName);

            // LookClose
            tryApplyLookCloseTrait(npc);

            // Give Citizens a nudge to allow movement: avoid sitting/frozen traits if any exist
            tryDisableIfTraitExists(npc, "net.citizensnpcs.trait.SitTrait");
            tryDisableIfTraitExists(npc, "net.citizensnpcs.trait.Gravity");
            tryDisableIfTraitExists(npc, "net.citizensnpcs.trait.SneakTrait");

            return new NpcSpawnResult(npc, ent);
        } catch (Throwable t) {
            if (debugLog) getLogger().warning("Citizens spawn failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private void applyCitizensSkinTrait(Object npc, String skinName) {
        if (npc == null || skinName == null || skinName.isEmpty()) return;

        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skinTrait = invokeWithSingleArg(npc, "getTrait", skinTraitClass);
            if (skinTrait == null) return;

            invokeWithSingleArg(skinTrait, "setSkinName", skinName);

            // optional fetch
            Method setFetch = findMethodByNameAndParamCount(skinTraitClass, "setFetchSkin", 1);
            if (setFetch != null) setFetch.invoke(skinTrait, true);
        } catch (Throwable t) {
            if (debugLog) getLogger().info("SkinTrait not applied (" + skinName + "): " + t.getClass().getSimpleName());
        }
    }

    private void tryApplyLookCloseTrait(Object npc) {
        try {
            Class<?> lookCloseClass = Class.forName("net.citizensnpcs.trait.LookClose");
            Object trait = invokeWithSingleArg(npc, "getTrait", lookCloseClass);
            if (trait == null) return;

            invokeWithSingleArgIfExists(trait, "setRealisticLooking", true);
            invokeWithSingleArgIfExists(trait, "setRange", 12);
            invokeWithSingleArgIfExists(trait, "setEnabled", true);
        } catch (Throwable ignored) {}
    }

    private void tryDisableIfTraitExists(Object npc, String traitClassName) {
        try {
            Class<?> traitClz = Class.forName(traitClassName);
            Object trait = invokeWithSingleArg(npc, "getTrait", traitClz);
            if (trait == null) return;
            invokeWithSingleArgIfExists(trait, "setEnabled", false);
        } catch (Throwable ignored) {}
    }

    // -----------------------------
    // Dynmap updates
    // -----------------------------
    private void updateDynmapMarkersForTrackedNpcs() {
        if (!integrateDynmap || dynmapBridge == null) return;

        int updated = 0;
        for (Map.Entry<UUID, NpcRecord> en : npcByEntityId.entrySet()) {
            Entity ent = findEntityByUuid(en.getKey());
            if (ent == null || !ent.isValid()) continue;
            dynmapBridge.upsertNpc(ent, en.getValue());
            updated++;
            if (updated >= 60) break; // avoid huge spikes
        }
    }

    // -----------------------------
    // Cleanup / prune
    // -----------------------------

private int countManagedNpcsInWorld(String worldName) {
    if (worldName == null) return 0;
    int count = 0;
    for (NpcRecord rec : npcByEntityId.values()) {
        if (rec == null) continue;
        if (!worldName.equalsIgnoreCase(rec.worldName)) continue;
        count++;
    }
    return count;
}

private int countManagedNpcsWithinRadius(Location center, int radiusBlocks) {
    if (center == null || center.getWorld() == null) return 0;
    double r2 = (double) radiusBlocks * (double) radiusBlocks;
    int count = 0;
    for (Map.Entry<UUID, NpcRecord> en : npcByEntityId.entrySet()) {
        Entity ent = findEntityByUuid(en.getKey());
        if (ent == null || !ent.isValid()) continue;
        if (ent.getWorld() == null || !ent.getWorld().equals(center.getWorld())) continue;
        if (ent.getLocation().distanceSquared(center) <= r2) count++;
    }
    return count;
}

    private void pruneNpcRegistry() {
        Iterator<Map.Entry<UUID, NpcRecord>> it = npcByEntityId.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, NpcRecord> en = it.next();
            UUID id = en.getKey();
            Entity found = findEntityByUuid(id);
            if (found == null || !found.isValid()) {
                it.remove();
            }
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ConversationState>> it2 = conversationByPlayer.entrySet().iterator();
        while (it2.hasNext()) {
            Map.Entry<UUID, ConversationState> en = it2.next();
            if (now > en.getValue().expiresAtMs) it2.remove();
        }
    }

    private void cleanupManagedCitizensNpcsFromOldVersions() {
        if (!integrateCitizens) return;

        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);

            // registry.iterator() or getNPCs()
            Collection<?> npcs = null;

            Method getNpcs = findMethodByNameAndParamCount(registry.getClass(), "sorted", 0);
            if (getNpcs != null) {
                // some versions have sorted() returning list
                Object o = getNpcs.invoke(registry);
                if (o instanceof Collection) npcs = (Collection<?>) o;
            }
            if (npcs == null) {
                Method all = findMethodByNameAndParamCount(registry.getClass(), "getNPCs", 0);
                if (all != null) {
                    Object o = all.invoke(registry);
                    if (o instanceof Collection) npcs = (Collection<?>) o;
                }
            }
            if (npcs == null) return;

            int removed = 0;
            for (Object npc : npcs) {
                Object entObj = invokeNoArg(npc, "getEntity");
                if (!(entObj instanceof Entity)) continue;
                Entity ent = (Entity) entObj;

                Set<String> tags = ent.getScoreboardTags();
                if (!tags.contains(SCORE_TAG_MANAGED)) continue;

                // If not this version, destroy it
                if (!tags.contains(PLUGIN_VERSION_TAG)) {
                    // npc.destroy()
                    invokeNoArg(npc, "destroy");

                    // registry.deregister(npc)
                    Method dereg = findMethodByNameAndParamCount(registry.getClass(), "deregister", 1);
                    if (dereg != null) dereg.invoke(registry, npc);

                    removed++;
                }
            }

            if (removed > 0) getLogger().info("Removed " + removed + " managed Citizens NPC(s) from older ViridianTownsFolk versions.");
        } catch (Throwable t) {
            if (debugLog) getLogger().warning("Cleanup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

/**
 * Purge ALL Citizens NPCs created/managed by ViridianTownsFolk.
 * Safe mode removes only NPCs with scoreboard tags indicating VTF ownership.
 * Unsafe mode also removes legacy nearby Citizens NPCs around known sites (for cleaning up old versions that didn't tag properly).
 */
int purgeAllViridianTownsFolkCitizensNpcs(boolean unsafe) {
    if (!integrateCitizens) return 0;

    int removed = 0;
    try {
        Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
        Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);

        Collection<?> npcs = null;
        Method sorted = findMethodByNameAndParamCount(registry.getClass(), "sorted", 0);
        if (sorted != null) {
            Object o = sorted.invoke(registry);
            if (o instanceof Collection) npcs = (Collection<?>) o;
        }
        if (npcs == null) {
            Method all = findMethodByNameAndParamCount(registry.getClass(), "getNPCs", 0);
            if (all != null) {
                Object o = all.invoke(registry);
                if (o instanceof Collection) npcs = (Collection<?>) o;
            }
        }
        if (npcs == null) return 0;

        for (Object npc : npcs) {
            Object entObj = invokeNoArg(npc, "getEntity");
            if (!(entObj instanceof Entity)) continue;
            Entity ent = (Entity) entObj;

            boolean shouldRemove = false;
            Set<String> tags = ent.getScoreboardTags();
            if (tags.contains(SCORE_TAG_MANAGED)) shouldRemove = true;
            if (!shouldRemove) {
                for (String t : tags) {
                    if (t != null && t.startsWith(SCORE_TAG_VERSION_PREFIX)) {
                        shouldRemove = true;
                        break;
                    }
                }
            }

            if (!shouldRemove && unsafe) {
                // Legacy cleanup heuristic: only remove Citizens NPCs that are close to OUR known sites
                // (and ONLY in worlds we manage), to avoid touching unrelated Citizens NPCs elsewhere.
                Location l = ent.getLocation();
                if (l != null && l.getWorld() != null) {
                    Site near = findNearestSite(l, 160);
                    if (near != null) {
                        // Another light heuristic: name references a site label OR matches our common generated patterns.
                        String name = safeNpcName(npc, ent).toLowerCase(Locale.ROOT);
                        String label = prettyLabel(near.label).toLowerCase(Locale.ROOT);

                        if (name.contains(" of ") || (!label.isEmpty() && name.contains(label))) {
                            shouldRemove = true;
                        }
                    }
                }
            }

            if (!shouldRemove) continue;

            // destroy + deregister
            invokeNoArg(npc, "destroy");
            Method dereg = findMethodByNameAndParamCount(registry.getClass(), "deregister", 1);
            if (dereg != null) dereg.invoke(registry, npc);

            removed++;
        }

        // Also clear dynmap markers created by this plugin (if dynmap is available)
        if (dynmapBridge != null) {
            dynmapBridge.deleteMarkersByPrefix(DYNMAP_MARKER_PREFIX);
        }
    } catch (Throwable t) {
        getLogger().warning("Purge failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    // Local runtime registry cleanup
    npcByEntityId.clear();
    conversationByPlayer.clear();

    return removed;
}

private String safeNpcName(Object citizensNpc, Entity ent) {
    try {
        Object o = invokeNoArg(citizensNpc, "getName");
        if (o instanceof String) return (String) o;
    } catch (Throwable ignored) {}
    try {
        String n = ent.getName();
        if (n != null) return n;
    } catch (Throwable ignored) {}
    return "";
}

    private Entity findEntityByUuid(UUID id) {
        if (id == null) return null;
        for (World w : Bukkit.getWorlds()) {
            if (w == null) continue;
            for (Entity e : w.getEntities()) {
                if (e != null && id.equals(e.getUniqueId())) return e;
            }
        }
        return null;
    }

    // -----------------------------
    // Sites + rosters
    // -----------------------------
    private void buildHardcodedSites() {
        sites.clear();

        // world
        addSite("west_frontier", "West_Frontier", "world", 686, 79, 149);
        addSite("harvest_bay", "Harvest_Bay", "world", 891, 65, 366);
        addSite("sunrise_river_outpost", "Sunrise_River_Outpost", "world", 4290, 69, 5880);
        addSite("tidewater_outpost", "Tidewater_Outpost", "world", 4167, 67, 5265);
        addSite("cliffside", "Cliffside", "world", 1093, 74, 372);
        addSite("east_frontier", "East_Frontier", "world", 930, 64, 208);
        addSite("docktown", "Docktown", "world", 809, 69, 415);
        addSite("bergentrueckung_point_outpost", "Bergentrückung_Point_Outpost", "world", 3368, 131, 4057);
        addSite("central_frontier", "Central_Frontier", "world", 835, 68, 190);
        addSite("pine_valley_colony", "Pine_Valley_Colony", "world", 366, 65, 99);
        addSite("kingsworth_bay", "Kingsworth_Bay", "world", 491, 68, 255);
        addSite("twin_lakes_protectorate", "Twin_Lakes_Protectorate", "world", 715, 64, 342);
        addSite("nether_portal_ruins", "Nether_Portal_Ruins", "world", 347, 67, 279);
        addSite("hinterland_canal_territory", "Hinterland_Canal_Territory", "world", 1604, 66, 178);
        addSite("rivendell_outpost", "Rivendell_Outpost", "world", 2770, 64, 320);
        addSite("berry_lake", "Berry_Lake", "world", 538, 70, 410);
        addSite("st_augustines_village", "St_Augustine's_Village", "world", 659, 71, 258);
        addSite("castleton_cape", "Castleton_Cape", "world", 793, 67, 273);
        addSite("perridew_national_forest", "Perridew_National_Forest", "world", 759, 98, 523);        addSite("intermarium", "Intermarium", "world", 621, 72, 785);
        addSite("lorens_domain", "Loren's_Domain", "world", 1164, 66, 764);
        addSite("mob_grinder", "Mob_Grinder", "world", 972, 120, 327);
        addSite("mystery_creek_territory", "Mystery_Creek_Territory", "world", 175, 66, -65);
        addSite("town_of_redmurk", "Town_of_Redmurk", "world", -502, 63, 446);
        addSite("intermarium_viridian_friendship_bridge", "INTERMARIUM-VIRIDIAN-FRIENDSHIP-BRIDGE", "world", 655, 83, 629);
        addSite("autumn_springs_colony", "Autumn_Springs_Colony", "world", -242, 64, 528);
        addSite("spawn_town", "SPAWN_TOWN", "world", 109, 68, 81);
        
        // tedkraft_world cluster
        addSite("finland", "Finland", "tedkraft_world", -234, 65, -455);
        addSite("bosnia", "Bosnia", "tedkraft_world", -308, 68, -455);
        addSite("terra_province", "Terra_Province", "tedkraft_world", -293, 68, -342);
        addSite("saberville_republic", "Saberville_Republic", "tedkraft_world", -172, 70, -301);
        addSite("new_gradiska", "New_Gradiska", "tedkraft_world", -294, 70, -218);
                        
        tailorSites();
    }

    private void addSite(String siteId, String label, String world, int x, int y, int z) {
        Site s = new Site(siteId, label, world, x, y, z);
        s.enabled = true;
        s.spawnRadius = defaultSpawnRadius;
        s.wanderRadius = defaultWanderRadius;
        s.maxNpcs = defaultMaxNpcsPerSite;
        s.siteType = classifySite(label);

        // Default rosters (tight pool -> less random)
        s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Villager"));
        s.rosterRoles = new ArrayList<>(Arrays.asList("Townsfolk", "Merchant", "Priest", "Ranger", "Guard"));

        sites.add(s);
    }

    private void tailorSites() {
        for (Site s : sites) {
            String lbl = s.label.toLowerCase(Locale.ROOT);

            // Base tuning
            s.maxNpcs = Math.min(s.maxNpcs, defaultMaxNpcsPerSite);
            s.spawnRadius = defaultSpawnRadius;
            s.wanderRadius = defaultWanderRadius;

            // Capital-ish
            if (lbl.equals("intermarium")) {
                s.siteType = "CAPITAL";
                s.maxNpcs = 10;
                s.spawnRadius = 72;
                s.wanderRadius = 30;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Guard", "Merchant", "Priest", "Townsfolk"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Villager", "Steve", "Alex", "Notch"));
                continue;
            }

            if (lbl.equals("spawn_town")) {
                s.siteType = "TOWN";
                s.maxNpcs = 7;
                s.spawnRadius = 60;
                s.wanderRadius = 28;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Townsfolk", "Merchant", "Guard"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Villager"));
                continue;
            }

            // Towns
            if (lbl.contains("town_of_") || lbl.contains("village") || lbl.contains("colony") || lbl.contains("protectorate")) {
                s.siteType = "TOWN";
                s.maxNpcs = 6;
                s.spawnRadius = 58;
                s.wanderRadius = 26;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Townsfolk", "Merchant", "Priest", "Guard"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Villager"));
            }

            // Dock
            if (lbl.contains("docktown") || lbl.contains("bay") || lbl.contains("cape")) {
                s.siteType = "DOCK";
                s.maxNpcs = 5;
                s.spawnRadius = 52;
                s.wanderRadius = 24;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Townsfolk", "Merchant", "Guard"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Villager", "Fisherman"));
            }

            // Castles/domains
            if (lbl.contains("castle") || lbl.contains("domain")) {
                s.siteType = "CASTLE";
                s.maxNpcs = 4;
                s.spawnRadius = 54;
                s.wanderRadius = 18;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Guard", "Guard", "Guard", "Priest")); // guard-heavy
                s.rosterSkins = new ArrayList<>(Arrays.asList("Guard", "Knight", "Steve"));
            }

            // Outposts/frontiers
            if (lbl.contains("outpost") || lbl.contains("frontier") || lbl.contains("cliffside")) {
                s.siteType = "OUTPOST";
                s.maxNpcs = 3;
                s.spawnRadius = 46;
                s.wanderRadius = 20;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Ranger", "Guard", "Townsfolk"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Ranger"));
            }

            // Forest
            if (lbl.contains("forest")) {
                s.siteType = "FOREST";
                s.maxNpcs = 2;
                s.spawnRadius = 58;
                s.wanderRadius = 22;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Ranger", "Ranger", "Townsfolk"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Ranger", "Alex"));
            }

            // Ruins
            if (lbl.contains("ruins") || lbl.contains("portal")) {
                s.siteType = "RUINS";
                s.maxNpcs = 2;
                s.spawnRadius = 40;
                s.wanderRadius = 14;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Priest", "Ranger"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Villager", "Steve", "Herobrine"));
            }

            // Bridge
            if (lbl.contains("bridge")) {
                s.siteType = "BRIDGE";
                s.maxNpcs = 2;
                s.spawnRadius = 40;
                s.wanderRadius = 18;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Guard", "Townsfolk"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex", "Villager"));
            }

            // Houses
            if (lbl.contains("house")) {
                s.siteType = "HOUSE";
                s.maxNpcs = 1;
                s.spawnRadius = 24;
                s.wanderRadius = 12;
                s.rosterRoles = new ArrayList<>(Arrays.asList("Townsfolk"));
                s.rosterSkins = new ArrayList<>(Arrays.asList("Steve", "Alex"));
            }

            }
    }

    private String classifySite(String label) {
        String lbl = label.toLowerCase(Locale.ROOT);

        if (lbl.equals("intermarium")) return "CAPITAL";
        if (lbl.contains("docktown")) return "DOCK";
        if (lbl.contains("bay") || lbl.contains("cape")) return "DOCK";
        if (lbl.contains("castle") || lbl.contains("domain")) return "CASTLE";
        if (lbl.contains("outpost") || lbl.contains("frontier")) return "OUTPOST";
        if (lbl.contains("forest")) return "FOREST";
        if (lbl.contains("ruins") || lbl.contains("portal")) return "RUINS";
        if (lbl.contains("bridge")) return "BRIDGE";
        if (lbl.contains("house")) return "HOUSE";
        if (lbl.contains("town") || lbl.contains("village") || lbl.contains("colony") || lbl.contains("protectorate")) return "TOWN";

        return "TOWN";
    }

    private Site findSite(String siteId) {
        if (siteId == null) return null;
        for (Site s : sites) {
            if (s.siteId.equalsIgnoreCase(siteId)) return s;
        }
        return null;
    }

    private Site findNearestSite(Location loc, int maxDistBlocks) {
        if (loc == null || loc.getWorld() == null) return null;

        double best = (double) maxDistBlocks * (double) maxDistBlocks;
        Site bestSite = null;

        for (Site s : sites) {
            if (!s.enabled) continue;
            if (!s.worldName.equalsIgnoreCase(loc.getWorld().getName())) continue;

            double dx = (s.x + 0.5) - loc.getX();
            double dz = (s.z + 0.5) - loc.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                bestSite = s;
            }
        }

        return bestSite;
    }

    private Site pickNearbyInterestingSite(Site from) {
        if (from == null) return null;

        List<Site> sameWorld = new ArrayList<>();
        for (Site s : sites) {
            if (!s.enabled) continue;
            if (!s.worldName.equalsIgnoreCase(from.worldName)) continue;
            if (s.siteId.equalsIgnoreCase(from.siteId)) continue;
            sameWorld.add(s);
        }
        if (sameWorld.isEmpty()) return null;

        // Prefer RUINS/CASTLE/BRIDGE
        List<Site> preferred = new ArrayList<>();
        for (Site s : sameWorld) {
            if (s.siteType.equals("RUINS") || s.siteType.equals("CASTLE") || s.siteType.equals("BRIDGE")) preferred.add(s);
        }

        List<Site> pickFrom = preferred.isEmpty() ? sameWorld : preferred;
        return pickFrom.get(runtimeRandom.nextInt(pickFrom.size()));
    }

    private String prettyLabel(String label) {
        if (label == null) return "Unknown";
        return label.replace("_", " ").replace("&#39;", "'").trim();
    }

    private String pickAmbientLine(Site s) {
        List<String> pool = new ArrayList<>();
        String pl = prettyLabel(s.label);

        pool.add("You hear a distant door close.");
        pool.add("A lantern flickers though there's no wind.");
        pool.add("For a moment, you feel watched.");
        pool.add("You catch the smell of smoke, but see no fire.");
        pool.add("Footsteps. Not yours.");

        if (s.siteType.equals("CAPITAL")) {
            pool.add("Paper shuffles somewhere nearby.");
            pool.add("A bell rings once, then stops.");
        } else if (s.siteType.equals("DOCK")) {
            pool.add("Rope creaks as if a boat is tied nearby.");
            pool.add("A gull cries overhead. Then silence.");
        } else if (s.siteType.equals("CASTLE")) {
            pool.add("Metal scrapes stone. Like armor turning.");
        } else if (s.siteType.equals("RUINS")) {
            pool.add("The air tastes like ash for a moment.");
        } else if (s.siteType.equals("FOREST")) {
            pool.add("Pines sway. You could swear they leaned away from you.");
        } else if (s.siteType.equals("BRIDGE")) {
            pool.add("The bridge hums quietly under your feet.");
        }

        // Tiny folklore nods
        if (runtimeRandom.nextInt(100) < 15) {
            pool.add("A name flashes through your mind. You don't know why.");
        }

        return pool.get(runtimeRandom.nextInt(pool.size()));
    }

    // -----------------------------
    // Config
    // -----------------------------
    private void ensureConfigExists() {
        if (configFile.exists()) return;

        if (!getDataFolder().exists()) {
            boolean ok = getDataFolder().mkdirs();
            if (!ok && !getDataFolder().exists()) {
                getLogger().warning("Could not create plugin folder: " + getDataFolder().getAbsolutePath());
            }
        }

        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("enabled", true);
        cfg.set("debugLog", false);

        cfg.set("integration.citizens.enable", true);
        cfg.set("integration.dynmap.enable", true);

        cfg.set("population.maintainEverySeconds", 45);
        cfg.set("population.moveEverySeconds", 8);
        cfg.set("population.maxSpawnsPerMaintainTick", 6);
        cfg.set("population.globalMaxNpcs", 120);
        cfg.set("population.minDistanceFromPlayersToSpawn", 10);

        cfg.set("sites.defaultMaxNpcsPerSite", 5);
        cfg.set("sites.defaultSpawnRadius", 42);
        cfg.set("sites.defaultWanderRadius", 22);

        cfg.set("dialogue.conversationTimeoutSeconds", 18);
        cfg.set("ambient.cooldownSeconds", 30);
        cfg.set("ambient.chancePercent", 10);

        try {
            cfg.save(configFile);
        } catch (IOException e) {
            getLogger().warning("Failed to write config.yml: " + e.getMessage());
        }
    }

    private void loadConfigValues() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        enabled = cfg.getBoolean("enabled", true);
        debugLog = cfg.getBoolean("debugLog", false);

        integrateCitizens = cfg.getBoolean("integration.citizens.enable", true);
        integrateDynmap = cfg.getBoolean("integration.dynmap.enable", true);

        maintainEverySeconds = cfg.getInt("population.maintainEverySeconds", 45);
        moveEverySeconds = cfg.getInt("population.moveEverySeconds", 8);
        maxSpawnsPerMaintainTick = cfg.getInt("population.maxSpawnsPerMaintainTick", 6);
        globalMaxNpcs = cfg.getInt("population.globalMaxNpcs", 120);
        minDistanceFromPlayersToSpawn = cfg.getInt("population.minDistanceFromPlayersToSpawn", 10);

        defaultMaxNpcsPerSite = cfg.getInt("sites.defaultMaxNpcsPerSite", 5);
        defaultSpawnRadius = cfg.getInt("sites.defaultSpawnRadius", 42);
        defaultWanderRadius = cfg.getInt("sites.defaultWanderRadius", 22);

        conversationTimeoutSeconds = cfg.getInt("dialogue.conversationTimeoutSeconds", 18);
        ambientChatterCooldownSeconds = cfg.getInt("ambient.cooldownSeconds", 30);
        ambientChatterChancePercent = cfg.getInt("ambient.chancePercent", 10);
    }

    private boolean isPluginEnabled(String name) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            return p != null && p.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // -----------------------------
    // Reflection helpers
    // -----------------------------
    private static Method findMethodByNameAndParamCount(Class<?> clz, String name, int paramCount) {
        if (clz == null) return null;
        for (Method m : clz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            return m;
        }
        return null;
    }

    private static Object invokeNoArg(Object obj, String method) {
        if (obj == null) return null;
        try {
            Method m = findMethodByNameAndParamCount(obj.getClass(), method, 0);
            if (m == null) return null;
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeWithSingleArg(Object obj, String method, Object arg) {
        if (obj == null) return null;
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (arg != null && !m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) continue;
                return m.invoke(obj, arg);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean invokeWithSingleArgIfExists(Object obj, String method, Object arg) {
        if (obj == null) return false;
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (m.getParameterTypes().length != 1) continue;

                // Handle primitive wrappers
                Class<?> p = m.getParameterTypes()[0];
                if (arg != null) {
                    if (p.isPrimitive()) {
                        if ((p == int.class && arg instanceof Integer) ||
                            (p == boolean.class && arg instanceof Boolean) ||
                            (p == double.class && arg instanceof Double) ||
                            (p == float.class && arg instanceof Float) ||
                            (p == long.class && arg instanceof Long)) {
                            // ok
                        } else {
                            continue;
                        }
                    } else if (!p.isAssignableFrom(arg.getClass())) {
                        continue;
                    }
                }
                m.invoke(obj, arg);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean invokeWithSingleArgIfExists(Object obj, String method, Location arg) {
        // overload for Location assignability issues
        if (obj == null) return false;
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (m.getParameterTypes().length != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isAssignableFrom(Location.class)) continue;
                m.invoke(obj, arg);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static byte[] httpGetBytes(String url, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ViridianTownsFolk");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
                return out.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return s;
        }
    }

    // -----------------------------
    // Dynmap bridge (classloader-safe reflection)
    // -----------------------------
    private static class DynmapBridge {
        private final ViridianTownsFolk plugin;

        private Plugin dynmapPlugin;
        private ClassLoader dynmapCl;
        private Object markerApi;
        private Object markerSet;

        // icon caching
        private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
        private final Map<String, String> skinToIconId = new ConcurrentHashMap<>();

        DynmapBridge(ViridianTownsFolk plugin) {
            this.plugin = plugin;
        }

        void init() {
            try {
                dynmapPlugin = Bukkit.getPluginManager().getPlugin("dynmap");
                if (dynmapPlugin == null || !dynmapPlugin.isEnabled()) {
                    markerApi = null;
                    markerSet = null;
                    return;
                }

                dynmapCl = dynmapPlugin.getClass().getClassLoader();

                // dynmap plugin instance usually has getMarkerAPI(); some builds provide getAPI() returning DynmapAPI
                markerApi = null;
                try {
                    Method getMarkerApi = dynmapPlugin.getClass().getMethod("getMarkerAPI");
                    markerApi = getMarkerApi.invoke(dynmapPlugin);
                } catch (NoSuchMethodException nsme) {
                    try {
                        Method getApi = dynmapPlugin.getClass().getMethod("getAPI");
                        Object apiObj = getApi.invoke(dynmapPlugin);
                        if (apiObj != null) {
                            Method getMarkerApi2 = apiObj.getClass().getMethod("getMarkerAPI");
                            markerApi = getMarkerApi2.invoke(apiObj);
                        }
                    } catch (Throwable ignored) {}
                }
                if (markerApi == null) {
                    markerSet = null;
                    return;
                }

                markerSet = getOrCreateMarkerSet(DYNMAP_MARKERSET_ID, DYNMAP_MARKERSET_LABEL);
                if (plugin.debugLog) plugin.getLogger().info("Dynmap bridge initialized. MarkerSet=" + DYNMAP_MARKERSET_ID);
            } catch (Throwable t) {
                markerApi = null;
                markerSet = null;
                plugin.getLogger().warning("Dynmap bridge init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        void upsertNpc(Entity ent, NpcRecord rec) {
            if (markerApi == null || markerSet == null) return;
            if (ent == null || ent.getWorld() == null || rec == null) return;

            String markerId = DYNMAP_MARKER_PREFIX + rec.npcKey;
            String label = rec.displayName;
            String world = ent.getWorld().getName();
            Location loc = ent.getLocation();

            // Icon per skin
            String skinKey = rec.skinName.toLowerCase(Locale.ROOT);
            String iconId = skinToIconId.computeIfAbsent(skinKey, k -> "vtf_face_" + k.replaceAll("[^a-z0-9_\\-]", "_"));

            // Ensure icon exists (async fetch once)
            if (!iconCache.containsKey(iconId)) {
                iconCache.put(iconId, new byte[]{1}); // placeholder to avoid duplicate fetches
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    byte[] png = fetchFace16(rec.skinName);
                    if (png == null) png = fetchFace16("Steve");
                    if (png != null) {
                        final byte[] iconPng = png;
                        iconCache.put(iconId, iconPng);
                        Bukkit.getScheduler().runTask(plugin, () -> ensureIcon(iconId, "NPC", iconPng));
                    }
                });
            } else {
                byte[] png = iconCache.get(iconId);
                if (png != null && png.length > 1) ensureIcon(iconId, "NPC", png);
            }

            // Upsert marker (main thread)
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Object iconObj = getMarkerIcon(iconId);
                    if (iconObj == null) iconObj = getMarkerIcon("default");

                    Object marker = findMarker(markerId);
                    if (marker == null) {
                        createMarker(markerId, label, world, loc, iconObj);
                    } else {
                        setMarkerLocation(marker, world, loc);
                        setMarkerLabel(marker, label);
                        setMarkerIcon(marker, iconObj);
                    }
                } catch (Throwable t) {
                    if (plugin.debugLog) plugin.getLogger().warning("Dynmap upsert failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        }

        private Object getOrCreateMarkerSet(String id, String label) throws Exception {
            // markerApi.getMarkerSet(String)
            Method get = findMethod(markerApi.getClass(), "getMarkerSet", 1);
            Object set = (get != null) ? get.invoke(markerApi, id) : null;

            if (set != null) {
                // setMarkerSetLabel(String)
                Method setLabel = findMethod(set.getClass(), "setMarkerSetLabel", 1);
                if (setLabel != null) setLabel.invoke(set, label);
                Method hide = findMethod(set.getClass(), "setHideByDefault", 1);
                if (hide != null) hide.invoke(set, false);
                return set;
            }

            // createMarkerSet(String, String, Set, boolean)
            Method create = findMethod(markerApi.getClass(), "createMarkerSet", 4);
            if (create != null) {
                Object created = create.invoke(markerApi, id, label, null, false);
                if (created != null) {
                    Method hide = findMethod(created.getClass(), "setHideByDefault", 1);
                    if (hide != null) hide.invoke(created, false);
                }
                return created;
            }

            // Older signature: createMarkerSet(String, String, Set)
            Method create3 = findMethod(markerApi.getClass(), "createMarkerSet", 3);
            if (create3 != null) {
                return create3.invoke(markerApi, id, label, null);
            }

            return null;
        }

        private void ensureIcon(String iconId, String label, byte[] pngBytes) {
            try {
                if (getMarkerIcon(iconId) != null) return;

                Method createIcon = findMethod(markerApi.getClass(), "createMarkerIcon", 3);
                if (createIcon == null) return;

                InputStream in = new ByteArrayInputStream(pngBytes);
                createIcon.invoke(markerApi, iconId, label, in);
            } catch (Throwable t) {
                if (plugin.debugLog) plugin.getLogger().info("Icon create failed: " + t.getClass().getSimpleName());
            }
        }

        private Object getMarkerIcon(String iconId) throws Exception {
            Method get = findMethod(markerApi.getClass(), "getMarkerIcon", 1);
            if (get == null) return null;
            return get.invoke(markerApi, iconId);
        }

        private Object findMarker(String markerId) throws Exception {
            Method find = findMethod(markerSet.getClass(), "findMarker", 1);
            if (find == null) return null;
            return find.invoke(markerSet, markerId);
        }

        private void createMarker(String markerId, String label, String world, Location loc, Object iconObj) throws Exception {
            // createMarker(String id, String label, boolean markup, String world, double x, double y, double z, MarkerIcon icon, boolean persistent)
            for (Method m : markerSet.getClass().getMethods()) {
                if (!m.getName().equals("createMarker")) continue;
                if (m.getParameterTypes().length < 8) continue;

                Object[] args = buildCreateMarkerArgs(m, markerId, label, world, loc, iconObj);
                if (args == null) continue;

                m.invoke(markerSet, args);
                return;
            }
        }

        private Object[] buildCreateMarkerArgs(Method m, String markerId, String label, String world, Location loc, Object iconObj) {
            Class<?>[] p = m.getParameterTypes();

            // We'll support the common 10-arg signature.
            // If it's something else, skip.
            if (p.length == 10) {
                return new Object[]{
                        markerId, label, false,
                        world, loc.getX(), loc.getY(), loc.getZ(),
                        iconObj, true
                };
            }
            if (p.length == 9) {
                // Some versions omit 'markup'
                return new Object[]{
                        markerId, label,
                        world, loc.getX(), loc.getY(), loc.getZ(),
                        iconObj, true
                };
            }
            return null;
        }

        private void setMarkerLocation(Object marker, String world, Location loc) throws Exception {
            Method setLoc = findMethod(marker.getClass(), "setLocation", 4);
            if (setLoc != null) setLoc.invoke(marker, world, loc.getX(), loc.getY(), loc.getZ());
        }

        private void setMarkerLabel(Object marker, String label) throws Exception {
            // setLabel(String, boolean) or setLabel(String)
            Method set2 = findMethod(marker.getClass(), "setLabel", 2);
            if (set2 != null) {
                set2.invoke(marker, label, false);
                return;
            }
            Method set1 = findMethod(marker.getClass(), "setLabel", 1);
            if (set1 != null) set1.invoke(marker, label);
        }

        private void setMarkerIcon(Object marker, Object iconObj) throws Exception {
            // setMarkerIcon(MarkerIcon) or setMarkerIcon(String)
            Method set = findMethod(marker.getClass(), "setMarkerIcon", 1);
            if (set != null) set.invoke(marker, iconObj);
        }

        private static Method findMethod(Class<?> clz, String name, int paramCount) {
            for (Method m : clz.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != paramCount) continue;
                return m;
            }
            return null;
        }

        private byte[] fetchFace16(String skinName) {
            try {
                UUID uuid = null;
                try {
                    OfflinePlayer off = Bukkit.getOfflinePlayer(skinName);
                    if (off != null) uuid = off.getUniqueId();
                } catch (Throwable ignored) {}

                // Prefer crafatar if uuid available, else minotar by name
                List<String> urls = new ArrayList<>();
                if (uuid != null) urls.add("https://crafatar.com/avatars/" + uuid + "?size=16&overlay");
                urls.add("https://minotar.net/avatar/" + urlEncode(skinName) + "/16.png");

                for (String u : urls) {
                    byte[] png = httpGetBytes(u, 4000, 7000);
                    if (png != null && png.length > 0) return png;
                }
            } catch (Throwable ignored) {}
            return null;
        }
    

void deleteMarkersByPrefix(String prefix) {
    if (markerSet == null) return;
    try {
        // markerSet.getMarkers() : Set<Marker>
        Method getMarkers = findMethodByNameAndParamCount(markerSet.getClass(), "getMarkers", 0);
        Object o = (getMarkers != null) ? getMarkers.invoke(markerSet) : null;
        if (!(o instanceof Collection)) return;

        Collection<?> markers = (Collection<?>) o;
        List<Object> toDelete = new ArrayList<>();
        for (Object m : markers) {
            try {
                Object idObj = invokeNoArg(m, "getMarkerID");
                if (!(idObj instanceof String)) continue;
                String id = (String) idObj;
                if (id != null && id.startsWith(prefix)) {
                    toDelete.add(m);
                }
            } catch (Throwable ignored) {}
        }

        for (Object m : toDelete) {
            try { invokeNoArg(m, "deleteMarker"); } catch (Throwable ignored) {}
        }

        if (plugin.debugLog && !toDelete.isEmpty()) {
            plugin.getLogger().info("Dynmap: deleted " + toDelete.size() + " marker(s) with prefix " + prefix);
        }
    } catch (Throwable t) {
        if (plugin.debugLog) plugin.getLogger().warning("Dynmap deleteMarkersByPrefix failed: " + t.getMessage());
    }
}
}

    // -----------------------------
    // Data classes
    // -----------------------------
    private static class Site {
        final String siteId;
        final String label;
        final String worldName;
        final int x, y, z;

        boolean enabled = true;
        int maxNpcs = 5;
        int spawnRadius = 42;
        int wanderRadius = 22;
        String siteType = "TOWN";

        List<String> rosterSkins = new ArrayList<>();
        List<String> rosterRoles = new ArrayList<>();

        Site(String siteId, String label, String worldName, int x, int y, int z) {
            this.siteId = siteId;
            this.label = label;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean isSettlement() {
            return siteType.equals("CAPITAL") || siteType.equals("TOWN") || siteType.equals("DOCK") || siteType.equals("CASTLE");
        }

        boolean isChatterHeavy() {
            return isSettlement() || siteType.equals("BRIDGE");
        }
    }

    private static class NpcSpec {
        final String displayName;
        final String skinName;
        final String role;
        final String temperament;

        NpcSpec(String displayName, String skinName, String role, String temperament) {
            this.displayName = displayName;
            this.skinName = skinName;
            this.role = role;
            this.temperament = temperament;
        }
    }

    private static class NpcSpawnResult {
        final Object citizensNpc;
        final Entity entity;

        NpcSpawnResult(Object citizensNpc, Entity entity) {
            this.citizensNpc = citizensNpc;
            this.entity = entity;
        }
    }

    private static class NpcRecord {
        final String siteId;
        final String worldName;
        final Site fallbackSite;

        final String displayName;
        final String skinName;

        final String role;
        final String temperament;

        final String npcKey;
        final long createdAtMs;

        volatile long nextMoveAtMs = 0;

        NpcRecord(String siteId, String worldName, Site fallbackSite, String displayName, String skinName,
                  String role, String temperament, String npcKey, long createdAtMs) {
            this.siteId = siteId;
            this.worldName = worldName;
            this.fallbackSite = fallbackSite;
            this.displayName = displayName;
            this.skinName = skinName;
            this.role = role;
            this.temperament = temperament;
            this.npcKey = npcKey;
            this.createdAtMs = createdAtMs;
        }
    }

private static class ConversationState {
        final NpcRecord npc;
        final Site site;
        long expiresAtMs; // mutable
        int stage;
        String lastPrompt = "Yes or no?";

        ConversationState(NpcRecord npc, Site site, long expiresAtMs, int stage) {
            this.npc = npc;
            this.site = site;
            this.expiresAtMs = expiresAtMs;
            this.stage = stage;
        }
    }
}
