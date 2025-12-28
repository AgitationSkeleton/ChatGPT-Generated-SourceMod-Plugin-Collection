package com.redchanit.viridiantownsfolk;

import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.World;
import org.bukkit.Location;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import net.citizensnpcs.trait.SkinTrait;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ViridianTownsFolk (RESET baseline, crash-safe)
 *
 * Key goals:
 * - No Dynmap API usage (reads dynmap markers.yml only as data)
 * - No ChunkLoadEvent entity scanning (avoids NMS iterator/ticket edge cases)
 * - Population is player-proximity gated + spawn throttled (spawn queue)
 * - Auto-wakes managed NPCs on NPCSpawnEvent + player proximity
 * - Configurable ignore list for sites (ignoreContains/ignoreExact/ignoreRegex)
 *
 * Author: ChatGPT
 */
public final class ViridianTownsFolk extends JavaPlugin implements Listener, CommandExecutor {

    /* ============================================================
     *  Site data
     * ============================================================ */

    private static final class Site {
        final String name;
        final String worldName;
        final int x;
        final int z;

        Site(String name, String worldName, int x, int z) {
            this.name = name;
            this.worldName = worldName;
            this.x = x;
            this.z = z;
        }

        Location center(World w) {
            // Do NOT call getHighestBlockYAt() here (can load chunks). This is only used for distance checks.
            return new Location(w, x + 0.5, w.getSeaLevel(), z + 0.5);
        }

        @Override
        public String toString() {
            return worldName + "@" + x + "," + z + " (" + name + ")";
        }
    }

    /* ============================================================
     *  Keys / tags
     * ============================================================ */

    private NamespacedKey KEY_VTF_MANAGED;
    private NamespacedKey KEY_VTF_ROLE;
    private NamespacedKey KEY_VTF_SITE;
    private NamespacedKey KEY_VTF_VER;
    private NamespacedKey KEY_VTF_NAME;      // generated personal name
    private NamespacedKey KEY_VTF_SUBROLE;   // optional: worker:farmer/smith/mason/carpenter
    private NamespacedKey KEY_VTF_TASK;      // current task hint for contextual dialogue
    private NamespacedKey KEY_VTF_IDLE_UNTIL; // tick until which NPC should linger/idle
    private NamespacedKey KEY_VTF_EXPECT_IDLE; // flag set when a target was chosen and we want to linger on arrival

    private static final String SCORE_TAG = "vtf_managed";


/* ============================================================
 *  Townsfolk skins (MineSkin + Citizens SkinTrait)
 * ============================================================ */

private static final List<String> TOWNSFOLK_SKIN_FILES = Arrays.asList(
        "acolyte.png",
        "acolyte_f.png",
        "dockhand.png",
        "dockhand_f.png",
        "farmer.png",
        "farmer_f.png",
        "guard.png",
        "guard_f.png",
        "hermit.png",
        "mason.png",
        "mason_f.png",
        "merchant.png",
        "merchant_f.png",
        "priest.png",
        "ranger.png",
        "scholar.png",
        "scribe.png",
        "sentinel.png",
        "smith.png",
        "smith_f.png",
        "townsfolk.png",
        "townsfolk_f.png",
        "watchman.png",
        "worker.png",
        "worker_f.png"
);

private static final class SkinData {
    final String value;
    final String signature;
    SkinData(String value, String signature) {
        this.value = value;
        this.signature = signature;
    }
}

// skinKey (base filename without .png, lowercase) -> value/signature
private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();
private final Object mineSkinRateLock = new Object();
private volatile long nextMineSkinRequestAtMs = 0L;
private volatile boolean warnedBlankMineSkinKey = false;

private int skinRetryTaskId = -1;
private int skinEnforceTaskId = -1;

private NamespacedKey KEY_VTF_SKIN; // stores chosen skinKey on entity PDC

// Approximate server tick counter (Spigot API doesn't expose Bukkit.getCurrentTick())
private long approxServerTick = 0L;
private BukkitTask approxServerTickTask = null;


    /* ============================================================
     *  Legacy/adoption heuristics and exclusions
     * ============================================================ */

    private static final List<String> legacyRolePrefixes = Arrays.asList(
            "Worker", "Guard", "Priest", "Dockhand", "Townsfolk",
            "Merchant", "Scholar", "Ranger", "Hermit", "Watchman",
            "Sentinel", "Acolyte", "Smith", "Mason", "Scribe", "Farmer"
    );

    private static final List<String> excludedNameContains = Arrays.asList(
            "herobrine", "indev", "wanderlore", "wl_", "indevmobs"
    );

    private static final List<String> excludedExactish = Arrays.asList(
            "rana", "steve", "beast boy", "black steve", "indevblksteve"
    );

    /* ============================================================
     *  Runtime state
     * ============================================================ */

    // Loaded marker sites
    private final List<Site> sites = new ArrayList<>();

    // Ignore filters (configurable)
    private final List<String> ignoreExact = new ArrayList<>();
    private final List<String> ignoreContains = new ArrayList<>();
    private final List<Pattern> ignoreRegex = new ArrayList<>();

    // Autonomy tasks keyed by NPC id
    private final Map<Integer, BukkitTask> autonomyTasks = new HashMap<>();

    // Fast pulse task (doors/gates, swimming nudges, lightweight routines)
    private BukkitTask behaviorPulseTask;
    private int behaviorPulsePerTick = 16; // cap per pulse to avoid spikes
    private int behaviorPulseCursor = 0;

    // Door/gate open-close tracking
    private final Map<String, Long> openedBlocksCloseAtTick = new HashMap<>();

    // Simple interactive dialogue sessions (player -> session)
    private static final class ConversationSession {
        final int npcId;
        String topic;
        int step;
        long expiresAtMs;
        BukkitTask lookTask;
        boolean lockEnabled;

        ConversationSession(int npcId, String topic, int step, long expiresAtMs) {
            this.npcId = npcId;
            this.topic = topic;
            this.step = step;
            this.expiresAtMs = expiresAtMs;
        }
    }
    private final Map<UUID, ConversationSession> conversations = new HashMap<>();

    private BukkitTask conversationCleanupTask;

    // Player scan throttle
    private final Map<UUID, Long> lastWakeScanMs = new HashMap<>();

    // Spawn queue to avoid spikes
    private final ArrayDeque<Runnable> spawnQueue = new ArrayDeque<>();
    private BukkitTask spawnQueueTask;

    // Population tick task
    private BukkitTask populationTask;

    /* ============================================================
     *  Config values (generated if missing)
     * ============================================================ */

    private boolean enabled = true;

    // Dimension restrictions
    private boolean allowSpawningInNether = false;
    private boolean allowSpawningInEnd = false;

    // Portal usage (Citizens NPCs owned by VTF)
    private boolean allowNetherPortals = false;
    private boolean allowEndPortals = false;


    private boolean hideNameplates = true;

    // Citizens collision (pushable/bumpable)
    private boolean citizensCollidable = true;
    private boolean citizensKnockback = true;

    private int wakePlayerScanRadius = 48;
    private int wakePlayerScanCooldownMs = 2500;
    private int wakeMaxPerScan = 24;

    // Adoption
    private boolean adoptByNameNearSitesOnly = true;
    private int siteAdoptRadius = 128;

    // Population
    private int populationMinPerSite = 10;
    private int populationMaxPerSite = 22;
    private int populationRadius = 64;
    private int populationCycleSeconds = 120;
    private int populationMaxQueuePerCycle = 6;

    // Critical crash-safety gate: only populate sites that have a nearby player
    private int populationSiteActivationRadius = 192;

    // Spawn queue throttle (per tick)
    private int populationSpawnPerTick = 1;

    // Roaming/autonomy
    private int roamRadiusTownsfolk = 14;
    private int roamRadiusWorker = 14;
    private int roamRadiusGuard = 18;
    private int roamRadiusPriest = 10;
    private int roamRadiusScholar = 10;
    private int roamRadiusRanger = 22;
    private int roamRadiusHermit = 20;

    // Site anchor leash (if they drift too far from site center, guide back)
    private int returnToSiteDistance = 56;

    // Behavior pulse
    private boolean interactiveDialogueEnabled = true;
    private int dialogueSessionSeconds = 45;

    /* ============================================================
     *  Enable/disable
     * ============================================================ */

