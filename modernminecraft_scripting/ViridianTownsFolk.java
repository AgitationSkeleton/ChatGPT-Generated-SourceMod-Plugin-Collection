package com.redchanit.viridiantownsfolk;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ViridianTownsFolk (no Dynmap)
 * - Citizens-powered "townsfolk" around Viridian marker sites (read markers.yml if present)
 * - Auto-adopt + wake NPCs on restart/chunkload/proximity
 * - Lightweight routines, dialogue memory, door/gate interaction, light combat
 *
 * Author: ChatGPT
 */
public final class ViridianTownsFolk extends JavaPlugin implements Listener, CommandExecutor {

    /* ============================================================
     *  Data types
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

        Location toLocation(World w) {
            return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
        }
    }

    private static final class DialogueMemory {
        long lastSpokeAtMs;
        String lastTopic;
        int timesSpokenRecently;

        DialogueMemory(long nowMs, String topic) {
            this.lastSpokeAtMs = nowMs;
            this.lastTopic = topic;
            this.timesSpokenRecently = 1;
        }
    }

    /* ============================================================
     *  Keys / constants
     * ============================================================ */

    private NamespacedKey KEY_VTF_MANAGED;
    private NamespacedKey KEY_VTF_ROLE;
    private NamespacedKey KEY_VTF_SITE;
    private NamespacedKey KEY_VTF_VER;

    private static final String SCORE_TAG = "vtf_managed";

    // Roles we use
    private static final List<String> ROLE_POOL = Arrays.asList(
            "townsfolk", "worker", "guard", "priest", "scholar", "dockhand", "ranger", "hermit"
    );

    // Adoption prefixes (legacy)
    private static final List<String> legacyRolePrefixes = Arrays.asList(
            "Worker", "Guard", "Priest", "Dockhand", "Townsfolk",
            "Merchant", "Scholar", "Ranger", "Hermit", "Watchman",
            "Sentinel", "Acolyte", "Smith", "Mason", "Scribe", "Farmer"
    );

    // Things we do NOT want to adopt/modify
    private static final List<String> excludedNameContains = Arrays.asList(
            "herobrine", "indev", "wanderlore", "wl_", "indevmobs"
    );

    // Specific “classic” names you mentioned to exclude from adoption heuristics
    private static final List<String> excludedExactish = Arrays.asList(
            "rana", "steve", "beast boy", "black steve", "indevblksteve"
    );

    /* ============================================================
     *  Runtime state
     * ============================================================ */

    // NPC id -> autonomy task
    private final Map<Integer, BukkitTask> autonomyTasks = new HashMap<>();

    // NPC id -> (player uuid -> memory)
    private final Map<Integer, Map<UUID, DialogueMemory>> dialogueMemory = new HashMap<>();

    // Player move throttle
    private final Map<UUID, Long> lastWakeScanMs = new HashMap<>();

    // Actionbar throttle
    private final Map<UUID, Long> lastActionbarMs = new HashMap<>();

    // Sites loaded from plugins/dynmap/markers.yml (read-only), plus optional sites.txt in our folder
    private final List<Site> sites = new ArrayList<>();

    /* ============================================================
     *  Config (generated if missing)
     * ============================================================ */

    private boolean enabled = true;

    private boolean hideNameplates = true;
    private int nearbyHintRadius = 10;
    private int nearbyHintCooldownMs = 2000;

    private int wakePlayerScanRadius = 48;
    private int wakePlayerScanCooldownMs = 2500;
    private int wakeMaxPerScan = 20;

    private int wakeChunkMaxPerChunk = 50;

    private int siteAdoptRadius = 128; // only adopt-by-name if near a site (prevents grabbing random NPCs)
    private boolean adoptByNameNearSitesOnly = true;

    private int populationMinPerSite = 8;
    private int populationMaxPerSite = 18;
    private int populationRadius = 64;
    private int populationCycleSeconds = 90;
    private int populationMaxSpawnPerCycle = 6;

    private double engageChanceGuards = 0.85;
    private double engageChanceWorkers = 0.20;
    private double engageChanceOther = 0.06;

