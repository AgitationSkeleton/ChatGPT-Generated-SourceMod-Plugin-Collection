package com.redchanit.wanderlore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WanderLore extends JavaPlugin implements Listener {

    // -----------------------------
    // Files + config
    // -----------------------------
    private File configFile;
    private File dataFolder;
    private File structuresDbFile;
    private YamlConfiguration structuresDb;

    // Alive-world persistence
    private File aliveDbFile;
    private YamlConfiguration aliveDb;
	
	// Dynmap NPC marker integration
	private boolean dynmapNpcMarkersEnabled;
	private String dynmapNpcMarkerSetId;
	private String dynmapNpcMarkerSetLabel;
	private String dynmapNpcIconProvider;
	
	private Object dynmapMarkerApiObj;      // MarkerAPI (reflection)
	private Object dynmapNpcMarkerSetObj;   // MarkerSet (reflection)
	
	// Caches
	private final Map<String, String> skinToIconId = new ConcurrentHashMap<>();     // skinName -> iconId
	private final Map<String, byte[]> iconPngCache = new ConcurrentHashMap<>();     // iconId -> png bytes
	private final Map<String, String> npcIdToMarkerId = new ConcurrentHashMap<>();  // npcId -> markerId


    // -----------------------------
    // Runtime state
    // -----------------------------
    private final Random runtimeRandom = new Random();
    private final Set<String> generatedChunkKeys = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DialogueSession> dialogueSessions = new ConcurrentHashMap<>();

    private NamespacedKey keySeenFragments;
    private NamespacedKey keySeenNpcs;

    // NPC metadata
    private static final String META_WANDERLORE_NPC = "wanderlore_npc_tag";

    // Player cue cooldowns
    private final Map<UUID, Long> playerWatcherCooldownMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerAmbientCooldownMs = new ConcurrentHashMap<>();

    // -----------------------------
    // Config values (base)
    // -----------------------------
    private boolean enabled;
    private boolean debugLog;

    private List<String> worldAllowList;
    private int minDistanceFromSpawnBlocks;
    private double structureChancePerNewChunk; // legacy (overworld fallback)
    private double structureChanceOverworld;
    private double structureChanceNether;
    private double structureChanceEnd;
    private boolean allowNether;
    private boolean allowEnd;
    private boolean npcAllowNetherPortals;
    private boolean npcAllowEndPortals;

    private double npcChanceGivenStructure;
    private int maxNpcsPerWorld;
    private int maxStructuresPerHour;
    private int minY;
    private int maxY;

    private boolean integrateCitizens;
    private boolean integrateDynmap;
    private int dynmapScanMinutes;
    private int markerNpcChancePerScan;
    private List<String> dynmapMarkerSetWhitelist;

    // Base structure rate limiting
    private final Map<String, Long> worldRateWindowStartMs = new HashMap<>();
    private final Map<String, Integer> worldRateCount = new HashMap<>();

    // -----------------------------
    // Alive-world config values
    // -----------------------------
    private boolean aliveEnabled;

    // region mood grid
    private int regionCellSizeBlocks;
    private double regionMoodDriftPerMinute;      // magnitude of drift per minute
    private double regionPlayerCalmingPerTick;    // when players are present, mood trends down
    private double regionStructureUncannyBonus;   // when a structure exists in cell, mood trends up
    private int regionNeighborDiffuseIntervalMin;
    private double regionNeighborDiffuseStrength;

    // micro-ruins
    private boolean microRuinsEnabled;
    private double microRuinChancePerNewChunk;
    private int microRuinsMaxPerHour;
    private int microRuinMinDistanceFromSpawnBlocks;

    private final Map<String, Long> microRateWindowStartMs = new HashMap<>();
    private final Map<String, Integer> microRateCount = new HashMap<>();

    // watcher anchors
    private boolean watchersEnabled;
    private int watcherAnchorsPerWorld;
    private int watcherTriggerRadiusBlocks;
    private int watcherRelocateMinutesMin;
    private int watcherRelocateMinutesMax;
    private int watcherPlayerCooldownSeconds;
    private int watcherCueChancePercent; // chance to cue when in radius (checked every tick loop)

    // anchors by world
    private final Map<String, List<WatcherAnchor>> watcherAnchors = new HashMap<>();

    // -----------------------------
    // Lifecycle
    // -----------------------------
    @Override
    public void onEnable() {
        dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Failed to create plugin data folder: " + dataFolder.getAbsolutePath());
        }

        configFile = new File(dataFolder, "config.yml");
        ensureConfigExists();
        reloadLocalConfig();

        keySeenFragments = new NamespacedKey(this, "seen_fragments");
        keySeenNpcs = new NamespacedKey(this, "seen_npcs");

        structuresDbFile = new File(dataFolder, "structures_db.yml");
        structuresDb = YamlConfiguration.loadConfiguration(structuresDbFile);
        loadStructuresDbToMemory();

        aliveDbFile = new File(dataFolder, "alive_db.yml");
        aliveDb = YamlConfiguration.loadConfiguration(aliveDbFile);
        loadAliveDb();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (integrateDynmap) {
            startDynmapMarkerWatcherTask();
        }
		
		if (integrateDynmap && dynmapNpcMarkersEnabled) {
			initDynmapNpcMarkerSet();
		}
		

        if (aliveEnabled) {
            startAliveWorldTasks();
        }

        getLogger().info("WanderLore enabled.");
    }

    @Override
    public void onDisable() {
        saveStructuresDb();
        saveAliveDb();
        getLogger().info("WanderLore disabled.");
    }
	
	private void initDynmapNpcMarkerSet() {
		Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
		if (dynmap == null || !dynmap.isEnabled()) {
			if (debugLog) getLogger().info("Dynmap not present/enabled; NPC markers disabled.");
			dynmapMarkerApiObj = null;
			dynmapNpcMarkerSetObj = null;
			return;
		}
	
		try {
			// DynmapAPI markerAPI = dynmapAPI.getMarkerAPI()
			Class<?> dynmapApiClass = Class.forName("org.dynmap.DynmapAPI");
			Object api = dynmapApiClass.cast(dynmap);
			Method getMarkerAPI = dynmapApiClass.getMethod("getMarkerAPI");
			Object markerApi = getMarkerAPI.invoke(api);
			if (markerApi == null) return;
	
			dynmapMarkerApiObj = markerApi;
	
			// MarkerSet set = markerApi.getMarkerSet(id) or createMarkerSet(id,label,null,false)
			Method getMarkerSet = markerApi.getClass().getMethod("getMarkerSet", String.class);
			Object setObj = getMarkerSet.invoke(markerApi, dynmapNpcMarkerSetId);
	
			if (setObj == null) {
				Method createMarkerSet = markerApi.getClass().getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class);
				setObj = createMarkerSet.invoke(markerApi, dynmapNpcMarkerSetId, dynmapNpcMarkerSetLabel, null, false);
			} else {
				// keep label as requested
				try {
					Method setLabel = setObj.getClass().getMethod("setMarkerSetLabel", String.class);
					setLabel.invoke(setObj, dynmapNpcMarkerSetLabel);
				} catch (Throwable ignored) { }
			}
	
			dynmapNpcMarkerSetObj = setObj;
	
			if (debugLog) getLogger().info("Dynmap NPC MarkerSet ready: id=" + dynmapNpcMarkerSetId + " label=" + dynmapNpcMarkerSetLabel);
		} catch (Throwable t) {
			dynmapMarkerApiObj = null;
			dynmapNpcMarkerSetObj = null;
			getLogger().warning("Failed to init dynmap NPC marker set: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}
	

    // -----------------------------
    // Config creation
    // -----------------------------
    private void ensureConfigExists() {
        if (configFile.exists()) return;

        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("enabled", true);
        cfg.set("debugLog", false);

        cfg.set("worlds.allowList", Arrays.asList("world", "world_nether", "world_the_end", "tedkraft_world", "tedkraft_world_nether", "tedkraft_world_the_end"));
        cfg.set("generation.minDistanceFromSpawnBlocks", 1200);
        cfg.set("generation.structureChancePerNewChunk", 0.0018); // legacy (overworld fallback)
        cfg.set("generation.structureChance.overworld", 0.0018); // 0.18% per new chunk
        cfg.set("generation.structureChance.nether", 0.0010);    // 0.10% per new chunk
        cfg.set("generation.structureChance.end", 0.0006);       // 0.06% per new chunk
        cfg.set("generation.allowNether", true);
        cfg.set("generation.allowEnd", true);
        cfg.set("generation.npcChanceGivenStructure", 0.03);
        cfg.set("generation.maxStructuresPerHour", 8);

        // Overworld Y range (legacy; Nether/End use their own origin finders)
        cfg.set("generation.yRange.minY", 55);
        cfg.set("generation.yRange.maxY", 120);

        cfg.set("integration.citizens.enable", true);
        cfg.set("integration.citizens.maxNpcsPerWorld", 12);
        cfg.set("integration.citizens.portalUse.allowNetherPortals", false);
        cfg.set("integration.citizens.portalUse.allowEndPortals", false);
		cfg.set("integration.dynmap.npcMarkers.enable", true);
		cfg.set("integration.dynmap.npcMarkers.markerSetId", "wanderlore.npcs");
		cfg.set("integration.dynmap.npcMarkers.markerSetLabel", "npcs");
		cfg.set("integration.dynmap.npcMarkers.iconProvider", "crafatar"); // crafatar|minotar
        cfg.set("integration.dynmap.enable", true);
        cfg.set("integration.dynmap.scanEveryMinutes", 8);
        cfg.set("integration.dynmap.markerNpcChancePerScanPercent", 25);
        cfg.set("integration.dynmap.markerSetWhitelist", Arrays.asList("markers", "signs", "areas"));

        cfg.set("commands.allowTestSpawn", true);

        cfg.set("lore.fragments", Arrays.asList(
                "The Sand remembers names the stone forgets.",
                "Two voices. One path. Many lies.",
                "The shadow does not follow you. It waits ahead.",
                "Do not drink from the red basin under the quiet moon.",
                "When the map shows a circle, walk the line instead.",
                "The watchers do not blink. They only relocate.",
                "The door that is not a door opens when you stop trying."
        ));

        cfg.set("lore.npcNames", Arrays.asList(
                "Wanderer",
                "Cartographer",
                "Salt-Speaker",
                "Old Miner",
                "Pilgrim",
                "Archivist",
                "Lantern-Bearer"
        ));

        cfg.set("lore.fakePlayerSkins", Arrays.asList(
                "Herobrine",
                "Notch",
                "shadow",
                "MHF_Question",
                "MHF_Villager"
        ));

        cfg.set("structures.enabled", Arrays.asList(
                "RUINED_COTTAGE",
                "STONE_CIRCLE",
                "SUNKEN_SHRINE",
                "OBSIDIAN_STEP_PYRAMID",
                "HANGING_TRIAL",
                "RUINED_WATCHTOWER",
                "BURIED_CRYPT",
                "LOST_LIBRARY"
        ));

        // Environment-specific structure pools (preferred; legacy list above still honored)
        cfg.set("structures.overworld.enabled", Arrays.asList(
                "RUINED_COTTAGE",
                "STONE_CIRCLE",
                "SUNKEN_SHRINE",
                "OBSIDIAN_STEP_PYRAMID",
                "HANGING_TRIAL",
                "RUINED_WATCHTOWER",
                "BURIED_CRYPT",
                "LOST_LIBRARY"
        ));
        cfg.set("structures.nether.enabled", Arrays.asList(
                "NETHER_SIGNAL_PYLON",
                "BASALT_ALTAR",
                "FORGOTTEN_HALL"
        ));
        cfg.set("structures.end.enabled", Arrays.asList(
                "ENDER_OBELISK",
                "VOID_RUIN",
                "CHORUS_MAZE"
        ));

        // Alive-world defaults
        cfg.set("aliveWorld.enable", true);

        cfg.set("aliveWorld.region.cellSizeBlocks", 512);
        cfg.set("aliveWorld.region.moodDriftPerMinute", 0.8);
        cfg.set("aliveWorld.region.playerCalmingPerTick", 0.15);
        cfg.set("aliveWorld.region.structureUncannyBonus", 0.35);
        cfg.set("aliveWorld.region.neighborDiffuseIntervalMinutes", 10);
        cfg.set("aliveWorld.region.neighborDiffuseStrength", 0.08);

        cfg.set("aliveWorld.microRuins.enable", true);
        cfg.set("aliveWorld.microRuins.chancePerNewChunk", 0.02); // 2%
        cfg.set("aliveWorld.microRuins.maxPerHour", 40);
        cfg.set("aliveWorld.microRuins.minDistanceFromSpawnBlocks", 800);

        cfg.set("aliveWorld.watchers.enable", true);
        cfg.set("aliveWorld.watchers.anchorsPerWorld", 6);
        cfg.set("aliveWorld.watchers.triggerRadiusBlocks", 28);
        cfg.set("aliveWorld.watchers.relocateMinutesMin", 18);
        cfg.set("aliveWorld.watchers.relocateMinutesMax", 55);
        cfg.set("aliveWorld.watchers.playerCooldownSeconds", 120);
        cfg.set("aliveWorld.watchers.cueChancePercent", 18);

        try {
            cfg.save(configFile);
        } catch (IOException e) {
            getLogger().warning("Failed to write default config.yml: " + e.getMessage());
        }
    }

    private void reloadLocalConfig() {
        reloadConfig();

        enabled = getConfig().getBoolean("enabled", true);
        debugLog = getConfig().getBoolean("debugLog", false);

        worldAllowList = getConfig().getStringList("worlds.allowList");
        if (worldAllowList == null) worldAllowList = new ArrayList<>();

        minDistanceFromSpawnBlocks = getConfig().getInt("generation.minDistanceFromSpawnBlocks", 1200);
        structureChancePerNewChunk = getConfig().getDouble("generation.structureChancePerNewChunk", 0.0018);
        structureChanceOverworld = getConfig().getDouble("generation.structureChance.overworld", structureChancePerNewChunk);
        structureChanceNether = getConfig().getDouble("generation.structureChance.nether", Math.max(0.0, structureChancePerNewChunk * 0.6));
        structureChanceEnd = getConfig().getDouble("generation.structureChance.end", Math.max(0.0, structureChancePerNewChunk * 0.35));
        allowNether = getConfig().getBoolean("generation.allowNether", true);
        allowEnd = getConfig().getBoolean("generation.allowEnd", true);
        npcChanceGivenStructure = getConfig().getDouble("generation.npcChanceGivenStructure", 0.03);
        maxStructuresPerHour = getConfig().getInt("generation.maxStructuresPerHour", 8);
        minY = getConfig().getInt("generation.yRange.minY", 55);
        maxY = getConfig().getInt("generation.yRange.maxY", 120);

        integrateCitizens = getConfig().getBoolean("integration.citizens.enable", true);

        maxNpcsPerWorld = Math.max(0, getConfig().getInt("integration.citizens.maxNpcsPerWorld", 12));
        npcAllowNetherPortals = getConfig().getBoolean("integration.citizens.portalUse.allowNetherPortals", false);
        npcAllowEndPortals = getConfig().getBoolean("integration.citizens.portalUse.allowEndPortals", false);
        integrateDynmap = getConfig().getBoolean("integration.dynmap.enable", true);
        dynmapScanMinutes = Math.max(1, getConfig().getInt("integration.dynmap.scanEveryMinutes", 8));
        markerNpcChancePerScan = clampInt(getConfig().getInt("integration.dynmap.markerNpcChancePerScanPercent", 25), 0, 100);
        dynmapMarkerSetWhitelist = getConfig().getStringList("integration.dynmap.markerSetWhitelist");
		dynmapNpcMarkersEnabled = getConfig().getBoolean("integration.dynmap.npcMarkers.enable", true);
		dynmapNpcMarkerSetId = getConfig().getString("integration.dynmap.npcMarkers.markerSetId", "wanderlore.npcs");
		dynmapNpcMarkerSetLabel = getConfig().getString("integration.dynmap.npcMarkers.markerSetLabel", "npcs");
		dynmapNpcIconProvider = getConfig().getString("integration.dynmap.npcMarkers.iconProvider", "crafatar");
		if (dynmapNpcIconProvider == null) dynmapNpcIconProvider = "crafatar";
		dynmapNpcIconProvider = dynmapNpcIconProvider.trim().toLowerCase(Locale.ROOT);
		
        if (dynmapMarkerSetWhitelist == null) dynmapMarkerSetWhitelist = new ArrayList<>();

        // Alive-world
        aliveEnabled = getConfig().getBoolean("aliveWorld.enable", true);

        regionCellSizeBlocks = Math.max(128, getConfig().getInt("aliveWorld.region.cellSizeBlocks", 512));
        regionMoodDriftPerMinute = clampDouble(getConfig().getDouble("aliveWorld.region.moodDriftPerMinute", 0.8), 0.0, 10.0);
        regionPlayerCalmingPerTick = clampDouble(getConfig().getDouble("aliveWorld.region.playerCalmingPerTick", 0.15), 0.0, 5.0);
        regionStructureUncannyBonus = clampDouble(getConfig().getDouble("aliveWorld.region.structureUncannyBonus", 0.35), 0.0, 5.0);
        regionNeighborDiffuseIntervalMin = Math.max(1, getConfig().getInt("aliveWorld.region.neighborDiffuseIntervalMinutes", 10));
        regionNeighborDiffuseStrength = clampDouble(getConfig().getDouble("aliveWorld.region.neighborDiffuseStrength", 0.08), 0.0, 0.5);

        microRuinsEnabled = getConfig().getBoolean("aliveWorld.microRuins.enable", true);
        microRuinChancePerNewChunk = clampDouble(getConfig().getDouble("aliveWorld.microRuins.chancePerNewChunk", 0.02), 0.0, 1.0);
        microRuinsMaxPerHour = Math.max(0, getConfig().getInt("aliveWorld.microRuins.maxPerHour", 40));
        microRuinMinDistanceFromSpawnBlocks = Math.max(0, getConfig().getInt("aliveWorld.microRuins.minDistanceFromSpawnBlocks", 800));

        watchersEnabled = getConfig().getBoolean("aliveWorld.watchers.enable", true);
        watcherAnchorsPerWorld = Math.max(0, getConfig().getInt("aliveWorld.watchers.anchorsPerWorld", 6));
        watcherTriggerRadiusBlocks = Math.max(8, getConfig().getInt("aliveWorld.watchers.triggerRadiusBlocks", 28));
        watcherRelocateMinutesMin = Math.max(2, getConfig().getInt("aliveWorld.watchers.relocateMinutesMin", 18));
        watcherRelocateMinutesMax = Math.max(watcherRelocateMinutesMin, getConfig().getInt("aliveWorld.watchers.relocateMinutesMax", 55));
        watcherPlayerCooldownSeconds = Math.max(10, getConfig().getInt("aliveWorld.watchers.playerCooldownSeconds", 120));
        watcherCueChancePercent = clampInt(getConfig().getInt("aliveWorld.watchers.cueChancePercent", 18), 0, 100);
    }

private boolean isOurNpcEntity(Entity entity) {
    return entity != null && entity.hasMetadata(META_WANDERLORE_NPC);
}

private int countOurNpcEntities(World world) {
    if (world == null) return 0;
    int count = 0;
    for (Entity e : world.getEntities()) {
        if (isOurNpcEntity(e)) count++;
    }
    return count;
}

    // -----------------------------
    // DB loading/saving
    // -----------------------------
    private void loadStructuresDbToMemory() {
        generatedChunkKeys.clear();
        for (String key : structuresDb.getKeys(false)) {
            if (structuresDb.getBoolean(key + ".generated", false)) {
                generatedChunkKeys.add(key);
            }
        }
    }

    private void saveStructuresDb() {
        try {
            structuresDb.save(structuresDbFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save structures_db.yml: " + e.getMessage());
        }
    }

    private void loadAliveDb() {
        // load anchors into memory
        watcherAnchors.clear();
        ConfigurationSection worldsSec = aliveDb.getConfigurationSection("watchers.worlds");
        if (worldsSec != null) {
            for (String worldName : worldsSec.getKeys(false)) {
                ConfigurationSection wSec = worldsSec.getConfigurationSection(worldName);
                if (wSec == null) continue;

                List<WatcherAnchor> list = new ArrayList<>();
                ConfigurationSection anchorsSec = wSec.getConfigurationSection("anchors");
                if (anchorsSec != null) {
                    for (String idx : anchorsSec.getKeys(false)) {
                        ConfigurationSection a = anchorsSec.getConfigurationSection(idx);
                        if (a == null) continue;

                        String locStr = a.getString("loc", null);
                        long nextRelocate = a.getLong("nextRelocateMs", 0L);

                        Location loc = deserializeLoc(locStr);
                        if (loc == null) continue;

                        list.add(new WatcherAnchor(loc, nextRelocate));
                    }
                }
                watcherAnchors.put(worldName, list);
            }
        }
    }

    private void saveAliveDb() {
        // moods are stored lazily; anchors saved actively
        aliveDb.set("watchers.worlds", null);

        ConfigurationSection base = aliveDb.createSection("watchers.worlds");
        for (Map.Entry<String, List<WatcherAnchor>> e : watcherAnchors.entrySet()) {
            String worldName = e.getKey();
            List<WatcherAnchor> list = e.getValue();
            ConfigurationSection wSec = base.createSection(worldName);
            ConfigurationSection anchorsSec = wSec.createSection("anchors");
            for (int i = 0; i < list.size(); i++) {
                WatcherAnchor a = list.get(i);
                ConfigurationSection as = anchorsSec.createSection(String.valueOf(i));
                as.set("loc", serializeLoc(a.location));
                as.set("nextRelocateMs", a.nextRelocateMs);
            }
        }

        try {
            aliveDb.save(aliveDbFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to save alive_db.yml: " + ex.getMessage());
        }
    }

    // -----------------------------
    // Events
    // -----------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled) return;
        if (!event.isNewChunk()) return;

        World world = event.getWorld();
        if (!isWorldAllowed(world)) return;

        // Dimension gating (Overworld/Nether/End)
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER && !allowNether) return;
        if (env == World.Environment.THE_END && !allowEnd) return;

        double envChance = switch (env) {
            case NETHER -> structureChanceNether;
            case THE_END -> structureChanceEnd;
            default -> structureChanceOverworld;
        };

        Location chunkCenter = new Location(world,
                event.getChunk().getX() * 16 + 8,
                80,
                event.getChunk().getZ() * 16 + 8);

        Location spawn = world.getSpawnLocation();
        if (spawn.distanceSquared(chunkCenter) < (double) minDistanceFromSpawnBlocks * minDistanceFromSpawnBlocks) {
            return;
        }

        long seed = computeDeterministicSeed(world.getSeed(), event.getChunk().getX(), event.getChunk().getZ());
        Random chunkRandom = new Random(seed);

        // micro-ruins first (more common)
        if (aliveEnabled && microRuinsEnabled) {
            maybeGenerateMicroRuin(world, event.getChunk().getX(), event.getChunk().getZ(), chunkRandom);
        }

        String chunkKey = makeChunkKey(world, event.getChunk().getX(), event.getChunk().getZ());
        if (generatedChunkKeys.contains(chunkKey)) {
            return;
        }

        if (!rateLimitAllows(world.getName())) return;

        if (chunkRandom.nextDouble() > envChance) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                tryGenerateStructureInChunk(world, event.getChunk().getX(), event.getChunk().getZ(), chunkRandom, chunkKey);
            }
        }.runTask(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!enabled) return;

        Entity clicked = event.getRightClicked();
        if (!clicked.hasMetadata(META_WANDERLORE_NPC)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        String npcId = clicked.getMetadata(META_WANDERLORE_NPC).get(0).asString();
        beginNpcDialogue(player, npcId);
    }

@EventHandler(ignoreCancelled = true)
public void onEntityPortal(EntityPortalEvent event) {
    if (!enabled) return;

    Entity entity = event.getEntity();
    if (!isOurNpcEntity(entity)) return;

    Location to = event.getTo();
    if (to == null || to.getWorld() == null) return;

    World.Environment envTo = to.getWorld().getEnvironment();
    if (envTo == World.Environment.NETHER && !npcAllowNetherPortals) {
        event.setCancelled(true);
        return;
    }
    if (envTo == World.Environment.THE_END && !npcAllowEndPortals) {
        event.setCancelled(true);
    }
}


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        DialogueSession session = dialogueSessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;

        event.setCancelled(true);

        String msg = event.getMessage().trim().toLowerCase(Locale.ROOT);
        String answer;
        if (msg.equals("yes") || msg.equals("y")) answer = "yes";
        else if (msg.equals("no") || msg.equals("n")) answer = "no";
        else {
            event.getPlayer().sendMessage(color("&7[&bWanderLore&7] &fSay &aYES&f or &cNO&f."));
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> continueDialogue(event.getPlayer(), answer));
    }

    // -----------------------------
    // Commands
    // -----------------------------
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("wanderlore")) return false;

        if (args.length == 0) {
    if (args[0].equalsIgnoreCase("purgeNpcs") || args[0].equalsIgnoreCase("purgenpcs")) {
    if (!sender.hasPermission("wanderlore.admin")) {
        sender.sendMessage(color("&cNo permission."));
        return true;
    }
    String targetWorldName = (args.length >= 2) ? args[1] : null;
    int removed = purgeOwnedNpcs(targetWorldName);
    sender.sendMessage(color("&7[&bWanderLore&7] &aPurged &f" + removed + "&a WanderLore NPC(s)."));
    return true;
}

if (args[0].equalsIgnoreCase("purgeAllNpcs") || args[0].equalsIgnoreCase("purgeallnpcs")) {
    if (!sender.hasPermission("wanderlore.admin")) {
        sender.sendMessage(color("&cNo permission."));
        return true;
    }
    int removed = purgeOwnedNpcs(null);
    sender.sendMessage(color("&7[&bWanderLore&7] &aPurged &f" + removed + "&a WanderLore NPC(s) in all worlds."));
    return true;
}

        sender.sendMessage(color("&7[&bWanderLore&7] &f/wanderlore reload | stats | spawn | purgeNpcs [world] | purgeAllNpcs"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("wanderlore.admin")) {
                sender.sendMessage(color("&cNo permission."));
                return true;
            }
            reloadLocalConfig();
            sender.sendMessage(color("&7[&bWanderLore&7] &aReloaded. (Restart recommended if you changed anchor counts)"));
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Player-only.");
                return true;
            }
            int seenFrag = getSeenCount(player, keySeenFragments);
            int seenNpc = getSeenCount(player, keySeenNpcs);
            sender.sendMessage(color("&7[&bWanderLore&7] &fSeen fragments: &b" + seenFrag + " &7| &fSeen NPC encounters: &b" + seenNpc));

            if (aliveEnabled) {
                double mood = getCellMood(player.getWorld(), player.getLocation());
                sender.sendMessage(color("&7[&bWanderLore&7] &fArea mood: &b" + String.format(Locale.ROOT, "%.1f", mood) + "&f/100"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (!sender.hasPermission("wanderlore.admin")) {
                sender.sendMessage(color("&cNo permission."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Player-only.");
                return true;
            }
            boolean allowTest = getConfig().getBoolean("commands.allowTestSpawn", true);
            if (!allowTest) {
                sender.sendMessage(color("&cTest spawn disabled in config."));
                return true;
            }

            World world = player.getWorld();
            Location loc = player.getLocation();

            Random r = new Random(System.currentTimeMillis());
            StructureType type = pickEnabledStructureType(player.getWorld(), r);
            if (type == null) {
                sender.sendMessage(color("&cNo structures enabled."));
                return true;
            }

            Location origin = switch (world.getEnvironment()) {
                case NETHER -> findNetherOriginInChunk(world, loc.getChunk().getX(), loc.getChunk().getZ(), r);
                case THE_END -> findEndOriginInChunk(world, loc.getChunk().getX(), loc.getChunk().getZ(), r);
                default -> findSurfaceOriginNear(loc, r);
            };
            if (origin == null) {
                sender.sendMessage(color("&cCould not find a surface origin here."));
                return true;
            }

            generateStructure(type, origin, r);
            sender.sendMessage(color("&7[&bWanderLore&7] &aSpawned &f" + type.name() + "&a near you."));

            if (aliveEnabled && microRuinsEnabled) {
                spawnMicroRuinNear(origin, r);
                sender.sendMessage(color("&7[&bWanderLore&7] &aAlso spawned a micro-ruin nearby."));
            }

            if (integrateCitizens && r.nextBoolean()) {
                spawnCitizensNpcIfPossible(origin.clone().add(2, 0, 2), makeNpcId(world, origin), r);
            }

            return true;
        }

        sender.sendMessage(color("&7[&bWanderLore&7] &f/wanderlore reload | stats | spawn | purgeNpcs [world] | purgeAllNpcs"));
        return true;
    }

    // -----------------------------
    // Alive-world tasks
    // -----------------------------
    private void startAliveWorldTasks() {
        // Ensure anchors exist
        Bukkit.getScheduler().runTask(this, () -> ensureWatcherAnchorsInitialized());

        // Every 5 seconds: per-player ambient checks + update region mood with presence
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled || !aliveEnabled) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == null || !p.isOnline()) continue;
                    World w = p.getWorld();
                    if (!isWorldAllowed(w)) continue;

                    tickRegionPresence(w, p.getLocation());
                    maybeDoAmbientCue(p);

                    if (watchersEnabled) {
                        maybeTriggerWatcherCue(p);
                    }
                }
            }
        }.runTaskTimer(this, 20L * 5, 20L * 5);

        // Every 1 minute: drift all recently-touched cells, relocate anchors when due
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled || !aliveEnabled) return;
                tickRegionDriftAndDiffuse();
                if (watchersEnabled) {
                    tickWatcherRelocations();
                }
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);
    }

    // -----------------------------
    // Region mood grid (persistent)
    // -----------------------------
    private String cellKey(World w, int cellX, int cellZ) {
        return w.getName() + ":" + cellX + ":" + cellZ;
    }

    private int cellCoord(int blockCoord) {
        // floor division
        if (blockCoord >= 0) return blockCoord / regionCellSizeBlocks;
        return -(((-blockCoord - 1) / regionCellSizeBlocks) + 1);
    }

    private double getCellMood(World w, Location loc) {
        int cx = cellCoord(loc.getBlockX());
        int cz = cellCoord(loc.getBlockZ());
        String key = cellKey(w, cx, cz);
        return getCellMoodByKey(key);
    }

    private double getCellMoodByKey(String key) {
        double mood = aliveDb.getDouble("region.cells." + key + ".mood", 0.0);
        return clampDouble(mood, 0.0, 100.0);
    }

    private void setCellMoodByKey(String key, double mood) {
        aliveDb.set("region.cells." + key + ".mood", clampDouble(mood, 0.0, 100.0));
        aliveDb.set("region.cells." + key + ".lastTouchMs", System.currentTimeMillis());
    }

    private void tickRegionPresence(World w, Location loc) {
        int cx = cellCoord(loc.getBlockX());
        int cz = cellCoord(loc.getBlockZ());
        String key = cellKey(w, cx, cz);

        // Player presence tends to calm
        double mood = getCellMoodByKey(key);
        mood -= regionPlayerCalmingPerTick;

        // If this cell contains a big structure (best-effort), it tends to become uncanny
        if (cellHasStructure(w.getName(), cx, cz)) {
            mood += regionStructureUncannyBonus;
        }

        setCellMoodByKey(key, mood);
    }

    private boolean cellHasStructure(String worldName, int cellX, int cellZ) {
        // Approx: if any stored structure origin falls into this cell, consider it "haunted"
        ConfigurationSection root = structuresDb.getConfigurationSection("");
        if (root == null) return false;

        // This is a cheap scan; structures count is typically small because rarity is low.
        for (String key : structuresDb.getKeys(false)) {
            String w = structuresDb.getString(key + ".world", "");
            if (!worldName.equalsIgnoreCase(w)) continue;

            String origin = structuresDb.getString(key + ".origin", null);
            Location loc = deserializeLoc(origin);
            if (loc == null) continue;

            int cx = cellCoord(loc.getBlockX());
            int cz = cellCoord(loc.getBlockZ());
            if (cx == cellX && cz == cellZ) return true;
        }
        return false;
    }

    private void tickRegionDriftAndDiffuse() {
        // Drift: random walk
        long now = System.currentTimeMillis();
        ConfigurationSection cells = aliveDb.getConfigurationSection("region.cells");
        if (cells == null) return;

        // Only touch cells updated in last ~2 hours to keep file from becoming huge.
        long activeCutoff = now - (2L * 60L * 60L * 1000L);

        List<String> activeKeys = new ArrayList<>();
        for (String key : cells.getKeys(false)) {
            long last = aliveDb.getLong("region.cells." + key + ".lastTouchMs", 0L);
            if (last >= activeCutoff) activeKeys.add(key);
        }

        // Apply drift
        for (String key : activeKeys) {
            double mood = getCellMoodByKey(key);
            double delta = (runtimeRandom.nextDouble() * 2.0 - 1.0) * regionMoodDriftPerMinute;
            mood += delta;
            setCellMoodByKey(key, mood);
        }

        // Diffuse occasionally
        long lastDiffuse = aliveDb.getLong("region.lastDiffuseMs", 0L);
        if ((now - lastDiffuse) < (long) regionNeighborDiffuseIntervalMin * 60L * 1000L) return;
        aliveDb.set("region.lastDiffuseMs", now);

        // Simple diffusion: pull each cell slightly toward average of its neighbors (N,S,E,W)
        for (String key : activeKeys) {
            CellRef ref = parseCellKey(key);
            if (ref == null) continue;

            String kN = cellKey(ref.worldName, ref.cellX, ref.cellZ - 1);
            String kS = cellKey(ref.worldName, ref.cellX, ref.cellZ + 1);
            String kE = cellKey(ref.worldName, ref.cellX + 1, ref.cellZ);
            String kW = cellKey(ref.worldName, ref.cellX - 1, ref.cellZ);

            double m = getCellMoodByKey(key);
            double mn = getCellMoodByKey(kN);
            double ms = getCellMoodByKey(kS);
            double me = getCellMoodByKey(kE);
            double mw = getCellMoodByKey(kW);

            double avg = (m + mn + ms + me + mw) / 5.0;
            double blended = m + (avg - m) * regionNeighborDiffuseStrength;
            setCellMoodByKey(key, blended);
        }

        // Save occasionally
        saveAliveDb();
    }

    private String cellKey(String worldName, int cellX, int cellZ) {
        return worldName + ":" + cellX + ":" + cellZ;
    }

    private static class CellRef {
        final String worldName;
        final int cellX;
        final int cellZ;

        private CellRef(String worldName, int cellX, int cellZ) {
            this.worldName = worldName;
            this.cellX = cellX;
            this.cellZ = cellZ;
        }
    }

    private CellRef parseCellKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(":");
        if (parts.length != 3) return null;
        try {
            String worldName = parts[0];
            int cx = Integer.parseInt(parts[1]);
            int cz = Integer.parseInt(parts[2]);
            return new CellRef(worldName, cx, cz);
        } catch (Exception ignored) {
            return null;
        }
    }

    // -----------------------------
    // Ambient cues (subtle, mood-based)
    // -----------------------------
    private void maybeDoAmbientCue(Player p) {
        World w = p.getWorld();
        Location loc = p.getLocation();

        double mood = getCellMood(w, loc);

        // Low mood: almost never; high mood: sometimes
        int chance = (int) clampDouble((mood / 100.0) * 20.0, 0.0, 20.0); // 0..20%
        if (chance <= 0) return;

        // Cooldown per player (keep it rare)
        long now = System.currentTimeMillis();
        long cooldown = 60_000L; // 60s
        Long last = playerAmbientCooldownMs.get(p.getUniqueId());
        if (last != null && (now - last) < cooldown) return;

        if (runtimeRandom.nextInt(100) >= chance) return;

        // Cue selection depends on environment
        boolean isNight = (w.getTime() > 13000 && w.getTime() < 23000);
        boolean raining = w.hasStorm();
        boolean alone = isPlayerAloneNearby(p, 48);

        // Very light touch: sound + tiny particle
        Location edge = loc.clone().add(runtimeRandom.nextInt(19) - 9, 0, runtimeRandom.nextInt(19) - 9);
        edge.setY(w.getHighestBlockYAt(edge) + 1);

        if (alone && (isNight || raining)) {
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.7f);
            w.spawnParticle(Particle.SMOKE, edge, 8, 0.4, 0.4, 0.4, 0.0);
        } else {
            // softer
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 0.6f);
            w.spawnParticle(Particle.ASH, edge, 10, 0.6, 0.4, 0.6, 0.0);
        }

        playerAmbientCooldownMs.put(p.getUniqueId(), now);
    }

    private boolean isPlayerAloneNearby(Player p, int radius) {
        Location loc = p.getLocation();
        int r2 = radius * radius;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == null || !other.isOnline()) continue;
            if (other.getUniqueId().equals(p.getUniqueId())) continue;
            if (!other.getWorld().equals(p.getWorld())) continue;
            if (other.getLocation().distanceSquared(loc) <= r2) return false;
        }
        return true;
    }

    // -----------------------------
    // Watcher anchors (presence zones)
    // -----------------------------
    private static class WatcherAnchor {
        Location location;
        long nextRelocateMs;

        WatcherAnchor(Location location, long nextRelocateMs) {
            this.location = location;
            this.nextRelocateMs = nextRelocateMs;
        }
    }

    private void ensureWatcherAnchorsInitialized() {
        if (!watchersEnabled || watcherAnchorsPerWorld <= 0) return;

        for (World w : Bukkit.getWorlds()) {
            if (!isWorldAllowed(w)) continue;

            List<WatcherAnchor> list = watcherAnchors.getOrDefault(w.getName(), new ArrayList<>());
            while (list.size() < watcherAnchorsPerWorld) {
                Location loc = pickRemoteAnchorLocation(w, null);
                if (loc == null) break;
                list.add(new WatcherAnchor(loc, System.currentTimeMillis() + minutesToMs(randBetween(watcherRelocateMinutesMin, watcherRelocateMinutesMax))));
            }
            watcherAnchors.put(w.getName(), list);
        }

        saveAliveDb();
    }

    private void tickWatcherRelocations() {
        long now = System.currentTimeMillis();

        for (World w : Bukkit.getWorlds()) {
            if (!isWorldAllowed(w)) continue;

            List<WatcherAnchor> list = watcherAnchors.get(w.getName());
            if (list == null || list.isEmpty()) continue;

            for (WatcherAnchor a : list) {
                if (now < a.nextRelocateMs) continue;

                // relocate somewhere remote; bias around a random online player if available
                Player bias = pickRandomOnlinePlayerInWorld(w);
                Location newLoc = pickRemoteAnchorLocation(w, bias);
                if (newLoc != null) {
                    a.location = newLoc;
                }
                a.nextRelocateMs = now + minutesToMs(randBetween(watcherRelocateMinutesMin, watcherRelocateMinutesMax));
            }
        }

        saveAliveDb();
    }

    private void maybeTriggerWatcherCue(Player p) {
        if (watcherAnchorsPerWorld <= 0) return;

        World w = p.getWorld();
        List<WatcherAnchor> list = watcherAnchors.get(w.getName());
        if (list == null || list.isEmpty()) return;

        // Cooldown per player
        long now = System.currentTimeMillis();
        long minCd = watcherPlayerCooldownSeconds * 1000L;
        Long last = playerWatcherCooldownMs.get(p.getUniqueId());
        if (last != null && (now - last) < minCd) return;

        // If you're in a "calm" cell, don't bother
        double mood = getCellMood(w, p.getLocation());
        if (mood < 12.0) return;

        int radius2 = watcherTriggerRadiusBlocks * watcherTriggerRadiusBlocks;

        for (WatcherAnchor a : list) {
            if (!a.location.getWorld().equals(w)) continue;
            if (a.location.distanceSquared(p.getLocation()) > radius2) continue;

            // chance gate
            if (runtimeRandom.nextInt(100) >= watcherCueChancePercent) return;

            doWatcherCue(p, a.location, mood);
            playerWatcherCooldownMs.put(p.getUniqueId(), now);
            return;
        }
    }

    private void doWatcherCue(Player p, Location anchor, double mood) {
        World w = p.getWorld();

        boolean night = (w.getTime() > 13000 && w.getTime() < 23000);
        boolean storm = w.hasStorm();
        boolean alone = isPlayerAloneNearby(p, 64);

        // pick a "behind you" spot roughly
        Location loc = p.getLocation();
        float yaw = loc.getYaw();
        double radians = Math.toRadians(yaw);
        double bx = -Math.sin(radians) * 6.0;
        double bz = Math.cos(radians) * 6.0;
        Location behind = loc.clone().add(bx, 0, bz);
        behind.setY(w.getHighestBlockYAt(behind) + 1);

        // "Presence" particles near anchor, but subtle
        Location a = anchor.clone().add(runtimeRandom.nextInt(5) - 2, 1, runtimeRandom.nextInt(5) - 2);
        w.spawnParticle(Particle.ASH, a, 16, 0.7, 0.4, 0.7, 0.0);

        // Sound palette
        if (alone && (night || storm) && mood > 55.0) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.35f, 0.55f);
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.65f, 0.7f);
            w.spawnParticle(Particle.SMOKE, behind, 10, 0.3, 0.3, 0.3, 0.0);
        } else {
            p.playSound(p.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.45f, 0.7f);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.25f, 0.55f);
        }

        // Tiny chance of “proof” (still subtle): a bat, or an enderman far away
        if (runtimeRandom.nextInt(100) < 6) {
            EntityTypeSpawn(w, loc, mood);
        }
    }

    private void EntityTypeSpawn(World w, Location near, double mood) {
        Location spawnLoc = near.clone().add(runtimeRandom.nextInt(33) - 16, 0, runtimeRandom.nextInt(33) - 16);
        spawnLoc.setY(w.getHighestBlockYAt(spawnLoc) + 1);

        if (mood > 70.0 && runtimeRandom.nextInt(100) < 40) {
            w.spawnEntity(spawnLoc, org.bukkit.entity.EntityType.ENDERMAN);
        } else {
            w.spawnEntity(spawnLoc, org.bukkit.entity.EntityType.BAT);
        }
    }

    private Location pickRemoteAnchorLocation(World w, Player bias) {
        Location spawn = w.getSpawnLocation();

        // pick around bias player if available; otherwise around spawn but far
        Location center = (bias != null) ? bias.getLocation() : spawn;

        for (int attempt = 0; attempt < 18; attempt++) {
            int dist = 900 + runtimeRandom.nextInt(2800);
            double angle = runtimeRandom.nextDouble() * Math.PI * 2.0;

            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            Location test = new Location(w, x, 0, z);

            // keep far from spawn anyway
            if (spawn.distanceSquared(new Location(w, x, spawn.getY(), z)) < (double) minDistanceFromSpawnBlocks * minDistanceFromSpawnBlocks) {
                continue;
            }

            int y = w.getHighestBlockYAt(x, z);
            if (y <= 0) continue;

            Block below = w.getBlockAt(x, y - 1, z);
            if (below.getType() == Material.WATER || below.getType() == Material.LAVA) continue;

            test.setY(y + 1);
            return test;
        }

        return null;
    }

    private Player pickRandomOnlinePlayerInWorld(World w) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline() && p.getWorld().equals(w)) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(runtimeRandom.nextInt(candidates.size()));
    }

    private long minutesToMs(int minutes) {
        return minutes * 60_000L;
    }

    private int randBetween(int min, int max) {
        if (max <= min) return min;
        return min + runtimeRandom.nextInt(max - min + 1);
    }

    // -----------------------------
    // Micro-ruins
    // -----------------------------
    private void maybeGenerateMicroRuin(World world, int chunkX, int chunkZ, Random chunkRandom) {
        // far enough from spawn (micro ruins can start closer than big structures, but still out there)
        Location chunkCenter = new Location(world, chunkX * 16 + 8, 80, chunkZ * 16 + 8);
        if (world.getSpawnLocation().distanceSquared(chunkCenter) < (double) microRuinMinDistanceFromSpawnBlocks * microRuinMinDistanceFromSpawnBlocks) {
            return;
        }

        if (!microRateLimitAllows(world.getName())) return;

        if (chunkRandom.nextDouble() > microRuinChancePerNewChunk) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                Location origin = findSurfaceOriginInChunk(world, chunkX, chunkZ, chunkRandom);
                if (origin == null) return;
                if (origin.getBlockY() < minY || origin.getBlockY() > maxY) return;

                MicroRuinType type = MicroRuinType.pick(chunkRandom);
                if (spawnMicroRuin(type, origin, chunkRandom)) {
                    incrementMicroRate(world.getName());

                    // Mood bump: this area feels a bit more uncanny after a micro-ruin appears
                    bumpMood(world, origin, 2.0 + chunkRandom.nextDouble() * 2.0);

                    if (debugLog) getLogger().info("Micro-ruin " + type.name() + " at " + serializeLoc(origin));
                }
            }
        }.runTask(this);
    }

    private void spawnMicroRuinNear(Location origin, Random r) {
        Location loc = findSurfaceOriginNear(origin, r);
        if (loc == null) loc = origin;
        spawnMicroRuin(MicroRuinType.pick(r), loc, r);
    }

    private enum MicroRuinType {
        CAMP_REMAINS,
        MARKER_STONE,
        HALF_BURIED_STAIR,
        HANGING_CHAIN,
        TINY_SHRINE;

        static MicroRuinType pick(Random r) {
            MicroRuinType[] all = values();
            return all[r.nextInt(all.length)];
        }
    }

    private boolean spawnMicroRuin(MicroRuinType type, Location origin, Random r) {
        return switch (type) {
            case CAMP_REMAINS -> microCampRemains(origin, r);
            case MARKER_STONE -> microMarkerStone(origin, r);
            case HALF_BURIED_STAIR -> microHalfBuriedStair(origin, r);
            case HANGING_CHAIN -> microHangingChain(origin, r);
            case TINY_SHRINE -> microTinyShrine(origin, r);
        };
    }

    private boolean microCampRemains(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();

        // small flat check
        if (!ensureBuildableSurface(w, x, y, z, 5, 5, 2)) return false;

        // 2 logs + campfire + scattered slabs
        Material log = r.nextBoolean() ? Material.SPRUCE_LOG : Material.OAK_LOG;
        setBlockSafe(w, x - 1, y, z, log);
        setBlockSafe(w, x + 1, y, z, log);

        Material campfire = r.nextInt(100) < 70 ? Material.CAMPFIRE : Material.SOUL_CAMPFIRE;
        setBlockSafe(w, x, y, z, campfire);

        scatterBlocks(w, x - 2, y, z - 2, 5, 2, 5, Material.COBBLESTONE_SLAB, r, 6);

        // rare chest under a carpet “stash”
        if (r.nextInt(100) < 18) {
            setBlockSafe(w, x, y - 1, z + 1, Material.CHEST);
            Block cb = w.getBlockAt(x, y - 1, z + 1);
            if (cb.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();
                chest.getBlockInventory().addItem(makeScrapNote(r, "camp"));
                if (r.nextInt(100) < 35) chest.getBlockInventory().addItem(new ItemStack(Material.BREAD, 1 + r.nextInt(2)));
                if (r.nextInt(100) < 20) chest.getBlockInventory().addItem(new ItemStack(Material.COAL, 2 + r.nextInt(4)));
                chest.update(true);
            }
            setBlockSafe(w, x, y, z + 1, Material.BROWN_CARPET);
        }

        // Mood nudges upward slightly
        return true;
    }

    private boolean microMarkerStone(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();

        Material base = r.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
        Material top = r.nextBoolean() ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;

        int height = 2 + r.nextInt(3);
        for (int i = 0; i < height; i++) setBlockSafe(w, x, y + i, z, base);
        setBlockSafe(w, x, y + height, z, top);

        // Sometimes a sign with a short line
        if (r.nextInt(100) < 15) {
            setBlockSafe(w, x, y + 1, z + 1, Material.OAK_SIGN);
            Block sb = w.getBlockAt(x, y + 1, z + 1);
            if (sb.getState() instanceof Sign sign) {
                List<String> lines = crypticLines(r);
                sign.setLine(0, lines.get(0));
                sign.setLine(1, "");
                sign.setLine(2, lines.get(2));
                sign.setLine(3, "");
                sign.update(true);
            }
        }

        return true;
    }

    private boolean microHalfBuriedStair(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();

        // a tiny “top of something” impression
        Material stair = r.nextBoolean() ? Material.STONE_BRICK_STAIRS : Material.COBBLESTONE_STAIRS;
        setBlockSafe(w, x, y, z, stair);
        setBlockSafe(w, x, y - 1, z, r.nextBoolean() ? Material.COBBLESTONE : Material.GRAVEL);
        setBlockSafe(w, x, y, z + 1, r.nextBoolean() ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);

        // sometimes a loose plank
        if (r.nextInt(100) < 25) {
            setBlockSafe(w, x + 1, y, z, r.nextBoolean() ? Material.OAK_PLANKS : Material.SPRUCE_PLANKS);
        }

        return true;
    }

    private boolean microHangingChain(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        // Try to find a nearby overhang by scanning upward for stone
        int x = origin.getBlockX();
        int z = origin.getBlockZ();

        int topY = Math.min(w.getMaxHeight() - 2, origin.getBlockY() + 40);
        for (int y = origin.getBlockY() + 8; y <= topY; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid()) {
                Material chain = mat("CHAIN", Material.IRON_BARS);
                int len = 3 + r.nextInt(7);
                for (int i = 1; i <= len; i++) {
                    if (w.getBlockAt(x, y - i, z).getType() != Material.AIR) break;
                    setBlockSafe(w, x, y - i, z, chain);
                }
                return true;
            }
        }

        return false;
    }

    private boolean microTinyShrine(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();

        if (!ensureBuildableSurface(w, x, y, z, 5, 5, 2)) return false;

        Material brick = r.nextBoolean() ? Material.STONE_BRICKS : Material.DEEPSLATE_BRICKS;

        // 3x3 base
        fillRect(w, x - 1, y, z - 1, 3, 3, brick);
        setBlockSafe(w, x, y + 1, z, Material.CANDLE);
        setBlockSafe(w, x, y + 1, z - 1, Material.CANDLE);

        // tiny offering chest sometimes
        if (r.nextInt(100) < 22) {
            setBlockSafe(w, x + 1, y + 1, z, Material.CHEST);
            Block cb = w.getBlockAt(x + 1, y + 1, z);
            if (cb.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();
                chest.getBlockInventory().addItem(makeScrapNote(r, "shrine"));
                if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.AMETHYST_SHARD, 1));
                chest.update(true);
            }
        }

        return true;
    }

    private ItemStack makeScrapNote(Random r, String tag) {
        ItemStack paper = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&fScrap (" + tag + ")"));
            List<String> fragments = getConfig().getStringList("lore.fragments");
            if (fragments == null || fragments.isEmpty()) fragments = Collections.singletonList("...");
            String fragment = fragments.get(r.nextInt(fragments.size()));
            meta.setLore(Arrays.asList(
                    color("&7A torn note."),
                    color("&8\"" + fragment + "\"")
            ));
            paper.setItemMeta(meta);
        }
        return paper;
    }

    private void bumpMood(World w, Location loc, double amount) {
        int cx = cellCoord(loc.getBlockX());
        int cz = cellCoord(loc.getBlockZ());
        String key = cellKey(w, cx, cz);

        double mood = getCellMoodByKey(key);
        mood += amount;
        setCellMoodByKey(key, mood);
    }

    private boolean microRateLimitAllows(String worldName) {
        long now = System.currentTimeMillis();
        long windowMs = 60L * 60L * 1000L;

        long start = microRateWindowStartMs.getOrDefault(worldName, 0L);
        int count = microRateCount.getOrDefault(worldName, 0);

        if ((now - start) > windowMs) {
            microRateWindowStartMs.put(worldName, now);
            microRateCount.put(worldName, 0);
            return true;
        }

        return count < microRuinsMaxPerHour;
    }

    private void incrementMicroRate(String worldName) {
        long now = System.currentTimeMillis();
        long windowMs = 60L * 60L * 1000L;

        long start = microRateWindowStartMs.getOrDefault(worldName, 0L);
        if ((now - start) > windowMs) {
            microRateWindowStartMs.put(worldName, now);
            microRateCount.put(worldName, 1);
        } else {
            microRateCount.put(worldName, microRateCount.getOrDefault(worldName, 0) + 1);
        }
    }

    // -----------------------------
    // Structure generation pipeline (big structures)
    // -----------------------------
    private void tryGenerateStructureInChunk(World world, int chunkX, int chunkZ, Random chunkRandom, String chunkKey) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) return;

        Location origin = switch (world.getEnvironment()) {
            case NETHER -> findNetherOriginInChunk(world, chunkX, chunkZ, chunkRandom);
            case THE_END -> findEndOriginInChunk(world, chunkX, chunkZ, chunkRandom);
            default -> findSurfaceOriginInChunk(world, chunkX, chunkZ, chunkRandom);
        };
        if (origin == null) return;

        // Overworld Y range guard (Nether/End origins already bounded)
        if (world.getEnvironment() == World.Environment.NORMAL) {
            if (origin.getBlockY() < minY || origin.getBlockY() > maxY) return;
        }

        StructureType type = pickEnabledStructureType(world, chunkRandom);
        if (type == null) return;

        if (!generateStructure(type, origin, chunkRandom)) return;

        generatedChunkKeys.add(chunkKey);
        structuresDb.set(chunkKey + ".generated", true);
        structuresDb.set(chunkKey + ".world", world.getName());
        structuresDb.set(chunkKey + ".chunkX", chunkX);
        structuresDb.set(chunkKey + ".chunkZ", chunkZ);
        structuresDb.set(chunkKey + ".type", type.name());
        structuresDb.set(chunkKey + ".origin", serializeLoc(origin));
        saveStructuresDb();

        incrementRate(world.getName());

        if (aliveEnabled) {
            bumpMood(world, origin, 8.0 + chunkRandom.nextDouble() * 6.0);
        }

        if (debugLog) {
            getLogger().info("Generated " + type.name() + " at " + serializeLoc(origin) + " (chunk " + chunkKey + ")");
        }

        if (integrateCitizens) {
            if (maxNpcsPerWorld > 0 && countOurNpcEntities(world) >= maxNpcsPerWorld) {
                if (debugLog) getLogger().info("NPC cap reached in " + world.getName() + " (" + maxNpcsPerWorld + "); skipping NPC spawn.");
            } else if (chunkRandom.nextDouble() <= npcChanceGivenStructure) {
                Location npcLoc = origin.clone().add(2, 0, 2);
                spawnCitizensNpcIfPossible(npcLoc, makeNpcId(world, origin), chunkRandom);
            }
        }
    }

    private Location findSurfaceOriginInChunk(World world, int chunkX, int chunkZ, Random r) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;

        for (int attempt = 0; attempt < 12; attempt++) {
            int x = baseX + r.nextInt(16);
            int z = baseZ + r.nextInt(16);

            int y = world.getHighestBlockYAt(x, z);
            if (y <= 0) continue;

            Block below = world.getBlockAt(x, y - 1, z);
            Material mat = below.getType();
            if (mat == Material.WATER || mat == Material.LAVA) continue;

            return new Location(world, x, y, z);
        }
        return null;
    }