    @Override
    public void onEnable() {
        KEY_VTF_MANAGED = new NamespacedKey(this, "vtf");
        KEY_VTF_ROLE = new NamespacedKey(this, "vtf_role");
        KEY_VTF_SITE = new NamespacedKey(this, "vtf_site");
        KEY_VTF_VER = new NamespacedKey(this, "vtf_ver");
        KEY_VTF_NAME = new NamespacedKey(this, "vtf_name");
        KEY_VTF_SUBROLE = new NamespacedKey(this, "vtf_subrole");
        KEY_VTF_TASK = new NamespacedKey(this, "vtf_task");
        KEY_VTF_IDLE_UNTIL = new NamespacedKey(this, "vtf_idle_until");
        KEY_VTF_EXPECT_IDLE = new NamespacedKey(this, "vtf_expect_idle");
        KEY_VTF_SKIN = new NamespacedKey(this, "vtf_skin");

        ensureConfigFile();
        loadConfigValues();


ensureSkinFolder();
loadSkinCacheFromConfig();
startSkinTasks();

        if (!enabled) {
            getLogger().info("ViridianTownsFolk disabled in config.yml.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("vtf") != null) getCommand("vtf").setExecutor(this);
        if (getCommand("viridiantownsfolk") != null) getCommand("viridiantownsfolk").setExecutor(this);

        loadSites();

        startSpawnQueueWorker();
        startPopulationMaintainer();
        startApproxServerTickCounter();
        startBehaviorPulse();
        startConversationCleanupTask();

        // Startup wake for already-spawned NPCs (safe: Citizens registry iteration only)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int woke = wakeAllSpawned("startup");
            getLogger().info("Startup wake: " + woke);
        }, 40L);

        // Best-effort rename/migrate managed NPCs that are already spawned.
        Bukkit.getScheduler().runTaskLater(this, this::migrateSpawnedManagedNpcs, 60L);

        // Citizens sometimes spawns NPCs later; do a couple late sweeps
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllSpawned("startup-late"), 20L * 10L);
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllSpawned("startup-later"), 20L * 30L);

        getLogger().info("ViridianTownsFolk RESET baseline enabled. Sites: " + sites.size());
    }

    @Override
    public void onDisable() {
        if (populationTask != null) populationTask.cancel();
        populationTask = null;

        if (spawnQueueTask != null) spawnQueueTask.cancel();
        spawnQueueTask = null;

        if (behaviorPulseTask != null) behaviorPulseTask.cancel();
        behaviorPulseTask = null;

        if (approxServerTickTask != null) approxServerTickTask.cancel();
        approxServerTickTask = null;

        if (conversationCleanupTask != null) conversationCleanupTask.cancel();
        conversationCleanupTask = null;

        synchronized (conversations) {
            for (ConversationSession s : conversations.values()) {
                if (s != null && s.lookTask != null) {
                    try { s.lookTask.cancel(); } catch (Throwable ignored) {}
                }
            }
            conversations.clear();
        }

        if (conversationCleanupTask != null) conversationCleanupTask.cancel();
        conversationCleanupTask = null;

        synchronized (conversations) {
            for (ConversationSession s : conversations.values()) {
                if (s != null && s.lookTask != null) {
                    try { s.lookTask.cancel(); } catch (Throwable ignored) {}
                }
            }
            conversations.clear();
        }

        for (BukkitTask t : autonomyTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        autonomyTasks.clear();
        spawnQueue.clear();
    }

    /* ============================================================
     *  Commands
     * ============================================================ */

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!enabled) {
            sender.sendMessage("ViridianTownsFolk is disabled in config.yml.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GRAY + "ViridianTownsFolk:");
            sender.sendMessage(ChatColor.GRAY + "/vtf rehook       - wake/rehook all spawned NPCs");
            sender.sendMessage(ChatColor.GRAY + "/vtf adoptall     - adopt legacy name-prefix NPCs (spawned only)");
            sender.sendMessage(ChatColor.GRAY + "/vtf topup        - queue one population pass now");
            sender.sendMessage(ChatColor.GRAY + "/vtf purge        - remove ALL VTF-managed NPCs");
            sender.sendMessage(ChatColor.GRAY + "/vtf purgenether - purge VTF NPCs in the Nether");
            sender.sendMessage(ChatColor.GRAY + "/vtf purgeend    - purge VTF NPCs in the End");
            sender.sendMessage(ChatColor.GRAY + "/vtf stats        - show counts");
            sender.sendMessage(ChatColor.GRAY + "/vtf reloadsites  - reload sites from markers.yml + sites.txt");
            sender.sendMessage(ChatColor.GRAY + "/vtf reloadconfig - reload config.yml");
            sender.sendMessage(ChatColor.GRAY + "/vtf migrate      - rename/update spawned VTF NPCs (back-compat)");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "rehook": {
                int woke = wakeAllSpawned("command-rehook");
                sender.sendMessage(ChatColor.GREEN + "Woke/rehooked: " + woke);
                return true;
            }
            case "adoptall": {
                int adopted = adoptAllSpawnedLegacyByName();
                int woke = wakeAllSpawned("command-adoptall");
                sender.sendMessage(ChatColor.GREEN + "Adopted: " + adopted + " | Woke: " + woke);
                return true;
            }
            case "topup": {
                int queued = queuePopulationCycle("command-topup");
                sender.sendMessage(ChatColor.GREEN + "Queued spawns: " + queued);
                return true;
            }
            case "purge": {
                int removed = purgeAllManaged();
                sender.sendMessage(ChatColor.RED + "Purged VTF-managed NPCs: " + removed);
                return true;
            }
            case "purgenether": {
                int removed = purgeManagedInEnvironment(World.Environment.NETHER);
                sender.sendMessage(ChatColor.RED + "Purged VTF-managed NPCs in the Nether: " + removed);
                return true;
            }
            case "purgeend": {
                int removed = purgeManagedInEnvironment(World.Environment.THE_END);
                sender.sendMessage(ChatColor.RED + "Purged VTF-managed NPCs in the End: " + removed);
                return true;
            }
            case "stats": {
                int total = 0;
                int spawned = 0;
                int managedSpawned = 0;

                for (NPC npc : CitizensAPI.getNPCRegistry()) {
                    total++;
                    if (npc.isSpawned()) {
                        spawned++;
                        if (isManagedNpc(npc) && !isExcludedNpc(npc)) managedSpawned++;
                    }
                }

                sender.sendMessage(ChatColor.GRAY + "Citizens NPCs total: " + total);
                sender.sendMessage(ChatColor.GRAY + "Spawned now: " + spawned);
                sender.sendMessage(ChatColor.GRAY + "VTF-managed spawned: " + managedSpawned);
                sender.sendMessage(ChatColor.GRAY + "Sites loaded: " + sites.size());
                sender.sendMessage(ChatColor.GRAY + "Spawn queue pending: " + spawnQueue.size());
                return true;
            }
            case "reloadsites": {
                loadSites();
                sender.sendMessage(ChatColor.GREEN + "Reloaded sites. Count: " + sites.size());
                return true;
            }
            case "reloadconfig": {
                loadConfigValues();
                sender.sendMessage(ChatColor.GREEN + "Reloaded config.yml.");
                return true;
            }
            case "migrate": {
                int updated = migrateSpawnedManagedNpcs();
                sender.sendMessage(ChatColor.GREEN + "Migrated/updated spawned VTF NPCs: " + updated);
                return true;
            }
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /vtf");
                return true;
        }
    }

    /* ============================================================
     *  Citizens NPC spawn hook (safer than chunk entity scans)
     * ============================================================ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCitizensNpcSpawn(NPCSpawnEvent e) {
        if (!enabled) return;
        NPC npc = e.getNPC();
        if (npc == null) return;
        if (!npc.isSpawned()) return;
        if (isExcludedNpc(npc)) return;

        // If already managed, wake it.
        if (isManagedNpc(npc)) {
            wakeNpc(npc, "npcspawn-managed");
            ensureTownSkinApplied(npc, "npcspawn-managed");
            return;
        }

        // If it looks like legacy VTF, adopt + wake
        if (looksLikeLegacyVtfByName(npc)) {
            if (adoptByNameNearSitesOnly) {
                Entity ent = npc.getEntity();
                if (ent == null) return;
                Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
                if (near == null) return;
            }

            tagManaged(npc);
            wakeNpc(npc, "npcspawn-adopted");
            ensureTownSkinApplied(npc, "npcspawn-adopted");
        }
    }

    /* ============================================================
     *  Player scan wake-up (loaded chunks only)
     * ============================================================ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        // Throttle scans
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        long last = lastWakeScanMs.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < wakePlayerScanCooldownMs) return;
        lastWakeScanMs.put(p.getUniqueId(), now);

        wakeNearPlayer(p);
    }

    private void wakeNearPlayer(Player p) {
        Location pl = p.getLocation();
        World w = pl.getWorld();
        if (w == null) return;

        int woke = 0;
        int scanned = 0;

        Collection<Entity> ents = w.getNearbyEntities(pl, wakePlayerScanRadius, wakePlayerScanRadius, wakePlayerScanRadius);
        for (Entity ent : ents) {
            if (scanned++ >= wakeMaxPerScan) break;

            NPC npc = CitizensAPI.getNPCRegistry().getNPC(ent);
            if (npc == null) continue;
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;

            if (isManagedNpc(npc)) {
                if (wakeNpc(npc, "proximity")) woke++;
                continue;
            }

            if (looksLikeLegacyVtfByName(npc)) {
                if (adoptByNameNearSitesOnly) {
                    Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
                    if (near == null) continue;
                }
                tagManaged(npc);
                if (wakeNpc(npc, "proximity-adopt")) woke++;
            }
        }
    }

    /* ============================================================
     *  Interaction (simple baseline dialogue)
     * ============================================================ */

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractEntityEvent e) {
        if (!enabled) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(e.getRightClicked());
        if (npc == null || !npc.isSpawned()) return;
        if (!isManagedNpc(npc) || isExcludedNpc(npc)) return;

        wakeNpc(npc, "interact");

        if (!interactiveDialogueEnabled) {
            e.getPlayer().sendMessage(ChatColor.GRAY + pickDialogue(npc));
            return;
        }

        beginConversation(e.getPlayer(), npc);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        org.bukkit.entity.Entity ent = event.getEntity();
        if (!isVtfManagedEntity(ent)) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        World.Environment fromEnv = (from != null && from.getWorld() != null) ? from.getWorld().getEnvironment() : null;
        World.Environment toEnv = (to != null && to.getWorld() != null) ? to.getWorld().getEnvironment() : null;

        // Nether portal travel (either direction)
        if (!allowNetherPortals && (fromEnv == World.Environment.NETHER || toEnv == World.Environment.NETHER)) {
            event.setCancelled(true);
            return;
        }

        // End portal / gateway travel (either direction)
        if (!allowEndPortals && (fromEnv == World.Environment.THE_END || toEnv == World.Environment.THE_END)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        // Citizens NPCs can sometimes fire PlayerPortalEvent if their entity type is PLAYER
        Player player = event.getPlayer();
        if (player == null) return;
        if (!isVtfManagedEntity(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        World.Environment fromEnv = (from != null && from.getWorld() != null) ? from.getWorld().getEnvironment() : null;
        World.Environment toEnv = (to != null && to.getWorld() != null) ? to.getWorld().getEnvironment() : null;

        if (!allowNetherPortals && (fromEnv == World.Environment.NETHER || toEnv == World.Environment.NETHER)) {
            event.setCancelled(true);
            return;
        }

        if (!allowEndPortals && (fromEnv == World.Environment.THE_END || toEnv == World.Environment.THE_END)) {
            event.setCancelled(true);
        }
    }



    private String pickDialogue(NPC npc) {
        boolean night = false;
        Entity ent = npc.getEntity();
        if (ent != null && ent.getWorld() != null) {
            long t = ent.getWorld().getTime();
            night = (t >= 13000 && t <= 23000);
        }

        String role = getRole(ent);
        List<String> lines = new ArrayList<>();

        if (night) {
            lines.add("Keep your voice down. The dark carries.");
            lines.add("Out late, are we?");
            lines.add("Stay near the light.");
        } else {
            lines.add("Roads are safer than the woods.");
            lines.add("If you’re lost, you’re not the first.");
            lines.add("Some places aren’t on the signposts.");
        }

        if ("guard".equals(role)) lines.add("No trouble today.");
        if ("worker".equals(role)) lines.add("Hands are busy. Speak quick.");
        if ("priest".equals(role)) lines.add("Prayers don’t stop everything. Just the worst of it.");
        if ("scholar".equals(role)) lines.add("History repeats. Names change. The shape stays.");

        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    String pickFarewell(String role) {
        List<String> lines = new ArrayList<>();
        lines.add("Stay safe out there.");
        lines.add("Mind the roads.");
        lines.add("Until we meet again.");

        if ("guard".equals(role)) {
            lines.add("Move along.");
            lines.add("Keep your head down.");
            lines.add("No trouble, understood?");
        } else if ("worker".equals(role)) {
            lines.add("Back to it, then.");
            lines.add("Work waits for no one.");
        } else if ("priest".equals(role)) {
            lines.add("May order keep you.");
            lines.add("Walk in the light.");
        } else if ("scholar".equals(role)) {
            lines.add("Come back with questions.");
            lines.add("Knowledge favors the curious.");
        } else if ("dockhand".equals(role)) {
            lines.add("Tide’s turning. I’ve work.");
            lines.add("Watch your step on the boards.");
        } else if ("ranger".equals(role)) {
            lines.add("Don’t stray from the paths.");
            lines.add("The woods listen.");
        } else if ("hermit".equals(role)) {
            lines.add("Leave me to my quiet.");
            lines.add("Go. Before the wind changes.");
        }

        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }


    
    private void startConversationCleanupTask() {
        if (conversationCleanupTask != null) {
            try { conversationCleanupTask.cancel(); } catch (Throwable ignored) {}
            conversationCleanupTask = null;
        }

        // Every second, expire stale sessions so NPCs stop "staring" if the player goes silent.
        conversationCleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            List<UUID> toEnd = new ArrayList<>();

            synchronized (conversations) {
                for (Map.Entry<UUID, ConversationSession> e : conversations.entrySet()) {
                    UUID playerId = e.getKey();
                    ConversationSession s = e.getValue();
                    if (s == null) { toEnd.add(playerId); continue; }
                    if (s.expiresAtMs <= now) { toEnd.add(playerId); continue; }
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) { toEnd.add(playerId); }
                }
            }

            for (UUID playerId : toEnd) {
                endConversation(playerId, false, "(They fall silent.)");
            }
        }, 40L, 20L);
    }

    private void endConversation(UUID playerId, boolean sendMessage, String message) {
        if (playerId == null) return;

        ConversationSession session;
        synchronized (conversations) {
            session = conversations.remove(playerId);
        }
        if (session == null) return;

        session.lockEnabled = false;

        if (session.lookTask != null) {
            try { session.lookTask.cancel(); } catch (Throwable ignored) {}
            session.lookTask = null;
        }

        if (sendMessage) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline() && message != null && !message.isBlank()) {
                p.sendMessage(ChatColor.DARK_GRAY + message);
            }
        }
    }

    private BukkitTask startNpcLookTask(Player player, NPC npc, ConversationSession session) {
    if (player == null || npc == null || session == null) return null;

    // Lock the NPC in place and keep them facing the player while the conversation is active.
    session.lockEnabled = true;

    return Bukkit.getScheduler().runTaskTimer(this, () -> {
        if (!session.lockEnabled) return;

        if (!player.isOnline()) {
            endConversation(player.getUniqueId(), false, null);
            return;
        }
        if (!npc.isSpawned() || npc.getEntity() == null) {
            endConversation(player.getUniqueId(), false, null);
            return;
        }
        if (session.expiresAtMs <= System.currentTimeMillis()) {
            endConversation(player.getUniqueId(), true, "(No reply.)");
            return;
        }

        try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}

        Entity ent = npc.getEntity();
        if (ent instanceof LivingEntity) {
            try {
                Vector vel = ((LivingEntity) ent).getVelocity();
                ((LivingEntity) ent).setVelocity(new Vector(0, vel.getY(), 0));
            } catch (Throwable ignored) {}
        }

        // Citizens rotates NPCs without needing teleport tricks.
        try { npc.faceLocation(player.getLocation()); } catch (Throwable ignored) {}
    }, 1L, 1L);
}

@EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        if (!enabled) return;
        if (!interactiveDialogueEnabled) return;

        UUID playerId = e.getPlayer().getUniqueId();
        ConversationSession session;
        synchronized (conversations) {
            session = conversations.get(playerId);
        }
        if (session == null) return;

        long now = System.currentTimeMillis();
        if (session.expiresAtMs < now) {
            Bukkit.getScheduler().runTask(this, () -> endConversation(playerId, false, null));
            return;
        }

        // Prevent public chat spam while in a conversation.
        e.setCancelled(true);

        String msgRaw = e.getMessage();
        String msg = (msgRaw == null) ? "" : msgRaw.trim().toLowerCase(Locale.ROOT);
        if (msg.isBlank()) return;

        Bukkit.getScheduler().runTask(this, () -> {
            try { continueConversation(e.getPlayer(), session, msg); } catch (Throwable ignored) {}
        });
    }

    private void beginConversation(Player player, NPC npc) {
        if (player == null || npc == null || !npc.isSpawned()) return;

        // If the player was already talking to someone, end that session cleanly.
        endConversation(player.getUniqueId(), false, null);

        Entity ent = npc.getEntity();
        if (ent == null) return;

        // Ensure identity data exists (back-compat and new spawns)
        ensureIdentityAndName(npc, ent, false);

        String role = getRole(ent);
        String subrole = getSubrole(ent);
        String who = npc.getName();

        Site origin = getSite(ent);
        Site near = findNearestSite(ent.getLocation(), 4096);
        String originName = (origin != null) ? origin.name : (near != null ? near.name : "these lands");
        String nearName = (near != null) ? near.name : originName;

        String opener = buildOpenerLine(role, subrole, originName, nearName);
        player.sendMessage(ChatColor.GRAY + who + ": " + opener);
        player.sendMessage(ChatColor.DARK_GRAY + "(Reply with: rumors, directions, work, empire, or bye)");

        long exp = System.currentTimeMillis() + (long) dialogueSessionSeconds * 1000L;
        ConversationSession sess = new ConversationSession(npc.getId(), "root", 0, exp);
        synchronized (conversations) {
            conversations.put(player.getUniqueId(), sess);
        }
        sess.lookTask = startNpcLookTask(player, npc, sess);
    }

    private void continueConversation(Player player, ConversationSession session, String msg) {
        if (player == null || session == null) return;

        NPC npc = CitizensAPI.getNPCRegistry().getById(session.npcId);
        if (npc == null || !npc.isSpawned()) {
            synchronized (conversations) {
                conversations.remove(player.getUniqueId());
            }
            player.sendMessage(ChatColor.DARK_GRAY + "(No response.)");
            return;
        }

        Entity ent = npc.getEntity();
        if (ent == null) return;
        ensureIdentityAndName(npc, ent, false);

        String role = getRole(ent);
        String subrole = getSubrole(ent);
        Site origin = getSite(ent);
        Site near = findNearestSite(ent.getLocation(), 4096);
        String originName = (origin != null) ? origin.name : (near != null ? near.name : "these lands");
        String nearName = (near != null) ? near.name : originName;

        // Extend session
        session.expiresAtMs = System.currentTimeMillis() + (long) dialogueSessionSeconds * 1000L;

        if (containsAny(msg, "bye", "goodbye", "farewell", "later", "leave", "stop")) {
            player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + pickFarewell(role));
            endConversation(player.getUniqueId(), false, null);
            return;
        }

        if (containsAny(msg, "directions", "where", "how do", "map", "road", "route")) {
            player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + directionsLine(role, originName, nearName));
            player.sendMessage(ChatColor.DARK_GRAY + "(Ask about: rumors, work, empire, or bye)");
            return;
        }

        if (containsAny(msg, "rumor", "rumors", "gossip", "hear", "news")) {
            player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + rumorLine(role, originName, nearName));
            player.sendMessage(ChatColor.DARK_GRAY + "(Ask about: directions, work, empire, or bye)");
            return;
        }

        if (containsAny(msg, "work", "job", "help", "task", "hire")) {
            player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + workLine(role, subrole, originName, nearName));
            player.sendMessage(ChatColor.DARK_GRAY + "(Say: yes, no, or ask about rumors/directions)");
            session.topic = "work";
            session.step = 1;
            return;
        }

        if (session.topic.equals("work") && session.step == 1) {
            if (containsAny(msg, "yes", "yea", "yeah", "yep", "sure", "ok")) {
                player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + acceptWorkLine(role, subrole, originName, nearName));
                session.topic = "root";
                session.step = 0;
                player.sendMessage(ChatColor.DARK_GRAY + "(Ask about: rumors, directions, empire, or bye)");
                return;
            }
            if (containsAny(msg, "no", "nah", "nope")) {
                player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + declineWorkLine(role));
                session.topic = "root";
                session.step = 0;
                player.sendMessage(ChatColor.DARK_GRAY + "(Ask about: rumors, directions, empire, or bye)");
                return;
            }
        }

        if (containsAny(msg, "empire", "viridia", "law", "order", "king", "queen", "emperor", "tax")) {
            player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + empireLine(role, originName, nearName));
            player.sendMessage(ChatColor.DARK_GRAY + "(Ask about: rumors, directions, work, or bye)");
            return;
        }

        player.sendMessage(ChatColor.GRAY + npc.getName() + ": " + fallbackLine(role));
        player.sendMessage(ChatColor.DARK_GRAY + "(Try: rumors, directions, work, empire, or bye)");
    }

    private boolean containsAny(String msg, String... needles) {
        if (msg == null) return false;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (msg.contains(n)) return true;
        }
        return false;
    }

    private String buildOpenerLine(String role, String subrole, String originName, String nearName) {
        boolean mentionOrigin = ThreadLocalRandom.current().nextDouble() < 0.70;
        String place = mentionOrigin ? originName : nearName;

        if ("guard".equals(role)) {
            return "Keep the peace and mind your hands. The watch answers for " + place + ".";
        }
        if ("priest".equals(role)) {
            return "Peace upon you. The lectern at " + place + " never lacks a seeker.";
        }
        if ("scholar".equals(role)) {
            return "If you have questions, speak plain. Records from " + place + " are... incomplete.";
        }
        if ("dockhand".equals(role)) {
            return "Winds shift fast near " + place + ". Don’t linger by the water at night.";
        }
        if ("worker".equals(role)) {
            if ("farmer".equalsIgnoreCase(subrole)) return "Fields don't tend themselves. " + place + " eats what we grow.";
            if ("smith".equalsIgnoreCase(subrole)) return "Iron is honest if you treat it right. " + place + " needs nails and blades.";
            if ("mason".equalsIgnoreCase(subrole)) return "Stone remembers. " + place + " is built on careful cuts.";
            if ("carpenter".equalsIgnoreCase(subrole)) return "Wood is kinder than stone. " + place + " still creaks in the wind.";
            return "Work is work. Speak quick.";
        }

        if ("ranger".equals(role)) {
            return "Trail’s thin beyond " + place + ". Keep to paths if you value daylight.";
        }
        if ("hermit".equals(role)) {
            return "I keep my counsel. But you’ve walked far, and that counts for something.";
        }
        return "You look like you’ve got a question. Ask.";
    }

    private String rumorLine(String role, String originName, String nearName) {
        String place = (ThreadLocalRandom.current().nextDouble() < 0.55) ? nearName : originName;
        List<String> r = new ArrayList<>();
        r.add("They say old tunnels under " + place + " still breathe at night.");
        r.add("A wagon went missing on the road to " + place + ". No tracks, just... silence.");
        r.add("Someone keeps lighting candles where no shrine stands. Near " + place + ".");
        if ("guard".equals(role)) r.add("If you hear bells with no hands to pull them, leave the street.");
        if ("priest".equals(role)) r.add("Prayer helps, but locks help more. Keep both.");
        if ("scholar".equals(role)) r.add("There are pages torn from the register. Not burned. Taken.");
        return r.get(ThreadLocalRandom.current().nextInt(r.size()));
    }

    private String directionsLine(String role, String originName, String nearName) {
        String to = (ThreadLocalRandom.current().nextDouble() < 0.50) ? nearName : originName;
        if ("ranger".equals(role)) {
            return "Follow the worn ground and keep the sun on your shoulder. " + to + " lies where the paths thicken.";
        }
        return "If you can see a marker for " + to + ", you’re already close. Keep to the travelled ground.";
    }

    private String workLine(String role, String subrole, String originName, String nearName) {
        String place = (ThreadLocalRandom.current().nextDouble() < 0.55) ? nearName : originName;
        if ("guard".equals(role)) return "Keep your eyes open near " + place + ". If you see trouble, shout before you swing.";
        if ("priest".equals(role)) return "Bring candles or books to the lectern at " + place + ". Order matters.";
        if ("scholar".equals(role)) return "If you find odd carvings or fragments, report them. Names and dates, not stories.";
        if ("worker".equals(role)) {
            if ("farmer".equalsIgnoreCase(subrole)) return "If crops are ripe, harvest and leave seed behind. We replant what we take.";
            if ("smith".equalsIgnoreCase(subrole)) return "Coal and iron. That’s all a forge asks. Bring what you find.";
            if ("mason".equalsIgnoreCase(subrole)) return "Clear rubble and keep stone stacked. " + place + " is always settling.";
            if ("carpenter".equalsIgnoreCase(subrole)) return "Planks, fences, hinges. Keep doors standing.";
            return "Haul what you can and don’t break what you can’t replace.";
        }
        return "Help where you can. " + place + " remembers who does.";
    }

    private String acceptWorkLine(String role, String subrole, String originName, String nearName) {
        if ("guard".equals(role)) return "Good. Keep your blade down until it’s needed.";
        if ("priest".equals(role)) return "Then go with steadiness. Speak with respect and listen twice.";
        if ("scholar".equals(role)) return "Bring facts. I can’t use fear.";
        if ("worker".equals(role)) {
            if ("farmer".equalsIgnoreCase(subrole)) return "Harvest clean. Replant what you cut. Store what you spare.";
            return "Keep your hands busy and your eyes open.";
        }
        return "Fair enough. Walk safe.";
    }

    private String declineWorkLine(String role) {
        if ("guard".equals(role)) return "Then don’t make my job harder.";
        if ("priest".equals(role)) return "No shame in caution. Just don’t mock what you don’t understand.";
        return "Suit yourself.";
    }

    private String empireLine(String role, String originName, String nearName) {
        List<String> lines = new ArrayList<>();
        lines.add("Viridia holds because law holds. Chaos is easy. Order is built.");
        lines.add("The Empire isn’t just banners. It’s roads, ledgers, and the promise that someone answers for harm.");
        lines.add("We’re not perfect. But we’re still here, and that counts.");
        if ("guard".equals(role)) lines.add("If you break the peace, you answer. Simple as that.");
        if ("priest".equals(role)) lines.add("Belief without duty is noise. Duty without belief is rot.");
        if ("scholar".equals(role)) lines.add("Empires fall when they forget their records. That’s why I write.");
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    private String fallbackLine(String role) {
        if ("hermit".equals(role)) return "Words are cheap. Leave me.";
        if ("guard".equals(role)) return "I don’t have time for riddles. Speak plain.";
        return "I’m not sure what you mean. Try asking for rumors, directions, work, or the Empire.";
    }

    /* ============================================================
     *  Wake/rehook and autonomy
     * ============================================================ */

    private int wakeAllSpawned(String reason) {
        int woke = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;

            if (isManagedNpc(npc)) {
                if (wakeNpc(npc, reason)) woke++;
                continue;
            }

            if (looksLikeLegacyVtfByName(npc)) {
                if (adoptByNameNearSitesOnly) {
                    Entity ent = npc.getEntity();
                    if (ent == null) continue;
                    Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
                    if (near == null) continue;
                }
                tagManaged(npc);
                if (wakeNpc(npc, reason + "-adopt")) woke++;
            }
        }
        return woke;
    }

    private boolean wakeNpc(NPC npc, String reason) {
        if (npc == null || !npc.isSpawned()) return false;
        if (!isManagedNpc(npc) || isExcludedNpc(npc)) return false;

        Entity ent = npc.getEntity();
        if (ent == null) return false;

        applyNpcCollisionPolicy(npc);

        applyNameplatePolicy(ent);
        ensureRoleAndSite(ent, npc.getName());
        ensureIdentityAndName(npc, ent, false);

        int id = npc.getId();
        if (!autonomyTasks.containsKey(id)) {
            long periodSeconds = 4 + ThreadLocalRandom.current().nextInt(6);
            BukkitTask t = Bukkit.getScheduler().runTaskTimer(this, () -> autonomyTick(npc), 20L, 20L * periodSeconds);
            autonomyTasks.put(id, t);
        }

        return true;
    }

    private void autonomyTick(NPC npc) {
        if (npc == null || !npc.isSpawned()) return;
        if (!isManagedNpc(npc) || isExcludedNpc(npc)) return;

        Entity ent = npc.getEntity();
        if (!(ent instanceof LivingEntity)) return;

        LivingEntity le = (LivingEntity) ent;
        Location base = le.getLocation();
        World w = base.getWorld();
        if (w == null) return;

        // If navigating already, usually leave it
        if (npc.getNavigator().isNavigating() && ThreadLocalRandom.current().nextDouble() < 0.70) return;

        // Prefer returning to site if too far
        Site site = getSite(ent);
        if (site != null && w.getName().equalsIgnoreCase(site.worldName)) {
            Location center = site.center(w);
            double dist2 = center.distanceSquared(base);
            if (dist2 > (double) returnToSiteDistance * (double) returnToSiteDistance) {
                Location target = findSafeGroundNear(center, 12);
                if (target != null) {
                    npc.getNavigator().setTarget(target);
                    return;
                }
            }
        }

        boolean night = isNight(w);
        String role = getRole(ent);

        // Night bias: less wandering for non-guards
        double r = ThreadLocalRandom.current().nextDouble();
        if (night && !"guard".equals(role) && r < 0.45) {
            idleLook(le);
            return;
        }

        int roam = roamRadiusForRole(role);
        Location anchor = (site != null && w.getName().equalsIgnoreCase(site.worldName)) ? site.center(w) : base;

        // Mostly roam around the site anchor (or current position if no site)
        Location target = findSafeGroundNear(anchor, roam);
        if (target != null) {
            npc.getNavigator().setTarget(target);
        } else {
            idleLook(le);
        }
    }

    private int roamRadiusForRole(String role) {
        switch (role) {
            case "guard": return roamRadiusGuard;
            case "worker": return roamRadiusWorker;
            case "priest": return roamRadiusPriest;
            case "scholar": return roamRadiusScholar;
            case "ranger": return roamRadiusRanger;
            case "hermit": return roamRadiusHermit;
            default: return roamRadiusTownsfolk;
        }
    }

    private static boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000 && t <= 23000;
    }

    private void idleLook(LivingEntity le) {
        Location l = le.getLocation();
        float yaw = l.getYaw() + (ThreadLocalRandom.current().nextFloat() * 80f - 40f);
        l.setYaw(yaw);
        try { le.teleport(l); } catch (Throwable ignored) {}
    }

    /* ============================================================
     *  Population (crash-safe: only near players, no far chunk touches)
     * ============================================================ */

    private void startPopulationMaintainer() {
        if (populationTask != null) populationTask.cancel();
        populationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try { queuePopulationCycle("cycle"); } catch (Throwable ignored) {}
        }, 20L * 15L, 20L * populationCycleSeconds);
    }

    private void startSpawnQueueWorker() {
        if (spawnQueueTask != null) spawnQueueTask.cancel();
        spawnQueueTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int n = Math.max(1, populationSpawnPerTick);
            while (n-- > 0) {
                Runnable r = spawnQueue.pollFirst();
                if (r == null) break;
                try { r.run(); } catch (Throwable ignored) {}
            }
        }, 1L, 1L);
    }

    private void startBehaviorPulse() {
        if (behaviorPulseTask != null) behaviorPulseTask.cancel();

        // Run fairly often, but cap work each pulse.
        behaviorPulseTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                behaviorPulseTick();
            } catch (Throwable ignored) {
            }
        }, 5L, 5L);
    }


    void startApproxServerTickCounter() {
        if (approxServerTickTask != null) {
            approxServerTickTask.cancel();
            approxServerTickTask = null;
        }
        // Count ticks on the main thread. This is an approximation but stable enough for timeouts and door auto-close.
        approxServerTickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            approxServerTick++;
        }, 1L, 1L);
    }

    long getApproxServerTick() {
        return approxServerTick;
    }

    private void behaviorPulseTick() {
        int max = Math.max(4, behaviorPulsePerTick);

        // Snapshot ids to avoid iterator oddities if Citizens changes under us.
        List<NPC> spawned = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc) || isExcludedNpc(npc)) continue;
            spawned.add(npc);
        }
        if (spawned.isEmpty()) return;

        int size = spawned.size();
        if (behaviorPulseCursor >= size) behaviorPulseCursor = 0;

        long nowTick = getApproxServerTick();

        for (int i = 0; i < max && i < size; i++) {
            NPC npc = spawned.get(behaviorPulseCursor);
            behaviorPulseCursor = (behaviorPulseCursor + 1) % size;
            pulseNpc(npc, nowTick);
        }

        // close any previously opened doors/gates whose timers expired
        closeExpiredOpenables(nowTick);
    }

    private void pulseNpc(NPC npc, long nowTick) {
        if (npc == null || !npc.isSpawned()) return;
        Entity ent = npc.getEntity();
        if (!(ent instanceof LivingEntity)) return;

        LivingEntity living = (LivingEntity) ent;
        Location loc = living.getLocation();

        // If this NPC is currently idling (lingering at a job POI), keep them planted.
        try {
            PersistentDataContainer pdc = living.getPersistentDataContainer();
            Long idleUntil = pdc.get(KEY_VTF_IDLE_UNTIL, PersistentDataType.LONG);
            if (idleUntil != null && idleUntil > nowTick) {
                try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}
                return;
            }

            // If we previously set a target and they have arrived (navigation ended), start a linger window.
            Byte expect = pdc.get(KEY_VTF_EXPECT_IDLE, PersistentDataType.BYTE);
            if ((expect != null && expect == (byte) 1) && !npc.getNavigator().isNavigating()) {
                String roleTmp = getRole(living);
                int baseIdle = 80;
                if ("priest".equals(roleTmp) || "scholar".equals(roleTmp)) baseIdle = 140;
                else if ("guard".equals(roleTmp)) baseIdle = 100;
                long linger = baseIdle + ThreadLocalRandom.current().nextInt(120);
                pdc.set(KEY_VTF_IDLE_UNTIL, PersistentDataType.LONG, nowTick + linger);
                pdc.set(KEY_VTF_EXPECT_IDLE, PersistentDataType.BYTE, (byte) 0);
            }
        } catch (Throwable ignored) {}

        // Swimming assistance (light nudge)
        tryAssistSwimming(living);

        // Door/gate assist when navigating
        if (npc.getNavigator().isNavigating()) {
            tryOpenDoorOrGateAhead(living, nowTick);
        }

        // Lightweight role routine hints (POI bias) - only occasionally
        if (ThreadLocalRandom.current().nextDouble() < 0.12) {
            tryRoleRoutineStep(npc, living, loc);
        }
    }

    private void closeExpiredOpenables(long nowTick) {
        if (openedBlocksCloseAtTick.isEmpty()) return;

        Iterator<Map.Entry<String, Long>> it = openedBlocksCloseAtTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() == null || e.getValue() > nowTick) continue;

            String key = e.getKey();
            it.remove();
            Block block = blockFromKey(key);
            if (block == null) continue;
            tryCloseOpenable(block);
        }
    }

    private void tryAssistSwimming(LivingEntity living) {
        try {
            Material in = living.getLocation().getBlock().getType();
            if (in != Material.WATER) return;

            Vector vel = living.getVelocity();
            // Small upward bias to keep head above water.
            double vy = Math.max(vel.getY(), 0.08);
            living.setVelocity(new Vector(vel.getX(), vy, vel.getZ()));
        } catch (Throwable ignored) {
        }
    }

    private void tryOpenDoorOrGateAhead(LivingEntity living, long nowTick) {
        Location l = living.getLocation();
        World w = l.getWorld();
        if (w == null) return;

        Vector dir = l.getDirection();
        if (dir.lengthSquared() < 0.0001) return;
        dir = dir.normalize();

        // Check a spot about 1 block ahead at foot level.
        int cx = l.getBlockX() + (int) Math.round(dir.getX());
        int cy = l.getBlockY();
        int cz = l.getBlockZ() + (int) Math.round(dir.getZ());

        Block b0 = w.getBlockAt(cx, cy, cz);
        Block b1 = w.getBlockAt(cx, cy + 1, cz);

        if (!tryOpenIfOpenable(b0, nowTick)) {
            tryOpenIfOpenable(b1, nowTick);
        }
    }

    private boolean tryOpenIfOpenable(Block block, long nowTick) {
        if (block == null) return false;
        try {
            BlockData data = block.getBlockData();
            if (!(data instanceof Openable)) return false;
            Openable openable = (Openable) data;
            if (openable.isOpen()) return false;

            // Only open doors and gates (not trapdoors, etc.)
            if (!(data instanceof Door) && !(data instanceof Gate)) return false;

            openable.setOpen(true);
            block.setBlockData(openable, true);

            // Close again in ~3-5 seconds.
            long closeAt = nowTick + 60L + ThreadLocalRandom.current().nextInt(40);
            openedBlocksCloseAtTick.put(keyForBlock(block), closeAt);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void tryCloseOpenable(Block block) {
        try {
            BlockData data = block.getBlockData();
            if (!(data instanceof Openable)) return;
            if (!(data instanceof Door) && !(data instanceof Gate)) return;
            Openable openable = (Openable) data;
            if (!openable.isOpen()) return;
            openable.setOpen(false);
            block.setBlockData(openable, true);
        } catch (Throwable ignored) {
        }
    }

    private String keyForBlock(Block block) {
        if (block == null || block.getWorld() == null) return null;
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private Block blockFromKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(":");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        int x = parseIntSafe(parts[1], Integer.MIN_VALUE);
        int y = parseIntSafe(parts[2], Integer.MIN_VALUE);
        int z = parseIntSafe(parts[3], Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) return null;
        return w.getBlockAt(x, y, z);
    }

    private void tryRoleRoutineStep(NPC npc, LivingEntity living, Location loc) {
        if (npc == null || living == null || loc == null) return;

        // Don't override an in-progress navigation too aggressively.
        if (npc.getNavigator().isNavigating() && ThreadLocalRandom.current().nextDouble() < 0.65) return;

        String role = getRole(living);
        String subrole = getSubrole(living);

        Site site = getSite(living);
        Location anchor = (site != null && loc.getWorld() != null && site.worldName.equalsIgnoreCase(loc.getWorld().getName())) ? site.center(loc.getWorld()) : loc;

        Location target = null;

        // Role-specific POI bias, but only in loaded chunks.
        if ("priest".equals(role)) {
            target = findPoiNearLoaded(anchor, 22, Arrays.asList(Material.LECTERN, Material.BELL, Material.BOOKSHELF, Material.CANDLE));
        } else if ("scholar".equals(role)) {
            target = findPoiNearLoaded(anchor, 22, Arrays.asList(Material.LECTERN, Material.BOOKSHELF, Material.CARTOGRAPHY_TABLE));
        } else if ("dockhand".equals(role)) {
            target = findDockPoiNearLoaded(anchor, 26);
        } else if ("guard".equals(role)) {
            target = findBuiltHotspotNearLoaded(anchor, 26);
        } else if ("ranger".equals(role)) {
            target = findEdgeHotspotNearLoaded(anchor, 34);
        } else if ("worker".equals(role)) {
            if ("farmer".equalsIgnoreCase(subrole)) {
                target = findPoiNearLoaded(anchor, 24, Arrays.asList(Material.FARMLAND, Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.COMPOSTER));
            } else {
                target = findPoiNearLoaded(anchor, 24, Arrays.asList(Material.CRAFTING_TABLE, Material.ANVIL, Material.SMITHING_TABLE, Material.STONECUTTER, Material.GRINDSTONE, Material.FURNACE, Material.BLAST_FURNACE));
            }
        } else {
            // Townsfolk/hermit baseline: go where builds are.
            target = findBuiltHotspotNearLoaded(anchor, 24);
        }

        if (target == null) {
            // fallback to safe ground around anchor
            target = findSafeGroundNearLoaded(anchor, roamRadiusForRole(role));
        }


if (target != null) {
    try {
        PersistentDataContainer pdc = living.getPersistentDataContainer();
        pdc.set(KEY_VTF_EXPECT_IDLE, PersistentDataType.BYTE, (byte) 1);
    } catch (Throwable ignored) {}
    npc.getNavigator().setTarget(target);
}
    }

    private Location findPoiNearLoaded(Location base, int radius, List<Material> targets) {
        if (base == null || base.getWorld() == null) return null;
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        // Sample random spots; only examine loaded chunks.
        for (int tries = 0; tries < 160; tries++) {
            int x = bx + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = bz + ThreadLocalRandom.current().nextInt(-radius, radius + 1);

            Chunk c = w.getChunkAt(x >> 4, z >> 4);
            if (!c.isLoaded()) continue;

            int topY = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, topY, z);

            // If the ground itself is a POI, stand near it.
            if (targets.contains(ground.getType())) {
                Location stand = new Location(w, x + 0.5, topY + 1, z + 0.5);
                if (isStandable(stand)) return stand;
            }

            // Also check a small neighborhood around the topY.
            for (int dy = -1; dy <= 2; dy++) {
                Block b = w.getBlockAt(x, topY + dy, z);
                if (targets.contains(b.getType())) {
                    Location stand = b.getLocation().add(0.5, 1.0, 0.5);
                    if (isStandable(stand)) return stand;
                }
            }
        }
        return null;
    }

    private Location findDockPoiNearLoaded(Location base, int radius) {
        if (base == null || base.getWorld() == null) return null;
        World w = base.getWorld();
        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        for (int tries = 0; tries < 180; tries++) {
            int x = bx + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = bz + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            Chunk c = w.getChunkAt(x >> 4, z >> 4);
            if (!c.isLoaded()) continue;

            int topY = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, topY, z);

            // Prefer wood slabs/planks near water.
            Material g = ground.getType();
            if (!(g.name().endsWith("_PLANKS") || g.name().endsWith("_SLAB") || g.name().endsWith("_STAIRS"))) continue;

            boolean nearWater = false;
            for (int ox = -2; ox <= 2 && !nearWater; ox++) {
                for (int oz = -2; oz <= 2 && !nearWater; oz++) {
                    Material m = w.getBlockAt(x + ox, topY, z + oz).getType();
                    if (m == Material.WATER) nearWater = true;
                }
            }
            if (!nearWater) continue;

            Location stand = new Location(w, x + 0.5, topY + 1, z + 0.5);
            if (isStandable(stand)) return stand;
        }
        return null;
    }

    private Location findBuiltHotspotNearLoaded(Location base, int radius) {
        if (base == null || base.getWorld() == null) return null;
        World w = base.getWorld();
        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        Location best = null;
        int bestScore = 0;

        for (int tries = 0; tries < 40; tries++) {
            int x = bx + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = bz + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            Chunk c = w.getChunkAt(x >> 4, z >> 4);
            if (!c.isLoaded()) continue;
            int topY = w.getHighestBlockYAt(x, z);
            Location stand = new Location(w, x + 0.5, topY + 1, z + 0.5);
            if (!isStandable(stand)) continue;

            int score = builtDensityScore(w, x, topY, z);
            if (score > bestScore) {
                bestScore = score;
                best = stand;
            }
        }

        return (bestScore >= 6) ? best : null;
    }

    private Location findEdgeHotspotNearLoaded(Location base, int radius) {
        // Ranger: prefer a moderate built score (edge), not the densest core.
        if (base == null || base.getWorld() == null) return null;
        World w = base.getWorld();
        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        Location best = null;
        int bestDistBias = -999999;

        for (int tries = 0; tries < 50; tries++) {
            int x = bx + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = bz + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            Chunk c = w.getChunkAt(x >> 4, z >> 4);
            if (!c.isLoaded()) continue;
            int topY = w.getHighestBlockYAt(x, z);
            Location stand = new Location(w, x + 0.5, topY + 1, z + 0.5);
            if (!isStandable(stand)) continue;

            int score = builtDensityScore(w, x, topY, z);
            if (score < 3 || score > 10) continue;

            int dist = (int) Math.round(Math.sqrt((x - bx) * (double) (x - bx) + (z - bz) * (double) (z - bz)));
            int bias = dist * 2 + score; // prefer further out, but still somewhat built

            if (bias > bestDistBias) {
                bestDistBias = bias;
                best = stand;
            }
        }

        return best;
    }

    private int builtDensityScore(World w, int x, int y, int z) {
        int score = 0;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                Material m = w.getBlockAt(x + ox, y, z + oz).getType();
                String n = m.name();

                if (n.endsWith("_PLANKS") || n.endsWith("_LOG") || n.contains("STRIPPED_")) score += 2;
                else if (n.contains("BRICKS") || n.contains("STONE_BRICKS") || n.equals("COBBLESTONE")) score += 2;
                else if (m == Material.GLASS || n.endsWith("_GLASS")) score += 2;
                else if (m == Material.LANTERN || m == Material.TORCH || n.endsWith("_TORCH")) score += 2;
                else if (m == Material.CHEST || m == Material.BARREL) score += 2;
                else if (m == Material.DIRT_PATH || m == Material.GRAVEL || m == Material.COBBLESTONE) score += 1;
                else if (n.contains("FENCE") || n.contains("DOOR") || n.contains("GATE")) score += 1;
            }
        }
        return score;
    }

    private boolean isStandable(Location stand) {
        if (stand == null || stand.getWorld() == null) return false;
        World w = stand.getWorld();
        int x = stand.getBlockX();
        int y = stand.getBlockY();
        int z = stand.getBlockZ();
        Material a0 = w.getBlockAt(x, y, z).getType();
        Material a1 = w.getBlockAt(x, y + 1, z).getType();
        Material ground = w.getBlockAt(x, y - 1, z).getType();
        if (!a0.isAir() || !a1.isAir()) return false;
        if (ground == Material.WATER || ground == Material.LAVA) return false;
        return true;
    }

    private int queuePopulationCycle(String reason) {
        if (sites.isEmpty()) return 0;

        // Determine which sites are "active" (player nearby) without touching chunks
        List<Site> active = new ArrayList<>();
        for (Site s : sites) {
            World w = Bukkit.getWorld(s.worldName);
            if (w == null) continue;

            Location center = s.center(w);
            if (hasPlayerNear(center, populationSiteActivationRadius)) {
                active.add(s);
            }
        }

        if (active.isEmpty()) return 0;

        Collections.shuffle(active);

        int queued = 0;
        for (Site s : active) {
            if (queued >= populationMaxQueuePerCycle) break;

            World w = Bukkit.getWorld(s.worldName);
            if (w == null) continue;

            Location center = s.center(w);

            int current = countManagedNear(center, populationRadius);
            if (current >= populationMaxPerSite) continue;

            int need = Math.max(0, populationMinPerSite - current);
            if (need <= 0) continue;

            int add = Math.min(need, populationMaxQueuePerCycle - queued);
            for (int i = 0; i < add; i++) {
                Site site = s;
                spawnQueue.addLast(() -> spawnOneNearSite(site));
                queued++;
                if (queued >= populationMaxQueuePerCycle) break;
            }
        }

        if (queued > 0) getLogger().info("Population queued: " + queued + " (" + reason + ")");
        return queued;
    }

    private void spawnOneNearSite(Site site) {
        World w = Bukkit.getWorld(site.worldName);
        if (w == null) return;

        World.Environment env = w.getEnvironment();
        if ((env == World.Environment.NETHER && !allowSpawningInNether) || (env == World.Environment.THE_END && !allowSpawningInEnd)) {
            return;
        }

        Location center = site.center(w);

        // Hard safety: require player still nearby
        if (!hasPlayerNear(center, populationSiteActivationRadius)) return;

        // Find a safe spawn location without loading chunks:
        // - If a candidate's chunk isn't loaded, skip it.
        Location spawnLoc = findSafeGroundNearLoaded(center, Math.min(28, populationRadius / 2));
        if (spawnLoc == null) return;

        String role = pickRole();
        String subrole = pickSubroleForRole(role);

        // Create NPC first so we can use its stable ID for deterministic skin gender choice.
        NPC npc = null;
        try {
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Townsfolk");
        } catch (Throwable ignored) { }

        if (npc == null) return;

        // Choose role-based skin key (deterministic 50/50 female if available) and then generate a matching name.
        String roleBase = roleBaseFromRole(role, subrole);
        String skinKey = pickRoleBasedSkinKeyByBaseAndId(roleBase, npc.getId());
        boolean preferFemale = isFemaleSkinKey(skinKey);

        String personal = generatePersonalName(role, subrole, preferFemale);
        String displayName = formatDisplayName(role, subrole, personal);

        try {
            npc.setName(displayName);
            applyNpcCollisionPolicy(npc);
            npc.spawn(spawnLoc);

            tagManaged(npc);
            setStoredSkinKey(npc, skinKey);
            ensureTownSkinApplied(npc, "spawn");

            Entity ent = npc.getEntity();
            if (ent != null) {
                PersistentDataContainer pdc = ent.getPersistentDataContainer();
                pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
                if (subrole != null && !subrole.isBlank()) {
                    pdc.set(KEY_VTF_SUBROLE, PersistentDataType.STRING, subrole);
                }
                pdc.set(KEY_VTF_NAME, PersistentDataType.STRING, personal);
                pdc.set(KEY_VTF_SITE, PersistentDataType.STRING, site.name);
            }

            wakeNpc(npc, "spawn");
        } catch (Throwable ignored) {
        }
    }

    private boolean hasPlayerNear(Location loc, int radius) {
        World w = loc.getWorld();
        if (w == null) return false;
        double r2 = (double) radius * radius;

        for (Player p : w.getPlayers()) {
            if (p == null) continue;
            if (!p.isOnline()) continue;
            if (p.getLocation().distanceSquared(loc) <= r2) return true;
        }
        return false;
    }

    private int countManagedNear(Location center, int radius) {
        World w = center.getWorld();
        if (w == null) return 0;

        double r2 = (double) radius * radius;
        int c = 0;

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc) || isExcludedNpc(npc)) continue;

            Entity ent = npc.getEntity();
            if (ent == null || ent.getWorld() != w) continue;
            if (ent.getLocation().distanceSquared(center) <= r2) c++;
        }
        return c;
    }

    /* ============================================================
     *  Purge
     * ============================================================ */

    private int purgeAllManaged() {
        int removed = 0;

        // Cancel tasks first
        for (BukkitTask t : autonomyTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        autonomyTasks.clear();

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc) || isExcludedNpc(npc)) continue;

            try {
                npc.despawn();
                npc.destroy();
                removed++;
            } catch (Throwable ignored) {}
        }

        return removed;
    }

    int purgeManagedInEnvironment(World.Environment env) {
        int removed = 0;

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!isManagedNpc(npc) || isExcludedNpc(npc)) continue;

            Location loc = null;
            try {
                if (npc.isSpawned() && npc.getEntity() != null) {
                    loc = npc.getEntity().getLocation();
                } else {
                    loc = npc.getStoredLocation();
                }
            } catch (Throwable ignored) {}

            if (loc == null || loc.getWorld() == null) continue;
            if (loc.getWorld().getEnvironment() != env) continue;

            try {
                if (npc.isSpawned()) {
                    npc.despawn();
                }
                npc.destroy();
                removed++;
            } catch (Throwable ignored) {}
        }

        return removed;
    }


    /* ============================================================
     *  Adoption checks
     * ============================================================ */

    private int adoptAllSpawnedLegacyByName() {
        int adopted = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;
            if (isManagedNpc(npc)) continue;

            if (!looksLikeLegacyVtfByName(npc)) continue;

            if (adoptByNameNearSitesOnly) {
                Entity ent = npc.getEntity();
                if (ent == null) continue;
                Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
                if (near == null) continue;
            }

            tagManaged(npc);
            adopted++;
        }
        return adopted;
    }

    private boolean looksLikeLegacyVtfByName(NPC npc) {
        String name = safeStrip(npc.getName());
        if (name == null) return false;

        String lower = name.toLowerCase(Locale.ROOT);
        if (isExcludedName(lower)) return false;

        for (String prefix : legacyRolePrefixes) {
            if (name.equals(prefix)) return true;
            if (name.startsWith(prefix + " ")) return true;
            if (name.startsWith(prefix + "-")) return true;
            if (name.startsWith(prefix + "_")) return true;
            if (name.startsWith(prefix + "#")) return true;
        }
        return false;
    }

    private boolean isExcludedName(String lower) {
        if (lower == null) return true;

        for (String s : excludedNameContains) {
            if (lower.contains(s)) return true;
        }
        for (String s : excludedExactish) {
            if (lower.equals(s)) return true;
        }
        return false;
    }

    private boolean isExcludedNpc(NPC npc) {
        String n = safeStrip(npc.getName());
        if (n != null && isExcludedName(n.toLowerCase(Locale.ROOT))) return true;

        if (!npc.isSpawned()) return false;
        Entity ent = npc.getEntity();
        if (ent == null) return false;

        applyNpcCollisionPolicy(npc);

        for (String tag : ent.getScoreboardTags()) {
            String tl = tag.toLowerCase(Locale.ROOT);
            if (tl.contains("wanderlore") || tl.startsWith("wl_")) return true;
            if (tl.contains("indevmobs") || tl.contains("imobs")) return true;
            if (tl.contains("herobrine")) return true;
        }

        return false;
    }

    private boolean isManagedNpc(NPC npc) {
        if (!npc.isSpawned()) return false;
        Entity ent = npc.getEntity();
        if (ent == null) return false;

        applyNpcCollisionPolicy(npc);

        if (ent.getScoreboardTags().contains(SCORE_TAG)) return true;

        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        Byte b = pdc.get(KEY_VTF_MANAGED, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private boolean isVtfManagedEntity(org.bukkit.entity.Entity ent) {
        if (ent == null) return false;
        try {
            if (ent.getScoreboardTags() != null && ent.getScoreboardTags().contains(SCORE_TAG)) return true;
        } catch (Throwable ignored) {}
        try {
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            Byte b = pdc.get(KEY_VTF_MANAGED, PersistentDataType.BYTE);
            return b != null && b == (byte) 1;
        } catch (Throwable t) {
            return false;
        }
    }


    private void tagManaged(NPC npc) {
        if (!npc.isSpawned()) return;
        Entity ent = npc.getEntity();
        if (ent == null) return;

        ent.addScoreboardTag(SCORE_TAG);

        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        pdc.set(KEY_VTF_MANAGED, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_VTF_VER, PersistentDataType.STRING, "reset-baseline");
    }

    /* ============================================================
     *  Role/site metadata
     * ============================================================ */

    private void ensureRoleAndSite(Entity ent, String nameHint) {
        if (ent == null) return;

        PersistentDataContainer pdc = ent.getPersistentDataContainer();

        String role = pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING);
        if (role == null || role.isBlank()) {
            role = inferRoleFromName(nameHint);
            if (role == null) role = "townsfolk";
            pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
        }

        String siteName = pdc.get(KEY_VTF_SITE, PersistentDataType.STRING);
        if (siteName == null || siteName.isBlank()) {
            Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
            if (near != null) pdc.set(KEY_VTF_SITE, PersistentDataType.STRING, near.name);
        }
    }

    private String getRole(Entity ent) {
        if (ent == null) return "townsfolk";
        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        String r = pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING);
        return (r == null || r.isBlank()) ? "townsfolk" : r;
    }

    private Site getSite(Entity ent) {
        if (ent == null) return null;
        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        String siteName = pdc.get(KEY_VTF_SITE, PersistentDataType.STRING);
        if (siteName == null || siteName.isBlank()) return null;
        for (Site s : sites) {
            if (s.name.equalsIgnoreCase(siteName)) return s;
        }
        return null;
    }

    private String inferRoleFromName(String rawName) {
        String name = safeStrip(rawName);
        if (name == null) return null;

        if (name.startsWith("Guard") || name.startsWith("Watchman") || name.startsWith("Sentinel")) return "guard";
        if (name.startsWith("Worker") || name.startsWith("Smith") || name.startsWith("Mason") || name.startsWith("Farmer")) return "worker";
        if (name.startsWith("Dockhand")) return "dockhand";
        if (name.startsWith("Priest") || name.startsWith("Acolyte")) return "priest";
        if (name.startsWith("Scholar") || name.startsWith("Scribe")) return "scholar";
        if (name.startsWith("Ranger")) return "ranger";
        if (name.startsWith("Hermit")) return "hermit";
        return "townsfolk";
    }

    private String pickRole() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.45) return "townsfolk";
        if (r < 0.75) return "worker";
        if (r < 0.90) return "guard";
        if (r < 0.95) return "dockhand";
        if (r < 0.98) return "priest";
        if (r < 0.995) return "scholar";
        return "ranger";
    }

    private String roleDisplay(String role) {
        switch (role) {
            case "guard": return "Guard";
            case "worker": return "Worker";
            case "dockhand": return "Dockhand";
            case "priest": return "Priest";
            case "scholar": return "Scholar";
            case "ranger": return "Ranger";
            case "hermit": return "Hermit";
            default: return "Townsfolk";
        }
    }

    private String getSubrole(Entity ent) {
        if (ent == null) return "";
        try {
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            String s = pdc.get(KEY_VTF_SUBROLE, PersistentDataType.STRING);
            return (s == null) ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String pickSubroleForRole(String role) {
        if (role == null) return "";
        switch (role) {
            case "worker": {
                // split into a few believable trades; keep "farmer" fairly common.
                double r = ThreadLocalRandom.current().nextDouble();
                if (r < 0.40) return "farmer";
                if (r < 0.60) return "smith";
                if (r < 0.78) return "mason";
                if (r < 0.90) return "carpenter";
                return "laborer";
            }
            case "guard": {
                double r = ThreadLocalRandom.current().nextDouble();
                if (r < 0.65) return "watch";
                if (r < 0.90) return "patrol";
                return "archer";
            }
            default:
                return "";
        }
    }

    private String formatDisplayName(String role, String subrole, String personalName) {
        String title = roleDisplay(role);
        if ("worker".equals(role)) {
            if ("farmer".equalsIgnoreCase(subrole)) title = "Farmer";
            else if ("smith".equalsIgnoreCase(subrole)) title = "Smith";
            else if ("mason".equalsIgnoreCase(subrole)) title = "Mason";
            else if ("carpenter".equalsIgnoreCase(subrole)) title = "Carpenter";
            else if ("scribe".equalsIgnoreCase(subrole)) title = "Scribe";
        } else if ("guard".equals(role)) {
            if ("archer".equalsIgnoreCase(subrole)) title = "Archer";
            else if ("watch".equalsIgnoreCase(subrole)) title = "Watchman";
        }

        if (personalName == null || personalName.isBlank()) {
            personalName = generatePersonalName(role, subrole);
        }

        return title + " " + personalName;
    }

    private String generatePersonalName(String role, String subrole) {
        return generatePersonalName(role, subrole, false);
    }

    private String generatePersonalName(String role, String subrole, boolean preferFemale) {
                // Medieval-ish / colonial-ish name pool.
        // Rule: if the NPC is using a *_f skin, prefer the female pool.
        // (Neutral names may appear for either.)
        String[] firstMale = new String[] {
                "Alden", "Alaric", "Bennett", "Corwin", "Edwin", "Elias", "Emmett", "Fergus", "Gideon", "Hugh",
                "Jasper", "Jonas", "Marek", "Miles", "Nolan", "Owen", "Rowan", "Silas", "Theron", "Wyatt"
        };
        String[] firstFemale = new String[] {
                "Beatrice", "Cecily", "Elowen", "Isolde", "Leona", "Lysa", "Matilda", "Mira", "Tamsin", "Willa",
                "Adelaide", "Briony", "Cordelia", "Edith", "Evelyn", "Rosamund"
        };
        String[] firstNeutral = new String[] {
                "Ash", "Quinn", "Robin", "Morgan", "Avery"
        };

        String[] last = new String[] {
                "Ashford","Barrow","Blackwood","Briggs","Cavendish","Crowley","Davenport","Dunwick","Fairchild",
                "Fletcher","Graves","Harrow","Hawthorne","Kell","Lancaster","Marrow","Pembroke","Reeve","Stanton",
                "Thorne","Wainwright","Wells","Westbrook","Whitlock"
        };

        String f;
        double neutralChance = 0.12;
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < neutralChance) {
            f = firstNeutral[ThreadLocalRandom.current().nextInt(firstNeutral.length)];
        } else if (preferFemale) {
            f = firstFemale[ThreadLocalRandom.current().nextInt(firstFemale.length)];
        } else {
            f = firstMale[ThreadLocalRandom.current().nextInt(firstMale.length)];
        }

        String l = last[ThreadLocalRandom.current().nextInt(last.length)];
        return f + " " + l;
    }



    private boolean shouldRegenerateForGender(String personalName, boolean preferFemale) {
        if (personalName == null) return true;
        String trimmed = personalName.trim();
        if (trimmed.isEmpty()) return true;
        String firstToken = trimmed.split("\\s+")[0].trim();
        if (firstToken.isEmpty()) return true;

        // If the skin is female, avoid obviously-male first names, and vice versa.
        Set<String> male = new HashSet<>(Arrays.asList(
                "Alden","Alaric","Bennett","Corwin","Edwin","Elias","Emmett","Fergus","Gideon","Hugh",
                "Jasper","Jonas","Marek","Miles","Nolan","Owen","Rowan","Silas","Theron","Wyatt"
        ));
        Set<String> female = new HashSet<>(Arrays.asList(
                "Beatrice","Cecily","Elowen","Isolde","Leona","Lysa","Matilda","Mira","Tamsin","Willa",
                "Adelaide","Briony","Cordelia","Edith","Evelyn","Rosamund"
        ));

        if (preferFemale && male.contains(firstToken)) return true;
        if (!preferFemale && female.contains(firstToken)) return true;
        return false;
    }

    private boolean isNpcFemaleBySkin(NPC npc) {
        if (npc == null) return false;
        String key = getStoredSkinKey(npc);
        if (key == null) key = pickRoleBasedSkinKey(npc);
        return isFemaleSkinKey(key);
    }

    private int migrateSpawnedManagedNpcs() {
        int updated = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc) || isExcludedNpc(npc)) continue;
            Entity ent = npc.getEntity();
            if (ent == null) continue;

            ensureRoleAndSite(ent, npc.getName());
            if (ensureIdentityAndName(npc, ent, true)) {
                updated++;
            }
        }
        return updated;
    }

    private boolean ensureIdentityAndName(NPC npc, Entity ent, boolean forceRefresh) {
        if (npc == null || ent == null) return false;

        PersistentDataContainer pdc = ent.getPersistentDataContainer();

        String role = pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING);
        if (role == null || role.isBlank()) role = inferRoleFromName(npc.getName());
        if (role == null || role.isBlank()) role = "townsfolk";

        String subrole = pdc.get(KEY_VTF_SUBROLE, PersistentDataType.STRING);
        if (subrole == null) subrole = "";

        // Back-compat: infer subrole from legacy names
        String strippedName = safeStrip(npc.getName());
        if (strippedName != null) {
            if (strippedName.startsWith("Farmer")) { role = "worker"; subrole = "farmer"; }
            if (strippedName.startsWith("Smith")) { role = "worker"; subrole = "smith"; }
            if (strippedName.startsWith("Mason")) { role = "worker"; subrole = "mason"; }
            if (strippedName.startsWith("Scribe")) { role = "scholar"; subrole = "scribe"; }
            if (strippedName.startsWith("Watchman")) { role = "guard"; subrole = "watch"; }
            if (strippedName.startsWith("Sentinel")) { role = "guard"; subrole = "patrol"; }
        }

        if (subrole.isBlank()) {
            subrole = pickSubroleForRole(role);
        }

        String personal = pdc.get(KEY_VTF_NAME, PersistentDataType.STRING);

        boolean preferFemale = isNpcFemaleBySkin(npc);

        boolean changed = false;
        boolean genderMismatch = (personal != null && !personal.isBlank() && shouldRegenerateForGender(personal, preferFemale));
        if (personal == null || personal.isBlank() || (forceRefresh && genderMismatch)) {
            personal = generatePersonalName(role, subrole, preferFemale);
            pdc.set(KEY_VTF_NAME, PersistentDataType.STRING, personal);
            changed = true;
        }

