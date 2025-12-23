// IndevMobs.java
// Author: ChatGPT
package com.redchanit.indevmobs;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.world.ChunkLoadEvent;


import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;

public final class IndevMobs extends JavaPlugin implements Listener {

    // PDC markers
    private NamespacedKey indevMobKey;
    private NamespacedKey indevMobTypeKey;

    // Citizens
    private boolean citizensAvailable = false;

    // Citizens persistent data keys (stored on NPCs so they survive restarts)
    private static final String CIT_KEY_INDEVMOBS = "indevmobs";
    private static final String CIT_KEY_TYPE = "indevmobs_type";

    // skin cache: mobType -> SkinData(value,signature)
    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();

    // entity UUID -> mobType
    private final Map<UUID, String> spawnedEntities = new ConcurrentHashMap<>();

    // entity UUID -> NPC id (Citizens)
    private final Map<UUID, Integer> citizensNpcIdByEntity = new ConcurrentHashMap<>();

    // tasks
    private int spawnTaskId = -1;
    private int wanderTaskId = -1;
    private int dynmapTaskId = -1;
    private int enforceTaskId = -1;
    private int adoptTaskId = -1;

    // Dynmap (reflection, no compile dep)
    private boolean dynmapAvailable = false;
    private Plugin dynmapPlugin = null;
    private Object dynmapApi = null;         // org.dynmap.DynmapAPI
    private Object dynmapMarkerApi = null;   // org.dynmap.markers.MarkerAPI
    private Object dynmapMarkerSet = null;   // org.dynmap.markers.MarkerSet
    private final Map<UUID, Object> dynmapMarkerByEntity = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        indevMobKey = new NamespacedKey(this, "indevmob");
        indevMobTypeKey = new NamespacedKey(this, "indevmob_type");

        ensureConfigDefaults();
        ensureSkinFolder();
        loadSkinCacheFromConfig();

        Plugin citizensPlugin = Bukkit.getPluginManager().getPlugin("Citizens");
        citizensAvailable = (citizensPlugin != null && citizensPlugin.isEnabled());
        if (citizensAvailable) {
            getLogger().info("Citizens detected. Humanoid Indev mobs enabled.");
        } else {
            getLogger().warning("Citizens not found/enabled. Install Citizens to enable IndevMobs spawning.");
        }