// Dimension-aware origin finders (Nether/End)
private Location findNetherOriginInChunk(World world, int chunkX, int chunkZ, Random r) {
    int baseX = chunkX * 16;
    int baseZ = chunkZ * 16;

    // Pick random columns and scan down from near the ceiling.
    for (int attempt = 0; attempt < 20; attempt++) {
        int x = baseX + r.nextInt(16);
        int z = baseZ + r.nextInt(16);

        // Scan a reasonable vertical range in the Nether (avoid ceiling bedrock edits).
        for (int y = Math.min(world.getMaxHeight() - 5, 120); y >= 20; y--) {
            Material below = world.getBlockAt(x, y - 1, z).getType();
            Material at = world.getBlockAt(x, y, z).getType();
            Material above = world.getBlockAt(x, y + 1, z).getType();

            if (at != Material.AIR) continue;
            if (above != Material.AIR) continue;
            if (below == Material.AIR) continue;

            if (below == Material.LAVA || below == Material.FIRE) continue;
            if (below == Material.BEDROCK) continue;

            // Don't spawn on soul fire/lava pools
            if (below == Material.MAGMA_BLOCK) {
                if (r.nextInt(100) < 60) continue;
            }

            return new Location(world, x, y, z);
        }
    }
    return null;
}

private Location findEndOriginInChunk(World world, int chunkX, int chunkZ, Random r) {
    int baseX = chunkX * 16;
    int baseZ = chunkZ * 16;

    for (int attempt = 0; attempt < 16; attempt++) {
        int x = baseX + r.nextInt(16);
        int z = baseZ + r.nextInt(16);

        int y = world.getHighestBlockYAt(x, z);
        if (y <= 0) continue;

        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (below == Material.AIR) continue;
        if (below == Material.WATER || below == Material.LAVA) continue;

        // Prefer End terrain blocks, but allow obsidian platforms, etc.
        if (below != Material.END_STONE && below != Material.OBSIDIAN && below != Material.CRYING_OBSIDIAN) {
            if (r.nextInt(100) < 70) continue;
        }

        return new Location(world, x, y, z);
    }
    return null;
}

    private Location findSurfaceOriginNear(Location near, Random r) {
        World world = near.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 24; attempt++) {
            int x = near.getBlockX() + r.nextInt(33) - 16;
            int z = near.getBlockZ() + r.nextInt(33) - 16;
            int y = world.getHighestBlockYAt(x, z);
            if (y <= 0) continue;
            Block below = world.getBlockAt(x, y - 1, z);
            if (below.getType() == Material.WATER || below.getType() == Material.LAVA) continue;
            return new Location(world, x, y, z);
        }
        return null;
    }

    private boolean generateStructure(StructureType type, Location origin, Random r) {
    return switch (type) {
        // Overworld
        case RUINED_COTTAGE -> genRuinedCottage(origin, r);
        case STONE_CIRCLE -> genStoneCircle(origin, r);
        case SUNKEN_SHRINE -> genSunkenShrine(origin, r);
        case OBSIDIAN_STEP_PYRAMID -> genObsidianStepPyramid(origin, r);
        case HANGING_TRIAL -> genHangingTrial(origin, r);
        case RUINED_WATCHTOWER -> genRuinedWatchtower(origin, r);
        case BURIED_CRYPT -> genBuriedCrypt(origin, r);
        case LOST_LIBRARY -> genLostLibrary(origin, r);

        // Nether
        case NETHER_SIGNAL_PYLON -> genNetherSignalPylon(origin, r);
        case BASALT_ALTAR -> genBasaltAltar(origin, r);
        case FORGOTTEN_HALL -> genForgottenHall(origin, r);

        // End
        case ENDER_OBELISK -> genEnderObelisk(origin, r);
        case VOID_RUIN -> genVoidRuin(origin, r);
        case CHORUS_MAZE -> genChorusMaze(origin, r);
    };
}


    private StructureType pickEnabledStructureType(World world, Random r) {
        World.Environment env = world.getEnvironment();
        String preferredKey = switch (env) {
            case NETHER -> "structures.nether.enabled";
            case THE_END -> "structures.end.enabled";
            default -> "structures.overworld.enabled";
        };

        List<String> enabledList = getConfig().getStringList(preferredKey);
        if (enabledList == null || enabledList.isEmpty()) {
            // Backward compat
            enabledList = getConfig().getStringList("structures.enabled");
        }
        if (enabledList == null || enabledList.isEmpty()) return null;

        List<StructureType> candidates = new ArrayList<>();
        for (String name : enabledList) {
            try {
                candidates.add(StructureType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (candidates.isEmpty()) return null;

        return candidates.get(r.nextInt(candidates.size()));
    }

    // -----------------------------
    // Structures (same vibe as v1.0, with CHAIN resolved safely)
    // -----------------------------
    private boolean genRuinedCottage(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        if (!ensureBuildableSurface(w, ox, oy, oz, 7, 7, 2)) return false;

        Material wallMat = (r.nextInt(3) == 0) ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
        Material plankMat = (r.nextBoolean()) ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS;

        fillRect(w, ox - 3, oy, oz - 3, 7, 7, plankMat);
        hollowBox(w, ox - 3, oy + 1, oz - 3, 7, 4, 7, wallMat);
        punchHoles(w, ox - 3, oy + 1, oz - 3, 7, 4, 7, r, 14);

        for (int y = 0; y < 3; y++) {
            int inset = y;
            fillHollowRing(w, ox - 3 + inset, oy + 5 + y, oz - 3 + inset, 7 - inset * 2, 7 - inset * 2, Material.DARK_OAK_SLAB);
        }
        scatterBlocks(w, ox - 4, oy + 1, oz - 4, 9, 6, 9, Material.DARK_OAK_SLAB, r, 10);

        setBlockSafe(w, ox, oy + 1, oz - 3, Material.AIR);
        setBlockSafe(w, ox, oy + 2, oz - 3, Material.AIR);

        Location chestLoc = new Location(w, ox + 1, oy + 1, oz + 1);
        setBlockSafe(w, chestLoc.getBlockX(), chestLoc.getBlockY(), chestLoc.getBlockZ(), Material.CHEST);
        Block b = chestLoc.getBlock();
        if (b.getState() instanceof Chest chest) {
            Inventory inv = chest.getBlockInventory();
            inv.clear();
            inv.addItem(makeLoreBook(w, r, "COTTAGE"));
            inv.addItem(makeRelic(r));
            chest.update(true);
        }

        Location signLoc = new Location(w, ox - 1, oy + 1, oz + 2);
        setBlockSafe(w, signLoc.getBlockX(), signLoc.getBlockY(), signLoc.getBlockZ(), Material.OAK_SIGN);
        if (signLoc.getBlock().getState() instanceof Sign sign) {
            List<String> lines = crypticLines(r);
            sign.setLine(0, lines.get(0));
            sign.setLine(1, lines.get(1));
            sign.setLine(2, lines.get(2));
            sign.setLine(3, lines.get(3));
            sign.update(true);
        }

        if (r.nextInt(100) < 20) {
            w.spawnEntity(origin.clone().add(0, 1, 0), org.bukkit.entity.EntityType.ZOMBIE);
        }

        return true;
    }

    private boolean genStoneCircle(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        if (!ensureBuildableSurface(w, ox, oy, oz, 11, 11, 2)) return false;

        Material pillar = (r.nextBoolean()) ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
        Material cap = Material.SMOOTH_STONE_SLAB;

        List<int[]> points = circlePoints(4);
        for (int[] p : points) {
            int x = ox + p[0];
            int z = oz + p[1];

            int y = w.getHighestBlockYAt(x, z);
            for (int h = 0; h < 4; h++) setBlockSafe(w, x, y + h, z, pillar);
            setBlockSafe(w, x, y + 4, z, cap);
        }

        int cy = w.getHighestBlockYAt(ox, oz);
        setBlockSafe(w, ox, cy, oz, Material.CHISELED_STONE_BRICKS);
        setBlockSafe(w, ox, cy + 1, oz, Material.CAMPFIRE);

        Location chestLoc = origin.clone().add(0, -1, 0);
        setBlockSafe(w, chestLoc.getBlockX(), cy - 1, chestLoc.getBlockZ(), Material.CHEST);
        Block b = w.getBlockAt(chestLoc.getBlockX(), cy - 1, chestLoc.getBlockZ());
        if (b.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeLoreBook(w, r, "CIRCLE"));
            chest.getBlockInventory().addItem(new ItemStack(Material.AMETHYST_SHARD, 1 + r.nextInt(3)));
            chest.update(true);
        }

        if (w.getTime() > 12000 && r.nextInt(100) < 30) {
            for (int i = 0; i < 6; i++) w.spawnEntity(origin.clone().add(r.nextInt(5) - 2, 2, r.nextInt(5) - 2), org.bukkit.entity.EntityType.BAT);
        }

        return true;
    }

    private boolean genSunkenShrine(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int ox = origin.getBlockX();
        int oz = origin.getBlockZ();
        int surfaceY = origin.getBlockY();

        int depth = 10 + r.nextInt(8);
        int floorY = Math.max(12, surfaceY - depth);

        for (int x = ox - 2; x <= ox + 2; x++) {
            for (int z = oz - 2; z <= oz + 2; z++) {
                for (int y = floorY; y <= surfaceY + 2; y++) {
                    setBlockSafe(w, x, y, z, Material.AIR);
                }
            }
        }

        Material brick = (r.nextBoolean()) ? Material.DEEPSLATE_BRICKS : Material.DEEPSLATE_TILES;
        for (int x = ox - 2; x <= ox + 2; x++) {
            for (int z = oz - 2; z <= oz + 2; z++) {
                if (Math.abs(x - ox) == 2 || Math.abs(z - oz) == 2) {
                    for (int y = floorY; y <= surfaceY; y++) setBlockSafe(w, x, y, z, brick);
                }
            }
        }

        for (int y = floorY; y <= surfaceY; y++) {
            setBlockSafe(w, ox - 2, y, oz, Material.LADDER);
        }

        int roomY = floorY - 4;
        if (roomY < 8) roomY = 8;

        carveRoom(w, ox, roomY, oz, 9, 5, 9);
        frameRoom(w, ox, roomY, oz, 9, 5, 9, brick);

        int ax = ox;
        int az = oz + 2;
        int ay = roomY + 1;

        setBlockSafe(w, ax, ay, az, Material.RESPAWN_ANCHOR);
        setBlockSafe(w, ax, ay + 1, az, mat("CHAIN", Material.IRON_BARS));
        setBlockSafe(w, ax, ay + 2, az, Material.LANTERN);

        Location chestLoc = new Location(w, ox, roomY + 1, oz - 2);
        setBlockSafe(w, chestLoc.getBlockX(), chestLoc.getBlockY(), chestLoc.getBlockZ(), Material.CHEST);
        Block cb = chestLoc.getBlock();
        if (cb.getState() instanceof Chest chest) {
            Inventory inv = chest.getBlockInventory();
            inv.clear();
            inv.addItem(makeLoreBook(w, r, "SHRINE"));
            inv.addItem(makeRelic(r));
            inv.addItem(new ItemStack(Material.GOLD_INGOT, 1 + r.nextInt(3)));
            if (r.nextInt(100) < 35) {
                ItemStack tool = new ItemStack(Material.IRON_SWORD);
                ItemMeta meta = tool.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color("&7Dull Blade"));
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    tool.setItemMeta(meta);
                }
                inv.addItem(tool);
            }
            chest.update(true);
        }

        if (r.nextInt(100) < 40) {
            setBlockSafe(w, ox + 2, roomY + 1, oz + 2, Material.SCULK_SENSOR);
            if (r.nextInt(100) < 20) setBlockSafe(w, ox + 2, roomY + 1, oz - 2, Material.SCULK_SHRIEKER);
        }

        if (r.nextInt(100) < 45) {
            w.spawnEntity(new Location(w, ox, roomY + 1, oz), org.bukkit.entity.EntityType.HUSK);
        }

        return true;
    }

    private boolean genObsidianStepPyramid(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        if (!ensureBuildableSurface(w, ox, oy, oz, 17, 17, 3)) return false;

        Material step = (r.nextInt(3) == 0) ? Material.BLACKSTONE : Material.OBSIDIAN;
        int height = 7;

        for (int y = 0; y < height; y++) {
            int size = 17 - y * 2;
            int sx = ox - (size / 2);
            int sz = oz - (size / 2);
            fillRect(w, sx, oy + y, sz, size, size, step);
        }

        fillRect(w, ox - 2, oy + height, oz - 2, 5, 5, Material.BLACKSTONE);
        hollowBox(w, ox - 2, oy + height + 1, oz - 2, 5, 4, 5, Material.CHISELED_POLISHED_BLACKSTONE);
        setBlockSafe(w, ox, oy + height + 1, oz - 2, Material.AIR);
        setBlockSafe(w, ox, oy + height + 2, oz - 2, Material.AIR);

        setBlockSafe(w, ox, oy + height + 1, oz, Material.LECTERN);
        setBlockSafe(w, ox + 1, oy + height + 1, oz, Material.CHEST);
        Block cb = w.getBlockAt(ox + 1, oy + height + 1, oz);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeLoreBook(w, r, "PYRAMID"));
            chest.getBlockInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
            chest.update(true);
        }

        if (r.nextInt(100) < 45) {
            fillRect(w, ox - 1, oy, oz + 7, 3, 3, Material.SUSPICIOUS_SAND);
        }

        if (r.nextInt(100) < 30) {
            w.spawnEntity(origin.clone().add(0, 2, 0), org.bukkit.entity.EntityType.WITHER_SKELETON);
        }

        return true;
    }

    private boolean genHangingTrial(Location origin, Random r) {
        World w = origin.getWorld();
        if (w == null) return false;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY() + 18 + r.nextInt(10);
        int oz = origin.getBlockZ();

        Material chainMat = mat("CHAIN", Material.IRON_BARS);

        setBlockSafe(w, ox, oy, oz, chainMat);
        setBlockSafe(w, ox, oy + 1, oz, chainMat);
        setBlockSafe(w, ox, oy + 2, oz, Material.IRON_BLOCK);

        int length = 16 + r.nextInt(10);
        int x = ox;
        int y = oy - 2;
        int z = oz;

        Material platform = (r.nextBoolean()) ? Material.SMOOTH_STONE_SLAB : Material.DEEPSLATE_BRICK_SLAB;

        for (int i = 0; i < length; i++) {
            setBlockSafe(w, x, y, z, platform);

            int dir = r.nextInt(4);
            if (dir == 0) x += 1;
            else if (dir == 1) x -= 1;
            else if (dir == 2) z += 1;
            else z -= 1;

            if (r.nextInt(100) < 22) {
                y += (r.nextBoolean() ? 1 : -1);
            }

            if (r.nextInt(100) < 18) {
                if (dir == 0) x += 1;
                else if (dir == 1) x -= 1;
                else if (dir == 2) z += 1;
                else z -= 1;
            }
        }

        setBlockSafe(w, x, y + 1, z, Material.IRON_BARS);
        setBlockSafe(w, x + 1, y + 1, z, Material.IRON_BARS);
        setBlockSafe(w, x - 1, y + 1, z, Material.IRON_BARS);
        setBlockSafe(w, x, y + 1, z + 1, Material.IRON_BARS);
        setBlockSafe(w, x, y + 1, z - 1, Material.IRON_BARS);

        setBlockSafe(w, x, y, z, Material.CHEST);
        Block cb = w.getBlockAt(x, y, z);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeLoreBook(w, r, "TRIAL"));
            chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 1));
            chest.getBlockInventory().addItem(makeRelic(r));
            chest.update(true);
        }

        return true;
    }

    // -----------------------------
    // Lore items
    // -----------------------------
    private ItemStack makeLoreBook(World w, Random r, String chapterTag) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle("Fragment: " + chapterTag);
            meta.setAuthor("—");

            List<String> fragments = getConfig().getStringList("lore.fragments");
            if (fragments == null || fragments.isEmpty()) fragments = Collections.singletonList("...");
            String fragment = fragments.get(r.nextInt(fragments.size()));

            List<String> pages = new ArrayList<>();
            pages.add(color("&0" + fragment + "\n\n&8[" + chapterTag + "]"));
            pages.add(color("&0You feel watched when you stop moving.\n\n&8If you found this far from home,\n&8someone wanted you to."));
            pages.add(color("&0A note, half-burned:\n\n&8\"The circle is not a place.\nIt is an instruction.\""));

            meta.setPages(pages);
            meta.setLore(Arrays.asList(
                    color("&7A brittle book,"),
                    color("&7inked like it was written"),
                    color("&7in a hurry.")
            ));
            book.setItemMeta(meta);
        }
        return book;
    }

    private ItemStack makeRelic(Random r) {
        Material mat = r.nextBoolean() ? Material.PRISMARINE_SHARD : Material.QUARTZ;
        ItemStack item = new ItemStack(mat, 1);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&bRelic"));
            meta.setLore(Arrays.asList(
                    color("&7It hums when no one is looking."),
                    color("&7It means nothing."),
                    color("&7That is why it matters.")
            ));
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> crypticLines(Random r) {
        List<String[]> pool = Arrays.asList(
                new String[]{"TWO WANDERERS", "PASSED HERE.", "THEY LEFT", "NO FOOTSTEPS."},
                new String[]{"THE SAND", "IS NOT SAND.", "IT IS", "A MEMORY."},
                new String[]{"IF YOU HEAR", "BREATHING,", "STOP", "LISTENING."},
                new String[]{"THE CIRCLE", "POINTS EAST.", "WALK WEST", "ANYWAY."},
                new String[]{"DO NOT", "DIG STRAIGHT", "DOWN. DIG", "SIDEWAYS."},
                new String[]{"SAY YES", "IF YOU MEAN", "NO. HE", "LIKES THAT."}
        );
        String[] pick = pool.get(r.nextInt(pool.size()));
        return Arrays.asList(pick);
    }

    // -----------------------------
    // Dialogue system
    // -----------------------------
    private void beginNpcDialogue(Player player, String npcId) {
        if (dialogueSessions.containsKey(player.getUniqueId())) {
            player.sendMessage(color("&7[&bWanderLore&7] &fFinish your current conversation first."));
            return;
        }

        DialogueSession session = new DialogueSession(npcId);
        dialogueSessions.put(player.getUniqueId(), session);

        incrementSeen(player, keySeenNpcs);

        player.sendMessage(color("&7[&bWanderLore&7] &fThe figure turns its head a little too smoothly."));
        player.sendMessage(color("&7[&bWanderLore&7] &fIt asks: &b\"Did you come here on purpose?\" &7(&aYES&7/&cNO&7)"));

        new BukkitRunnable() {
            @Override
            public void run() {
                DialogueSession s = dialogueSessions.get(player.getUniqueId());
                if (s != null && s.npcId.equals(npcId) && s.stage == 0) {
                    dialogueSessions.remove(player.getUniqueId());
                    player.sendMessage(color("&7[&bWanderLore&7] &7No answer. The figure loses interest."));
                }
            }
        }.runTaskLater(this, 20L * 25);
    }

    private void continueDialogue(Player player, String answer) {
        DialogueSession session = dialogueSessions.get(player.getUniqueId());
        if (session == null) return;

        if (session.stage == 0) {
            if (answer.equals("yes")) {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Good. Then it isn't an accident.\""));
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Do you want a name?\" &7(&aYES&7/&cNO&7)"));
                session.stage = 1;
            } else {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Then you were guided.\""));
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Will you pretend you chose it?\" &7(&aYES&7/&cNO&7)"));
                session.stage = 2;
            }
            return;
        }

        if (session.stage == 1) {
            if (answer.equals("yes")) {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Two voices once took this road.\""));
                player.sendMessage(color("&7[&bWanderLore&7] &f\"They called the world 'home' and meant it.\""));
            } else {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Better. Names stick.\""));
            }
            dropHint(player);
            dialogueSessions.remove(player.getUniqueId());
            return;
        }

        if (session.stage == 2) {
            if (answer.equals("yes")) {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"That's the right kind of lie.\""));
            } else {
                player.sendMessage(color("&7[&bWanderLore&7] &f\"Honesty is loud. The watchers prefer quiet.\""));
            }
            dropHint(player);
            dialogueSessions.remove(player.getUniqueId());
        }
    }

    private void dropHint(Player player) {
        incrementSeen(player, keySeenFragments);

        List<String> fragments = getConfig().getStringList("lore.fragments");
        if (fragments == null || fragments.isEmpty()) fragments = Collections.singletonList("...");

        String fragment = fragments.get(runtimeRandom.nextInt(fragments.size()));
        player.sendMessage(color("&7[&bWanderLore&7] &8It leaves you with a thought:"));
        player.sendMessage(color("&7[&bWanderLore&7] &f\"" + fragment + "\""));
    }

    private int getSeenCount(Player player, NamespacedKey key) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer v = pdc.get(key, PersistentDataType.INTEGER);
        return (v == null) ? 0 : v;
    }

    private void incrementSeen(Player player, NamespacedKey key) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer v = pdc.get(key, PersistentDataType.INTEGER);
        if (v == null) v = 0;
        pdc.set(key, PersistentDataType.INTEGER, v + 1);
    }

    private static class DialogueSession {
        final String npcId;
        int stage = 0;

        DialogueSession(String npcId) {
            this.npcId = npcId;
        }
    }

    // -----------------------------
    // Citizens integration (reflection)
    // -----------------------------
    private void spawnCitizensNpcIfPossible(Location loc, String npcId, Random r) {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) {
            if (debugLog) getLogger().info("Citizens not present/enabled; skipping NPC spawn.");
            return;
        }

        try {
            Class<?> citizensApiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = citizensApiClass.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);

            Class<?> npcRegistryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            Class<?> entityTypeClass = Class.forName("net.citizensnpcs.api.util.EntityType");
            Object playerType = Enum.valueOf((Class<Enum>) entityTypeClass.asSubclass(Enum.class), "PLAYER");

            List<String> names = getConfig().getStringList("lore.npcNames");
            if (names == null || names.isEmpty()) names = Collections.singletonList("Wanderer");
            String name = names.get(r.nextInt(names.size()));

            Method createNpc = npcRegistryClass.getMethod("createNPC", entityTypeClass, String.class);
            Object npc = createNpc.invoke(registry, playerType, name);

            try {
                Method dataMethod = npc.getClass().getMethod("data");
                Object data = dataMethod.invoke(npc);
                Method setPersistent = data.getClass().getMethod("setPersistent", String.class, Object.class);
                setPersistent.invoke(data, "wanderlore_npc_id", npcId);
            } catch (Throwable ignored) {
            }

            String chosenSkin = maybeApplySkinTrait(npc, r);

            Method spawnMethod = npc.getClass().getMethod("spawn", Location.class);
            spawnMethod.invoke(npc, loc);

            Method getEntity = npc.getClass().getMethod("getEntity");
            Object entObj = getEntity.invoke(npc);
            Entity spawnedEntity = null;
            if (entObj instanceof Entity e) {
                spawnedEntity = e;
                spawnedEntity.setMetadata(META_WANDERLORE_NPC, new FixedMetadataValue(this, npcId));
            }
            if (debugLog) getLogger().info("Spawned Citizens NPC '" + name + "' at " + serializeLoc(loc) + " id=" + npcId);
			
			if (integrateDynmap && dynmapNpcMarkersEnabled && chosenSkin != null) {
			if (spawnedEntity != null) {
                scheduleDynmapNpcMarkerUpsert(spawnedEntity, npcId, name, chosenSkin);
            }
			}


        } catch (Throwable t) {
            getLogger().warning("Failed to spawn Citizens NPC (reflection): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }


private int purgeOwnedNpcs(String worldNameOrNull) {
    if (!integrateCitizens) return 0;

    Plugin citizensPlugin = Bukkit.getPluginManager().getPlugin("Citizens");
    if (citizensPlugin == null || !citizensPlugin.isEnabled()) return 0;

    int removed = 0;
    try {
        // CitizensAPI is present at compile time via your builder classpath.
        net.citizensnpcs.api.npc.NPCRegistry reg = net.citizensnpcs.api.CitizensAPI.getNPCRegistry();
        if (reg == null) return 0;

        for (net.citizensnpcs.api.npc.NPC npc : reg) {
            if (npc == null) continue;
            if (!npc.isSpawned()) continue;

            Entity ent = npc.getEntity();
            if (ent == null) continue;

            if (!ent.hasMetadata(META_WANDERLORE_NPC)) continue;

            if (worldNameOrNull != null) {
                World w = ent.getWorld();
                if (w == null) continue;
                if (!w.getName().equalsIgnoreCase(worldNameOrNull)) continue;
            }

            npc.destroy();
            removed++;
        }
    } catch (Throwable t) {
        getLogger().warning("purgeOwnedNpcs failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    return removed;
}

	private String maybeApplySkinTrait(Object npc, Random r) {
		try {
			Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
			Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
			Object skinTrait = getOrAddTrait.invoke(npc, skinTraitClass);
	
			List<String> skins = getConfig().getStringList("lore.fakePlayerSkins");
			if (skins == null || skins.isEmpty()) return null;
			String skinName = skins.get(r.nextInt(skins.size()));
	
			Method setSkinName = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);
			setSkinName.invoke(skinTrait, skinName, true);
	
			return skinName;
		} catch (Throwable ignored) {
			return null;
		}
	}


    // -----------------------------
    // Dynmap integration (reflection)
    // -----------------------------
    private void startDynmapMarkerWatcherTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                trySpawnMarkerNpcFromDynmap();
            }
        }.runTaskTimer(this, 20L * 60L * dynmapScanMinutes, 20L * 60L * dynmapScanMinutes);
    }

    private void trySpawnMarkerNpcFromDynmap() {
        if (!integrateCitizens) return;
        if (runtimeRandom.nextInt(100) >= markerNpcChancePerScan) return;

        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null || !dynmap.isEnabled()) return;

        try {
            Class<?> dynmapApiClass = Class.forName("org.dynmap.DynmapAPI");
            Object api = dynmapApiClass.cast(dynmap);

            Method getMarkerApi = dynmapApiClass.getMethod("getMarkerAPI");
            Object markerApi = getMarkerApi.invoke(api);
            if (markerApi == null) return;

            Method getMarkerSets = markerApi.getClass().getMethod("getMarkerSets");
            Set<?> markerSets = (Set<?>) getMarkerSets.invoke(markerApi);
            if (markerSets == null || markerSets.isEmpty()) return;

            List<Object> candidateMarkers = new ArrayList<>();

            for (Object setObj : markerSets) {
                String id = safeCallString(setObj, "getMarkerSetID");
                if (id == null) continue;

                if (!dynmapMarkerSetWhitelist.isEmpty()) {
                    boolean ok = false;
                    for (String allow : dynmapMarkerSetWhitelist) {
                        if (allow != null && id.equalsIgnoreCase(allow.trim())) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) continue;
                }

                Method getMarkers = setObj.getClass().getMethod("getMarkers");
                Set<?> markers = (Set<?>) getMarkers.invoke(setObj);
                if (markers == null) continue;

                candidateMarkers.addAll(markers);
            }

            if (candidateMarkers.isEmpty()) return;

            Object marker = candidateMarkers.get(runtimeRandom.nextInt(candidateMarkers.size()));

            String worldName = safeCallString(marker, "getWorld");
            if (worldName == null) return;

            World w = Bukkit.getWorld(worldName);
            if (w == null) return;

            double x = safeCallDouble(marker, "getX");
            double y = safeCallDouble(marker, "getY");
            double z = safeCallDouble(marker, "getZ");

            Location loc = new Location(w, x, y, z);

            Location npcLoc = loc.clone().add(runtimeRandom.nextInt(9) - 4, 0, runtimeRandom.nextInt(9) - 4);
            npcLoc.setY(w.getHighestBlockYAt(npcLoc) + 1);

            if (w.getSpawnLocation().distanceSquared(npcLoc) < (double) minDistanceFromSpawnBlocks * minDistanceFromSpawnBlocks) return;

            spawnCitizensNpcIfPossible(npcLoc, "dynmap:" + worldName + ":" + ((int) x) + "," + ((int) y) + "," + ((int) z), runtimeRandom);

        } catch (Throwable t) {
            if (debugLog) getLogger().warning("Dynmap integration failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
	
	private void scheduleDynmapNpcMarkerUpsert(Entity ent, String npcId, String displayName, String skinName) {
		if (dynmapMarkerApiObj == null || dynmapNpcMarkerSetObj == null) return;
		if (ent == null || ent.getWorld() == null) return;
	
		Location loc = ent.getLocation().clone();
		String worldName = loc.getWorld().getName();
	
		// Marker IDs must be unique and stable
		String markerId = npcIdToMarkerId.computeIfAbsent(npcId, k -> "wanderlore_npc_" + Integer.toHexString(k.hashCode()));
		String iconId = skinToIconId.computeIfAbsent(skinName.toLowerCase(Locale.ROOT), k -> "wl_face_" + k.replaceAll("[^a-z0-9_\\-]", "_"));
	
		// If we already have icon bytes cached, we can upsert immediately on main thread
		if (iconPngCache.containsKey(iconId)) {
			Bukkit.getScheduler().runTask(this, () -> upsertDynmapNpcMarker(markerId, displayName, worldName, loc, iconId));
			return;
		}
	
		// Otherwise fetch icon asynchronously
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			byte[] png = fetchFacePng16(skinName);
			if (png == null) {
				if (debugLog) getLogger().info("Could not fetch face PNG for skin: " + skinName);
				return;
			}
	
			iconPngCache.put(iconId, png);
	
			Bukkit.getScheduler().runTask(this, () -> {
				ensureDynmapIconExists(iconId, "NPC: " + skinName, png);
				upsertDynmapNpcMarker(markerId, displayName, worldName, loc, iconId);
			});
		});
	}
	
	private void ensureDynmapIconExists(String iconId, String label, byte[] pngBytes) {
		if (dynmapMarkerApiObj == null) return;
	
		try {
			// if markerAPI.getMarkerIcon(iconId) != null, done
			Method getMarkerIcon = dynmapMarkerApiObj.getClass().getMethod("getMarkerIcon", String.class);
			Object existing = getMarkerIcon.invoke(dynmapMarkerApiObj, iconId);
			if (existing != null) return;
	
			// createMarkerIcon(String id, String label, InputStream in)
			Method createMarkerIcon = dynmapMarkerApiObj.getClass().getMethod("createMarkerIcon", String.class, String.class, java.io.InputStream.class);
			java.io.InputStream in = new java.io.ByteArrayInputStream(pngBytes);
			createMarkerIcon.invoke(dynmapMarkerApiObj, iconId, label, in);
	
			if (debugLog) getLogger().info("Registered dynmap icon: " + iconId);
		} catch (Throwable t) {
			if (debugLog) getLogger().warning("Failed to create dynmap icon " + iconId + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}
	
	private void upsertDynmapNpcMarker(String markerId, String label, String worldName, Location loc, String iconId) {
		if (dynmapMarkerApiObj == null || dynmapNpcMarkerSetObj == null) return;
	
		try {
			// MarkerIcon icon = markerAPI.getMarkerIcon(iconId)
			Method getMarkerIcon = dynmapMarkerApiObj.getClass().getMethod("getMarkerIcon", String.class);
			Object iconObj = getMarkerIcon.invoke(dynmapMarkerApiObj, iconId);
			if (iconObj == null) {
				// Fallback to default
				iconObj = getMarkerIcon.invoke(dynmapMarkerApiObj, "default");
			}
	
			// Marker m = set.findMarker(markerId) (if available) else createMarker(...)
			Object markerObj = null;
			try {
				Method findMarker = dynmapNpcMarkerSetObj.getClass().getMethod("findMarker", String.class);
				markerObj = findMarker.invoke(dynmapNpcMarkerSetObj, markerId);
			} catch (Throwable ignored) { }
	
			if (markerObj == null) {
				// createMarker(String id, String label, boolean markup, String world, double x, double y, double z, MarkerIcon icon, boolean persistent)
				Method createMarker = dynmapNpcMarkerSetObj.getClass().getMethod(
						"createMarker",
						String.class, String.class, boolean.class,
						String.class, double.class, double.class, double.class,
						Class.forName("org.dynmap.markers.MarkerIcon"),
						boolean.class
				);
	
				createMarker.invoke(
						dynmapNpcMarkerSetObj,
						markerId, label, false,
						worldName, loc.getX(), loc.getY(), loc.getZ(),
						iconObj,
						true
				);
			} else {
				// Update position + icon + label
				try {
					Method setLocation = markerObj.getClass().getMethod("setLocation", String.class, double.class, double.class, double.class);
					setLocation.invoke(markerObj, worldName, loc.getX(), loc.getY(), loc.getZ());
				} catch (Throwable ignored) { }
	
				try {
					Method setLabel = markerObj.getClass().getMethod("setLabel", String.class, boolean.class);
					setLabel.invoke(markerObj, label, false);
				} catch (Throwable ignored) { }
	
				try {
					Method setIcon = markerObj.getClass().getMethod("setMarkerIcon", Class.forName("org.dynmap.markers.MarkerIcon"));
					setIcon.invoke(markerObj, iconObj);
				} catch (Throwable ignored) { }
			}
		} catch (Throwable t) {
			if (debugLog) getLogger().warning("Failed to upsert dynmap NPC marker: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}
	
	private byte[] fetchFacePng16(String skinName) {
		// Provider 1: Crafatar (uuid preferred, overlay on by default)
		// Provider 2: Minotar (name-based)
	
		try {
			// Try uuid if available
			UUID uuid = null;
			try {
				OfflinePlayer off = Bukkit.getOfflinePlayer(skinName);
				if (off != null) uuid = off.getUniqueId();
			} catch (Throwable ignored) { }
	
			List<String> urls = new ArrayList<>();
			if ("minotar".equalsIgnoreCase(dynmapNpcIconProvider)) {
				urls.add("https://minotar.net/avatar/" + urlEncode(skinName) + "/16.png");
				if (uuid != null) urls.add("https://crafatar.com/avatars/" + uuid + "?size=16&overlay");
			} else {
				if (uuid != null) urls.add("https://crafatar.com/avatars/" + uuid + "?size=16&overlay");
				urls.add("https://minotar.net/avatar/" + urlEncode(skinName) + "/16.png");
			}
	
			for (String u : urls) {
				byte[] png = httpGetBytes(u, 4000, 7000);
				if (png != null && png.length > 0) return png;
			}
		} catch (Throwable ignored) { }
	
		return null;
	}
	
	private String urlEncode(String s) {
		try {
			return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Throwable t) {
			return s;
		}
	}
	
	private byte[] httpGetBytes(String url, int connectTimeoutMs, int readTimeoutMs) {
		java.net.HttpURLConnection conn = null;
		try {
			java.net.URL u = new java.net.URL(url);
			conn = (java.net.HttpURLConnection) u.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(connectTimeoutMs);
			conn.setReadTimeout(readTimeoutMs);
			conn.setUseCaches(false);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "WanderLore");
	
			int code = conn.getResponseCode();
			if (code < 200 || code >= 300) return null;
	
			try (java.io.InputStream in = conn.getInputStream();
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
				byte[] buf = new byte[4096];
				int r;
				while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
				return out.toByteArray();
			}
		} catch (Throwable ignored) {
			return null;
		} finally {
			if (conn != null) {
				try { conn.disconnect(); } catch (Throwable ignored) { }
			}
		}
	}
	

    private String safeCallString(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object v = m.invoke(obj);
            return (v == null) ? null : String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private double safeCallDouble(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object v = m.invoke(obj);
            if (v instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    // -----------------------------
    // Helpers: build checks + placement
    // -----------------------------

private boolean genRuinedWatchtower(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    if (!ensureBuildableSurface(w, x, y - 1, z, 7, 7, 3)) return false;

    Material wall = r.nextBoolean() ? Material.COBBLESTONE : Material.STONE_BRICKS;
    Material accent = r.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.CRACKED_STONE_BRICKS;

    // base pad
    fillRect(w, x - 3, y - 1, z - 3, 7, 7, wall);

    int height = 10 + r.nextInt(6);
    for (int yy = 0; yy < height; yy++) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int ax = x + dx;
                int az = z + dz;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (edge) {
                    setBlockSafe(w, ax, y + yy, az, (r.nextInt(100) < 14) ? accent : wall);
                } else {
                    setBlockSafe(w, ax, y + yy, az, Material.AIR);
                }
            }
        }
    }

    // broken top
    for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
            if (r.nextInt(100) < 35) continue;
            setBlockSafe(w, x + dx, y + height, z + dz, (Math.abs(dx) == 2 || Math.abs(dz) == 2) ? wall : Material.AIR);
        }
    }

    // ladder column + torch
    for (int yy = 0; yy < height - 1; yy++) {
        setBlockSafe(w, x, y + yy, z - 1, Material.LADDER);
    }
    if (r.nextInt(100) < 70) setBlockSafe(w, x - 1, y + 2, z - 2, Material.TORCH);

    // stash chest
    if (r.nextInt(100) < 65) {
        setBlockSafe(w, x + 1, y, z + 1, Material.CHEST);
        Block cb = w.getBlockAt(x + 1, y, z + 1);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "watchtower"));
            if (r.nextInt(100) < 20) chest.getBlockInventory().addItem(makeLoreBook(w, r, "tower"));
        }
    }

    maybeScatter(w, x, y, z, Material.COBWEB, r, 7);
    return true;
}