// Write back role/subrole if missing or inferred.
        if (pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING) == null || forceRefresh) {
            pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
            changed = true;
        }
        if (!subrole.isBlank() && (pdc.get(KEY_VTF_SUBROLE, PersistentDataType.STRING) == null || forceRefresh)) {
            pdc.set(KEY_VTF_SUBROLE, PersistentDataType.STRING, subrole);
            changed = true;
        }

        // Rename display if it looks like the old numeric style or doesn't match our title+name format.
        String expected = formatDisplayName(role, subrole, personal);
        String current = safeStrip(npc.getName());
        if (forceRefresh || shouldRenameForBackCompat(current)) {
            try {
                if (current == null || !current.equals(expected)) {
                    npc.setName(expected);
                    changed = true;
                }
            } catch (Throwable ignored) {}
        }

        // Equip a light role-appropriate loadout (only if entity is a Player).
        tryEquipForRole(npc, ent, role, subrole);

        return changed;
    }

    private boolean shouldRenameForBackCompat(String currentName) {
        if (currentName == null || currentName.isBlank()) return true;
        for (String prefix : legacyRolePrefixes) {
            if (currentName.equals(prefix)) return true;
            if (currentName.startsWith(prefix + " ")) {
                String rest = currentName.substring((prefix + " ").length()).trim();
                // old style usually was a number.
                if (rest.matches("\\d{2,4}")) return true;
            }
        }
        return false;
    }

    private void tryEquipForRole(NPC npc, Entity ent, String role, String subrole) {
        if (!(ent instanceof Player)) return;
        Player p = (Player) ent;

        // Avoid constantly overwriting player-set items; only fill empty slots.
        try {
            if ("guard".equals(role)) {
                if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    Material weapon = "archer".equalsIgnoreCase(subrole) ? Material.BOW : Material.IRON_SWORD;
                    p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(weapon));
                }
                if (p.getInventory().getItemInOffHand() == null || p.getInventory().getItemInOffHand().getType() == Material.AIR) {
                    p.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(Material.SHIELD));
                }
            } else if ("priest".equals(role)) {
                if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.BOOK));
                }
            } else if ("scholar".equals(role)) {
                if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.WRITABLE_BOOK));
                }
            } else if ("dockhand".equals(role)) {
                if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.OAK_BOAT));
                }
            } else if ("worker".equals(role)) {
                if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    Material tool = Material.IRON_PICKAXE;
                    if ("farmer".equalsIgnoreCase(subrole)) tool = Material.IRON_HOE;
                    else if ("smith".equalsIgnoreCase(subrole)) tool = Material.IRON_AXE;
                    else if ("mason".equalsIgnoreCase(subrole)) tool = Material.IRON_PICKAXE;
                    p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(tool));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void applyNameplatePolicy(Entity ent) {
        if (!hideNameplates) return;
        try { ent.setCustomNameVisible(false); } catch (Throwable ignored) {}
    }

    private void applyNpcCollisionPolicy(NPC npc) {
        // Minimal, Citizens-native toggles. Safe to call repeatedly.
        if (npc == null) return;
        try {
            npc.data().setPersistent(NPC.Metadata.COLLIDABLE, citizensCollidable);
            npc.data().setPersistent(NPC.Metadata.KNOCKBACK, citizensKnockback);
        } catch (Throwable ignored) {
        }
    }

    /* ============================================================
     *  Safe ground selection
     * ============================================================ */

    private Location findSafeGroundNearLoaded(Location base, int radius) {
        World w = base.getWorld();
        if (w == null) return null;

        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        for (int tries = 0; tries < 24; tries++) {
            int dx = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int dz = ThreadLocalRandom.current().nextInt(-radius, radius + 1);

            int x = bx + dx;
            int z = bz + dz;

            // Skip if chunk not loaded (CRASH-SAFE CHOICE)
            Chunk c = w.getChunkAt(x >> 4, z >> 4);
            if (!c.isLoaded()) continue;

            int topY = w.getHighestBlockYAt(x, z);
            int y = topY + 1;

            Material ground = w.getBlockAt(x, topY, z).getType();
            if (ground == Material.WATER || ground == Material.LAVA) continue;

            String gn = ground.name();
            if (gn.endsWith("_STAIRS")) continue;
            if (gn.contains("LEAVES")) continue;

            Material a0 = w.getBlockAt(x, y, z).getType();
            Material a1 = w.getBlockAt(x, y + 1, z).getType();
            if (!a0.isAir() || !a1.isAir()) continue;

            return new Location(w, x + 0.5, y, z + 0.5);
        }

        return null;
    }

    private Location findSafeGroundNear(Location base, int radius) {
        World w = base.getWorld();
        if (w == null) return null;

        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        for (int tries = 0; tries < 24; tries++) {
            int dx = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int dz = ThreadLocalRandom.current().nextInt(-radius, radius + 1);

            int x = bx + dx;
            int z = bz + dz;

            int topY = w.getHighestBlockYAt(x, z);
            int y = topY + 1;

            Material ground = w.getBlockAt(x, topY, z).getType();
            if (ground == Material.WATER || ground == Material.LAVA) continue;

            String gn = ground.name();
            if (gn.endsWith("_STAIRS")) continue;
            if (gn.contains("LEAVES")) continue;

            Material a0 = w.getBlockAt(x, y, z).getType();
            Material a1 = w.getBlockAt(x, y + 1, z).getType();
            if (!a0.isAir() || !a1.isAir()) continue;

            return new Location(w, x + 0.5, y, z + 0.5);
        }

        return null;
    }

    /* ============================================================
     *  Sites loading + ignore list
     * ============================================================ */

    private void loadSites() {
        sites.clear();
        loadIgnoreFilters();

        int fromDynmap = 0;
        int fromLocal = 0;

        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir == null) pluginsDir = new File("plugins");

        File dynmapMarkers = new File(pluginsDir, "dynmap" + File.separator + "markers.yml");
        if (dynmapMarkers.exists()) {
            fromDynmap = parseDynmapMarkersYml(dynmapMarkers);
        }

        File localSites = new File(getDataFolder(), "sites.txt");
        if (localSites.exists()) {
            fromLocal = parseLocalSitesTxt(localSites);
        } else {
            writeSitesStub(localSites);
        }

        getLogger().info("Sites loaded: total=" + sites.size() + " dynmap=" + fromDynmap + " local=" + fromLocal);
    }

    private void loadIgnoreFilters() {
        ignoreExact.clear();
        ignoreContains.clear();
        ignoreRegex.clear();

        // Defaults (to match your earlier “crowded mistake” exclusions), but now configurable
        List<String> defContains = Arrays.asList("sharko", "le_trole_faic", "trole", "trollface", "castle loren");
        List<String> defRegex = Arrays.asList("(?i).*alpha.*house.*");

        // Load from config.yml if present; otherwise keep defaults
        ignoreContains.addAll(readStringList("sites.ignoreContains", defContains));
        ignoreExact.addAll(readStringList("sites.ignoreExact", Collections.emptyList()));

        List<String> regexes = readStringList("sites.ignoreRegex", defRegex);
        for (String r : regexes) {
            try { ignoreRegex.add(Pattern.compile(r)); } catch (Throwable ignored) {}
        }

        // Normalize
        for (int i = 0; i < ignoreContains.size(); i++) ignoreContains.set(i, ignoreContains.get(i).toLowerCase(Locale.ROOT));
        for (int i = 0; i < ignoreExact.size(); i++) ignoreExact.set(i, ignoreExact.get(i).toLowerCase(Locale.ROOT));
    }

    private List<String> readStringList(String path, List<String> def) {
        // We avoid pulling in YamlConfiguration directly; use Bukkit config API
        try {
            if (getConfig().contains(path)) {
                List<String> v = getConfig().getStringList(path);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private boolean shouldIgnoreSiteName(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);

        for (String ex : ignoreExact) {
            if (lower.equals(ex)) return true;
        }
        for (String sub : ignoreContains) {
            if (sub == null || sub.isBlank()) continue;
            if (lower.contains(sub)) return true;
        }
        for (Pattern p : ignoreRegex) {
            try {
                if (p.matcher(name).matches()) return true;
            } catch (Throwable ignored) {}
        }

        return false;
    }

    private int parseLocalSitesTxt(File f) {
        int added = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(";", 4);
                if (parts.length < 4) continue;

                String world = parts[0].trim();
                int x = parseIntSafe(parts[1].trim(), Integer.MIN_VALUE);
                int z = parseIntSafe(parts[2].trim(), Integer.MIN_VALUE);
                String name = parts[3].trim();
                if (x == Integer.MIN_VALUE || z == Integer.MIN_VALUE) continue;

                if (shouldIgnoreSiteName(name)) continue;

                sites.add(new Site(name, world, x, z));
                added++;
            }
        } catch (Throwable ignored) {}
        return added;
    }

    private void writeSitesStub(File f) {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            if (f.exists()) return;

            String stub =
                    "# Optional: define sites manually if you don't use dynmap markers.yml\n" +
                    "# Format per line: world;x;z;name\n" +
                    "# Example:\n" +
                    "# world;123;456;Viridian Gate\n";
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(stub.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private int parseDynmapMarkersYml(File f) {
        int added = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;

            String label = null;
            String world = null;
            Integer x = null;
            Integer z = null;

            while ((line = br.readLine()) != null) {
                String t = line.trim();

                // marker id header line like "someid:"
                if (t.endsWith(":") && !t.startsWith("-") && !t.startsWith("label:") && !t.startsWith("world:")) {
                    if (label != null && world != null && x != null && z != null) {
                        if (!shouldIgnoreSiteName(label)) {
                            sites.add(new Site(label, world, x, z));
                            added++;
                        }
                    }
                    label = null; world = null; x = null; z = null;
                    continue;
                }

                if (t.startsWith("label:")) {
                    label = stripQuotes(t.substring("label:".length()).trim());
                    continue;
                }
                if (t.startsWith("world:")) {
                    world = stripQuotes(t.substring("world:".length()).trim());
                    continue;
                }
                if (t.startsWith("x:")) {
                    x = (int) Math.round(parseDoubleSafe(t.substring(2).trim(), Double.NaN));
                    continue;
                }
                if (t.startsWith("z:")) {
                    z = (int) Math.round(parseDoubleSafe(t.substring(2).trim(), Double.NaN));
                    continue;
                }
            }

            if (label != null && world != null && x != null && z != null) {
                if (!shouldIgnoreSiteName(label)) {
                    sites.add(new Site(label, world, x, z));
                    added++;
                }
            }

        } catch (Throwable ignored) {}

        return added;
    }

    private Site findNearestSite(Location loc, int maxDistBlocks) {
        if (loc == null || loc.getWorld() == null) return null;

        double best = (double) maxDistBlocks * (double) maxDistBlocks;
        Site bestSite = null;

        for (Site s : sites) {
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

    /* ============================================================
     *  Config file generation (no embedded YAML strings in Java code)
     * ============================================================ */

    private void ensureConfigFile() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        File cfg = new File(getDataFolder(), "config.yml");
        if (cfg.exists()) {
            // Still load it through Bukkit config API
            reloadConfig();
            return;
        }

        String defaults =
                "enabled: true\n" +
                "\n" +
                "dimensions:\n" +
                "  allowNether: false\n" +
                "  allowEnd: false\n" +
                "\n" +
                "portals:\n" +
                "  allowNetherPortals: false\n" +
                "  allowEndPortals: false\n" +
                "\n" +
                "skins:\n" +
                "  localFolderName: skins\n" +
                "  preloadOnEnable: true\n" +
                "  allowMineskinRequests: true\n" +
                "  mineskinApiKey: \"\"\n" +
                "  userAgent: ViridianTownsFolk/1.0\n" +
                "  visibility: unlisted\n" +
                "  mineskinCooldownSeconds: 12\n" +
                "  enforceIntervalSeconds: 45\n" +
                "  retryMissingIntervalSeconds: 300\n" +
                "\n" +
                "nametags:\n" +
                "  hideNameplates: true\n" +
                "\n" +
                "wake:\n" +
                "  playerScanRadius: 48\n" +
                "  playerScanCooldownMs: 2500\n" +
                "  maxPerScan: 24\n" +
                "\n" +
                "adoption:\n" +
                "  adoptByNameNearSitesOnly: true\n" +
                "  siteAdoptRadius: 128\n" +
                "\n" +
                "population:\n" +
                "  minPerSite: 10\n" +
                "  maxPerSite: 22\n" +
                "  radius: 64\n" +
                "  cycleSeconds: 120\n" +
                "  maxQueuePerCycle: 6\n" +
                "  siteActivationRadius: 192\n" +
                "  spawnPerTick: 1\n" +
                "\n" +
                "roaming:\n" +
                "  returnToSiteDistance: 56\n" +
                "  townsfolk: 14\n" +
                "  worker: 14\n" +
                "  guard: 18\n" +
                "  priest: 10\n" +
                "  scholar: 10\n" +
                "  ranger: 22\n" +
                "  hermit: 20\n" +
                "\n" +
                "behavior:\n" +
                "  pulsePerTick: 16\n" +
                "\n" +
                "dialogue:\n" +
                "  enableInteractive: true\n" +
                "  sessionSeconds: 45\n" +
                "\n" +
                "sites:\n" +
                "  ignoreContains:\n" +
                "    - sharko\n" +
                "    - le_trole_faic\n" +
                "    - trole\n" +
                "    - trollface\n" +
                "    - castle loren\n" +
                "  ignoreExact: []\n" +
                "  ignoreRegex:\n" +
                "    - \"(?i).*alpha.*house.*\"\n";

        try (FileOutputStream fos = new FileOutputStream(cfg, false)) {
            fos.write(defaults.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            getLogger().warning("Failed to write default config.yml");
        }

        reloadConfig();
    }

    private void loadConfigValues() {
        try {
            reloadConfig();

            enabled = getConfig().getBoolean("enabled", enabled);

            allowSpawningInNether = getConfig().getBoolean("dimensions.allowNether", allowSpawningInNether);
            allowSpawningInEnd = getConfig().getBoolean("dimensions.allowEnd", allowSpawningInEnd);

            allowNetherPortals = getConfig().getBoolean("portals.allowNetherPortals", allowNetherPortals);
            allowEndPortals = getConfig().getBoolean("portals.allowEndPortals", allowEndPortals);


            hideNameplates = getConfig().getBoolean("nametags.hideNameplates", hideNameplates);

            citizensCollidable = getConfig().getBoolean("citizens.collidable", citizensCollidable);
            citizensKnockback = getConfig().getBoolean("citizens.knockback", citizensKnockback);

            wakePlayerScanRadius = getConfig().getInt("wake.playerScanRadius", wakePlayerScanRadius);
            wakePlayerScanCooldownMs = getConfig().getInt("wake.playerScanCooldownMs", wakePlayerScanCooldownMs);
            wakeMaxPerScan = getConfig().getInt("wake.maxPerScan", wakeMaxPerScan);

            adoptByNameNearSitesOnly = getConfig().getBoolean("adoption.adoptByNameNearSitesOnly", adoptByNameNearSitesOnly);
            siteAdoptRadius = getConfig().getInt("adoption.siteAdoptRadius", siteAdoptRadius);

            populationMinPerSite = getConfig().getInt("population.minPerSite", populationMinPerSite);
            populationMaxPerSite = getConfig().getInt("population.maxPerSite", populationMaxPerSite);
            populationRadius = getConfig().getInt("population.radius", populationRadius);
            populationCycleSeconds = getConfig().getInt("population.cycleSeconds", populationCycleSeconds);
            populationMaxQueuePerCycle = getConfig().getInt("population.maxQueuePerCycle", populationMaxQueuePerCycle);

            populationSiteActivationRadius = getConfig().getInt("population.siteActivationRadius", populationSiteActivationRadius);
            populationSpawnPerTick = getConfig().getInt("population.spawnPerTick", populationSpawnPerTick);

            behaviorPulsePerTick = getConfig().getInt("behavior.pulsePerTick", behaviorPulsePerTick);
            interactiveDialogueEnabled = getConfig().getBoolean("dialogue.enableInteractive", interactiveDialogueEnabled);
            dialogueSessionSeconds = getConfig().getInt("dialogue.sessionSeconds", dialogueSessionSeconds);

            returnToSiteDistance = getConfig().getInt("roaming.returnToSiteDistance", returnToSiteDistance);
            roamRadiusTownsfolk = getConfig().getInt("roaming.townsfolk", roamRadiusTownsfolk);
            roamRadiusWorker = getConfig().getInt("roaming.worker", roamRadiusWorker);
            roamRadiusGuard = getConfig().getInt("roaming.guard", roamRadiusGuard);
            roamRadiusPriest = getConfig().getInt("roaming.priest", roamRadiusPriest);
            roamRadiusScholar = getConfig().getInt("roaming.scholar", roamRadiusScholar);
            roamRadiusRanger = getConfig().getInt("roaming.ranger", roamRadiusRanger);
            roamRadiusHermit = getConfig().getInt("roaming.hermit", roamRadiusHermit);

            // reload ignore filters too
            loadIgnoreFilters();

        } catch (Throwable t) {
            getLogger().warning("Config load failed; using defaults.");
        }
    }

    /* ============================================================
     *  Utility
     * ============================================================ */

    private static String safeStrip(String s) {
        if (s == null) return null;
        return ChatColor.stripColor(s);
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    private static double parseDoubleSafe(String s, double def) {
        try { return Double.parseDouble(s); } catch (Throwable t) { return def; }
    }


/* ============================================================
 *  Skin logic
 * ============================================================ */

private File getSkinDirectory() {
    String folderName = getConfig().getString("skins.localFolderName", "skins");
    if (folderName == null || folderName.trim().isEmpty()) folderName = "skins";
    return new File(getDataFolder(), folderName);
}

private void ensureSkinFolder() {
    try {
        File dir = getSkinDirectory();
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (!ok) getLogger().warning("Failed to create skins folder: " + dir.getPath());
        }
    } catch (Throwable t) {
        getLogger().warning("Failed ensuring skins folder: " + t.getMessage());
    }
}

private static String skinKeyFromFile(String fileName) {
    if (fileName == null) return null;
    String s = fileName.trim().toLowerCase(Locale.ROOT);
    if (s.endsWith(".png")) s = s.substring(0, s.length() - 4);
    return s;
}

private static boolean isFemaleSkinKey(String skinKey) {
    return skinKey != null && skinKey.toLowerCase(Locale.ROOT).endsWith("_f");
}

private void loadSkinCacheFromConfig() {
    try {
        for (String file : TOWNSFOLK_SKIN_FILES) {
            String key = skinKeyFromFile(file);
            if (key == null) continue;

            String base = "skins.cached." + key + ".";
            String value = getConfig().getString(base + "value", "");
            String sig = getConfig().getString(base + "signature", "");
            if (value != null && sig != null && !value.isEmpty() && !sig.isEmpty()) {
                skinCache.put(key, new SkinData(value, sig));
            }
        }
    } catch (Throwable t) {
        getLogger().warning("Failed loading skin cache: " + t.getMessage());
    }
}

private void persistSkinCacheEntry(String skinKey, SkinData data) {
    if (skinKey == null || data == null) return;
    try {
        String base = "skins.cached." + skinKey + ".";
        getConfig().set(base + "value", data.value);
        getConfig().set(base + "signature", data.signature);
        saveConfig();
    } catch (Throwable t) {
        getLogger().warning("Failed saving skin cache entry for " + skinKey + ": " + t.getMessage());
    }
}

private void startSkinTasks() {
    // cancel previous
    if (skinRetryTaskId != -1) {
        Bukkit.getScheduler().cancelTask(skinRetryTaskId);
        skinRetryTaskId = -1;
    }
    if (skinEnforceTaskId != -1) {
        Bukkit.getScheduler().cancelTask(skinEnforceTaskId);
        skinEnforceTaskId = -1;
    }

    final int retrySeconds = Math.max(30, getConfig().getInt("skins.retryMissingIntervalSeconds", 300));
    final int enforceSeconds = Math.max(10, getConfig().getInt("skins.enforceIntervalSeconds", 45));

    // Preload (async)
    if (getConfig().getBoolean("skins.preloadOnEnable", true)) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (String file : TOWNSFOLK_SKIN_FILES) {
                String key = skinKeyFromFile(file);
                if (key == null) continue;
                try { ensureSkinLoaded(key); } catch (Throwable ignored) { }
            }
        });
    }

    // Periodic retry of missing (async)
    skinRetryTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
        for (String file : TOWNSFOLK_SKIN_FILES) {
            String key = skinKeyFromFile(file);
            if (key == null) continue;
            if (skinCache.containsKey(key)) continue;
            try { ensureSkinLoaded(key); } catch (Throwable ignored) { }
        }
    }, 40L, retrySeconds * 20L).getTaskId();

    // Periodic enforce (sync): apply to managed NPCs not using our skins
    skinEnforceTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc == null) continue;
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;
            if (!isManagedNpc(npc)) continue;
            ensureTownSkinApplied(npc, "periodic");
        }
    }, 60L, enforceSeconds * 20L).getTaskId();
}

