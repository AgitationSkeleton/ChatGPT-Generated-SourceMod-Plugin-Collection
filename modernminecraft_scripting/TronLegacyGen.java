import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TronLegacyGen extends JavaPlugin {

    private File configFile;
    private YamlConfiguration config;

    private final Set<String> loggedStructureIds = ConcurrentHashMap.newKeySet();
    private File structureLogFile;

    // NEW: POI registry (stable machine-readable file)
    private File poiFile;
    private YamlConfiguration poiConfig;
    private final Object poiLock = new Object();

    private Set<String> tronWorldNames = new HashSet<>();
    private boolean applyToAllWorlds = false;

    private int gridY = 64;
    private int foundationDepth = 32;

    private int districtSizeBlocks = 256;
    private double outlandsChance = 0.08;

    private int majorLineEvery = 64;
    private int minorLineEvery = 16;

    private int cityMinHeight = 22;
    private int cityMaxHeight = 96;

    private long timeLockTicks = 200L;
    private long fixedTime = 18000L;

    @Override
    public void onEnable() {
        ensureConfigExists();
        loadSettings();

        ensureLogFileReady();
        loadExistingStructureIds();

        ensurePoiFileReady();
        loadPoiConfig();

        for (World world : Bukkit.getWorlds()) {
            maybeApplyTronRules(world);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                if (!isTronWorld(world)) {
                    continue;
                }
                enforceTronRules(world);
            }
        }, 20L, Math.max(20L, timeLockTicks));

        getLogger().info("TronLegacyGen enabled. Generator name: TronLegacyGen (use with Multiverse: -g TronLegacyGen)");
    }

    @Override
    public void onDisable() {
        getLogger().info("TronLegacyGen disabled.");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new TronChunkGenerator(this);
    }

    public BiomeProvider getDefaultBiomeProvider() {
        return new TronBiomeProvider(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("trongen")) {
            return false;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            ensureLogFileReady();

            ensurePoiFileReady();
            loadPoiConfig();

            for (World world : Bukkit.getWorlds()) {
                maybeApplyTronRules(world);
            }
            sender.sendMessage("TronLegacyGen reloaded.");
            return true;
        }

        sender.sendMessage("Usage: /trongen reload");
        return true;
    }

    private void ensureConfigExists() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                YamlConfiguration defaultConfig = new YamlConfiguration();
                defaultConfig.set("settings.applyToAllWorlds", false);
                defaultConfig.set("settings.tronWorldNames", Arrays.asList("The_Grid"));
                defaultConfig.set("settings.fixedTime", 18000);
                defaultConfig.set("settings.timeLockTicks", 200);
                defaultConfig.set("settings.gridY", 64);
                defaultConfig.set("settings.foundationDepth", 32);

                defaultConfig.set("districts.sizeBlocks", 256);
                defaultConfig.set("districts.outlandsChance", 0.08);

                defaultConfig.set("gridLines.majorEvery", 64);
                defaultConfig.set("gridLines.minorEvery", 16);

                defaultConfig.set("city.heightMin", 22);
                defaultConfig.set("city.heightMax", 96);

                defaultConfig.set("logging.enabled", true);
                defaultConfig.set("logging.fileName", "structure_log.txt");

                // NEW: POI export
                defaultConfig.set("poiExport.enabled", true);
                defaultConfig.set("poiExport.fileName", "grid_pois.yml");

                defaultConfig.save(configFile);
            } catch (Exception ex) {
                getLogger().severe("Failed creating config.yml: " + ex.getMessage());
            }
        }
    }

    private void loadSettings() {
        try {
            config = YamlConfiguration.loadConfiguration(configFile);

            applyToAllWorlds = config.getBoolean("settings.applyToAllWorlds", false);
            tronWorldNames = new HashSet<>(config.getStringList("settings.tronWorldNames"));

            fixedTime = config.getLong("settings.fixedTime", 18000L);
            timeLockTicks = config.getLong("settings.timeLockTicks", 200L);

            gridY = config.getInt("settings.gridY", 64);
            foundationDepth = config.getInt("settings.foundationDepth", 32);

            districtSizeBlocks = config.getInt("districts.sizeBlocks", 256);
            outlandsChance = config.getDouble("districts.outlandsChance", 0.08);

            majorLineEvery = config.getInt("gridLines.majorEvery", 64);
            minorLineEvery = config.getInt("gridLines.minorEvery", 16);

            cityMinHeight = config.getInt("city.heightMin", 22);
            cityMaxHeight = config.getInt("city.heightMax", 96);

        } catch (Exception ex) {
            getLogger().severe("Failed loading config.yml: " + ex.getMessage());
        }
    }

    private void ensureLogFileReady() {
        boolean loggingEnabled = config == null || config.getBoolean("logging.enabled", true);
        String logFileName = (config == null) ? "structure_log.txt" : config.getString("logging.fileName", "structure_log.txt");

        structureLogFile = new File(getDataFolder(), logFileName);
        if (loggingEnabled && !structureLogFile.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                structureLogFile.createNewFile();
            } catch (Exception ex) {
                getLogger().severe("Failed creating structure log file: " + ex.getMessage());
            }
        }
    }

    private void ensurePoiFileReady() {
        boolean poiEnabled = config == null || config.getBoolean("poiExport.enabled", true);
        String poiFileName = (config == null) ? "grid_pois.yml" : config.getString("poiExport.fileName", "grid_pois.yml");

        poiFile = new File(getDataFolder(), poiFileName);
        if (poiEnabled && !poiFile.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                poiFile.createNewFile();
            } catch (Exception ex) {
                getLogger().severe("Failed creating POI file: " + ex.getMessage());
            }
        }
    }

    private void loadPoiConfig() {
        synchronized (poiLock) {
            poiConfig = new YamlConfiguration();
            if (poiFile != null && poiFile.exists()) {
                try {
                    poiConfig = YamlConfiguration.loadConfiguration(poiFile);
                } catch (Exception ex) {
                    getLogger().warning("Failed loading POI file, starting fresh: " + ex.getMessage());
                }
            }
            if (!poiConfig.contains("version")) {
                poiConfig.set("version", 1);
            }
            if (!poiConfig.contains("pois")) {
                poiConfig.set("pois", new ArrayList<>());
            }
            savePoiConfig();
        }
    }

    private void savePoiConfig() {
        synchronized (poiLock) {
            if (poiFile == null) {
                return;
            }
            try {
                poiConfig.save(poiFile);
            } catch (Exception ex) {
                getLogger().warning("Failed saving POI file: " + ex.getMessage());
            }
        }
    }

    private void loadExistingStructureIds() {
        if (structureLogFile == null || !structureLogFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(structureLogFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // format: epoch|world|id|type|x|y|z|notes
                String[] parts = line.split("\\|");
                if (parts.length >= 3) {
                    String structureId = parts[2].trim();
                    if (!structureId.isEmpty()) {
                        loggedStructureIds.add(structureId);
                    }
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Could not read existing structure log file: " + ex.getMessage());
        }
    }

    public boolean isTronWorld(World world) {
        if (world == null) {
            return false;
        }
        if (applyToAllWorlds) {
            return true;
        }
        return tronWorldNames.contains(world.getName());
    }

    public void maybeApplyTronRules(World world) {
        if (!isTronWorld(world)) {
            return;
        }
        enforceTronRules(world);
    }

    private void enforceTronRules(World world) {
        world.setTime(fixedTime);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

        // You preferred this: suppress vanilla mobs in Tron worlds.
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);

        // Weather remains normal; biomes determine rain vs snow.
        // world.setGameRule(GameRule.DO_WEATHER_CYCLE, true);
    }

    // Exposed to generator/provider
    public int getGridY() { return gridY; }
    public int getFoundationDepth() { return foundationDepth; }
    public int getDistrictSizeBlocks() { return districtSizeBlocks; }
    public double getOutlandsChance() { return outlandsChance; }
    public int getMajorLineEvery() { return majorLineEvery; }
    public int getMinorLineEvery() { return minorLineEvery; }
    public int getCityMinHeight() { return cityMinHeight; }
    public int getCityMaxHeight() { return cityMaxHeight; }

    public void logStructureOnce(World world, String structureId, String type, int x, int y, int z, String notes) {
        if (config != null && !config.getBoolean("logging.enabled", true)) {
            return;
        }
        if (structureLogFile == null) {
            return;
        }
        if (!loggedStructureIds.add(structureId)) {
            return;
        }

        long epoch = System.currentTimeMillis();
        String worldName = (world == null) ? "unknown" : world.getName();
        String safeNotes = (notes == null) ? "" : notes.replace("\n", " ").replace("\r", " ");

        String line = epoch + "|" + worldName + "|" + structureId + "|" + type + "|" + x + "|" + y + "|" + z + "|" + safeNotes;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(structureLogFile, StandardCharsets.UTF_8, true))) {
            writer.write(line);
            writer.newLine();
        } catch (Exception ex) {
            getLogger().warning("Failed writing to structure log: " + ex.getMessage());
        }

        // NEW: also export to POI registry
        if (config == null || config.getBoolean("poiExport.enabled", true)) {
            exportPoi(worldName, structureId, type, x, y, z, safeNotes);
        }
    }

    private void exportPoi(String worldName, String structureId, String type, int x, int y, int z, String notes) {
        if (poiFile == null) {
            return;
        }

        String district = extractDistrictFromNotes(notes);
        PoiDefaults defaults = derivePoiDefaults(type, district);

        synchronized (poiLock) {
            List<Map<?, ?>> rawList = poiConfig.getMapList("pois");
            List<Map<String, Object>> pois = new ArrayList<>();
            for (Map<?, ?> m : rawList) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                pois.add(copy);
            }

            boolean updated = false;
            for (Map<String, Object> poi : pois) {
                String id = String.valueOf(poi.getOrDefault("id", ""));
                if (structureId.equals(id)) {
                    poi.put("world", worldName);
                    poi.put("type", type);
                    poi.put("district", district);
                    poi.put("x", x);
                    poi.put("y", y);
                    poi.put("z", z);
                    poi.put("radius", defaults.radius);
                    poi.put("factionBias", defaults.factionBias);
                    poi.put("notes", notes);
                    updated = true;
                    break;
                }
            }

            if (!updated) {
                Map<String, Object> poi = new LinkedHashMap<>();
                poi.put("id", structureId);
                poi.put("world", worldName);
                poi.put("type", type);
                poi.put("district", district);
                poi.put("x", x);
                poi.put("y", y);
                poi.put("z", z);
                poi.put("radius", defaults.radius);
                poi.put("factionBias", defaults.factionBias);
                poi.put("notes", notes);
                pois.add(poi);
            }

            poiConfig.set("pois", pois);
            savePoiConfig();
        }
    }

    private String extractDistrictFromNotes(String notes) {
        if (notes == null) {
            return "UNKNOWN";
        }
        // Current notes format includes "district=CORE_DENSE" etc.
        int idx = notes.indexOf("district=");
        if (idx < 0) {
            return "UNKNOWN";
        }
        String sub = notes.substring(idx + "district=".length()).trim();
        int end = sub.indexOf(' ');
        if (end >= 0) {
            sub = sub.substring(0, end);
        }
        if (sub.isEmpty()) {
            return "UNKNOWN";
        }
        return sub.toUpperCase(Locale.ROOT);
    }

    private PoiDefaults derivePoiDefaults(String type, String district) {
        // Baseline mapping (matches what you outlined)
        String t = (type == null) ? "" : type.toUpperCase(Locale.ROOT);

        int radius;
        String bias;

        switch (t) {
            case "IO_TOWER" -> { radius = 120; bias = "ORANGE"; }
            case "HANGAR_RECOGNIZER" -> { radius = 110; bias = "ORANGE"; }
            case "HANGAR_TANK" -> { radius = 110; bias = "ORANGE"; }
            case "DISC_ARENA" -> { radius = 140; bias = "BLUE"; }
            case "LIGHTCYCLE_ARENA" -> { radius = 160; bias = "BLUE"; }
            case "OUTLANDS_SAFEHOUSE" -> { radius = 90; bias = "WHITE"; }
            default -> { radius = 100; bias = "BLUE"; }
        }

        // District influence (optional but helpful):
        // CORE_DENSE trends ORANGE (Occupation presence), OUTLANDS trends WHITE.
        if ("CORE_DENSE".equalsIgnoreCase(district) && ("BLUE".equalsIgnoreCase(bias))) {
            // Don’t override arenas, but can nudge generic
            if (!t.contains("ARENA")) {
                bias = "ORANGE";
            }
        }
        if ("OUTLANDS".equalsIgnoreCase(district)) {
            // Outlands tends toward White/Resistance/neutral survival
            if (!t.contains("HANGAR") && !t.contains("IO_TOWER")) {
                bias = "WHITE";
            }
        }

        return new PoiDefaults(radius, bias);
    }

    private static class PoiDefaults {
        final int radius;
        final String factionBias;

        PoiDefaults(int radius, String factionBias) {
            this.radius = radius;
            this.factionBias = factionBias;
        }
    }

    // ----------------------------
    // Biome Provider
    // ----------------------------
    public static class TronBiomeProvider extends BiomeProvider {
        private final TronLegacyGen plugin;

        public TronBiomeProvider(TronLegacyGen plugin) {
            this.plugin = plugin;
        }

        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            DistrictType districtType = DistrictType.forBlock(worldInfo.getSeed(), x, z, plugin.getDistrictSizeBlocks(), plugin.getOutlandsChance());
            if (districtType == DistrictType.OUTLANDS) {
                return Biome.SNOWY_PLAINS;
            }
            return Biome.PLAINS;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return Arrays.asList(Biome.PLAINS, Biome.SNOWY_PLAINS);
        }
    }

    // ----------------------------
    // Chunk Generator
    // ----------------------------
    public static class TronChunkGenerator extends ChunkGenerator {
        private final TronLegacyGen plugin;

        public TronChunkGenerator(TronLegacyGen plugin) {
            this.plugin = plugin;
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
            return plugin.getDefaultBiomeProvider();
        }

        @Override
        public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
            ChunkData chunkData = createChunkData(world);

            int gridY = plugin.getGridY();
            int foundationDepth = plugin.getFoundationDepth();

            int baseMinY = world.getMinHeight();
            int baseMaxY = world.getMaxHeight();

            int foundationBottomY = Math.max(baseMinY, gridY - foundationDepth);
            int surfaceY = gridY;

            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            DistrictType districtType = DistrictType.forBlock(world.getSeed(), blockX + 8, blockZ + 8, plugin.getDistrictSizeBlocks(), plugin.getOutlandsChance());

            Material foundationMat = Material.BLACK_CONCRETE;
            Material surfaceMat = Material.TINTED_GLASS;

            if (districtType == DistrictType.OUTLANDS) {
                foundationMat = Material.POLISHED_DEEPSLATE;
                surfaceMat = Material.BLACK_CONCRETE;
            }

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int y = foundationBottomY; y < surfaceY; y++) {
                        chunkData.setBlock(localX, y, localZ, foundationMat);
                    }
                    if (surfaceY >= baseMinY && surfaceY < baseMaxY) {
                        chunkData.setBlock(localX, surfaceY, localZ, surfaceMat);
                    }

                    int absX = blockX + localX;
                    int absZ = blockZ + localZ;

                    boolean isMajorLine = plugin.getMajorLineEvery() > 0 &&
                            ((Math.floorMod(absX, plugin.getMajorLineEvery()) == 0) || (Math.floorMod(absZ, plugin.getMajorLineEvery()) == 0));
                    boolean isMinorLine = plugin.getMinorLineEvery() > 0 &&
                            ((Math.floorMod(absX, plugin.getMinorLineEvery()) == 0) || (Math.floorMod(absZ, plugin.getMinorLineEvery()) == 0));

                    if (isMajorLine || isMinorLine) {
                        int glowY = surfaceY - 1;
                        if (glowY >= baseMinY) {
                            chunkData.setBlock(localX, glowY, localZ, Material.SEA_LANTERN);
                        }
                        if (surfaceMat != Material.TINTED_GLASS) {
                            chunkData.setBlock(localX, surfaceY, localZ, Material.TINTED_GLASS);
                        }
                    }

                    if (isMajorLine && isMinorLine) {
                        int glowY = surfaceY - 1;
                        if (glowY >= baseMinY) {
                            chunkData.setBlock(localX, glowY, localZ, Material.SEA_LANTERN);
                        }
                        if (surfaceY + 1 < baseMaxY) {
                            chunkData.setBlock(localX, surfaceY + 1, localZ, Material.LIGHT_BLUE_STAINED_GLASS);
                        }
                    }

                    if (districtType == DistrictType.OUTLANDS) {
                        biome.setBiome(localX, localZ, Biome.SNOWY_PLAINS);
                    } else {
                        biome.setBiome(localX, localZ, Biome.PLAINS);
                    }
                }
            }

            return chunkData;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            return Collections.singletonList(new TronPopulator(plugin));
        }
    }

    // ----------------------------
    // Populator
    // ----------------------------
    public static class TronPopulator extends BlockPopulator {

        private final TronLegacyGen plugin;

        public TronPopulator(TronLegacyGen plugin) {
            this.plugin = plugin;
        }

        @Override
        public void populate(World world, Random random, Chunk source) {
            if (world == null || source == null) {
                return;
            }

            int chunkX = source.getX();
            int chunkZ = source.getZ();

            int absChunkBlockX = chunkX << 4;
            int absChunkBlockZ = chunkZ << 4;

            DistrictType districtType = DistrictType.forBlock(world.getSeed(), absChunkBlockX + 8, absChunkBlockZ + 8, plugin.getDistrictSizeBlocks(), plugin.getOutlandsChance());

            if (districtType == DistrictType.OUTLANDS) {
                populateOutlands(world, chunkX, chunkZ);
                maybePlaceOutlandsSafehouseAnchor(world, chunkX, chunkZ);
                return;
            }

            populateCityBuildings(world, chunkX, chunkZ, districtType);
            maybePlaceLandmark(world, chunkX, chunkZ, districtType);
        }

        private void populateOutlands(World world, int chunkX, int chunkZ) {
            int gridY = plugin.getGridY();
            long seed = world.getSeed();
            Random regionRandom = seededRandom(seed, chunkX, chunkZ, 0x51F1A9B2);

            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int x = baseX + localX;
                    int z = baseZ + localZ;

                    double noise = pseudoNoise2D(seed, x * 0.03, z * 0.03);
                    double cliff = pseudoNoise2D(seed ^ 0x9E3779B97F4A7C15L, x * 0.01, z * 0.01);

                    int heightDelta = (int) Math.round(noise * 10.0 + cliff * 24.0);
                    int topY = gridY + Math.max(-6, Math.min(28, heightDelta));

                    boolean carveRavine = (pseudoNoise2D(seed ^ 0xA83D2C1BL, x * 0.008, z * 0.008) > 0.55);
                    if (carveRavine) {
                        topY = gridY - 10 - (int) (Math.abs(noise) * 18);
                    }

                    int minY = world.getMinHeight();
                    int bottomY = Math.max(minY, gridY - plugin.getFoundationDepth());

                    for (int y = bottomY; y <= topY; y++) {
                        Material mat = Material.POLISHED_DEEPSLATE;
                        if (y == topY) {
                            mat = Material.BLACK_CONCRETE;
                        } else if (y > topY - 3) {
                            mat = Material.DEEPSLATE_TILES;
                        }
                        world.getBlockAt(x, y, z).setType(mat, false);
                    }

                    if (regionRandom.nextDouble() < 0.02) {
                        int glowY = topY;
                        if (glowY >= minY) {
                            world.getBlockAt(x, glowY, z).setType(Material.CYAN_STAINED_GLASS, false);
                            if (glowY - 1 >= minY) {
                                world.getBlockAt(x, glowY - 1, z).setType(Material.SEA_LANTERN, false);
                            }
                        }
                    }
                }
            }
        }

        private void populateCityBuildings(World world, int chunkX, int chunkZ, DistrictType districtType) {
            int gridY = plugin.getGridY();
            long seed = world.getSeed();
            Random localRandom = seededRandom(seed, chunkX, chunkZ, 0xD10C0A11);

            int buildingAttempts;
            switch (districtType) {
                case CORE_DENSE -> buildingAttempts = 3;
                case INDUSTRIAL -> buildingAttempts = 2;
                default -> buildingAttempts = 2;
            }

            for (int attempt = 0; attempt < buildingAttempts; attempt++) {
                if (localRandom.nextDouble() > 0.72) {
                    continue;
                }

                int localBaseX = troubledAlign(localRandom.nextInt(16));
                int localBaseZ = troubledAlign(localRandom.nextInt(16));

                int footprintW = 6 + localRandom.nextInt(9);
                int footprintD = 6 + localRandom.nextInt(9);

                if (localBaseX + footprintW > 16) footprintW = 16 - localBaseX;
                if (localBaseZ + footprintD > 16) footprintD = 16 - localBaseZ;

                if (footprintW < 5 || footprintD < 5) {
                    continue;
                }

                int absBaseX = (chunkX << 4) + localBaseX;
                int absBaseZ = (chunkZ << 4) + localBaseZ;

                int height = pickBuildingHeight(seed, absBaseX, absBaseZ, districtType, plugin.getCityMinHeight(), plugin.getCityMaxHeight());
                buildTronTower(world, absBaseX, gridY + 1, absBaseZ, footprintW, footprintD, height, districtType, localRandom);
            }
        }

        private int troubledAlign(int value) {
            return (value & ~3);
        }

        private void buildTronTower(World world, int baseX, int baseY, int baseZ, int width, int depth, int height, DistrictType districtType, Random random) {
            Material shell = Material.BLACK_CONCRETE;
            Material core = Material.POLISHED_DEEPSLATE;
            Material rib = Material.DEEPSLATE_TILES;

            Material accent = Material.CYAN_CONCRETE;
            Material accent2 = Material.LIGHT_BLUE_CONCRETE;

            if (districtType == DistrictType.INDUSTRIAL) {
                core = Material.SMOOTH_BASALT;
                rib = Material.POLISHED_DEEPSLATE;
            }

            int topY = baseY + height;

            for (int x = baseX; x < baseX + width; x++) {
                for (int z = baseZ; z < baseZ + depth; z++) {
                    for (int y = baseY; y < topY; y++) {
                        boolean edge = (x == baseX) || (x == baseX + width - 1) || (z == baseZ) || (z == baseZ + depth - 1);

                        if (edge && (y - baseY) % 4 == 1 && (y - baseY) > 6 && (y < topY - 4)) {
                            world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);

                            int innerX = clamp(x + (x == baseX ? 1 : (x == baseX + width - 1 ? -1 : 0)), baseX, baseX + width - 1);
                            int innerZ = clamp(z + (z == baseZ ? 1 : (z == baseZ + depth - 1 ? -1 : 0)), baseZ, baseZ + depth - 1);

                            if (innerX != x || innerZ != z) {
                                world.getBlockAt(innerX, y, innerZ).setType(Material.LIGHT, false);
                            }
                            continue;
                        }

                        Material mat = edge ? shell : core;
                        world.getBlockAt(x, y, z).setType(mat, false);

                        if (edge && ((x + z) % 3 == 0) && (y - baseY) > 2) {
                            world.getBlockAt(x, y, z).setType(rib, false);
                        }
                    }
                }
            }

            int outlineYStep = 6;
            for (int y = baseY + 2; y < topY; y += outlineYStep) {
                Material band = (y / outlineYStep) % 2 == 0 ? accent : accent2;
                outlineRectangle(world, baseX, y, baseZ, width, depth, band);
            }

            for (int x = baseX; x < baseX + width; x++) {
                for (int z = baseZ; z < baseZ + depth; z++) {
                    world.getBlockAt(x, topY, z).setType(Material.BLACK_CONCRETE, false);
                }
            }
            int roofCenterX = baseX + width / 2;
            int roofCenterZ = baseZ + depth / 2;
            world.getBlockAt(roofCenterX, topY, roofCenterZ).setType(Material.CYAN_STAINED_GLASS, false);
            world.getBlockAt(roofCenterX, topY - 1, roofCenterZ).setType(Material.SEA_LANTERN, false);

            if (random.nextDouble() < 0.08 && width >= 8 && depth >= 8) {
                int bridgeY = baseY + (height / 2);
                buildSkybridgeStub(world, roofCenterX, bridgeY, roofCenterZ, random);
            }
        }

        private void buildSkybridgeStub(World world, int centerX, int y, int centerZ, Random random) {
            int length = 10 + random.nextInt(12);
            int dir = random.nextInt(4);
            int dx = (dir == 0 ? 1 : dir == 1 ? -1 : 0);
            int dz = (dir == 2 ? 1 : dir == 3 ? -1 : 0);

            for (int i = 1; i <= length; i++) {
                int x = centerX + dx * i;
                int z = centerZ + dz * i;

                world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);
                world.getBlockAt(x, y - 1, z).setType(Material.BLACK_CONCRETE, false);

                if (i % 3 == 0) {
                    world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                }
            }
        }

        private void outlineRectangle(World world, int baseX, int y, int baseZ, int width, int depth, Material mat) {
            int x2 = baseX + width - 1;
            int z2 = baseZ + depth - 1;

            for (int x = baseX; x <= x2; x++) {
                world.getBlockAt(x, y, baseZ).setType(mat, false);
                world.getBlockAt(x, y, z2).setType(mat, false);
            }
            for (int z = baseZ; z <= z2; z++) {
                world.getBlockAt(baseX, y, z).setType(mat, false);
                world.getBlockAt(x2, y, z).setType(mat, false);
            }
        }

        private void maybePlaceLandmark(World world, int chunkX, int chunkZ, DistrictType districtType) {
            int districtSize = plugin.getDistrictSizeBlocks();

            int blockCenterX = (chunkX << 4) + 8;
            int blockCenterZ = (chunkZ << 4) + 8;

            int regionX = floorDiv(blockCenterX, districtSize);
            int regionZ = floorDiv(blockCenterZ, districtSize);

            int anchorChunkX = regionX * (districtSize >> 4);
            int anchorChunkZ = regionZ * (districtSize >> 4);

            if (chunkX != anchorChunkX || chunkZ != anchorChunkZ) {
                return;
            }

            Random regionRandom = seededRandom(world.getSeed(), regionX, regionZ, 0xC1A0BEEF);

            double landmarkChance;
            switch (districtType) {
                case CORE_DENSE -> landmarkChance = 0.55;
                case INDUSTRIAL -> landmarkChance = 0.45;
                default -> landmarkChance = 0.35;
            }

            if (regionRandom.nextDouble() > landmarkChance) {
                return;
            }

            int centerX = regionX * districtSize + (districtSize / 2);
            int centerZ = regionZ * districtSize + (districtSize / 2);
            int y = plugin.getGridY() + 1;

            LandmarkType type = pickLandmarkType(regionRandom, districtType);

            String structureId = world.getName() + ":" + type.name() + ":" + regionX + ":" + regionZ;
            plugin.logStructureOnce(world, structureId, type.name(), centerX, y, centerZ, "district=" + districtType.name());

            switch (type) {
                case DISC_ARENA -> buildDiscArena(world, centerX, y, centerZ, 46, regionRandom);
                case LIGHTCYCLE_ARENA -> buildLightCycleArena(world, centerX, y, centerZ, 70, 40, regionRandom);
                case IO_TOWER -> buildIOTower(world, centerX, y, centerZ, regionRandom);
                case HANGAR_TANK -> buildHangar(world, centerX, y, centerZ, 60, 34, false, regionRandom);
                case HANGAR_RECOGNIZER -> buildHangar(world, centerX, y, centerZ, 78, 38, true, regionRandom);
            }
        }

        private void maybePlaceOutlandsSafehouseAnchor(World world, int chunkX, int chunkZ) {
            int districtSize = plugin.getDistrictSizeBlocks();
            int blockCenterX = (chunkX << 4) + 8;
            int blockCenterZ = (chunkZ << 4) + 8;

            int regionX = floorDiv(blockCenterX, districtSize);
            int regionZ = floorDiv(blockCenterZ, districtSize);

            int anchorChunkX = regionX * (districtSize >> 4);
            int anchorChunkZ = regionZ * (districtSize >> 4);

            if (chunkX != anchorChunkX || chunkZ != anchorChunkZ) {
                return;
            }

            Random regionRandom = seededRandom(world.getSeed(), regionX, regionZ, 0x0B1A2C3D);

            if (regionRandom.nextDouble() > 0.20) {
                return;
            }

            int centerX = regionX * districtSize + (districtSize / 2);
            int centerZ = regionZ * districtSize + (districtSize / 2);

            int y = plugin.getGridY() + 1;
            String structureId = world.getName() + ":OUTLANDS_SAFEHOUSE:" + regionX + ":" + regionZ;

            // IMPORTANT: include district in notes so POI export can capture it.
            plugin.logStructureOnce(world, structureId, "OUTLANDS_SAFEHOUSE", centerX, y, centerZ, "district=OUTLANDS anchorOnly=true");
        }

        private void buildDiscArena(World world, int centerX, int y, int centerZ, int radius, Random random) {
            int minY = world.getMinHeight();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    if (dist <= radius) {
                        world.getBlockAt(x, y - 1, z).setType(Material.BLACK_CONCRETE, false);
                        world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);

                        boolean edge = dist >= radius - 1.5;
                        if (edge) {
                            if (y - 1 >= minY) {
                                world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                            }
                            world.getBlockAt(x, y, z).setType(Material.CYAN_STAINED_GLASS, false);
                        }

                        if (!edge && (Math.floorMod(x, 8) == 0 || Math.floorMod(z, 8) == 0) && random.nextDouble() < 0.25) {
                            world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                        }
                    }
                }
            }
        }

        private void buildLightCycleArena(World world, int centerX, int y, int centerZ, int halfW, int halfD, Random random) {
            int minY = world.getMinHeight();

            int x1 = centerX - halfW;
            int x2 = centerX + halfW;
            int z1 = centerZ - halfD;
            int z2 = centerZ + halfD;

            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x, y - 1, z).setType(Material.BLACK_CONCRETE, false);
                    world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);

                    boolean edge = (x == x1 || x == x2 || z == z1 || z == z2);
                    if (edge) {
                        if (y - 1 >= minY) {
                            world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                        }
                        world.getBlockAt(x, y, z).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                    } else {
                        if ((Math.floorMod(x, 8) == 0 || Math.floorMod(z, 8) == 0) && random.nextDouble() < 0.55) {
                            world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                        }
                    }
                }
            }
        }

        private void buildIOTower(World world, int centerX, int y, int centerZ, Random random) {
            int height = 80 + random.nextInt(60);
            int baseSize = 10;

            int x1 = centerX - baseSize;
            int x2 = centerX + baseSize;
            int z1 = centerZ - baseSize;
            int z2 = centerZ + baseSize;

            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x, y - 1, z).setType(Material.BLACK_CONCRETE, false);
                    world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);
                }
            }

            for (int dy = 0; dy < height; dy++) {
                int currentY = y + dy;

                int size = (dy < 12) ? 6 : 4;
                int tx1 = centerX - size;
                int tx2 = centerX + size;
                int tz1 = centerZ - size;
                int tz2 = centerZ + size;

                for (int x = tx1; x <= tx2; x++) {
                    for (int z = tz1; z <= tz2; z++) {
                        boolean edge = (x == tx1 || x == tx2 || z == tz1 || z == tz2);
                        Material mat = edge ? Material.POLISHED_DEEPSLATE : Material.BLACK_CONCRETE;
                        world.getBlockAt(x, currentY, z).setType(mat, false);
                    }
                }

                if (dy % 12 == 0 && dy > 0) {
                    outlineRectangle(world, centerX - (size + 2), currentY, centerZ - (size + 2),
                            (size + 2) * 2 + 1, (size + 2) * 2 + 1, Material.CYAN_CONCRETE);
                }
            }

            world.getBlockAt(centerX, y + height, centerZ).setType(Material.BEACON, false);
            world.getBlockAt(centerX, y + height - 1, centerZ).setType(Material.SEA_LANTERN, false);
        }

        private void buildHangar(World world, int centerX, int y, int centerZ, int width, int depth, boolean recognizerTheme, Random random) {
            int halfW = width / 2;
            int halfD = depth / 2;

            int x1 = centerX - halfW;
            int x2 = centerX + halfW;
            int z1 = centerZ - halfD;
            int z2 = centerZ + halfD;

            Material floor = Material.BLACK_CONCRETE;
            Material wall = Material.POLISHED_DEEPSLATE;
            Material accent = recognizerTheme ? Material.ORANGE_CONCRETE : Material.CYAN_CONCRETE;

            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x, y - 1, z).setType(floor, false);
                    world.getBlockAt(x, y, z).setType(Material.TINTED_GLASS, false);
                }
            }

            int wallHeight = 18;
            for (int dy = 0; dy < wallHeight; dy++) {
                int wy = y + dy;
                for (int x = x1; x <= x2; x++) {
                    world.getBlockAt(x, wy, z1).setType(wall, false);
                    world.getBlockAt(x, wy, z2).setType(wall, false);
                }
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x1, wy, z).setType(wall, false);
                    world.getBlockAt(x2, wy, z).setType(wall, false);
                }
            }

            int doorZ = z1;
            for (int x = centerX - 10; x <= centerX + 10; x++) {
                world.getBlockAt(x, y + 1, doorZ).setType(accent, false);
                world.getBlockAt(x, y + 2, doorZ).setType(Material.LIGHT, false);
            }

            for (int z = centerZ - halfD + 4; z <= centerZ + halfD - 4; z += 6) {
                for (int x = centerX - halfW + 6; x <= centerX + halfW - 6; x++) {
                    if ((x + z) % 7 == 0) {
                        world.getBlockAt(x, y, z).setType(recognizerTheme ? Material.ORANGE_STAINED_GLASS : Material.CYAN_STAINED_GLASS, false);
                        world.getBlockAt(x, y - 1, z).setType(Material.SEA_LANTERN, false);
                    }
                }
            }
        }

        private LandmarkType pickLandmarkType(Random random, DistrictType districtType) {
            if (districtType == DistrictType.INDUSTRIAL) {
                return random.nextBoolean() ? LandmarkType.HANGAR_TANK : LandmarkType.HANGAR_RECOGNIZER;
            }
            if (districtType == DistrictType.CORE_DENSE) {
                int roll = random.nextInt(3);
                if (roll == 0) return LandmarkType.IO_TOWER;
                if (roll == 1) return LandmarkType.DISC_ARENA;
                return LandmarkType.LIGHTCYCLE_ARENA;
            }

            int roll = random.nextInt(4);
            return switch (roll) {
                case 0 -> LandmarkType.DISC_ARENA;
                case 1 -> LandmarkType.LIGHTCYCLE_ARENA;
                case 2 -> LandmarkType.IO_TOWER;
                default -> LandmarkType.HANGAR_TANK;
            };
        }

        private int pickBuildingHeight(long seed, int x, int z, DistrictType districtType, int minHeight, int maxHeight) {
            double n = pseudoNoise2D(seed, x * 0.01, z * 0.01);
            double n2 = pseudoNoise2D(seed ^ 0x7F4A7C159E3779B9L, x * 0.006, z * 0.006);

            double base = (n + 1.0) * 0.5;
            double base2 = (n2 + 1.0) * 0.5;
            double blend = (base * 0.65) + (base2 * 0.35);

            double districtBoost;
            switch (districtType) {
                case CORE_DENSE -> districtBoost = 1.0;
                case INDUSTRIAL -> districtBoost = 0.75;
                default -> districtBoost = 0.85;
            }

            int heightRange = Math.max(1, maxHeight - minHeight);
            int result = minHeight + (int) Math.round(heightRange * blend * districtBoost);

            return Math.max(minHeight, Math.min(maxHeight, result));
        }

        private static int clamp(int value, int minValue, int maxValue) {
            return Math.max(minValue, Math.min(maxValue, value));
        }

        private static int floorDiv(int a, int b) {
            int r = a / b;
            if ((a ^ b) < 0 && (r * b != a)) {
                r--;
            }
            return r;
        }

        private static Random seededRandom(long seed, int a, int b, int salt) {
            long mixed = seed;
            mixed ^= (long) a * 341873128712L;
            mixed ^= (long) b * 132897987541L;
            mixed ^= (long) salt * 0x9E3779B97F4A7C15L;
            mixed = mix64(mixed);
            return new Random(mixed);
        }

        private static long mix64(long z) {
            z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
            z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return z ^ (z >>> 33);
        }

        private static double pseudoNoise2D(long seed, double x, double z) {
            long xi = (long) Math.floor(x * 1024.0);
            long zi = (long) Math.floor(z * 1024.0);

            long h = seed;
            h ^= xi * 0x9E3779B97F4A7C15L;
            h ^= zi * 0xC2B2AE3D27D4EB4FL;
            h = mix64(h);

            double unit = (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
            return (unit * 2.0) - 1.0;
        }
    }

    // ----------------------------
    // District / Landmark enums
    // ----------------------------
    public enum DistrictType {
        CORE_DENSE,
        URBAN,
        INDUSTRIAL,
        OUTLANDS;

        public static DistrictType forBlock(long seed, int x, int z, int districtSizeBlocks, double outlandsChance) {
            int regionX = TronPopulator.floorDiv(x, districtSizeBlocks);
            int regionZ = TronPopulator.floorDiv(z, districtSizeBlocks);

            Random regionRandom = TronPopulator.seededRandom(seed, regionX, regionZ, 0x1234ABCD);
            double roll = regionRandom.nextDouble();

            if (roll < outlandsChance) {
                return OUTLANDS;
            }

            double cityRoll = regionRandom.nextDouble();
            if (cityRoll < 0.25) {
                return CORE_DENSE;
            }
            if (cityRoll < 0.78) {
                return URBAN;
            }
            return INDUSTRIAL;
        }
    }

    public enum LandmarkType {
        DISC_ARENA,
        LIGHTCYCLE_ARENA,
        IO_TOWER,
        HANGAR_TANK,
        HANGAR_RECOGNIZER
    }
}