private boolean genBuriedCrypt(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = Math.max(w.getMinHeight() + 10, origin.getBlockY() - (6 + r.nextInt(5)));
    int z = origin.getBlockZ();

    if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;

    // dig a small chamber
    int rx = 4, rz = 4, ry = 4;
    for (int dx = -rx; dx <= rx; dx++) {
        for (int dz = -rz; dz <= rz; dz++) {
            for (int dy = -1; dy <= ry; dy++) {
                boolean wall = (Math.abs(dx) == rx || Math.abs(dz) == rz || dy == -1 || dy == ry);
                setBlockSafe(w, x + dx, y + dy, z + dz, wall ? Material.DEEPSLATE_BRICKS : Material.AIR);
            }
        }
    }

    // entrance shaft (subtle)
    for (int dy = 0; dy < 6; dy++) {
        setBlockSafe(w, x, origin.getBlockY() - dy, z, Material.AIR);
    }
    setBlockSafe(w, x, origin.getBlockY() - 6, z, Material.LADDER);
    setBlockSafe(w, x, origin.getBlockY() - 7, z, Material.LADDER);

    // sarcophagus
    fillRect(w, x - 1, y, z - 1, 3, 3, Material.POLISHED_DEEPSLATE);
    setBlockSafe(w, x, y + 1, z, Material.COBWEB);

    if (r.nextInt(100) < 60) {
        setBlockSafe(w, x + 2, y, z + 2, Material.CHEST);
        Block cb = w.getBlockAt(x + 2, y, z + 2);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "crypt"));
            if (r.nextInt(100) < 35) chest.getBlockInventory().addItem(new ItemStack(Material.GOLD_NUGGET, 3 + r.nextInt(9)));
            if (r.nextInt(100) < 18) chest.getBlockInventory().addItem(makeLoreBook(w, r, "crypt"));
        }
    }

    // very rare spawner (kept mild)
    if (r.nextInt(100) < 8) {
        setBlockSafe(w, x - 2, y, z - 2, Material.SPAWNER);
    }

    return true;
}