        initDynmap();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("skins.preloadOnEnable", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                for (String mobType : getMobTypes()) {
                    ensureSkinLoaded(mobType);
                }
                getLogger().info("MineSkin preload complete. Cached skins now: " + skinCache.keySet());
            });
        }

        startSpawner();
        startWanderLoop();
        startDynmapUpdater();
        startEnforceLoop();

        // Adopt/refresh any existing NPCs (including legacy ones) after the server is up
        scheduleAdoptPass();


    }

    @Override
    public void onDisable() {
        if (spawnTaskId != -1) Bukkit.getScheduler().cancelTask(spawnTaskId);
        if (wanderTaskId != -1) Bukkit.getScheduler().cancelTask(wanderTaskId);
        if (dynmapTaskId != -1) Bukkit.getScheduler().cancelTask(dynmapTaskId);
        if (enforceTaskId != -1) Bukkit.getScheduler().cancelTask(enforceTaskId);
        if (adoptTaskId != -1) Bukkit.getScheduler().cancelTask(adoptTaskId);

        spawnTaskId = -1;
        wanderTaskId = -1;
        dynmapTaskId = -1;
        enforceTaskId = -1;
        adoptTaskId = -1;

        // delete dynmap markers
        for (Object marker : dynmapMarkerByEntity.values()) {
            try {
                marker.getClass().getMethod("deleteMarker").invoke(marker);
            } catch (Throwable ignored) {}
        }
        dynmapMarkerByEntity.clear();

        spawnedEntities.clear();
        citizensNpcIdByEntity.clear();
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("indevmobs")) return false;

        if (args.length == 0) {
            sender.sendMessage("Usage: /indevmobs <status|reload|forcespawn>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            reloadConfig();
            ensureSkinFolder();
            loadSkinCacheFromConfig();
            initDynmap();
            sender.sendMessage("IndevMobs config reloaded.");
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage("IndevMobs v" + getDescription().getVersion());
            sender.sendMessage("Citizens: " + (citizensAvailable ? "OK" : "MISSING"));
            sender.sendMessage("Dynmap: " + (dynmapAvailable ? "OK" : "MISSING/DISABLED"));
            sender.sendMessage("Tracked indev mobs (alive): " + countAliveAllWorlds());
            sender.sendMessage("Skin cache keys: " + skinCache.keySet());

            File skinDir = getSkinDirectory();
            sender.sendMessage("Local skins folder: " + skinDir.getPath());
            for (String type : getMobTypes()) {
                File skinFile = new File(skinDir, type + ".png");
                sender.sendMessage(" - " + type + ".png: " + (skinFile.exists() ? (skinFile.length() + " bytes") : "MISSING"));
            }
            return true;
        }

        if (sub.equals("forcespawn")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Player-only command.");
                return true;
            }
            Player player = (Player) sender;

            String mobTypeArg = "random";
            if (args.length >= 2) mobTypeArg = args[1].toLowerCase(Locale.ROOT);

            if (!citizensAvailable) {
                player.sendMessage("Citizens is not enabled; cannot spawn Indev mobs.");
                return true;
            }

            String chosenType;
            if (mobTypeArg.equals("random")) {
                chosenType = pickMobTypeByWeight();
                if (chosenType == null) chosenType = "rana";
            } else {
                chosenType = mobTypeArg;
            }

            if (!getMobTypes().contains(chosenType)) {
                player.sendMessage("Unknown type. Use rana|steve|blacksteve|beastboy|random");
                return true;
            }

            Location base = player.getLocation().clone();
            Location spawnLoc = base.add(base.getDirection().normalize().multiply(4.0));
            spawnLoc.setY(player.getWorld().getHighestBlockYAt(spawnLoc) + 1.0);

            boolean ok = spawnIndevNpc(chosenType, spawnLoc);
            if (ok) player.sendMessage("Spawned " + chosenType + " near you.");
            else player.sendMessage("Spawn FAILED. Check console or /indevmobs status.");
            return true;
        }

        sender.sendMessage("Usage: /indevmobs <status|reload|forcespawn>");
        return true;
    }

    private int countAliveAllWorlds() {
        int total = 0;
        Iterator<Map.Entry<UUID, String>> iterator = spawnedEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, String> entry = iterator.next();
            Entity ent = Bukkit.getEntity(entry.getKey());
            if (ent == null || !ent.isValid() || ent.isDead()) {
                iterator.remove();
                citizensNpcIdByEntity.remove(entry.getKey());
                removeDynmapMarker(entry.getKey());
                continue;
            }
            total++;
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Config defaults
    // -------------------------------------------------------------------------

    private void ensureConfigDefaults() {
        FileConfiguration cfg = getConfig();

        cfg.addDefault("enabled", true);

        cfg.addDefault("spawning.enabledWorlds", new ArrayList<String>());
        cfg.addDefault("spawning.tickInterval", 200);
        cfg.addDefault("spawning.attemptsPerIntervalPerPlayer", 1);
        cfg.addDefault("spawning.spawnChancePerAttempt", 0.10);
        cfg.addDefault("spawning.spawnRadiusBlocks", 96);
        cfg.addDefault("spawning.minDistanceFromPlayer", 24.0);
        cfg.addDefault("spawning.locationTries", 12);
        cfg.addDefault("spawning.maxPerWorld", 20);

        cfg.addDefault("spawning.weights.rana", 1.0);
        cfg.addDefault("spawning.weights.steve", 1.0);
        cfg.addDefault("spawning.weights.blacksteve", 1.0);
        cfg.addDefault("spawning.weights.beastboy", 1.0);

        cfg.addDefault("sounds.hurt.volume", 1.0);
        cfg.addDefault("sounds.hurt.pitch", 1.0);

        // wandering
        cfg.addDefault("wander.enabled", true);
        cfg.addDefault("wander.tickPeriod", 40);
        cfg.addDefault("wander.targetChanceWhenIdle", 0.50);
        cfg.addDefault("wander.verticalSearch", 3);

        cfg.addDefault("skins.preloadOnEnable", true);
        cfg.addDefault("skins.allowMineskinRequests", true);
        cfg.addDefault("skins.mineskinApiKey", "");
        cfg.addDefault("skins.userAgent", "IndevMobs/1.4");
        cfg.addDefault("skins.variant", "classic");
        cfg.addDefault("skins.visibility", "unlisted");
        cfg.addDefault("skins.useLocalFiles", true);
        cfg.addDefault("skins.localFolderName", "skins");

        // URLs are only informational now (you chose local files), but keep them
        cfg.addDefault("skins.urls.rana", "https://files.catbox.moe/yo0n7z.png");
        cfg.addDefault("skins.urls.steve", "https://files.catbox.moe/lj5tkg.png");
        cfg.addDefault("skins.urls.blacksteve", "https://files.catbox.moe/ue85z2.png");
        cfg.addDefault("skins.urls.beastboy", "https://files.catbox.moe/p9lszo.png");

        addMobDefaults(cfg, "rana", "Rana", "IndevRana");
        addMobDefaults(cfg, "steve", "Steve", "IndevSteve");
        addMobDefaults(cfg, "blacksteve", "Black Steve", "IndevBlkSteve");
        addMobDefaults(cfg, "beastboy", "Beast Boy", "IndevBeastBoy");

        addCommonSteveDrops(cfg, "drops.steve");
        addCommonSteveDrops(cfg, "drops.blacksteve");
        addCommonSteveDrops(cfg, "drops.beastboy");

        cfg.addDefault("drops.rana.common.APPLE.min", 0);
        cfg.addDefault("drops.rana.common.APPLE.max", 2);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.chance", 0.05);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.min", 0);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.max", 1);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.max", 1);

        // Dynmap marker layer
        cfg.addDefault("dynmap.enabled", true);
        cfg.addDefault("dynmap.markerSetId", "npcs");
        cfg.addDefault("dynmap.markerSetLabel", "NPCs");
        cfg.addDefault("dynmap.updateTicks", 40);

        // icon IDs (must exist in Dynmap marker icons)
        cfg.addDefault("dynmap.icons.rana", "indev_rana");
        cfg.addDefault("dynmap.icons.steve", "indev_steve");
        cfg.addDefault("dynmap.icons.blacksteve", "indev_blacksteve");
        cfg.addDefault("dynmap.icons.beastboy", "indev_beastboy");

        cfg.options().copyDefaults(true);
        saveConfig();
    }

    private void addMobDefaults(FileConfiguration cfg, String mobKey, String displayName, String profileName) {
        cfg.addDefault("mobs." + mobKey + ".displayName", displayName);
        cfg.addDefault("mobs." + mobKey + ".profileName", profileName);
        cfg.addDefault("mobs." + mobKey + ".maxHealth", 20.0);
        cfg.addDefault("mobs." + mobKey + ".wander.radiusBlocks", 16);
        cfg.addDefault("mobs." + mobKey + ".wander.speed", 1.0);
    }

    private void addCommonSteveDrops(FileConfiguration cfg, String basePath) {
        cfg.addDefault(basePath + ".common.FEATHER.min", 0);
        cfg.addDefault(basePath + ".common.FEATHER.max", 2);
        cfg.addDefault(basePath + ".common.GUNPOWDER.min", 0);
        cfg.addDefault(basePath + ".common.GUNPOWDER.max", 2);
        cfg.addDefault(basePath + ".common.STRING.min", 0);
        cfg.addDefault(basePath + ".common.STRING.max", 2);
        cfg.addDefault(basePath + ".rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault(basePath + ".rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault(basePath + ".rare.FLINT_AND_STEEL.max", 1);
    }

    private List<String> getMobTypes() {
        return Arrays.asList("rana", "steve", "blacksteve", "beastboy");
    }

    // -------------------------------------------------------------------------
    // Skins folder
    // -------------------------------------------------------------------------

    private void ensureSkinFolder() {
        File dir = getSkinDirectory();
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) getLogger().info("Created skin folder: " + dir.getPath());
        }
    }

    private File getSkinDirectory() {
        String folderName = getConfig().getString("skins.localFolderName", "skins");
        if (folderName == null || folderName.trim().isEmpty()) folderName = "skins";
        return new File(getDataFolder(), folderName);
    }

    // -------------------------------------------------------------------------
    // Spawner loop
    // -------------------------------------------------------------------------

    private void startSpawner() {
        if (spawnTaskId != -1) Bukkit.getScheduler().cancelTask(spawnTaskId);

        int intervalTicks = Math.max(20, getConfig().getInt("spawning.tickInterval", 200));
        spawnTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!citizensAvailable) return;
            if (!getConfig().getBoolean("enabled", true)) return;

            for (World world : Bukkit.getWorlds()) {
                List<String> enabledWorlds = getConfig().getStringList("spawning.enabledWorlds");
                if (enabledWorlds != null && !enabledWorlds.isEmpty() && !enabledWorlds.contains(world.getName())) {
                    continue;
                }

                int maxPerWorld = Math.max(0, getConfig().getInt("spawning.maxPerWorld", 20));
                int currentCount = countAliveInWorld(world);
                if (currentCount >= maxPerWorld) continue;

                List<Player> players = world.getPlayers();
                if (players.isEmpty()) continue;

                int attemptsPerPlayer = Math.max(0, getConfig().getInt("spawning.attemptsPerIntervalPerPlayer", 1));
                int radius = Math.max(16, getConfig().getInt("spawning.spawnRadiusBlocks", 96));
                double spawnChance = clamp01(getConfig().getDouble("spawning.spawnChancePerAttempt", 0.10));

                for (Player player : players) {
                    for (int attemptIndex = 0; attemptIndex < attemptsPerPlayer; attemptIndex++) {
                        if (currentCount >= maxPerWorld) break;
                        if (Math.random() > spawnChance) continue;

                        Location spawnLoc = pickSpawnLocationNear(player.getLocation(), radius);
                        if (spawnLoc == null) continue;

                        double minDistance = Math.max(8.0, getConfig().getDouble("spawning.minDistanceFromPlayer", 24.0));
                        if (spawnLoc.distanceSquared(player.getLocation()) < (minDistance * minDistance)) continue;

                        String mobType = pickMobTypeByWeight();
                        if (mobType == null) continue;

                        boolean spawned = spawnIndevNpc(mobType, spawnLoc);
                        if (spawned) currentCount++;
                    }
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private int countAliveInWorld(World world) {
        int count = 0;
        Iterator<Map.Entry<UUID, String>> iterator = spawnedEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, String> entry = iterator.next();
            UUID uuid = entry.getKey();
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                iterator.remove();
                citizensNpcIdByEntity.remove(uuid);
                removeDynmapMarker(uuid);
                continue;
            }
            if (entity.getWorld().equals(world)) count++;
        }
        return count;
    }

    private Location pickSpawnLocationNear(Location origin, int radius) {
        World world = origin.getWorld();
        if (world == null) return null;

        int tries = Math.max(5, getConfig().getInt("spawning.locationTries", 12));

        for (int tryIndex = 0; tryIndex < tries; tryIndex++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);

            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;

            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            if (!isChunkGeneratedSafe(world, chunkX, chunkZ)) continue;

            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (!chunk.isLoaded()) chunk.load();

            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight()) continue;
            if (y >= world.getMaxHeight() - 3) continue;

            Location base = new Location(world, x + 0.5, y, z + 0.5);
            if (!base.getBlock().getType().isAir()) continue;
            if (!base.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
            if (base.clone().add(0, -1, 0).getBlock().isLiquid()) continue;

            return base;
        }
        return null;
    }

    private boolean isChunkGeneratedSafe(World world, int chunkX, int chunkZ) {
        try {
            return world.isChunkGenerated(chunkX, chunkZ);
        } catch (Throwable ignored) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            return chunk.isLoaded();
        }
    }

    private String pickMobTypeByWeight() {
        ConfigurationSection weights = getConfig().getConfigurationSection("spawning.weights");
        if (weights == null) return "rana";

        double total = 0.0;
        Map<String, Double> weighted = new HashMap<>();
        for (String type : getMobTypes()) {
            double value = Math.max(0.0, weights.getDouble(type, 1.0));
            weighted.put(type, value);
            total += value;
        }
        if (total <= 0.0) return null;

        double roll = Math.random() * total;
        double running = 0.0;
        for (String type : getMobTypes()) {
            running += weighted.getOrDefault(type, 0.0);
            if (roll <= running) return type;
        }
        return "rana";
    }

    // -------------------------------------------------------------------------
    // Citizens spawn + setup
    // -------------------------------------------------------------------------

    private boolean spawnIndevNpc(String mobType, Location spawnLoc) {
        if (!citizensAvailable) return false;

        SkinData skin = ensureSkinLoaded(mobType);
        if (skin == null || skin.value.isEmpty() || skin.signature.isEmpty()) {
            getLogger().warning("Skin not ready for " + mobType + " (value/signature missing).");
            return false;
        }

        String rawProfile = getConfig().getString("mobs." + mobType + ".profileName", "Indev" + mobType);
        String profileName = toValidUsername(rawProfile);

        try {
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            if (registry == null) {
                getLogger().warning("Citizens NPC registry is null.");
                return false;
            }

            NPC npc = registry.createNPC(EntityType.PLAYER, profileName);

            // Make it damageable and "mob-like"
            npc.setProtected(false);

            // Mark as ours (Citizens persistent tags for restart safety)
            try { npc.data().setPersistent(CIT_KEY_INDEVMOBS, true); } catch (Throwable ignored) {}
            try { npc.data().setPersistent(CIT_KEY_TYPE, mobType.toLowerCase(Locale.ROOT)); } catch (Throwable ignored) {}

            // Hide nameplate (no scoreboard packet hacks)
            npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);

            // Spawn
            boolean ok = npc.spawn(spawnLoc);
            if (!ok) return false;

            Entity entity = npc.getEntity();
            if (entity == null) return false;

            // Tag as indevmob
            tagEntity(entity, mobType);
            spawnedEntities.put(entity.getUniqueId(), mobType);
            citizensNpcIdByEntity.put(entity.getUniqueId(), npc.getId());

            // Health (player-like)
            try {
                double maxHealth = getConfig().getDouble("mobs." + mobType + ".maxHealth", 20.0);
                if (entity instanceof org.bukkit.entity.LivingEntity livingEntity) {
                    org.bukkit.attribute.Attribute attr = org.bukkit.attribute.Attribute.MAX_HEALTH;
                    var inst = livingEntity.getAttribute(attr);
                    if (inst != null) inst.setBaseValue(maxHealth);
                    livingEntity.setHealth(Math.min(livingEntity.getHealth(), maxHealth));
                }
            } catch (Throwable ignored) {}

            // Apply skin
            applyNpcSkin(npc, profileName, skin);

            // Re-apply skin + nameplate after a short delay (client reliability)
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    NPC again = getNpcByEntityUuid(entity.getUniqueId());
                    if (again != null) {
                        again.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                        applyNpcSkin(again, profileName, skin);
                    }
                } catch (Throwable ignored2) {}
            }, 10L);

            // Create/update dynmap marker
            updateDynmapMarker(entity.getUniqueId());

            return true;
        } catch (Throwable ex) {
            getLogger().warning("Spawn failed via Citizens: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void applyNpcSkin(NPC npc, String skinName, SkinData skin) {
        try {
            SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
            trait.setSkinPersistent(skinName, skin.signature, skin.value);
        } catch (Throwable ex) {
            getLogger().warning("Failed to apply SkinTrait: " + ex.getMessage());
        }
    }

    private NPC getNpcByEntityUuid(UUID uuid) {
        Integer npcId = citizensNpcIdByEntity.get(uuid);
        if (npcId == null) return null;
        try {
            return CitizensAPI.getNPCRegistry().getById(npcId);
        } catch (Throwable ignored) {
            return null;
        }
    }


    // -------------------------------------------------------------------------
    // Chunk "re-awakening" + adoption (restart / legacy compatibility)
    // -------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!citizensAvailable) return;
        // Delay 1 tick to let Citizens finish attaching NPCs to entities in this chunk
        Bukkit.getScheduler().runTaskLater(this, () -> adoptNpcsInChunk(event.getChunk()), 1L);
    }

    private void scheduleAdoptPass() {
        if (!citizensAvailable) return;
        if (adoptTaskId != -1) Bukkit.getScheduler().cancelTask(adoptTaskId);

        // Run once shortly after enable, and again a few seconds later
        adoptTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(this, this::adoptAllSpawnedNpcs, 40L);
        Bukkit.getScheduler().runTaskLater(this, this::adoptAllSpawnedNpcs, 220L);
    }

    private void adoptAllSpawnedNpcs() {
        if (!citizensAvailable) return;

        NPCRegistry registry;
        try { registry = CitizensAPI.getNPCRegistry(); } catch (Throwable t) { return; }
        if (registry == null) return;

        try {
            for (NPC npc : registry) {
                if (npc == null) continue;
                if (!npc.isSpawned()) continue;
                Entity entity = npc.getEntity();
                if (entity == null) continue;
                String mobType = resolveMobTypeForNpc(npc);
                if (mobType == null) continue;
                adoptNpc(npc, entity, mobType);
            }
        } catch (Throwable ignored) {}
    }

    private void adoptNpcsInChunk(Chunk chunk) {
        if (!citizensAvailable) return;

        // Fast path: scan entities in the chunk, then ask Citizens which NPC owns them
        try {
            for (Entity entity : chunk.getEntities()) {
                if (entity == null) continue;

                NPC npc = getNpcForEntity(entity);
                if (npc == null) continue;
                if (!npc.isSpawned()) continue;

                String mobType = resolveMobTypeForNpc(npc);
                if (mobType == null) continue;

                adoptNpc(npc, entity, mobType);
            }
        } catch (Throwable ignored) {}
    }

    private NPC getNpcForEntity(Entity entity) {
        try {
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            if (registry == null) return null;

            // Prefer direct method if present
            try {
                java.lang.reflect.Method m = registry.getClass().getMethod("getNPC", Entity.class);
                Object result = m.invoke(registry, entity);
                return (result instanceof NPC) ? (NPC) result : null;
            } catch (NoSuchMethodException ignored) {
                // fallback: try CitizensAPI.getNPCRegistry().getById via our map only
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveMobTypeForNpc(NPC npc) {
        // 1) Persistent tag (new versions)
        try {
            Object marker = npc.data().get(CIT_KEY_INDEVMOBS);
            if (marker instanceof Boolean && ((Boolean) marker)) {
                Object t = npc.data().get(CIT_KEY_TYPE);
                if (t != null) {
                    String mobType = String.valueOf(t).toLowerCase(Locale.ROOT).trim();
                    if (isKnownMobType(mobType)) return mobType;
                }
            }
        } catch (Throwable ignored) {}

        // 2) Legacy compatibility: infer from NPC name
        try {
            String name = npc.getName();
            if (name != null) {
                String inferred = inferMobTypeFromName(name);
                if (inferred != null) return inferred;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean isKnownMobType(String mobTypeLower) {
        for (String t : getMobTypes()) {
            if (t.equalsIgnoreCase(mobTypeLower)) return true;
        }
        return false;
    }

    private String inferMobTypeFromName(String npcName) {
        String n = npcName.toLowerCase(Locale.ROOT).trim();
        if (n.contains("rana")) return "rana";
        if (n.contains("blacksteve") || n.contains("black_steve") || n.contains("black steve")) return "blacksteve";
        if (n.contains("beastboy") || n.contains("beast_boy") || n.contains("beast boy")) return "beastboy";
        if (n.equals("steve") || n.contains("dock-steve") || n.contains("dock steve") || n.contains("indevsteve")) return "steve";
        // also accept our default profile names
        if (n.contains("indevrana")) return "rana";
        if (n.contains("indevblacksteve")) return "blacksteve";
        if (n.contains("indevbeastboy")) return "beastboy";
        if (n.contains("indevsteve")) return "steve";
        return null;
    }

    private void adoptNpc(NPC npc, Entity entity, String mobType) {
        // Persist tags
        try { npc.data().setPersistent(CIT_KEY_INDEVMOBS, true); } catch (Throwable ignored) {}
        try { npc.data().setPersistent(CIT_KEY_TYPE, mobType.toLowerCase(Locale.ROOT)); } catch (Throwable ignored) {}

        // Hide nameplate (and also on the Bukkit side)
        try { npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false); } catch (Throwable ignored) {}
        try {
            entity.setCustomNameVisible(false);
        } catch (Throwable ignored) {}

        // Tag entity PDC so we can quickly recognize it later
        tagEntity(entity, mobType);

        // Track for our loops
        spawnedEntities.put(entity.getUniqueId(), mobType);
        citizensNpcIdByEntity.put(entity.getUniqueId(), npc.getId());

        // Ensure skin is applied (best effort; safe if already applied)
        try {
            SkinData skin = ensureSkinLoaded(mobType);
            if (skin != null) {
                String profileName = getConfig().getString("mobs." + mobType + ".profileName", "Indev" + mobType);
                if (profileName == null || profileName.trim().isEmpty()) profileName = "Indev" + mobType;
                applyNpcSkin(npc, profileName, skin);
            }
        } catch (Throwable ignored) {}

        // Dynmap marker
        updateDynmapMarker(entity.getUniqueId());
    }

    private void startEnforceLoop() {
        if (enforceTaskId != -1) Bukkit.getScheduler().cancelTask(enforceTaskId);
        if (!citizensAvailable) return;

        int tickPeriod = Math.max(20, getConfig().getInt("nametag.enforceTickPeriod", 40));

        enforceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Map.Entry<UUID, String> entry : spawnedEntities.entrySet()) {
                UUID uuid = entry.getKey();
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid() || entity.isDead()) continue;

                NPC npc = getNpcByEntityUuid(uuid);
                if (npc != null) {
                    try { npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false); } catch (Throwable ignored) {}
                }
                try { entity.setCustomNameVisible(false); } catch (Throwable ignored) {}

                // Keep pitch reasonable (prevents “looking down”)
                try {
                    Location loc = entity.getLocation();
                    if (Math.abs(loc.getPitch()) > 15.0f) {
                        loc.setPitch(0.0f);
                        entity.teleport(loc);
                    }
                } catch (Throwable ignored) {}
            }
        }, tickPeriod, tickPeriod);
    }

    private void trySuppressAdventureDeathMessage(PlayerDeathEvent event) {
        // Paper/Adventure compatibility (safe on Spigot; method will not exist)
        try {
            Method m = event.getClass().getMethod("deathMessage", Class.forName("net.kyori.adventure.text.Component"));
            m.invoke(event, new Object[] { null });
        } catch (Throwable ignored) {}
    }

    private void tryRemovePlayerHeads(List<ItemStack> drops) {
        if (drops == null) return;
        try {
            drops.removeIf(item -> item != null && item.getType().name().toLowerCase(Locale.ROOT).contains("player_head"));
        } catch (Throwable ignored) {}
    }


    // -------------------------------------------------------------------------
    // Wandering (Citizens Navigator)
    // -------------------------------------------------------------------------

    private void startWanderLoop() {
        if (wanderTaskId != -1) Bukkit.getScheduler().cancelTask(wanderTaskId);
        if (!citizensAvailable) return;
        if (!getConfig().getBoolean("wander.enabled", true)) return;

        int tickPeriod = Math.max(10, getConfig().getInt("wander.tickPeriod", 40));
        double retargetChance = clamp01(getConfig().getDouble("wander.targetChanceWhenIdle", 0.50));
        int verticalSearch = Math.max(1, getConfig().getInt("wander.verticalSearch", 3));

        wanderTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            Iterator<Map.Entry<UUID, String>> iterator = spawnedEntities.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, String> entry = iterator.next();
                UUID uuid = entry.getKey();
                String mobType = entry.getValue();

                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    iterator.remove();
                    citizensNpcIdByEntity.remove(uuid);
                    removeDynmapMarker(uuid);
                    continue;
                }

                NPC npc = getNpcByEntityUuid(uuid);
                if (npc == null || !npc.isSpawned()) continue;

                Navigator navigator = npc.getNavigator();
                if (navigator == null) continue;

                if (navigator.isNavigating()) continue;
                if (Math.random() > retargetChance) continue;

                int radius = Math.max(4, getConfig().getInt("mobs." + mobType + ".wander.radiusBlocks", 16));
                double speed = Math.max(0.1, getConfig().getDouble("mobs." + mobType + ".wander.speed", 1.0));

                Location from = entity.getLocation();
                Location target = pickWanderTarget(from, radius, verticalSearch);
                if (target == null) continue;

                try {
                    // Speed modifier is on local parameters
                    navigator.getLocalParameters().speedModifier((float) speed);
                } catch (Throwable ignored) {}

                try {
                    Location face = entity.getLocation();
                    face.setDirection(target.toVector().subtract(face.toVector()));
                    face.setPitch(0.0f);
                    entity.teleport(face);
                } catch (Throwable ignored2) {}

                navigator.setTarget(target);
            }
        }, tickPeriod, tickPeriod);
    }

    private Location pickWanderTarget(Location from, int radius, int verticalSearch) {
        World world = from.getWorld();
        if (world == null) return null;

        for (int i = 0; i < 10; i++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);

            int x = from.getBlockX() + dx;
            int z = from.getBlockZ() + dz;

            int yTop = world.getHighestBlockYAt(x, z);

            for (int dy = -verticalSearch; dy <= verticalSearch; dy++) {
                int y = yTop + dy;
                if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) continue;

                Location feet = new Location(world, x + 0.5, y, z + 0.5);
                if (!feet.getBlock().getType().isAir()) continue;
                if (!feet.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
                if (feet.clone().add(0, -1, 0).getBlock().isLiquid()) continue;

                return feet;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Death message suppression + drops + hurt sound
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIndevNpcPlayerDeath(PlayerDeathEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;

        // Suppress obituary
        event.setDeathMessage(null);
        trySuppressAdventureDeathMessage(event);

        // Override drops (Citizens NPCs are players)
        try { event.getDrops().clear(); } catch (Throwable ignored) {}
        try {
            List<ItemStack> drops = buildDropsFor(mobType);
            for (ItemStack item : drops) {
                if (item == null) continue;
                event.getDrops().add(item);
            }
        } catch (Throwable ignored) {}

        // Strip player-head drops injected by other plugins (best effort)
        tryRemovePlayerHeads(event.getDrops());

        event.setDroppedExp(0);

        // Remove dynmap marker immediately
        removeDynmapMarker(event.getEntity().getUniqueId());
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;

        event.getDrops().clear();
        event.getDrops().addAll(buildDropsFor(mobType));

        // Remove dynmap marker immediately
        removeDynmapMarker(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIndevMobHurtSound(EntityDamageEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity living)) return;

        float volume = (float) getConfig().getDouble("sounds.hurt.volume", 1.0);
        float pitch = (float) getConfig().getDouble("sounds.hurt.pitch", 1.0);
        living.getWorld().playSound(living.getLocation(), Sound.ENTITY_PLAYER_HURT, volume, pitch);
    }

    private List<ItemStack> buildDropsFor(String mobType) {
        List<ItemStack> drops = new ArrayList<>();
        ConfigurationSection dropSection = getConfig().getConfigurationSection("drops." + mobType);
        if (dropSection == null) return drops;

        ConfigurationSection common = dropSection.getConfigurationSection("common");
        if (common != null) {
            for (String matName : common.getKeys(false)) {
                int min = Math.max(0, common.getInt(matName + ".min", 0));
                int max = Math.max(min, common.getInt(matName + ".max", min));
                int amount = randomInt(min, max);
                if (amount <= 0) continue;

                ItemStack stack = makeItemSafe(matName, amount);
                if (stack != null) drops.add(stack);
            }
        }

        ConfigurationSection rare = dropSection.getConfigurationSection("rare");
        if (rare != null) {
            for (String matName : rare.getKeys(false)) {
                double chance = clamp01(rare.getDouble(matName + ".chance", 0.05));
                int min = Math.max(0, rare.getInt(matName + ".min", 0));
                int max = Math.max(min, rare.getInt(matName + ".max", min));
                if (Math.random() > chance) continue;

                int amount = randomInt(min, max);
                if (amount <= 0) continue;

                ItemStack stack = makeItemSafe(matName, amount);
                if (stack != null) drops.add(stack);
            }
        }

        return drops;
    }

    private ItemStack makeItemSafe(String materialName, int amount) {
        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            return new ItemStack(mat, amount);
        } catch (Throwable ex) {
            getLogger().warning("Unknown material in config: " + materialName);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // PDC helpers
    // -------------------------------------------------------------------------

    private void tagEntity(Entity entity, String mobType) {
        try {
            entity.getPersistentDataContainer().set(indevMobKey, PersistentDataType.BYTE, (byte) 1);
            entity.getPersistentDataContainer().set(indevMobTypeKey, PersistentDataType.STRING, mobType.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {}
    }

    private String getTaggedMobType(Entity entity) {
        try {
            Byte present = entity.getPersistentDataContainer().get(indevMobKey, PersistentDataType.BYTE);
            if (present == null || present != (byte) 1) return null;
            return entity.getPersistentDataContainer().get(indevMobTypeKey, PersistentDataType.STRING);
        } catch (Throwable ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // MineSkin local upload + caching
    // -------------------------------------------------------------------------

    private void loadSkinCacheFromConfig() {
        skinCache.clear();
        ConfigurationSection cached = getConfig().getConfigurationSection("skins.cached");
        if (cached == null) return;

        for (String key : cached.getKeys(false)) {
            String value = cached.getString(key + ".value", "");
            String signature = cached.getString(key + ".signature", "");
            if (!value.isEmpty() && !signature.isEmpty()) {
                skinCache.put(key.toLowerCase(Locale.ROOT), new SkinData(value, signature));
            }
        }
    }

    private SkinData ensureSkinLoaded(String mobType) {
        mobType = mobType.toLowerCase(Locale.ROOT);

        SkinData existing = skinCache.get(mobType);
        if (existing != null) return existing;

        boolean allow = getConfig().getBoolean("skins.allowMineskinRequests", true);
        if (!allow) return null;

        String apiKey = getConfig().getString("skins.mineskinApiKey", "");
        String userAgent = getConfig().getString("skins.userAgent", "IndevMobs/1.4");
        String variant = getConfig().getString("skins.variant", "classic");
        String visibility = getConfig().getString("skins.visibility", "unlisted");

        File skinFile = new File(getSkinDirectory(), mobType + ".png");
        if (!skinFile.exists() || skinFile.length() <= 0) {
            getLogger().warning("Missing local skin file: " + skinFile.getPath());
            return null;
        }

        byte[] pngBytes;
        try {
            pngBytes = readFileBytes(skinFile);
        } catch (Throwable ex) {
            getLogger().warning("Failed reading local skin file for " + mobType + ": " + ex.getMessage());
            return null;
        }

        if (!looksLikePng(pngBytes)) {
            getLogger().warning("Skin data for " + mobType + " is not a valid PNG (check file).");
            return null;
        }

        SkinData generated = requestMineSkinByUploadBytes(pngBytes, mobType, variant, visibility, apiKey, userAgent);
        if (generated != null) {
            skinCache.put(mobType, generated);
            getConfig().set("skins.cached." + mobType + ".value", generated.value);
            getConfig().set("skins.cached." + mobType + ".signature", generated.signature);
            saveConfig();
        }

        return generated;
    }

    private SkinData requestMineSkinByUploadBytes(byte[] pngBytes, String name, String variant, String visibility,
                                                 String apiKey, String userAgent) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.mineskin.org/v2/generate");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            String boundary = "----IndevMobsBoundary" + System.currentTimeMillis();

            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                String trimmed = apiKey.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) trimmed = "Bearer " + trimmed;
                conn.setRequestProperty("Authorization", trimmed);
            }

            String safeName = sanitizeName(name);

            try (OutputStream os = conn.getOutputStream()) {
                writeFormField(os, boundary, "name", safeName);
                writeFormField(os, boundary, "variant", variant);
                writeFormField(os, boundary, "visibility", visibility);
                writeFileField(os, boundary, "file", name + ".png", "image/png", pngBytes);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body = readAll((code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream());

            if (code < 200 || code >= 300) {
                getLogger().warning("MineSkin upload failed (" + code + "): " + body);
                return null;
            }

            String value = extractJsonString(body, "\"value\":\"");
            String signature = extractJsonString(body, "\"signature\":\"");
            if (value == null || signature == null || value.isEmpty() || signature.isEmpty()) {
                getLogger().warning("MineSkin response missing value/signature: " + body);
                return null;
            }

            return new SkinData(value, signature);
        } catch (Throwable ex) {
            getLogger().warning("MineSkin upload error: " + ex.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void writeFormField(OutputStream os, String boundary, String fieldName, String value) throws Exception {
        String part =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n" +
                        "\r\n" +
                        value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(OutputStream os, String boundary, String fieldName, String fileName, String contentType, byte[] data) throws Exception {
        String header =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readFileBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream in = new BufferedInputStream(fis);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    private boolean looksLikePng(byte[] data) {
        if (data == null || data.length < 8) return false;
        return (data[0] == (byte) 0x89 &&
                data[1] == (byte) 0x50 &&
                data[2] == (byte) 0x4E &&
                data[3] == (byte) 0x47 &&
                data[4] == (byte) 0x0D &&
                data[5] == (byte) 0x0A &&
                data[6] == (byte) 0x1A &&
                data[7] == (byte) 0x0A);
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String extractJsonString(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        idx += marker.length();
        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                out.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return null;
    }

    private String sanitizeName(String input) {
        if (input == null) return "IndevMob";
        String trimmed = input.trim();
        if (trimmed.length() > 20) trimmed = trimmed.substring(0, 20);
        if (trimmed.isEmpty()) trimmed = "IndevMob";
        return trimmed;
    }

    // -------------------------------------------------------------------------
    // Dynmap markers (reflection)
    // -------------------------------------------------------------------------

    private void initDynmap() {
        dynmapAvailable = false;
        dynmapPlugin = null;
        dynmapApi = null;
        dynmapMarkerApi = null;
        dynmapMarkerSet = null;

        if (!getConfig().getBoolean("dynmap.enabled", true)) return;

        dynmapPlugin = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmapPlugin == null || !dynmapPlugin.isEnabled()) return;

        try {
            // dynmap plugin exposes getAPI()
            Method getApi = dynmapPlugin.getClass().getMethod("getAPI");
            dynmapApi = getApi.invoke(dynmapPlugin);
            if (dynmapApi == null) return;

            Method getMarkerApi = dynmapApi.getClass().getMethod("getMarkerAPI");
            dynmapMarkerApi = getMarkerApi.invoke(dynmapApi);
            if (dynmapMarkerApi == null) return;

            String setId = getConfig().getString("dynmap.markerSetId", "npcs");
            String setLabel = getConfig().getString("dynmap.markerSetLabel", "NPCs");

            // MarkerSet set = markerApi.getMarkerSet(id); if null createMarkerSet(id,label,null,false)
            Object markerSet = dynmapMarkerApi.getClass().getMethod("getMarkerSet", String.class).invoke(dynmapMarkerApi, setId);
            if (markerSet == null) {
                markerSet = dynmapMarkerApi.getClass()
                        .getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class)
                        .invoke(dynmapMarkerApi, setId, setLabel, null, false);
            }
            dynmapMarkerSet = markerSet;

            dynmapAvailable = (dynmapMarkerSet != null);
            if (dynmapAvailable) getLogger().info("Dynmap detected: NPCs marker layer active (set id '" + setId + "').");
        } catch (Throwable ex) {
            dynmapAvailable = false;
            dynmapMarkerApi = null;
            dynmapMarkerSet = null;
            getLogger().warning("Dynmap hook failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void startDynmapUpdater() {
        if (dynmapTaskId != -1) Bukkit.getScheduler().cancelTask(dynmapTaskId);
        if (enforceTaskId != -1) Bukkit.getScheduler().cancelTask(enforceTaskId);
        if (adoptTaskId != -1) Bukkit.getScheduler().cancelTask(adoptTaskId);
        dynmapTaskId = -1;
        enforceTaskId = -1;
        adoptTaskId = -1;
        if (!dynmapAvailable) return;

        int ticks = Math.max(20, getConfig().getInt("dynmap.updateTicks", 40));
        dynmapTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            // update all markers
            for (UUID uuid : new ArrayList<>(spawnedEntities.keySet())) {
                updateDynmapMarker(uuid);
            }
        }, ticks, ticks);
    }

    private void updateDynmapMarker(UUID entityUuid) {
        if (!dynmapAvailable || dynmapMarkerSet == null) return;

        Entity entity = Bukkit.getEntity(entityUuid);
        if (entity == null || !entity.isValid() || entity.isDead()) {
            removeDynmapMarker(entityUuid);
            return;
        }

        String mobType = spawnedEntities.get(entityUuid);
        if (mobType == null) return;

        try {
            String markerId = "indevmob_" + entityUuid;
            String label = getConfig().getString("mobs." + mobType + ".displayName", mobType);

            String iconId = getConfig().getString("dynmap.icons." + mobType, "default");
            Object icon = dynmapMarkerApi.getClass().getMethod("getMarkerIcon", String.class).invoke(dynmapMarkerApi, iconId);
            if (icon == null) {
                // fallback to builtin icon if your custom icon id doesn't exist
                icon = dynmapMarkerApi.getClass().getMethod("getMarkerIcon", String.class).invoke(dynmapMarkerApi, "sign");
            }

            Object marker = dynmapMarkerByEntity.get(entityUuid);

            if (marker == null) {
                // Marker createMarker(id,label,world,x,y,z,icon,markup)
                marker = dynmapMarkerSet.getClass()
                        .getMethod("createMarker", String.class, String.class, String.class, double.class, double.class, double.class, icon.getClass(), boolean.class)
                        .invoke(dynmapMarkerSet, markerId, label, entity.getWorld().getName(),
                                entity.getLocation().getX(), entity.getLocation().getY(), entity.getLocation().getZ(),
                                icon, false);

                if (marker != null) {
                    dynmapMarkerByEntity.put(entityUuid, marker);
                }
            } else {
                // setLocation(world,x,y,z)
                marker.getClass()
                        .getMethod("setLocation", String.class, double.class, double.class, double.class)
                        .invoke(marker, entity.getWorld().getName(),
                                entity.getLocation().getX(), entity.getLocation().getY(), entity.getLocation().getZ());
            }
        } catch (Throwable ignored) {
            // Keep quiet; dynmap API has some signature drift between builds.
        }
    }

    private void removeDynmapMarker(UUID entityUuid) {
        Object marker = dynmapMarkerByEntity.remove(entityUuid);
        if (marker == null) return;
        try {
            marker.getClass().getMethod("deleteMarker").invoke(marker);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private int randomInt(int min, int max) {
        if (max < min) max = min;
        if (min == max) return min;
        return min + (int) Math.floor(Math.random() * (max - min + 1));
    }

    private double clamp01(double v) { return clamp(v, 0.0, 1.0); }
    private double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private String toValidUsername(String input) {
        if (input == null) input = "IndevMob";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') ||
                    (c == '_');
            out.append(ok ? c : '_');
        }
        String s = out.toString();
        while (s.startsWith("_")) s = s.substring(1);
        if (s.isEmpty()) s = "IndevMob";
        if (s.length() > 16) s = s.substring(0, 16);
        return s;
    }

    private static final class SkinData {
        final String value;
        final String signature;
        SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }

// -------------------------------------------------------------------------
// Hacky fallback: identify Indev NPCs by their player name, then add drops and
// suppress obituary messages. This is intentionally hardcoded.
// -------------------------------------------------------------------------

@EventHandler(priority = EventPriority.HIGHEST)
public void onIndevNpcDeathByName(PlayerDeathEvent event) {
    Player dead = event.getEntity();
    if (dead == null) return;

    String name = dead.getName();
    if (!isIndevNpcName(name)) return;

    // Suppress obituary/death message
    try { event.setDeathMessage(null); } catch (Throwable ignored) {}

    // Add our custom drops (do NOT clear drops, so head-drop plugins can still add heads)
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (lower.equals("indevrana")) {
        addRandomDrop(event.getDrops(), Material.APPLE, 0, 2);
        if (Math.random() < 0.05) addRandomDrop(event.getDrops(), Material.GOLDEN_APPLE, 0, 1);
        if (Math.random() < 0.05) addRandomDrop(event.getDrops(), Material.FLINT_AND_STEEL, 0, 1);
    } else if (lower.equals("indevsteve") || lower.equals("indevblksteve") || lower.equals("indevbeastboy")) {
        addRandomDrop(event.getDrops(), Material.FEATHER, 0, 2);
        addRandomDrop(event.getDrops(), Material.GUNPOWDER, 0, 2);
        addRandomDrop(event.getDrops(), Material.STRING, 0, 2);
        if (Math.random() < 0.05) addRandomDrop(event.getDrops(), Material.FLINT_AND_STEEL, 0, 1);
    }
}

// Stomp any later death-message changes by other plugins
@EventHandler(priority = EventPriority.MONITOR)
public void onIndevNpcDeathByNameMonitor(PlayerDeathEvent event) {
    Player dead = event.getEntity();
    if (dead == null) return;

    if (!isIndevNpcName(dead.getName())) return;
    try { event.setDeathMessage(null); } catch (Throwable ignored) {}
}

private boolean isIndevNpcName(String name) {
    if (name == null) return false;
    return name.equalsIgnoreCase("IndevRana")
            || name.equalsIgnoreCase("IndevSteve")
            || name.equalsIgnoreCase("IndevBlkSteve")
            || name.equalsIgnoreCase("IndevBeastBoy");
}

private void addRandomDrop(java.util.List<ItemStack> drops, Material material, int minAmount, int maxAmount) {
    if (drops == null || material == null) return;
    if (maxAmount < minAmount) maxAmount = minAmount;

    int amount;
    if (maxAmount == minAmount) {
        amount = minAmount;
    } else {
        amount = minAmount + (int) Math.floor(Math.random() * (maxAmount - minAmount + 1));
    }

    if (amount <= 0) return;
    drops.add(new ItemStack(material, amount));
}

}