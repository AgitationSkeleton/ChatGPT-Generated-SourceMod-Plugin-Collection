package com.redchanit.viridiantownsfolk;

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

    private static final String SCORE_TAG = "vtf_managed";

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

    /* ============================================================
     *  Enable/disable
     * ============================================================ */

    @Override
    public void onEnable() {
        KEY_VTF_MANAGED = new NamespacedKey(this, "vtf");
        KEY_VTF_ROLE = new NamespacedKey(this, "vtf_role");
        KEY_VTF_SITE = new NamespacedKey(this, "vtf_site");
        KEY_VTF_VER = new NamespacedKey(this, "vtf_ver");

        ensureConfigFile();
        loadConfigValues();

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

        // Startup wake for already-spawned NPCs (safe: Citizens registry iteration only)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int woke = wakeAllSpawned("startup");
            getLogger().info("Startup wake: " + woke);
        }, 40L);

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
            sender.sendMessage(ChatColor.GRAY + "/vtf stats        - show counts");
            sender.sendMessage(ChatColor.GRAY + "/vtf reloadsites  - reload sites from markers.yml + sites.txt");
            sender.sendMessage(ChatColor.GRAY + "/vtf reloadconfig - reload config.yml");
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
        e.getPlayer().sendMessage(ChatColor.GRAY + pickDialogue(npc));
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

        Location center = site.center(w);

        // Hard safety: require player still nearby
        if (!hasPlayerNear(center, populationSiteActivationRadius)) return;

        // Find a safe spawn location without loading chunks:
        // - If a candidate's chunk isn't loaded, skip it.
        Location spawnLoc = findSafeGroundNearLoaded(center, Math.min(28, populationRadius / 2));
        if (spawnLoc == null) return;

        String role = pickRole();
        String name = roleDisplay(role) + " " + (100 + ThreadLocalRandom.current().nextInt(900));

        try {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
            applyNpcCollisionPolicy(npc);
            npc.spawn(spawnLoc);

            tagManaged(npc);

            Entity ent = npc.getEntity();
            if (ent != null) {
                PersistentDataContainer pdc = ent.getPersistentDataContainer();
                pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
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
}