private boolean genLostLibrary(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    if (!ensureBuildableSurface(w, x, y - 1, z, 9, 9, 3)) return false;

    // footprint 9x7
    Material base = Material.STONE_BRICKS;
    Material wall = r.nextBoolean() ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS;

    fillRect(w, x - 4, y - 1, z - 3, 9, 7, base);

    // walls
    for (int dy = 0; dy <= 4; dy++) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean edge = (Math.abs(dx) == 4 || Math.abs(dz) == 3);
                if (!edge) continue;
                if (dy == 1 && dz == 3 && (dx == 0 || dx == 1)) continue; // doorway
                setBlockSafe(w, x + dx, y + dy, z + dz, wall);
            }
        }
    }
    // hollow inside
    for (int dx = -3; dx <= 3; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy <= 4; dy++) {
                setBlockSafe(w, x + dx, y + dy, z + dz, Material.AIR);
            }
        }
    }

    // roof
    fillRect(w, x - 4, y + 5, z - 3, 9, 7, Material.DARK_OAK_SLAB);

    // shelves + lectern
    for (int dx = -3; dx <= 3; dx++) {
        setBlockSafe(w, x + dx, y, z - 2, Material.BOOKSHELF);
        setBlockSafe(w, x + dx, y, z + 2, Material.BOOKSHELF);
    }
    setBlockSafe(w, x, y, z, Material.LECTERN);
    if (r.nextInt(100) < 65) {
        setBlockSafe(w, x + 2, y, z, Material.CHEST);
        Block cb = w.getBlockAt(x + 2, y, z);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeLoreBook(w, r, "library"));
            chest.getBlockInventory().addItem(makeScrapNote(r, "library"));
            if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.MAP, 1));
        }
    }

    return true;
}