private String pickRandomTownsfolkSkinKey() {
    // Fallback: pick any known townsfolk skin at random.
    String file = TOWNSFOLK_SKIN_FILES.get(ThreadLocalRandom.current().nextInt(TOWNSFOLK_SKIN_FILES.size()));
    return skinKeyFromFile(file);
}


private String roleBaseFromRole(String role, String subrole) {
    if (role == null) return null;
    String r = role.toLowerCase(Locale.ROOT);

    if (r.equals("priest")) return "priest";
    if (r.equals("scholar")) {
        if ("scribe".equalsIgnoreCase(subrole)) return "scribe";
        return "scholar";
    }
    if (r.equals("dockhand")) return "dockhand";
    if (r.equals("ranger")) return "ranger";
    if (r.equals("hermit")) return "hermit";
    if (r.equals("merchant")) return "merchant";
    if (r.equals("townsfolk")) return "townsfolk";

    if (r.equals("worker")) {
        if ("farmer".equalsIgnoreCase(subrole)) return "farmer";
        if ("mason".equalsIgnoreCase(subrole)) return "mason";
        if ("smith".equalsIgnoreCase(subrole)) return "smith";
        return "worker";
    }

    if (r.equals("guard")) {
        if ("watch".equalsIgnoreCase(subrole)) return "watchman";
        if ("patrol".equalsIgnoreCase(subrole)) return "sentinel";
        return "guard";
    }

    return null;
}