    private BukkitTask populationTask;

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
            getLogger().info("ViridianTownsFolk disabled by config.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("vtf") != null) getCommand("vtf").setExecutor(this);
        if (getCommand("viridiantownsfolk") != null) getCommand("viridiantownsfolk").setExecutor(this);

        loadSites();

        // Startup sweep: wake/adopt existing spawned NPCs now
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int rehooked = wakeAllSpawnedNpcs("startup");
            getLogger().info("Startup wake: " + rehooked);
        }, 40L);

        // Follow-up sweeps (Citizens sometimes spawns later)
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllSpawnedNpcs("startup-late"), 20L * 10L);
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllSpawnedNpcs("startup-later"), 20L * 30L);

        startPopulationMaintainer();

        getLogger().info("ViridianTownsFolk enabled (no Dynmap). Sites loaded: " + sites.size());
    }

    @Override
    public void onDisable() {
        if (populationTask != null) populationTask.cancel();
        populationTask = null;

        for (BukkitTask t : autonomyTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        autonomyTasks.clear();
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
            sender.sendMessage(ChatColor.GRAY + "ViridianTownsFolk commands:");
            sender.sendMessage(ChatColor.GRAY + "/vtf rehook   - wake/rehook all currently spawned NPCs");
            sender.sendMessage(ChatColor.GRAY + "/vtf adoptall - adopt legacy name-prefix NPCs (spawned only)");
            sender.sendMessage(ChatColor.GRAY + "/vtf topup    - run one population cycle now");
            sender.sendMessage(ChatColor.GRAY + "/vtf purge    - remove ALL VTF-managed NPCs (dangerous)");
            sender.sendMessage(ChatColor.GRAY + "/vtf stats    - show counts");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("rehook")) {
            int c = wakeAllSpawnedNpcs("command-rehook");
            sender.sendMessage(ChatColor.GREEN + "Rehooked/woke: " + c);
            return true;
        }

        if (sub.equals("adoptall")) {
            int c = adoptAllSpawnedLegacyByName();
            sender.sendMessage(ChatColor.GREEN + "Adopted legacy by name: " + c);
            int w = wakeAllSpawnedNpcs("command-adoptall");
            sender.sendMessage(ChatColor.GREEN + "Woke after adopt: " + w);
            return true;
        }

        if (sub.equals("topup")) {
            int spawned = runPopulationCycle();
            sender.sendMessage(ChatColor.GREEN + "Top-up spawned: " + spawned);
            return true;
        }

        if (sub.equals("purge")) {
            int removed = purgeAllManagedNpcs();
            sender.sendMessage(ChatColor.RED + "Purged VTF-managed NPCs: " + removed);
            return true;
        }

        if (sub.equals("stats")) {
            int total = 0;
            int managed = 0;
            int spawned = 0;

            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                total++;
                if (npc.isSpawned()) spawned++;
                if (npc.isSpawned() && isManagedNpc(npc)) managed++;
            }

            sender.sendMessage(ChatColor.GRAY + "Citizens NPCs total: " + total);
            sender.sendMessage(ChatColor.GRAY + "Spawned now: " + spawned);
            sender.sendMessage(ChatColor.GRAY + "VTF-managed spawned: " + managed);
            sender.sendMessage(ChatColor.GRAY + "Sites loaded: " + sites.size());
            return true;
        }

        return true;
    }

    /* ============================================================
     *  Events: chunk load + player move + interact
     * ============================================================ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabled) return;
        // Wake NPCs inside this chunk as it loads
        int woke = wakeChunk(e.getChunk());
        if (woke > 0) {
            // Optional debug: getLogger().info("Woke " + woke + " NPCs in chunk " + e.getChunk().getX() + "," + e.getChunk().getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p == null) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // Only do work if moved a bit
        if (from.getWorld() == to.getWorld()) {
            if (from.distanceSquared(to) < 4.0) return;
        }

        long now = System.currentTimeMillis();
        long last = lastWakeScanMs.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < wakePlayerScanCooldownMs) return;
        lastWakeScanMs.put(p.getUniqueId(), now);

        int woke = wakeNearPlayer(p);
        if (woke > 0) {
            // Optional debug
        }

        // Nearby name hint to reduce clutter
        if (hideNameplates) {
            long lastAb = lastActionbarMs.getOrDefault(p.getUniqueId(), 0L);
            if (now - lastAb >= nearbyHintCooldownMs) {
                NPC nearest = findNearestManagedNpc(p.getLocation(), nearbyHintRadius);
                if (nearest != null) {
                    lastActionbarMs.put(p.getUniqueId(), now);
                    String n = safeStrip(nearest.getName());
                    if (n == null || n.isBlank()) n = "NPC";
                    sendActionBarSafe(p, ChatColor.GRAY + n);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractEntityEvent e) {
        if (!enabled) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(e.getRightClicked());
        if (npc == null) return;
        if (!npc.isSpawned()) return;

        if (!isManagedNpc(npc)) return;
        if (isExcludedNpc(npc)) return;

        Player p = e.getPlayer();

        // “Wake” on interaction in case it was missed
        wakeNpc(npc, "interact");

        p.sendMessage(ChatColor.GRAY + buildDialogue(npc, p));
    }

    /* ============================================================
     *  Wake / adopt logic
     * ============================================================ */

    private int wakeAllSpawnedNpcs(String reason) {
        int woke = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;

            // If it’s already managed, just ensure it is hooked
            if (isManagedNpc(npc)) {
                if (wakeNpc(npc, reason)) woke++;
                continue;
            }

            // If it looks like ours, adopt and wake
            if (looksLikeLegacyVtf(npc)) {
                tagManaged(npc);
                if (wakeNpc(npc, reason)) woke++;
            }
        }
        return woke;
    }

    private int wakeChunk(Chunk c) {
        int woke = 0;
        int processed = 0;

        for (Entity ent : c.getEntities()) {
            if (processed++ >= wakeChunkMaxPerChunk) break;

            NPC npc = CitizensAPI.getNPCRegistry().getNPC(ent);
            if (npc == null) continue;
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;

            if (isManagedNpc(npc)) {
                if (wakeNpc(npc, "chunkload")) woke++;
                continue;
            }

            if (looksLikeLegacyVtf(npc)) {
                tagManaged(npc);
                if (wakeNpc(npc, "chunkload")) woke++;
            }
        }

        return woke;
    }

    private int wakeNearPlayer(Player p) {
        Location pl = p.getLocation();
        World w = pl.getWorld();
        if (w == null) return 0;

        int woke = 0;
        int scanned = 0;

        // Scan nearby entities (cheap)
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

            if (looksLikeLegacyVtf(npc)) {
                tagManaged(npc);
                if (wakeNpc(npc, "proximity")) woke++;
            }
        }

        return woke;
    }

    private int adoptAllSpawnedLegacyByName() {
        int adopted = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;
            if (isManagedNpc(npc)) continue;

            if (looksLikeLegacyVtfByName(npc)) {
                if (adoptByNameNearSitesOnly) {
                    Site near = findNearestSite(npc.getEntity().getLocation(), siteAdoptRadius);
                    if (near == null) continue;
                }
                tagManaged(npc);
                adopted++;
            }
        }
        return adopted;
    }

    private boolean wakeNpc(NPC npc, String reason) {
        if (npc == null) return false;
        if (!npc.isSpawned()) return false;
        if (isExcludedNpc(npc)) return false;
        if (!isManagedNpc(npc)) return false;

        applyNameplatePolicy(npc);
        ensureRoleAndSite(npc);

        int id = npc.getId();
        if (!autonomyTasks.containsKey(id)) {
            long periodSeconds = 4 + ThreadLocalRandom.current().nextInt(6);
            BukkitTask t = Bukkit.getScheduler().runTaskTimer(this, () -> autonomyTick(npc), 20L, 20L * periodSeconds);
            autonomyTasks.put(id, t);
        }

        return true;
    }

    private boolean looksLikeLegacyVtf(NPC npc) {
        // Prefer explicit tag
        if (isManagedNpc(npc)) return true;
        // Otherwise, allow name-prefix match (with exclusions)
        return looksLikeLegacyVtfByName(npc);
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
            if (lower.equals(s) || lower.contains(s.replace(" ", ""))) return true;
        }
        return false;
    }

    private boolean isExcludedNpc(NPC npc) {
        // Name-based exclusions
        String n = safeStrip(npc.getName());
        if (n != null && isExcludedName(n.toLowerCase(Locale.ROOT))) return true;

        // Tag-based exclusions (don’t touch other plugins)
        if (!npc.isSpawned()) return false;
        Entity ent = npc.getEntity();
        if (ent == null) return false;

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
        pdc.set(KEY_VTF_VER, PersistentDataType.STRING, "reset");
    }

    private void applyNameplatePolicy(NPC npc) {
        if (!hideNameplates) return;
        if (!npc.isSpawned()) return;
        Entity ent = npc.getEntity();
        if (ent == null) return;

        try { ent.setCustomNameVisible(false); } catch (Throwable ignored) {}
    }

    private void ensureRoleAndSite(NPC npc) {
        if (!npc.isSpawned()) return;
        Entity ent = npc.getEntity();
        if (ent == null) return;

        PersistentDataContainer pdc = ent.getPersistentDataContainer();

        String role = pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING);
        if (role == null || role.isBlank()) {
            role = inferRoleFromName(npc.getName());
            if (role == null) role = "townsfolk";
            pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
        }

        String siteName = pdc.get(KEY_VTF_SITE, PersistentDataType.STRING);
        if (siteName == null || siteName.isBlank()) {
            Site near = findNearestSite(ent.getLocation(), siteAdoptRadius);
            if (near != null) {
                pdc.set(KEY_VTF_SITE, PersistentDataType.STRING, near.name);
            }
        }
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

    /* ============================================================
     *  Autonomy tick (routines + light combat + doors + “work”)
     * ============================================================ */

    private void autonomyTick(NPC npc) {
        if (npc == null) return;
        if (!npc.isSpawned()) return;
        if (!isManagedNpc(npc)) return;
        if (isExcludedNpc(npc)) return;

        Entity ent = npc.getEntity();
        if (!(ent instanceof LivingEntity)) return;

        LivingEntity le = (LivingEntity) ent;
        Location base = le.getLocation();
        World w = base.getWorld();
        if (w == null) return;

        ensureRoleAndSite(npc);

        String role = getRole(npc, le);
        boolean night = isNight(w);

        maybeToggleNearbyDoorOrGate(base);

        // Opportunistic “defend” near routine
        if (maybeDefendAgainstNearbyHostile(npc, le, base, role)) return;

        // If currently navigating, usually leave it alone
        if (npc.getNavigator().isNavigating() && ThreadLocalRandom.current().nextDouble() < 0.75) return;

        double r = ThreadLocalRandom.current().nextDouble();

        // Night bias: most non-guards idle more
        if (night && !role.equals("guard") && r < 0.55) {
            maybeIdleLookAround(le);
            return;
        }

        // Role-flavored actions
        if (role.equals("guard")) {
            if (night && r < 0.60) { navigatePatrol(npc, base, 10); return; }
            if (r < 0.25) { maybeIdleLookAround(le); return; }
            if (r < 0.55) { navigatePatrol(npc, base, 8); return; }
            wander(npc, base, 8);
            return;
        }

        if (role.equals("worker") || role.equals("dockhand")) {
            if (r < 0.35) {
                fakeInteractNearbyWorkBlock(le, base, Arrays.asList(
                        Material.CRAFTING_TABLE, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
                        Material.CHEST, Material.BARREL, Material.ANVIL
                ));
                return;
            }
            if (r < 0.50) { maybeIdleLookAround(le); return; }
            if (role.equals("dockhand") && r < 0.80) { wanderNearWater(npc, base, 10); return; }
            wander(npc, base, 10);
            return;
        }

        if (role.equals("priest") || role.equals("scholar")) {
            if (r < 0.55) {
                fakeInteractNearbyWorkBlock(le, base, Arrays.asList(Material.LECTERN, Material.BOOKSHELF));
                return;
            }
            if (r < 0.75) { maybeIdleLookAround(le); return; }
            wander(npc, base, 7);
            return;
        }

        if (role.equals("ranger") || role.equals("hermit")) {
            if (r < 0.25) { maybeIdleLookAround(le); return; }
            wander(npc, base, 12);
            return;
        }

        // townsfolk default
        if (r < 0.25) {
            fakeInteractNearbyWorkBlock(le, base, Arrays.asList(Material.CHEST, Material.BARREL, Material.CRAFTING_TABLE));
            return;
        }
        if (r < 0.40) { maybeIdleLookAround(le); return; }
        wander(npc, base, 9);
    }

    private String getRole(NPC npc, LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        String role = pdc.get(KEY_VTF_ROLE, PersistentDataType.STRING);
        if (role != null && !role.isBlank()) return role;
        return "townsfolk";
    }

    private static boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000 && t <= 23000;
    }

    private void maybeIdleLookAround(LivingEntity le) {
        Location l = le.getLocation();
        float yaw = l.getYaw();
        yaw += ThreadLocalRandom.current().nextFloat() * 80f - 40f;
        l.setYaw(yaw);
        try { le.teleport(l); } catch (Throwable ignored) {}
    }

    private void wander(NPC npc, Location base, int radius) {
        Location target = findSafeGroundNear(base, radius);
        if (target == null) return;
        npc.getNavigator().setTarget(target);
    }

    private void navigatePatrol(NPC npc, Location base, int radius) {
        // Patrol biased toward site center if we have it
        Entity ent = npc.getEntity();
        if (ent != null) {
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            String siteName = pdc.get(KEY_VTF_SITE, PersistentDataType.STRING);
            if (siteName != null) {
                Site s = findSiteByName(siteName);
                if (s != null) {
                    World w = base.getWorld();
                    if (w != null && w.getName().equalsIgnoreCase(s.worldName)) {
                        Location anchor = new Location(w, s.x + 0.5, base.getY(), s.z + 0.5);
                        Location t = findSafeGroundNear(anchor, radius);
                        if (t != null) {
                            npc.getNavigator().setTarget(t);
                            return;
                        }
                    }
                }
            }
        }
        wander(npc, base, radius);
    }

    private void wanderNearWater(NPC npc, Location base, int radius) {
        for (int tries = 0; tries < 10; tries++) {
            Location cand = findSafeGroundNear(base, radius);
            if (cand == null) break;
            if (isWaterNearby(cand, 3)) {
                npc.getNavigator().setTarget(cand);
                return;
            }
        }
        wander(npc, base, radius);
    }

    private Location findSafeGroundNear(Location base, int radius) {
        World w = base.getWorld();
        if (w == null) return null;

        for (int tries = 0; tries < 20; tries++) {
            double dx = ThreadLocalRandom.current().nextDouble(-radius, radius);
            double dz = ThreadLocalRandom.current().nextDouble(-radius, radius);

            int tx = base.getBlockX() + (int) Math.round(dx);
            int tz = base.getBlockZ() + (int) Math.round(dz);

            int topY = w.getHighestBlockYAt(tx, tz);
            int spawnY = topY + 1;

            Block below = w.getBlockAt(tx, topY, tz);
            Material ground = below.getType();

            // Avoid leaves/stairs/scaffolding and liquid tops
            String gn = ground.name();
            if (gn.endsWith("_STAIRS")) continue;
            if (gn.endsWith("_LEAVES") || gn.contains("LEAVES")) continue;
            if (ground == Material.SCAFFOLDING) continue;
            if (ground == Material.WATER || ground == Material.LAVA) continue;

            // Ensure two blocks of air
            Material at = w.getBlockAt(tx, spawnY, tz).getType();
            Material atUp = w.getBlockAt(tx, spawnY + 1, tz).getType();
            if (!at.isAir() || !atUp.isAir()) continue;

            return new Location(w, tx + 0.5, spawnY, tz + 0.5);
        }

        return null;
    }

    private boolean isWaterNearby(Location loc, int r) {
        World w = loc.getWorld();
        if (w == null) return false;

        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                Material m = w.getBlockAt(cx + x, cy - 1, cz + z).getType();
                if (m == Material.WATER) return true;
            }
        }
        return false;
    }

    private void maybeToggleNearbyDoorOrGate(Location base) {
        World w = base.getWorld();
        if (w == null) return;

        Block b = w.getBlockAt(base);
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (tryOpenCloseOpenable(b.getRelative(face))) return;
            if (tryOpenCloseOpenable(b.getRelative(face).getRelative(BlockFace.UP))) return;
        }
    }

    private boolean tryOpenCloseOpenable(Block block) {
        Material type = block.getType();
        String tn = type.name();
        if (!(tn.endsWith("_DOOR") || tn.endsWith("_GATE"))) return false;
        if (!(block.getBlockData() instanceof Openable)) return false;

        Openable o = (Openable) block.getBlockData();
        if (o.isOpen()) return false;

        o.setOpen(true);
        block.setBlockData(o, true);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                if (!(block.getBlockData() instanceof Openable)) return;
                Openable o2 = (Openable) block.getBlockData();
                o2.setOpen(false);
                block.setBlockData(o2, true);
            } catch (Throwable ignored) {}
        }, 30L + ThreadLocalRandom.current().nextInt(40));

        return true;
    }

    private void fakeInteractNearbyWorkBlock(LivingEntity npcEnt, Location base, List<Material> preferred) {
        World w = base.getWorld();
        if (w == null) return;

        int r = 4;
        Block best = null;
        double bestDist = Double.MAX_VALUE;

        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = w.getBlockAt(bx + x, by + y, bz + z);
                    Material mt = b.getType();
                    if (!preferred.contains(mt)) continue;

                    double d2 = b.getLocation().distanceSquared(base);
                    if (d2 < bestDist) {
                        bestDist = d2;
                        best = b;
                    }
                }
            }
        }

        if (best == null) return;

        Location face = npcEnt.getLocation().clone();
        Vector dir = best.getLocation().toVector().add(new Vector(0.5, 0.5, 0.5)).subtract(face.toVector());
        face.setYaw(yawFromVector(dir));
        try { npcEnt.teleport(face); } catch (Throwable ignored) {}
        try { npcEnt.swingMainHand(); } catch (Throwable ignored) {}
    }

    private static float yawFromVector(Vector v) {
        double yaw = Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
        return (float) yaw;
    }

    private boolean maybeDefendAgainstNearbyHostile(NPC npc, LivingEntity npcEnt, Location base, String role) {
        double engageChance;
        if ("guard".equals(role)) engageChance = engageChanceGuards;
        else if ("worker".equals(role) || "dockhand".equals(role)) engageChance = engageChanceWorkers;
        else engageChance = engageChanceOther;

        if (ThreadLocalRandom.current().nextDouble() > engageChance) return false;

        double radius = 7.0;
        Collection<Entity> nearby = base.getWorld().getNearbyEntities(base, radius, radius, radius);

        LivingEntity target = null;
        double bestD2 = Double.MAX_VALUE;

        for (Entity e : nearby) {
            if (!(e instanceof Monster)) continue;
            if (!(e instanceof LivingEntity)) continue;

            LivingEntity le = (LivingEntity) e;
            double d2 = le.getLocation().distanceSquared(base);
            if (d2 < bestD2) {
                bestD2 = d2;
                target = le;
            }
        }

        if (target == null) return false;

        // Move toward it and do a tiny “hit”
        npc.getNavigator().setTarget(target.getLocation());
        try { npcEnt.swingMainHand(); } catch (Throwable ignored) {}

        try {
            if (target.isValid() && !target.isDead()) {
                target.damage(1.0); // half-heart
            }
        } catch (Throwable ignored) {}

        // Return to routine shortly
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                if (!npc.isSpawned()) return;
                npc.getNavigator().cancelNavigation();
            } catch (Throwable ignored) {}
        }, 60L);

        return true;
    }

    /* ============================================================
     *  Dialogue (local procedural + memory)
     * ============================================================ */

    private String buildDialogue(NPC npc, Player p) {
        long now = System.currentTimeMillis();
        int npcId = npc.getId();

        Map<UUID, DialogueMemory> byPlayer = dialogueMemory.computeIfAbsent(npcId, k -> new HashMap<>());
        DialogueMemory mem = byPlayer.get(p.getUniqueId());

        if (mem != null && now - mem.lastSpokeAtMs > 120_000L) {
            byPlayer.remove(p.getUniqueId());
            mem = null;
        }

        Entity ent = npc.getEntity();
        World w = ent != null ? ent.getWorld() : null;
        boolean night = (w != null) && isNight(w);

        String role = "townsfolk";
        if (ent instanceof LivingEntity) {
            role = getRole(npc, (LivingEntity) ent);
        }

        if (mem == null) {
            DialogueMemory m = new DialogueMemory(now, "greet");
            byPlayer.put(p.getUniqueId(), m);
            return pickGreeting(role, night);
        }

        mem.timesSpokenRecently++;
        mem.lastSpokeAtMs = now;

        if (mem.timesSpokenRecently >= 3) {
            mem.lastTopic = "impatient";
            return pickImpatient(role, night);
        }

        mem.lastTopic = "followup";
        return pickFollowup(role, night);
    }

    private String pickGreeting(String role, boolean night) {
        List<String> lines = new ArrayList<>();
        if (night) {
            lines.add("…You’re out late.");
            lines.add("Keep your voice down. The dark carries.");
            lines.add("Not the best hour for visitors.");
        } else {
            lines.add("Morning. Or close enough.");
            lines.add("If you’re looking for someone, keep walking.");
            lines.add("Welcome. Don’t make it a habit.");
        }

        if (role.equals("guard")) lines.add(night ? "Stay near the light." : "Keep to the roads.");
        else if (role.equals("worker")) lines.add(night ? "Work’s done. Come back tomorrow." : "Hands are busy. Speak quick.");
        else if (role.equals("priest") || role.equals("scholar")) lines.add(night ? "Some books should stay closed at night." : "History has sharp edges.");
        else if (role.equals("hermit")) lines.add("I don’t remember inviting you.");

        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    private String pickFollowup(String role, boolean night) {
        List<String> lines = new ArrayList<>();
        if (night) {
            lines.add("If you hear footsteps behind you, don’t turn.");
            lines.add("The town sleeps. The roads do not.");
            lines.add("You’re safer inside than you think.");
        } else {
            lines.add("People come here with maps. Maps don’t help.");
            lines.add("We mend what we can. We ignore the rest.");
            lines.add("Some places aren’t on the signposts.");
        }

        if (role.equals("guard")) lines.add(night ? "I saw something move past the wall." : "No trouble today.");
        else if (role.equals("worker")) lines.add("Supplies go missing. Not stolen—just… gone.");
        else if (role.equals("priest")) lines.add("Prayers don’t stop everything. Just the worst of it.");
        else if (role.equals("scholar")) lines.add("Stories repeat. Names change. The shape stays.");
        else if (role.equals("dockhand")) lines.add("The water brings things. Sometimes it takes them back.");

        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    private String pickImpatient(String role, boolean night) {
        List<String> lines = new ArrayList<>();
        lines.add("That’s all I’ve got.");
        lines.add("I’ve said enough.");
        lines.add("Go on, then.");
        if (night) lines.add("Not now.");
        if (role.equals("guard")) lines.add("Move along.");
        if (role.equals("hermit")) lines.add("Leave.");
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /* ============================================================
     *  Population maintainer (spawns new VTF NPCs near sites)
     * ============================================================ */

    private void startPopulationMaintainer() {
        if (populationTask != null) populationTask.cancel();
        populationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try { runPopulationCycle(); } catch (Throwable ignored) {}
        }, 20L * 10L, 20L * populationCycleSeconds);
    }

    private int runPopulationCycle() {
        if (sites.isEmpty()) return 0;

        int spawned = 0;

        // Shuffle sites so we spread spawns
        List<Site> shuffled = new ArrayList<>(sites);
        Collections.shuffle(shuffled);

        for (Site s : shuffled) {
            if (spawned >= populationMaxSpawnPerCycle) break;

            World w = Bukkit.getWorld(s.worldName);
            if (w == null) continue;

            Location siteLoc = new Location(w, s.x + 0.5, w.getHighestBlockYAt(s.x, s.z) + 1, s.z + 0.5);

            int count = countManagedNear(siteLoc, populationRadius);
            if (count >= populationMaxPerSite) continue;

            int need = Math.max(0, populationMinPerSite - count);
            if (need <= 0) continue;

            int spawnNow = Math.min(need, populationMaxSpawnPerCycle - spawned);
            for (int i = 0; i < spawnNow; i++) {
                NPC created = spawnNewManagedNpcNearSite(s, siteLoc);
                if (created != null) spawned++;
                if (spawned >= populationMaxSpawnPerCycle) break;
            }
        }

        if (spawned > 0) getLogger().info("Population cycle spawned: " + spawned);
        return spawned;
    }

    private int countManagedNear(Location center, int radius) {
        int c = 0;
        World w = center.getWorld();
        if (w == null) return 0;

        double r2 = (double) radius * radius;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc)) continue;
            if (isExcludedNpc(npc)) continue;

            Entity ent = npc.getEntity();
            if (ent == null || ent.getWorld() != w) continue;
            if (ent.getLocation().distanceSquared(center) <= r2) c++;
        }
        return c;
    }

    private NPC spawnNewManagedNpcNearSite(Site site, Location siteLoc) {
        Location spawnLoc = findSafeGroundNear(siteLoc, Math.min(28, populationRadius / 2));
        if (spawnLoc == null) return null;

        String role = pickRoleForSite(site.name);
        String baseName = roleDisplay(role);

        String name = baseName + " " + (100 + ThreadLocalRandom.current().nextInt(900));

        try {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
            npc.spawn(spawnLoc);

            tagManaged(npc);

            Entity ent = npc.getEntity();
            if (ent != null) {
                PersistentDataContainer pdc = ent.getPersistentDataContainer();
                pdc.set(KEY_VTF_ROLE, PersistentDataType.STRING, role);
                pdc.set(KEY_VTF_SITE, PersistentDataType.STRING, site.name);
            }

            // Immediately wake (adds autonomy + hides nameplate)
            wakeNpc(npc, "spawn");

            return npc;
        } catch (Throwable t) {
            return null;
        }
    }

    private String pickRoleForSite(String siteName) {
        double r = ThreadLocalRandom.current().nextDouble();

        // Simple weighting: mostly townsfolk/workers, some guards, rare priest/scholar
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

    private int purgeAllManagedNpcs() {
        int removed = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (isExcludedNpc(npc)) continue;
            if (!isManagedNpc(npc)) continue;

            try {
                npc.despawn();
                npc.destroy();
                removed++;
            } catch (Throwable ignored) {}
        }
        // Cancel tasks map entries too
        for (Iterator<Map.Entry<Integer, BukkitTask>> it = autonomyTasks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BukkitTask> en = it.next();
            try { en.getValue().cancel(); } catch (Throwable ignored) {}
            it.remove();
        }
        return removed;
    }

    /* ============================================================
     *  Sites (from dynmap markers.yml if present; and optional sites.txt)
     * ============================================================ */

    private void loadSites() {
        sites.clear();

        // 1) Parse Dynmap markers.yml if present (read-only; no dynmap dependency)
        File pluginsDir = getDataFolder().getParentFile();
		if (pluginsDir == null) pluginsDir = new File("plugins");
		File dynmapMarkers = new File(pluginsDir, "dynmap" + File.separator + "markers.yml");

        if (dynmapMarkers.exists()) {
            int parsed = parseDynmapMarkersYml(dynmapMarkers);
            if (parsed > 0) getLogger().info("Loaded sites from dynmap/markers.yml: " + parsed);
        }

        // 2) Parse our own sites.txt if present (simple format)
        // Each line: world;x;z;name
        File localSites = new File(getDataFolder(), "sites.txt");
        if (localSites.exists()) {
            int parsed = parseLocalSitesTxt(localSites);
            if (parsed > 0) getLogger().info("Loaded sites from plugins/ViridianTownsFolk/sites.txt: " + parsed);
        }

        // If still empty, write a stub
        if (sites.isEmpty()) {
            writeSitesStub(localSites);
        }
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
        // This is intentionally a lightweight heuristic parser:
        // It scans for marker blocks that contain:
        //   label: "Name"
        //   world: "world"
        //   x: <number>
        //   z: <number>
        // Works for typical dynmap markers.yml.
        int added = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;

            String label = null;
            String world = null;
            Integer x = null;
            Integer z = null;

            while ((line = br.readLine()) != null) {
                String t = line.trim();

                // Reset “current marker” when we hit an id header line like "someid:"
                if (t.endsWith(":") && !t.startsWith("-") && !t.startsWith("label:") && !t.startsWith("world:")) {
                    // If we have a complete marker, add it
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

            // final flush
            if (label != null && world != null && x != null && z != null) {
                if (!shouldIgnoreSiteName(label)) {
                    sites.add(new Site(label, world, x, z));
                    added++;
                }
            }

        } catch (Throwable ignored) {}

        return added;
    }

    private boolean shouldIgnoreSiteName(String name) {
        if (name == null) return true;
        String n = name.toLowerCase(Locale.ROOT);

        // Your explicit ignores (crowded / mistake)
        if (n.contains("sharko")) return true;
        if (n.contains("le_trole_faic") || n.contains("trole") || n.contains("trollface")) return true;
        if (n.contains("alpha") && (n.contains("house") || n.contains("house_"))) return true;
        if (n.contains("castle loren")) return true;

        return false;
    }

    private Site findSiteByName(String name) {
        if (name == null) return null;
        for (Site s : sites) {
            if (s.name.equalsIgnoreCase(name)) return s;
        }
        return null;
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
     *  Utilities
     * ============================================================ */

    private NPC findNearestManagedNpc(Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) return null;
        World w = loc.getWorld();
        double bestD2 = (double) radius * radius;
        NPC best = null;

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            if (!isManagedNpc(npc)) continue;
            if (isExcludedNpc(npc)) continue;
            Entity e = npc.getEntity();
            if (e == null || e.getWorld() != w) continue;
            double d2 = e.getLocation().distanceSquared(loc);
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = npc;
            }
        }
        return best;
    }

    private void sendActionBarSafe(Player p, String msg) {
        try {
            Method m = Player.class.getMethod("sendActionBar", String.class);
            m.invoke(p, msg);
            return;
        } catch (Throwable ignored) {}
        // fallback: don't spam chat
    }

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
     *  Config file generation (no embedded config.yml)
     * ============================================================ */

    private void ensureConfigFile() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        File cfg = new File(getDataFolder(), "config.yml");
        if (cfg.exists()) return;

        String defaults =
                "enabled: true\n" +
                "\n" +
                "nametags:\n" +
                "  hideNameplates: true\n" +
                "  nearbyHintRadius: 10\n" +
                "  nearbyHintCooldownMs: 2000\n" +
                "\n" +
                "wake:\n" +
                "  playerScanRadius: 48\n" +
                "  playerScanCooldownMs: 2500\n" +
                "  maxPerScan: 20\n" +
                "  chunkMaxPerChunk: 50\n" +
                "\n" +
                "adoption:\n" +
                "  adoptByNameNearSitesOnly: true\n" +
                "  siteAdoptRadius: 128\n" +
                "\n" +
                "population:\n" +
                "  minPerSite: 8\n" +
                "  maxPerSite: 18\n" +
                "  radius: 64\n" +
                "  cycleSeconds: 90\n" +
                "  maxSpawnPerCycle: 6\n" +
                "\n" +
                "combat:\n" +
                "  engageChanceGuards: 0.85\n" +
                "  engageChanceWorkers: 0.20\n" +
                "  engageChanceOther: 0.06\n";

        try (FileOutputStream fos = new FileOutputStream(cfg, false)) {
            fos.write(defaults.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            getLogger().warning("Failed to write default config.yml");
        }
    }

    private void loadConfigValues() {
        File cfg = new File(getDataFolder(), "config.yml");
        if (!cfg.exists()) return;

        // Use Bukkit YamlConfiguration via reflection to avoid extra imports clutter
        try {
            Class<?> yamlCls = Class.forName("org.bukkit.configuration.file.YamlConfiguration");
            Method loadCfg = yamlCls.getMethod("loadConfiguration", File.class);
            Object yc = loadCfg.invoke(null, cfg);

            Method getBool = yamlCls.getMethod("getBoolean", String.class, boolean.class);
            Method getInt = yamlCls.getMethod("getInt", String.class, int.class);
            Method getDouble = yamlCls.getMethod("getDouble", String.class, double.class);

            enabled = (boolean) getBool.invoke(yc, "enabled", enabled);

            hideNameplates = (boolean) getBool.invoke(yc, "nametags.hideNameplates", hideNameplates);
            nearbyHintRadius = (int) getInt.invoke(yc, "nametags.nearbyHintRadius", nearbyHintRadius);
            nearbyHintCooldownMs = (int) getInt.invoke(yc, "nametags.nearbyHintCooldownMs", nearbyHintCooldownMs);

            wakePlayerScanRadius = (int) getInt.invoke(yc, "wake.playerScanRadius", wakePlayerScanRadius);
            wakePlayerScanCooldownMs = (int) getInt.invoke(yc, "wake.playerScanCooldownMs", wakePlayerScanCooldownMs);
            wakeMaxPerScan = (int) getInt.invoke(yc, "wake.maxPerScan", wakeMaxPerScan);
            wakeChunkMaxPerChunk = (int) getInt.invoke(yc, "wake.chunkMaxPerChunk", wakeChunkMaxPerChunk);

            adoptByNameNearSitesOnly = (boolean) getBool.invoke(yc, "adoption.adoptByNameNearSitesOnly", adoptByNameNearSitesOnly);
            siteAdoptRadius = (int) getInt.invoke(yc, "adoption.siteAdoptRadius", siteAdoptRadius);

            populationMinPerSite = (int) getInt.invoke(yc, "population.minPerSite", populationMinPerSite);
            populationMaxPerSite = (int) getInt.invoke(yc, "population.maxPerSite", populationMaxPerSite);
            populationRadius = (int) getInt.invoke(yc, "population.radius", populationRadius);
            populationCycleSeconds = (int) getInt.invoke(yc, "population.cycleSeconds", populationCycleSeconds);
            populationMaxSpawnPerCycle = (int) getInt.invoke(yc, "population.maxSpawnPerCycle", populationMaxSpawnPerCycle);

            engageChanceGuards = (double) getDouble.invoke(yc, "combat.engageChanceGuards", engageChanceGuards);
            engageChanceWorkers = (double) getDouble.invoke(yc, "combat.engageChanceWorkers", engageChanceWorkers);
            engageChanceOther = (double) getDouble.invoke(yc, "combat.engageChanceOther", engageChanceOther);

        } catch (Throwable t) {
            getLogger().warning("Config load failed, using defaults.");
        }
    }
}