// Nether structures
private boolean genNetherSignalPylon(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    Material pillar = Material.OBSIDIAN;
    for (int i = 0; i < 12 + r.nextInt(10); i++) {
        setBlockSafe(w, x, y + i, z, pillar);
        if (i % 3 == 0) {
            setBlockSafe(w, x + 1, y + i, z, Material.CRYING_OBSIDIAN);
            setBlockSafe(w, x - 1, y + i, z, Material.CRYING_OBSIDIAN);
        }
    }
    setBlockSafe(w, x, y + 14, z, Material.END_ROD);

    // small altar base
    fillRect(w, x - 2, y - 1, z - 2, 5, 5, Material.BLACKSTONE);
    if (r.nextInt(100) < 55) {
        setBlockSafe(w, x + 1, y, z + 1, Material.CHEST);
        Block cb = w.getBlockAt(x + 1, y, z + 1);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "pylon"));
            if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.GLOWSTONE_DUST, 4 + r.nextInt(6)));
        }
    }
    return true;
}

private boolean genBasaltAltar(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    fillRect(w, x - 3, y - 1, z - 3, 7, 7, Material.BASALT);
    fillRect(w, x - 2, y, z - 2, 5, 5, Material.POLISHED_BASALT);
    setBlockSafe(w, x, y, z, Material.SOUL_FIRE);

    if (r.nextInt(100) < 35) setBlockSafe(w, x, y + 1, z, Material.IRON_BARS);

    if (r.nextInt(100) < 50) {
        setBlockSafe(w, x + 2, y, z - 2, Material.CHEST);
        Block cb = w.getBlockAt(x + 2, y, z - 2);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "altar"));
            if (r.nextInt(100) < 22) chest.getBlockInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
        }
    }

    return true;
}