private String pickRoleBasedSkinKeyByBaseAndId(String roleBase, int npcId) {
    if (roleBase == null || roleBase.isBlank()) return pickRandomTownsfolkSkinKey();
    String base = roleBase.trim().toLowerCase(Locale.ROOT);

    boolean hasFemale = TOWNSFOLK_SKIN_FILES.contains(base + "_f.png");
    if (hasFemale) {
        int coin = Math.floorMod(Integer.valueOf(npcId).hashCode(), 2);
        if (coin == 0) {
            return skinKeyFromFile(base + "_f.png");
        }
    }
    return skinKeyFromFile(base + ".png");
}

private String pickRoleBasedSkinKey(NPC npc) {
    if (npc == null) return null;

    String roleBase = roleBaseFromNpcName(npc.getName());
    if (roleBase == null) return null;

    // Prefer 50/50 female variant if available for the role.
    boolean hasFemale = TOWNSFOLK_SKIN_FILES.contains(roleBase + "_f.png");
    if (hasFemale) {
        // Deterministic "coin flip" so the NPC doesn't swap genders/skins every scan.
        // Citizens NPC IDs are stable; use that for the 50% split.
        int coin = Math.floorMod(Integer.valueOf(npc.getId()).hashCode(), 2);
        if (coin == 0) {
            return skinKeyFromFile(roleBase + "_f.png");
        }
    }
    return skinKeyFromFile(roleBase + ".png");
}

