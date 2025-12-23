// IndevMobs.java
// Author: ChatGPT
package com.redchanit.indevmobs;

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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class IndevMobs extends JavaPlugin implements Listener {

    // Persistent tags on the Bukkit entity
    private NamespacedKey indevMobKey;
    private NamespacedKey indevMobTypeKey;

    // Citizens present?
    private boolean citizensAvailable = false;

    // MineSkin cache: mobType -> signature/value
    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();

    // Active spawned entity UUID -> mobType
    private final Map<UUID, String> spawnedEntities = new ConcurrentHashMap<>();

    // Spawner task
    private int spawnTaskId = -1;

    // Dynmap integration (reflection)
    private Plugin dynmapPlugin;
    private boolean dynmapAvailable = false;
    private Object dynmapMarkerApi; // MarkerAPI
    private Object dynmapMarkerSet; // MarkerSet
    private final Map<UUID, Object> dynmapMarkersByEntity = new ConcurrentHashMap<>();
    private int dynmapUpdateTaskId = -1;

    // Nametag hiding team
    private Scoreboard indevScoreboard;
    private Team indevNoTagTeam;

    @Override
    public void onEnable() {
        indevMobKey = new NamespacedKey(this, "indevmob");
        indevMobTypeKey = new NamespacedKey(this, "indevmob_type");

        ensureConfigDefaults();
        ensureSkinFolder();
        loadSkinCacheFromConfig();

        setupNametagHidingTeam();

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
        startDynmapUpdater();
    }

    @Override
    public void onDisable() {
        if (spawnTaskId != -1) {
            Bukkit.getScheduler().cancelTask(spawnTaskId);
            spawnTaskId = -1;
        }
        if (dynmapUpdateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dynmapUpdateTaskId);
            dynmapUpdateTaskId = -1;
        }

        // Cleanup dynmap markers
        try {
            for (Object marker : dynmapMarkersByEntity.values()) {
                safeInvoke(marker, "deleteMarker");
            }
        } catch (Throwable ignored) {}
        dynmapMarkersByEntity.clear();
        spawnedEntities.clear();
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
            setupNametagHidingTeam();
            initDynmap();
            sender.sendMessage("IndevMobs config reloaded.");
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage("IndevMobs v" + getDescription().getVersion());
            sender.sendMessage("Citizens: " + (citizensAvailable ? "OK" : "MISSING"));
            sender.sendMessage("Dynmap: " + (dynmapAvailable ? "OK" : "MISSING/DISABLED"));
            sender.sendMessage("Tracked alive indev mobs: " + countAliveAllWorlds());
            sender.sendMessage("Skin cache keys: " + skinCache.keySet());

            File skinDir = getSkinDirectory();
            sender.sendMessage("Local skins folder: " + skinDir.getPath());
            for (String type : getMobTypes()) {
                File f = new File(skinDir, type + ".png");
                sender.sendMessage(" - " + type + ".png: " + (f.exists() ? (f.length() + " bytes") : "MISSING"));
            }

            ConfigurationSection cached = getConfig().getConfigurationSection("skins.cached");
            if (cached == null || cached.getKeys(false).isEmpty()) {
                sender.sendMessage("Config skins.cached: EMPTY");
            } else {
                sender.sendMessage("Config skins.cached keys: " + cached.getKeys(false));
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

            String chosen;
            if (mobTypeArg.equals("random")) {
                chosen = pickMobTypeByWeight();
                if (chosen == null) chosen = "rana";
            } else {
                chosen = mobTypeArg;
            }

            if (!getMobTypes().contains(chosen)) {
                player.sendMessage("Unknown type. Use rana|steve|blacksteve|beastboy|random");
                return true;
            }

            Location loc = player.getLocation().clone();
            Location forward = loc.add(loc.getDirection().normalize().multiply(4.0));
            forward.setY(player.getWorld().getHighestBlockYAt(forward) + 1.0);

            boolean ok = spawnIndevNpc(chosen, forward);
            if (ok) {
                player.sendMessage("Spawned " + chosen + " near you.");
            } else {
                player.sendMessage("Spawn FAILED. Check console or /indevmobs status.");
            }
            return true;
        }

        sender.sendMessage("Usage: /indevmobs <status|reload|forcespawn>");
        return true;
    }

    private int countAliveAllWorlds() {
        int total = 0;
        Iterator<Map.Entry<UUID, String>> it = spawnedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent == null || !ent.isValid() || ent.isDead()) {
                it.remove();
                continue;
            }
            total++;
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Config defaults (no embedded config.yml)
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
        cfg.addDefault("spawning.despawnIfNoPlayersWithin", 128.0);

        cfg.addDefault("spawning.weights.rana", 1.0);
        cfg.addDefault("spawning.weights.steve", 1.0);
        cfg.addDefault("spawning.weights.blacksteve", 1.0);
        cfg.addDefault("spawning.weights.beastboy", 1.0);

        cfg.addDefault("sounds.hurt.volume", 1.0);
        cfg.addDefault("sounds.hurt.pitch", 1.0);

        cfg.addDefault("skins.preloadOnEnable", true);
        cfg.addDefault("skins.allowMineskinRequests", true);
        cfg.addDefault("skins.mineskinApiKey", "");
        cfg.addDefault("skins.userAgent", "IndevMobs/1.2.2");
        cfg.addDefault("skins.variant", "classic");
        cfg.addDefault("skins.visibility", "unlisted");

        cfg.addDefault("skins.useLocalFiles", true);
        cfg.addDefault("skins.localFolderName", "skins");
        cfg.addDefault("skins.autoDownloadIfMissing", false);

        // URLs only used if you toggle autoDownloadIfMissing or if local files are missing and URLs are present
        cfg.addDefault("skins.urls.rana", "https://files.catbox.moe/yo0n7z.png");
        cfg.addDefault("skins.urls.steve", "https://files.catbox.moe/lj5tkg.png");
        cfg.addDefault("skins.urls.blacksteve", "https://files.catbox.moe/ue85z2.png");
        cfg.addDefault("skins.urls.beastboy", "https://files.catbox.moe/p9lszo.png");

        addMobDefaults(cfg, "rana", "Rana");
        addMobDefaults(cfg, "steve", "Steve");
        addMobDefaults(cfg, "blacksteve", "Black Steve");
        addMobDefaults(cfg, "beastboy", "Beast Boy");

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

        // Dynmap NPCs marker layer (shared)
        cfg.addDefault("dynmap.enabled", true);
        cfg.addDefault("dynmap.markerSetId", "npcs");
        cfg.addDefault("dynmap.markerSetLabel", "NPCs");
        cfg.addDefault("dynmap.updateTicks", 40);

        cfg.addDefault("dynmap.icons.rana.id", "npc_rana");
        cfg.addDefault("dynmap.icons.rana.label", "Rana");
        cfg.addDefault("dynmap.icons.rana.url", "https://minecraft.wiki/images/EntitySprite_rana.png?3f2f9");

        cfg.addDefault("dynmap.icons.steve.id", "npc_steve");
        cfg.addDefault("dynmap.icons.steve.label", "Steve");
        cfg.addDefault("dynmap.icons.steve.url", "https://minecraft.wiki/images/EntitySprite_dock-steve.png?9897e");

        cfg.addDefault("dynmap.icons.blacksteve.id", "npc_blacksteve");
        cfg.addDefault("dynmap.icons.blacksteve.label", "Black Steve");
        cfg.addDefault("dynmap.icons.blacksteve.url", "https://minecraft.wiki/images/EntitySprite_black-steve.png?60477");

        cfg.addDefault("dynmap.icons.beastboy.id", "npc_beastboy");
        cfg.addDefault("dynmap.icons.beastboy.label", "Beast Boy");
        cfg.addDefault("dynmap.icons.beastboy.url", "https://minecraft.wiki/images/EntitySprite_beast-boy.png?ecb48");

        cfg.options().copyDefaults(true);
        saveConfig();
    }

    private void addMobDefaults(FileConfiguration cfg, String mobKey, String displayName) {
        cfg.addDefault("mobs." + mobKey + ".displayName", displayName);
        cfg.addDefault("mobs." + mobKey + ".maxHealth", 20.0);
        cfg.addDefault("mobs." + mobKey + ".wander.tickPeriod", 80);     // only used as fallback; Citizens wander trait used
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

    // -------------------------------------------------------------------------
    // Skins folder
    // -------------------------------------------------------------------------

    private void ensureSkinFolder() {
        File dir = getSkinDirectory();
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) getLogger().info("Created skin folder: " + dir.getPath());
        }

        if (!getConfig().getBoolean("skins.autoDownloadIfMissing", false)) return;

        for (String type : getMobTypes()) {
            File f = new File(dir, type + ".png");
            if (f.exists() && f.length() > 0) continue;

            String url = getConfig().getString("skins.urls." + type, "");
            if (url == null || url.isEmpty()) continue;

            try {
                byte[] bytes = downloadBytes(url, getConfig().getString("skins.userAgent", "IndevMobs/1.2.2"));
                if (bytes != null && bytes.length > 16 && looksLikePng(bytes)) {
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(bytes);
                    }
                    getLogger().info("Downloaded missing skin to " + f.getPath());
                } else {
                    getLogger().warning("Could not auto-download valid PNG for " + type + " from " + url);
                }
            } catch (Throwable ex) {
                getLogger().warning("Auto-download failed for " + type + ": " + ex.getMessage());
            }
        }
    }

    private File getSkinDirectory() {
        String folderName = getConfig().getString("skins.localFolderName", "skins");
        if (folderName == null || folderName.trim().isEmpty()) folderName = "skins";
        return new File(getDataFolder(), folderName);
    }

    // -------------------------------------------------------------------------
    // Nametag hiding (pseudo-mob feel)
    // -------------------------------------------------------------------------

    private void setupNametagHidingTeam() {
        try {
            if (Bukkit.getScoreboardManager() == null) return;
            indevScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

            Team existing = indevScoreboard.getTeam("indevmobs_notags");
            if (existing == null) {
                indevNoTagTeam = indevScoreboard.registerNewTeam("indevmobs_notags");
            } else {
                indevNoTagTeam = existing;
            }

            indevNoTagTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            indevNoTagTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        } catch (Throwable ignored) {}
    }

    private void tryAddToNoTagTeam(Entity entity) {
        if (!(entity instanceof Player)) return;
        if (indevNoTagTeam == null) return;

        Player p = (Player) entity;
        try {
            indevNoTagTeam.addEntry(p.getName());
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Dynmap init + updater (reflection)
    // -------------------------------------------------------------------------

    private void initDynmap() {
        dynmapAvailable = false;
        dynmapMarkerApi = null;
        dynmapMarkerSet = null;
        dynmapPlugin = null;

        if (!getConfig().getBoolean("dynmap.enabled", true)) return;

        dynmapPlugin = Bukkit.getPluginManager().getPlugin("dynmap");
        dynmapAvailable = (dynmapPlugin != null && dynmapPlugin.isEnabled());
        if (!dynmapAvailable) return;

        try {
            // DynmapPlugin.getAPI() -> DynmapAPI
            Object dynmapApi = safeInvoke(dynmapPlugin, "getAPI");
            if (dynmapApi == null) {
                getLogger().warning("Dynmap found but getAPI() returned null.");
                dynmapAvailable = false;
                return;
            }

            Object markerApi = safeInvoke(dynmapApi, "getMarkerAPI");
            if (markerApi == null) {
                getLogger().warning("Dynmap MarkerAPI is null (markers may be disabled).");
                dynmapAvailable = false;
                return;
            }
            dynmapMarkerApi = markerApi;

            String setId = getConfig().getString("dynmap.markerSetId", "npcs");
            String setLabel = getConfig().getString("dynmap.markerSetLabel", "NPCs");

            Object existingSet = safeInvoke(markerApi, "getMarkerSet",
                    new Class<?>[]{String.class}, new Object[]{setId});
            if (existingSet != null) {
                dynmapMarkerSet = existingSet;
            } else {
                dynmapMarkerSet = safeInvoke(markerApi, "createMarkerSet",
                        new Class<?>[]{String.class, String.class, Set.class, boolean.class},
                        new Object[]{setId, setLabel, null, true});
            }

            if (dynmapMarkerSet == null) {
                getLogger().warning("Dynmap MarkerSet could not be created or retrieved.");
                dynmapAvailable = false;
                return;
            }

            ensureDynmapIconRegistered("rana");
            ensureDynmapIconRegistered("steve");
            ensureDynmapIconRegistered("blacksteve");
            ensureDynmapIconRegistered("beastboy");

            getLogger().info("Dynmap detected: NPCs marker layer active (set id '" + setId + "').");
        } catch (Throwable ex) {
            dynmapAvailable = false;
            getLogger().warning("Dynmap init failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void startDynmapUpdater() {
        if (dynmapUpdateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dynmapUpdateTaskId);
            dynmapUpdateTaskId = -1;
        }
        if (!dynmapAvailable) return;

        int ticks = Math.max(20, getConfig().getInt("dynmap.updateTicks", 40));
        dynmapUpdateTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!dynmapAvailable || dynmapMarkerApi == null || dynmapMarkerSet == null) return;

            // Cleanup dead markers
            Iterator<Map.Entry<UUID, Object>> it = dynmapMarkersByEntity.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Object> entry = it.next();
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    safeInvoke(entry.getValue(), "deleteMarker");
                    it.remove();
                }
            }

            // Ensure markers exist + update positions
            for (Map.Entry<UUID, String> entry : spawnedEntities.entrySet()) {
                UUID uuid = entry.getKey();
                String mobType = entry.getValue();

                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid() || entity.isDead()) continue;

                Object marker = dynmapMarkersByEntity.get(uuid);
                if (marker == null) {
                    marker = createDynmapMarkerFor(entity, mobType);
                    if (marker != null) dynmapMarkersByEntity.put(uuid, marker);
                } else {
                    updateDynmapMarkerLocation(marker, entity.getLocation());
                }
            }
        }, ticks, ticks);
    }

    private void ensureDynmapIconRegistered(String mobType) {
        if (!dynmapAvailable || dynmapMarkerApi == null) return;

        String iconId = getConfig().getString("dynmap.icons." + mobType + ".id", "npc_" + mobType);
        String iconLabel = getConfig().getString("dynmap.icons." + mobType + ".label", mobType);
        String iconUrl = getConfig().getString("dynmap.icons." + mobType + ".url", "");
        if (iconUrl == null || iconUrl.isEmpty()) return;

        Object existing = safeInvoke(dynmapMarkerApi, "getMarkerIcon",
                new Class<?>[]{String.class}, new Object[]{iconId});
        if (existing != null) return;

        try {
            File iconDir = new File(getDataFolder(), "dynmap_icons");
            if (!iconDir.exists()) iconDir.mkdirs();

            File localPng = new File(iconDir, iconId + ".png");
            if (!localPng.exists() || localPng.length() < 16) {
                downloadToFile(iconUrl, localPng);
            }

            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(localPng))) {
                safeInvoke(dynmapMarkerApi, "createMarkerIcon",
                        new Class<?>[]{String.class, String.class, java.io.InputStream.class},
                        new Object[]{iconId, iconLabel, in});
            }
        } catch (Throwable ex) {
            getLogger().warning("Dynmap icon register failed for " + mobType + ": " + ex.getMessage());
        }
    }

    private Object createDynmapMarkerFor(Entity entity, String mobType) {
        if (!dynmapAvailable || dynmapMarkerSet == null || dynmapMarkerApi == null) return null;

        try {
            String markerId = "indevmobs_" + entity.getUniqueId();
            String label = getConfig().getString("mobs." + mobType + ".displayName", mobType);
            String worldName = entity.getWorld().getName();
            Location loc = entity.getLocation();

            String iconId = getConfig().getString("dynmap.icons." + mobType + ".id", "npc_" + mobType);
            Object icon = safeInvoke(dynmapMarkerApi, "getMarkerIcon",
                    new Class<?>[]{String.class}, new Object[]{iconId});
            if (icon == null) return null;

            // Dynmap 3.x createMarker signatures vary; try common 9-arg:
            // createMarker(id, label, markup, world, x,y,z, icon, persistent)
            for (Method m : dynmapMarkerSet.getClass().getMethods()) {
                if (!m.getName().equals("createMarker")) continue;
                if (m.getParameterCount() != 9) continue;
                return m.invoke(dynmapMarkerSet,
                        markerId, label, false, worldName,
                        loc.getX(), loc.getY(), loc.getZ(),
                        icon, true);
            }

            return null;
        } catch (Throwable ex) {
            getLogger().warning("Dynmap marker create failed: " + ex.getMessage());
            return null;
        }
    }

    private void updateDynmapMarkerLocation(Object marker, Location loc) {
        if (marker == null || loc == null || loc.getWorld() == null) return;
        invokeByNameAndArgCount(marker, "setLocation",
                new Object[]{loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()});
    }

    private void downloadToFile(String urlStr, File outFile) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "IndevMobs/1.2.2");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }

        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) fos.write(buf, 0, r);
        } finally {
            conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Spawner loop
    // -------------------------------------------------------------------------

    private void startSpawner() {
        if (spawnTaskId != -1) {
            Bukkit.getScheduler().cancelTask(spawnTaskId);
            spawnTaskId = -1;
        }

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
                    for (int attempt = 0; attempt < attemptsPerPlayer; attempt++) {
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
        Iterator<Map.Entry<UUID, String>> it = spawnedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> entry = it.next();
            UUID uuid = entry.getKey();
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                it.remove();
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

        for (int t = 0; t < tries; t++) {
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

    private List<String> getMobTypes() {
        return Arrays.asList("rana", "steve", "blacksteve", "beastboy");
    }

    private String pickMobTypeByWeight() {
        ConfigurationSection weights = getConfig().getConfigurationSection("spawning.weights");
        if (weights == null) return "rana";

        double total = 0.0;
        Map<String, Double> w = new HashMap<>();
        for (String type : getMobTypes()) {
            double value = Math.max(0.0, weights.getDouble(type, 1.0));
            w.put(type, value);
            total += value;
        }
        if (total <= 0.0) return null;

        double roll = Math.random() * total;
        double acc = 0.0;
        for (String type : getMobTypes()) {
            acc += w.getOrDefault(type, 0.0);
            if (roll <= acc) return type;
        }
        return "rana";
    }

    // -------------------------------------------------------------------------
    // Citizens spawning (reflection)
    // -------------------------------------------------------------------------

    private boolean spawnIndevNpc(String mobType, Location spawnLoc) {
        if (!citizensAvailable) return false;

        SkinData skin = ensureSkinLoaded(mobType);
        if (skin == null || skin.value.isEmpty() || skin.signature.isEmpty()) {
            return false;
        }

        String displayName = getConfig().getString("mobs." + mobType + ".displayName", mobType);

        try {
            // CitizensAPI.getNPCRegistry()
            Class<?> citizensApiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApiClass.getMethod("getNPCRegistry").invoke(null);
            if (registry == null) {
                getLogger().warning("CitizensAPI.getNPCRegistry() returned null.");
                return false;
            }

            Object npc = registry.getClass()
                    .getMethod("createNPC", org.bukkit.entity.EntityType.class, String.class)
                    .invoke(registry, org.bukkit.entity.EntityType.PLAYER, displayName);

            // Disable Citizens protection if method exists
            try {
                Method setProtectedMethod = npc.getClass().getMethod("setProtected", boolean.class);
                setProtectedMethod.invoke(npc, false);
            } catch (Throwable ignored) {}

            boolean spawned = (boolean) npc.getClass().getMethod("spawn", Location.class).invoke(npc, spawnLoc);
            if (!spawned) return false;

            Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity == null) return false;

            // Clear Bukkit invulnerable flag
            try { entity.setInvulnerable(false); } catch (Throwable ignored) {}

            // Tag entity and hide its nametag (pseudo-mob)
            tagEntity(entity, mobType);
            tryAddToNoTagTeam(entity);

            // Also try to avoid showing a "custom name"
            // (for Player entities, custom name can be odd; keep it unset)
            try { entity.setCustomNameVisible(false); } catch (Throwable ignored) {}

            // Health (compat): reflection setMaxHealth/setHealth if present
            try {
                double maxHealth = Math.max(1.0, getConfig().getDouble("mobs." + mobType + ".maxHealth", 20.0));
                try { entity.getClass().getMethod("setMaxHealth", double.class).invoke(entity, maxHealth); }
                catch (Throwable ignored) {}
                try { entity.getClass().getMethod("setHealth", double.class).invoke(entity, maxHealth); }
                catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Apply skin
            applyCitizensSkinTrait(npc, displayName, skin.signature, skin.value);

            // Enable Citizens wander (this is what makes them actually walk reliably)
            enableCitizensWanderTrait(npc, mobType);

            // Track
            spawnedEntities.put(entity.getUniqueId(), mobType);

            // Create dynmap marker immediately if available
            if (dynmapAvailable) {
                Object marker = createDynmapMarkerFor(entity, mobType);
                if (marker != null) dynmapMarkersByEntity.put(entity.getUniqueId(), marker);
            }

            return true;

        } catch (Throwable ex) {
            getLogger().warning("Spawn failed via Citizens: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void applyCitizensSkinTrait(Object npc, String skinName, String signature, String value) {
        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skinTrait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTraitClass);

            skinTrait.getClass().getMethod("setSkinPersistent", String.class, String.class, String.class)
                    .invoke(skinTrait, skinName, signature, value);
        } catch (Throwable ex) {
            getLogger().warning("Could not apply Citizens SkinTrait (may appear default): " + ex.getMessage());
        }
    }

    // Citizens-native wandering (Waypoints wander provider first; fallback to older WanderTrait)
    private void enableCitizensWanderTrait(Object npc, String mobType) {
        int radius = Math.max(4, getConfig().getInt("mobs." + mobType + ".wander.radiusBlocks", 16));

        // Attempt 1: Waypoints + WanderWaypointProvider
        try {
            Class<?> waypointsTraitClass = Class.forName("net.citizensnpcs.trait.waypoint.Waypoints");
            Object waypointsTrait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, waypointsTraitClass);

            Class<?> wanderProviderClass = Class.forName("net.citizensnpcs.trait.waypoint.WanderWaypointProvider");
            Object wanderProvider = wanderProviderClass.getConstructor().newInstance();

            // Try to set range/radius depending on build
            try {
                wanderProviderClass.getMethod("setRange", int.class).invoke(wanderProvider, radius);
            } catch (Throwable ignored) {
                try {
                    wanderProviderClass.getMethod("range", int.class).invoke(wanderProvider, radius);
                } catch (Throwable ignored2) {}
            }

            // Set provider (type varies across builds)
            try {
                Class<?> providerIface = Class.forName("net.citizensnpcs.trait.waypoint.WaypointProvider");
                waypointsTrait.getClass().getMethod("setProvider", providerIface).invoke(waypointsTrait, wanderProvider);
            } catch (Throwable ignored) {
                for (Method m : waypointsTrait.getClass().getMethods()) {
                    if (!m.getName().equals("setProvider")) continue;
                    if (m.getParameterCount() != 1) continue;
                    try {
                        m.invoke(waypointsTrait, wanderProvider);
                        break;
                    } catch (Throwable ignored2) {}
                }
            }

            // Unpause if supported
            try {
                waypointsTrait.getClass().getMethod("setPaused", boolean.class).invoke(waypointsTrait, false);
            } catch (Throwable ignored) {}

            return; // success
        } catch (Throwable ignored) {}

        // Attempt 2: Older WanderTrait
        try {
            Class<?> wanderTraitClass = Class.forName("net.citizensnpcs.trait.WanderTrait");
            Object wanderTrait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, wanderTraitClass);

            try { wanderTrait.getClass().getMethod("setRadius", int.class).invoke(wanderTrait, radius); } catch (Throwable ignored2) {}
            try { wanderTrait.getClass().getMethod("setActive", boolean.class).invoke(wanderTrait, true); } catch (Throwable ignored2) {}
            try { wanderTrait.getClass().getMethod("setWandering", boolean.class).invoke(wanderTrait, true); } catch (Throwable ignored2) {}
        } catch (Throwable ignored) {}
    }

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
    // Damage & drops + suppress player obituary
    // -------------------------------------------------------------------------

    // Citizens often cancels damage for NPCs. We force-apply it here.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIndevMobDamageWorkaround(EntityDamageEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;

        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity living)) return;

        double damage = event.getFinalDamage();
        if (damage <= 0) return;

        // We handle it manually so Citizens protection doesn't matter
        event.setCancelled(true);

        float volume = (float) getConfig().getDouble("sounds.hurt.volume", 1.0);
        float pitch = (float) getConfig().getDouble("sounds.hurt.pitch", 1.0);
        living.getWorld().playSound(living.getLocation(), Sound.ENTITY_PLAYER_HURT, volume, pitch);

        double newHealth = living.getHealth() - damage;
        if (newHealth <= 0.0) {
            living.setHealth(0.0); // triggers death
        } else {
            living.setHealth(newHealth);
        }
    }

    // Clear obituary chat message for NPC "players"
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIndevNpcDeathMessage(PlayerDeathEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;

        event.setDeathMessage(null);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String mobType = getTaggedMobType(event.getEntity());
        if (mobType == null) return;

        event.getDrops().clear();
        event.getDrops().addAll(buildDropsFor(mobType));

        Object marker = dynmapMarkersByEntity.remove(event.getEntity().getUniqueId());
        if (marker != null) safeInvoke(marker, "deleteMarker");
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
    // MineSkin: local file upload + caching to config
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
        String userAgent = getConfig().getString("skins.userAgent", "IndevMobs/1.2.2");
        String variant = getConfig().getString("skins.variant", "classic");
        String visibility = getConfig().getString("skins.visibility", "unlisted");

        byte[] pngBytes = null;

        boolean useLocal = getConfig().getBoolean("skins.useLocalFiles", true);
        if (useLocal) {
            File f = new File(getSkinDirectory(), mobType + ".png");
            if (f.exists() && f.length() > 0) {
                try {
                    pngBytes = readFileBytes(f);
                } catch (Throwable ex) {
                    getLogger().warning("Failed reading local skin file for " + mobType + ": " + ex.getMessage());
                }
            } else {
                getLogger().warning("Missing local skin file: " + f.getPath());
            }
        }

        // Optional fallback: download to memory (if URL set)
        if (pngBytes == null) {
            String url = getConfig().getString("skins.urls." + mobType, "");
            if (url != null && !url.isEmpty()) {
                pngBytes = downloadBytes(url, userAgent);
                if (pngBytes == null || pngBytes.length < 16) {
                    getLogger().warning("Could not download fallback skin for " + mobType + " from " + url);
                    return null;
                }
            } else {
                return null;
            }
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

            long delayMs = parseRateLimitDelayMillis(body);
            if (delayMs > 0) safeSleep(delayMs);

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

    private byte[] downloadBytes(String urlStr, String userAgent) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                getLogger().warning("Download failed (HTTP " + code + ") for " + urlStr);
                return null;
            }

            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                return baos.toByteArray();
            }
        } catch (Throwable ex) {
            getLogger().warning("Download error for " + urlStr + ": " + ex.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
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

    private String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private long parseRateLimitDelayMillis(String body) {
        if (body == null) return 0;
        int idx = body.indexOf("\"delay\":{\"millis\":");
        if (idx < 0) return 0;
        idx += "\"delay\":{\"millis\":".length();
        int end = idx;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        try { return Long.parseLong(body.substring(idx, end)); }
        catch (Throwable ignored) { return 0; }
    }

    private void safeSleep(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(Math.min(millis, 5000)); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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
    // Reflection helpers
    // -------------------------------------------------------------------------

    private Object safeInvoke(Object target, String methodName) {
        try { return target.getClass().getMethod(methodName).invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    private Object safeInvoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try { return target.getClass().getMethod(methodName, paramTypes).invoke(target, args); }
        catch (Throwable ignored) { return null; }
    }

    private Object invokeByNameAndArgCount(Object target, String methodName, Object[] args) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != args.length) continue;
                try { return m.invoke(target, args); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Utilities
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

    private static final class SkinData {
        final String value;
        final String signature;
        SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }
}