private boolean genForgottenHall(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    int length = 10 + r.nextInt(9);
    for (int i = 0; i < length; i++) {
        int zz = z + i;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                boolean wall = (Math.abs(dx) == 2 || dy == -1 || dy == 3);
                setBlockSafe(w, x + dx, y + dy, zz, wall ? Material.POLISHED_BLACKSTONE_BRICKS : Material.AIR);
            }
        }
        if (i % 4 == 0) {
            setBlockSafe(w, x - 1, y + 1, zz, Material.SOUL_LANTERN);
            setBlockSafe(w, x + 1, y + 1, zz, Material.SOUL_LANTERN);
        }
    }
    if (r.nextInt(100) < 60) {
        setBlockSafe(w, x, y, z + length - 2, Material.CHEST);
        Block cb = w.getBlockAt(x, y, z + length - 2);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "hall"));
            if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.GOLD_NUGGET, 6 + r.nextInt(10)));
        }
    }
    return true;
}

// End structures
private boolean genEnderObelisk(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    fillRect(w, x - 2, y - 1, z - 2, 5, 5, Material.END_STONE_BRICKS);
    for (int i = 0; i < 9 + r.nextInt(6); i++) {
        setBlockSafe(w, x, y + i, z, Material.OBSIDIAN);
        if (i % 2 == 0 && r.nextInt(100) < 60) {
            setBlockSafe(w, x + 1, y + i, z, Material.PURPUR_PILLAR);
        }
    }
    setBlockSafe(w, x, y + 10, z, Material.END_ROD);

    if (r.nextInt(100) < 55) {
        setBlockSafe(w, x - 1, y, z - 1, Material.CHEST);
        Block cb = w.getBlockAt(x - 1, y, z - 1);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "obelisk"));
            if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1 + r.nextInt(2)));
        }
    }
    return true;
}