private String roleBaseFromNpcName(String rawName) {
    if (rawName == null) return null;

    // Strip Bukkit color codes if present (§x) and trim.
    String name = rawName.replaceAll("(?i)\u00A7[0-9A-FK-OR]", "").trim();
    if (name.isEmpty()) return null;

    // Use the first "word" to determine role (e.g., "Guard", "Guard 12", "Guard - East Gate")
    String firstToken = name.split("\\s+")[0].trim();
    if (firstToken.isEmpty()) return null;

    String token = firstToken.toLowerCase();

    // Map display names to skin base names (filenames without extension, without _f).
    if (token.equals("acolyte")) return "acolyte";
    if (token.equals("dockhand")) return "dockhand";
    if (token.equals("farmer")) return "farmer";
    if (token.equals("guard")) return "guard";
    if (token.equals("hermit")) return "hermit";
    if (token.equals("mason")) return "mason";
    if (token.equals("merchant")) return "merchant";
    if (token.equals("priest")) return "priest";
    if (token.equals("ranger")) return "ranger";
    if (token.equals("scholar")) return "scholar";
    if (token.equals("scribe")) return "scribe";
    if (token.equals("sentinel")) return "sentinel";
    if (token.equals("smith")) return "smith";
    if (token.equals("townsfolk")) return "townsfolk";
    if (token.equals("watchman")) return "watchman";
    if (token.equals("worker")) return "worker";

    // Unknown role name: no role-based skin.
    return null;
}