private boolean genVoidRuin(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    Material base = (r.nextInt(100) < 70) ? Material.END_STONE : Material.PURPUR_BLOCK;
    for (int dx = -5; dx <= 5; dx++) {
        for (int dz = -5; dz <= 5; dz++) {
            if (r.nextInt(100) < 35) continue;
            setBlockSafe(w, x + dx, y - 1, z + dz, base);
            if (r.nextInt(100) < 10) setBlockSafe(w, x + dx, y, z + dz, Material.END_ROD);
        }
    }
    if (r.nextInt(100) < 40) {
        setBlockSafe(w, x, y, z, Material.CHEST);
        Block cb = w.getBlockAt(x, y, z);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "void"));
            if (r.nextInt(100) < 22) chest.getBlockInventory().addItem(new ItemStack(Material.CHORUS_FRUIT, 1 + r.nextInt(3)));
        }
    }
    return true;
}

private boolean genChorusMaze(Location origin, Random r) {
    World w = origin.getWorld();
    if (w == null) return false;

    int x = origin.getBlockX();
    int y = origin.getBlockY();
    int z = origin.getBlockZ();

    int size = 9;
    for (int dx = -size; dx <= size; dx++) {
        for (int dz = -size; dz <= size; dz++) {
            if (Math.abs(dx) == size || Math.abs(dz) == size || (r.nextInt(100) < 18 && (Math.abs(dx) % 2 == 0 || Math.abs(dz) % 2 == 0))) {
                setBlockSafe(w, x + dx, y, z + dz, Material.PURPUR_BLOCK);
                if (r.nextInt(100) < 7) setBlockSafe(w, x + dx, y + 1, z + dz, Material.END_ROD);
            } else {
                setBlockSafe(w, x + dx, y, z + dz, Material.AIR);
            }
        }
    }
    // chorus in the middle
    setBlockSafe(w, x, y, z, Material.CHORUS_PLANT);
    if (r.nextInt(100) < 50) setBlockSafe(w, x, y + 1, z, Material.CHORUS_FLOWER);

    if (r.nextInt(100) < 40) {
        setBlockSafe(w, x + 2, y, z + 2, Material.CHEST);
        Block cb = w.getBlockAt(x + 2, y, z + 2);
        if (cb.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(makeScrapNote(r, "maze"));
            if (r.nextInt(100) < 25) chest.getBlockInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
        }
    }

    return true;
}
    private boolean ensureBuildableSurface(World w, int centerX, int centerY, int centerZ, int widthX, int widthZ, int maxHeightDelta) {
        int startX = centerX - (widthX / 2);
        int startZ = centerZ - (widthZ / 2);

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int x = startX; x < startX + widthX; x++) {
            for (int z = startZ; z < startZ + widthZ; z++) {
                int y = w.getHighestBlockYAt(x, z);
                min = Math.min(min, y);
                max = Math.max(max, y);
                if ((max - min) > maxHeightDelta) return false;
            }
        }
        return true;
    }

    private void fillRect(World w, int x, int y, int z, int sizeX, int sizeZ, Material mat) {
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                setBlockSafe(w, x + dx, y, z + dz, mat);
            }
        }


    }

    private void maybeScatter(World w, int centerX, int centerY, int centerZ, Material mat, Random r, int attempts) {
    if (w == null || mat == null || r == null) return;
    for (int i = 0; i < attempts; i++) {
        int x = centerX + (r.nextInt(9) - 4);
        int y = centerY + (r.nextInt(5) - 2);
        int z = centerZ + (r.nextInt(9) - 4);

        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

        Material at = w.getBlockAt(x, y, z).getType();
        Material below = w.getBlockAt(x, y - 1, z).getType();
        if (at != Material.AIR) continue;
        if (below == Material.AIR || below == Material.WATER || below == Material.LAVA) continue;

        setBlockSafe(w, x, y, z, mat);
    }
}

    private void hollowBox(World w, int x, int y, int z, int sx, int sy, int sz, Material mat) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    boolean wall = (dx == 0 || dx == sx - 1 || dz == 0 || dz == sz - 1);
                    if (wall) setBlockSafe(w, x + dx, y + dy, z + dz, mat);
                    else setBlockSafe(w, x + dx, y + dy, z + dz, Material.AIR);
                }
            }
        }
    }

    private void punchHoles(World w, int x, int y, int z, int sx, int sy, int sz, Random r, int holes) {
        for (int i = 0; i < holes; i++) {
            int hx = x + r.nextInt(sx);
            int hy = y + r.nextInt(sy);
            int hz = z + r.nextInt(sz);
            setBlockSafe(w, hx, hy, hz, Material.AIR);
        }
    }

    private void fillHollowRing(World w, int x, int y, int z, int sx, int sz, Material mat) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                boolean edge = (dx == 0 || dx == sx - 1 || dz == 0 || dz == sz - 1);
                if (edge) setBlockSafe(w, x + dx, y, z + dz, mat);
            }
        }
    }

    private void scatterBlocks(World w, int x, int y, int z, int sx, int sy, int sz, Material mat, Random r, int count) {
        for (int i = 0; i < count; i++) {
            int bx = x + r.nextInt(sx);
            int by = y + r.nextInt(sy);
            int bz = z + r.nextInt(sz);
            if (w.getBlockAt(bx, by, bz).getType() == Material.AIR) {
                setBlockSafe(w, bx, by, bz, mat);
            }
        }
    }

    private void carveRoom(World w, int cx, int y, int cz, int sx, int sy, int sz) {
        int x0 = cx - sx / 2;
        int z0 = cz - sz / 2;
        for (int x = x0; x < x0 + sx; x++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int dy = 0; dy < sy; dy++) {
                    setBlockSafe(w, x, y + dy, z, Material.AIR);
                }
            }
        }
    }

    private void frameRoom(World w, int cx, int y, int cz, int sx, int sy, int sz, Material mat) {
        int x0 = cx - sx / 2;
        int z0 = cz - sz / 2;
        for (int x = x0; x < x0 + sx; x++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int dy = 0; dy < sy; dy++) {
                    boolean wall = (x == x0 || x == x0 + sx - 1 || z == z0 || z == z0 + sz - 1 || dy == 0 || dy == sy - 1);
                    if (wall) setBlockSafe(w, x, y + dy, z, mat);
                }
            }
        }
    }

    private void setBlockSafe(World w, int x, int y, int z, Material mat) {
        if (y < w.getMinHeight() || y > w.getMaxHeight()) return;
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() == Material.BEDROCK) return;
        b.setType(mat, false);
    }

    private List<int[]> circlePoints(int radius) {
        List<int[]> pts = new ArrayList<>();
        for (int deg = 0; deg < 360; deg += 20) {
            double rad = Math.toRadians(deg);
            int x = (int) Math.round(Math.cos(rad) * radius);
            int z = (int) Math.round(Math.sin(rad) * radius);
            pts.add(new int[]{x, z});
        }
        Map<String, int[]> uniq = new HashMap<>();
        for (int[] p : pts) uniq.put(p[0] + "," + p[1], p);
        return new ArrayList<>(uniq.values());
    }

    // -----------------------------
    // Rate limiting (big structures)
    // -----------------------------
    private boolean rateLimitAllows(String worldName) {
        long now = System.currentTimeMillis();
        long windowMs = 60L * 60L * 1000L;

        long start = worldRateWindowStartMs.getOrDefault(worldName, 0L);
        int count = worldRateCount.getOrDefault(worldName, 0);

        if ((now - start) > windowMs) {
            worldRateWindowStartMs.put(worldName, now);
            worldRateCount.put(worldName, 0);
            return true;
        }

        return count < maxStructuresPerHour;
    }

    private void incrementRate(String worldName) {
        long now = System.currentTimeMillis();
        long windowMs = 60L * 60L * 1000L;

        long start = worldRateWindowStartMs.getOrDefault(worldName, 0L);
        if ((now - start) > windowMs) {
            worldRateWindowStartMs.put(worldName, now);
            worldRateCount.put(worldName, 1);
        } else {
            worldRateCount.put(worldName, worldRateCount.getOrDefault(worldName, 0) + 1);
        }
    }

    // -----------------------------
    // Utils
    // -----------------------------
    private boolean isWorldAllowed(World w) {
        if (worldAllowList == null || worldAllowList.isEmpty()) return true;
        for (String name : worldAllowList) {
            if (name != null && name.equalsIgnoreCase(w.getName())) return true;
        }
        return false;
    }

    private String makeChunkKey(World w, int chunkX, int chunkZ) {
        return w.getName() + ":" + chunkX + ":" + chunkZ;
    }

    private String makeNpcId(World w, Location origin) {
        return "structure:" + w.getName() + ":" + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ();
    }

    private long computeDeterministicSeed(long worldSeed, int chunkX, int chunkZ) {
        long h = worldSeed;
        h ^= (chunkX * 341873128712L);
        h ^= (chunkZ * 132897987541L);
        h ^= (chunkX * 31L + chunkZ * 17L);
        return h;
    }

    private String serializeLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserializeLoc(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(w, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // Safe runtime material lookup (avoids compile-time missing constants)
    private Material mat(String materialName, Material fallback) {
        if (materialName == null || materialName.isEmpty()) return fallback;
        Material found = Material.matchMaterial(materialName);
        return (found != null) ? found : fallback;
    }

    private enum StructureType {
        // Overworld
        RUINED_COTTAGE,
        STONE_CIRCLE,
        SUNKEN_SHRINE,
        OBSIDIAN_STEP_PYRAMID,
        HANGING_TRIAL,
        RUINED_WATCHTOWER,
        BURIED_CRYPT,
        LOST_LIBRARY,

        // Nether
        NETHER_SIGNAL_PYLON,
        BASALT_ALTAR,
        FORGOTTEN_HALL,

        // End
        ENDER_OBELISK,
        VOID_RUIN,
        CHORUS_MAZE
    }
}