private String getStoredSkinKey(NPC npc) {
    if (npc == null || !npc.isSpawned()) return null;
    try {
        Entity ent = npc.getEntity();
        if (ent == null) return null;
        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        String key = pdc.get(KEY_VTF_SKIN, PersistentDataType.STRING);
        if (key == null) return null;
        key = key.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    } catch (Throwable ignored) {
        return null;
    }
}

private void setStoredSkinKey(NPC npc, String skinKey) {
    if (npc == null || !npc.isSpawned()) return;
    try {
        Entity ent = npc.getEntity();
        if (ent == null) return;
        if (skinKey == null) return;
        String k = skinKey.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return;
        ent.getPersistentDataContainer().set(KEY_VTF_SKIN, PersistentDataType.STRING, k);
    } catch (Throwable ignored) { }
}

private boolean isAllowedTownsfolkSkinKey(String skinKey) {
    if (skinKey == null) return false;
    String k = skinKey.trim().toLowerCase(Locale.ROOT);
    if (k.isEmpty()) return false;
    for (String file : TOWNSFOLK_SKIN_FILES) {
        String fk = skinKeyFromFile(file);
        if (k.equals(fk)) return true;
    }
    return false;
}

private String getCurrentSkinNameSafe(NPC npc) {
    try {
        SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
        try {
            // Citizens has getSkinName() in many builds, but guard just in case
            return (String) SkinTrait.class.getMethod("getSkinName").invoke(trait);
        } catch (Throwable ignored) {
            return null;
        }
    } catch (Throwable ignored) {
        return null;
    }
}

private void applySkinToNpc(NPC npc, String skinKey, SkinData skin) {
    if (npc == null || skinKey == null || skin == null) return;
    if (!npc.isSpawned()) return;
    try {
        String skinName = "VTF_" + skinKey;
        SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
        trait.setSkinPersistent(skinName, skin.signature, skin.value);
        setStoredSkinKey(npc, skinKey);
    } catch (Throwable t) {
        getLogger().warning("Failed to apply skin '" + skinKey + "': " + t.getMessage());
    }
}

private void ensureTownSkinApplied(NPC npc, String reason) {
    if (npc == null) return;
    if (!npc.isSpawned()) return;

    // Prefer a role-based skin derived from the NPC's name (e.g., "Guard" -> guard.png).
    String desiredKey = pickRoleBasedSkinKey(npc);
    if (desiredKey != null && isAllowedTownsfolkSkinKey(desiredKey)) {
        setStoredSkinKey(npc, desiredKey);
    } else {
        // Fall back to stored/random behavior if role is unknown.
        desiredKey = getStoredSkinKey(npc);

        if (!isAllowedTownsfolkSkinKey(desiredKey)) {
            desiredKey = pickRandomTownsfolkSkinKey();
            if (desiredKey != null) setStoredSkinKey(npc, desiredKey);
        }
    }

    if (desiredKey == null) return;

    // If already visibly the correct one of ours, skip.
    String currentSkinName = getCurrentSkinNameSafe(npc);
    if (currentSkinName != null) {
        String expectedSkinName = "VTF_" + desiredKey;
        if (currentSkinName.equalsIgnoreCase(expectedSkinName)) {
            return;
        }
    }

    // If cached, apply immediately on main thread.
    SkinData cached = skinCache.get(desiredKey);
    if (cached != null) {
        applySkinToNpc(npc, desiredKey, cached);
        return;
    }

    // Not cached: load/upload async (never block main thread), then apply sync.
    final String finalDesiredKey = desiredKey;
    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        SkinData loaded = ensureSkinLoaded(finalDesiredKey);
        if (loaded == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (npc.isSpawned()) applySkinToNpc(npc, finalDesiredKey, loaded);
        });
    });
}

private SkinData ensureSkinLoaded(String skinKey) {
    if (skinKey == null) return null;
    String k = skinKey.toLowerCase(Locale.ROOT).trim();
    if (k.isEmpty()) return null;

    SkinData existing = skinCache.get(k);
    if (existing != null) return existing;

    if (!getConfig().getBoolean("skins.allowMineskinRequests", true)) return null;

    String apiKey = getConfig().getString("skins.mineskinApiKey", "").trim();
    if (apiKey.isEmpty()) {
        if (!warnedBlankMineSkinKey) {
            warnedBlankMineSkinKey = true;
            getLogger().warning("MineSkin API key is blank. Townsfolk skins will not be uploaded. Place your PNGs in the skins folder and set skins.mineskinApiKey to enable uploads.");
        }
        return null;
    }

    File dir = getSkinDirectory();
    File f = new File(dir, k + ".png");
    if (!f.exists() || f.length() <= 0) {
        // The user may have not installed skins yet; don't spam log on every retry.
        return null;
    }

    byte[] pngBytes = readFileBytes(f);
    if (pngBytes == null || pngBytes.length == 0) return null;

    String variant = isFemaleSkinKey(k) ? "slim" : "classic";
    String visibility = getConfig().getString("skins.visibility", "unlisted");
    String userAgent = getConfig().getString("skins.userAgent", "ViridianTownsFolk/1.0");

    SkinData data = requestMineSkinByUploadBytes(pngBytes, "VTF_" + k, variant, visibility, apiKey, userAgent);
    if (data != null) {
        skinCache.put(k, data);
        // Persist to disk on main thread to avoid Bukkit config threading weirdness.
        final SkinData persist = data;
        Bukkit.getScheduler().runTask(this, () -> persistSkinCacheEntry(k, persist));
    }
    return data;
}

private SkinData requestMineSkinByUploadBytes(byte[] pngBytes, String name, String variant, String visibility,
                                             String apiKey, String userAgent) {

    final int maxAttempts = Math.max(1, getConfig().getInt("skins.mineskinMaxAttempts", 5));
    final long cooldownMs = Math.max(1000L, getConfig().getLong("skins.mineskinCooldownSeconds", 12) * 1000L);

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        HttpURLConnection conn = null;
        try {
            // Global cooldown/rate-limit guard across all uploads
            synchronized (mineSkinRateLock) {
                long now = System.currentTimeMillis();
                long wait = nextMineSkinRequestAtMs - now;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                nextMineSkinRequestAtMs = System.currentTimeMillis() + cooldownMs;
            }

            URL url = new URL("https://api.mineskin.org/generate/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);
            if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            String boundary = "----VTFBoundary" + System.nanoTime();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                String safeName = sanitizeName(name);
                writeFormField(os, boundary, "name", safeName);
                writeFormField(os, boundary, "variant", variant);
                writeFormField(os, boundary, "visibility", visibility);
                writeFileField(os, boundary, "file", name + ".png", "image/png", pngBytes);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body = readAll((code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream());

            if (code >= 200 && code < 300) {
                String value = extractJsonString(body, "\"value\":\"");
                String signature = extractJsonString(body, "\"signature\":\"");
                if (value == null || signature == null || value.isEmpty() || signature.isEmpty()) {
                    getLogger().warning("MineSkin response missing value/signature.");
                    return null;
                }
                return new SkinData(value, signature);
            }

            if (code == 429) {
                long waitMs = parseMineSkinBackoffMs(body);

                if (attempt >= maxAttempts) {
                    getLogger().warning("MineSkin upload failed (429) after " + maxAttempts + " attempts: " + body);
                    return null;
                }

                getLogger().warning("MineSkin rate limited (429). Backing off for " + waitMs +
                        "ms (attempt " + attempt + "/" + maxAttempts + ").");

                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            getLogger().warning("MineSkin upload failed (" + code + "): " + body);
            return null;

        } catch (Throwable ex) {
            getLogger().warning("MineSkin upload error: " + ex.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    return null;
}

private static String sanitizeName(String s) {
    if (s == null) return "vtf";
    String t = s.trim();
    if (t.length() > 32) t = t.substring(0, 32);
    t = t.replaceAll("[^A-Za-z0-9_\\- ]", "");
    if (t.isEmpty()) t = "vtf";
    return t;
}

private static void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
    os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
}

private static void writeFileField(OutputStream os, String boundary, String name, String filename, String contentType, byte[] bytes) throws IOException {
    os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(bytes);
    os.write("\r\n".getBytes(StandardCharsets.UTF_8));
}

private static String readAll(InputStream is) throws IOException {
    if (is == null) return "";
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}

private static String extractJsonString(String json, String needle) {
    if (json == null) return null;
    int idx = json.indexOf(needle);
    if (idx < 0) return null;
    idx += needle.length();
    StringBuilder sb = new StringBuilder();
    boolean escape = false;
    for (int i = idx; i < json.length(); i++) {
        char c = json.charAt(i);
        if (escape) {
            // handle basic escapes
            if (c == 'n') sb.append('\n');
            else if (c == 'r') sb.append('\r');
            else if (c == 't') sb.append('\t');
            else sb.append(c);
            escape = false;
            continue;
        }
        if (c == '\\') {
            escape = true;
            continue;
        }
        if (c == '"') break;
        sb.append(c);
    }
    return sb.toString();
}

private static long parseMineSkinBackoffMs(String body) {
    // MineSkin sometimes returns "nextRequest":<seconds> or similar fields; fallback to 10s.
    if (body == null) return 10000L;
    try {
        // Try: "nextRequest":12345 (ms) OR "nextRequest":12 (seconds) OR "retry_after":...
        Long n = extractJsonNumber(body, "nextRequest");
        if (n != null) {
            if (n < 1000L) return Math.max(1000L, n * 1000L);
            return Math.max(1000L, n);
        }
        Long r = extractJsonNumber(body, "retry_after");
        if (r != null) return Math.max(1000L, r * 1000L);
    } catch (Throwable ignored) { }
    return 10000L;
}

private static Long extractJsonNumber(String json, String key) {
    if (json == null || key == null) return null;
    String needle = "\"" + key + "\"";
    int idx = json.indexOf(needle);
    if (idx < 0) return null;
    idx = json.indexOf(":", idx);
    if (idx < 0) return null;
    idx++;
    while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
    StringBuilder sb = new StringBuilder();
    while (idx < json.length()) {
        char c = json.charAt(idx);
        if ((c >= '0' && c <= '9')) {
            sb.append(c);
            idx++;
            continue;
        }
        break;
    }
    if (sb.length() == 0) return null;
    try { return Long.parseLong(sb.toString()); } catch (Throwable ignored) { return null; }
}

private static byte[] readFileBytes(File f) {
    if (f == null || !f.exists()) return null;
    try (FileInputStream fis = new FileInputStream(f);
         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        byte[] buf = new byte[8192];
        int r;
        while ((r = fis.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    } catch (Throwable ignored) {
        return null;
    }
}


}