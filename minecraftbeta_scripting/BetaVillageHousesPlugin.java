import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CreatureType;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

// Old Bukkit event API (Beta era)
import org.bukkit.event.world.WorldListener;
import org.bukkit.event.world.WorldLoadEvent;

public class BetaVillageHousesPlugin extends JavaPlugin {

    // ----------------------------
    // Config (defaults)
    // ----------------------------

    // If empty => allow all non-NETHER worlds
    private final Set allowedWorldNamesLower = new HashSet(); // raw type for old compatibility

    // pk behavior
    private int chanceDivisor = 15;     // random.nextInt(15) == 0
    private int attemptsPerChunk = 8;   // 8 attempts when triggered

    private boolean spawnPigZombie = true;

    // Block IDs (Beta)
    private int idCobble = 4;       // stoneBrick -> cobble
    private int idMossy = 48;       // mossyStoneBrick -> mossy cobble
    private int idWood = 5;         // roof
    private int idDoor = 64;        // wooden door
    private int idGlass = 20;
    private int idTorch = 50;

    // Repopulation system config
    private boolean repopEnabled = true;
    private int repopCheckIntervalTicks = 1200; // 60s
    private int repopChanceDivisor = 6;
    private int repopSearchRadius = 12;
    private boolean repopLogSpawns = true;

    private final BlockPopulator populator = new VillageHousePopulator();

    private final WorldListener worldListener = new WorldListener() {
        @Override
        public void onWorldLoad(WorldLoadEvent event) {
            tryAttachToWorld(event.getWorld());
        }
    };

    // ----------------------------
    // House registry
    // ----------------------------

    private static final class HouseRecord {
        public final String worldName;
        public final int x;
        public final int y;
        public final int z;

        public HouseRecord(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String toLine() {
            return worldName + "," + x + "," + y + "," + z;
        }

        public static HouseRecord fromLine(String line) {
            try {
                String[] parts = line.split(",");
                if (parts.length != 4) return null;
                String w = parts[0].trim();
                int x = Integer.parseInt(parts[1].trim());
                int y = Integer.parseInt(parts[2].trim());
                int z = Integer.parseInt(parts[3].trim());
                return new HouseRecord(w, x, y, z);
            } catch (Throwable t) {
                return null;
            }
        }

        public String key() {
            return worldName + ":" + x + ":" + y + ":" + z;
        }
    }

    private final List houseRecords = new ArrayList(); // List<HouseRecord> but raw for old compat
    private final Set houseRecordKeys = new HashSet(); // Set<String>
    private File housesFile;
    private File spawnLogFile;

    @Override
    public void onEnable() {
        loadOrCreateConfig();

        // Setup data files
        File pluginDir = getDataFolder();
        if (!pluginDir.exists()) pluginDir.mkdirs();
        housesFile = new File(pluginDir, "houses.txt");
        spawnLogFile = new File(pluginDir, "pigzombie_spawns.txt");

        loadHouseRegistry();

        // Attach to already-loaded worlds
        List worlds = getServer().getWorlds();
        for (int i = 0; i < worlds.size(); i++) {
            World w = (World) worlds.get(i);
            tryAttachToWorld(w);
        }

        // Register world load listener (old Bukkit style)
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(org.bukkit.event.Event.Type.WORLD_LOAD, worldListener,
                org.bukkit.event.Event.Priority.Normal, this);

        // Start repop task
        startRepopTask();

        System.out.println("[BetaVillageHousesPlugin] Enabled. Loaded houses: " + houseRecords.size());
    }

    @Override
    public void onDisable() {
        // Nothing special; registry writes are append-based.
        System.out.println("[BetaVillageHousesPlugin] Disabled.");
    }

    private void tryAttachToWorld(World world) {
        // Only overworld/sky (not nether)
        if (world.getEnvironment() == World.Environment.NETHER) return;

        // allowedWorlds empty => all non-nether
        if (!allowedWorldNamesLower.isEmpty()) {
            String worldNameLower = world.getName().toLowerCase();
            if (!allowedWorldNamesLower.contains(worldNameLower)) return;
        }

        if (!world.getPopulators().contains(populator)) {
            world.getPopulators().add(populator);
            System.out.println("[BetaVillageHousesPlugin] Generation enabled in world: " + world.getName());
        }
    }

    // ----------------------------
    // Config I/O (simple YAML-ish)
    // ----------------------------

    private void loadOrCreateConfig() {
        try {
            File pluginDir = getDataFolder();
            if (!pluginDir.exists()) pluginDir.mkdirs();

            File cfgFile = new File(pluginDir, "config.yml");
            if (!cfgFile.exists()) {
                writeDefaultConfig(cfgFile);
            }

            parseConfig(cfgFile);
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Config load failed; using defaults. " + t);
        }
    }

    private void writeDefaultConfig(File cfgFile) {
        try {
            FileWriter fw = new FileWriter(cfgFile);
            fw.write("# BetaVillageHousesPlugin config\n");
            fw.write("# If allowedWorlds is empty, applies to ALL non-NETHER worlds.\n");
            fw.write("allowedWorlds: []\n");
            fw.write("\n");
            fw.write("# Generation settings (matches pk system)\n");
            fw.write("chanceDivisor: 15\n");
            fw.write("attemptsPerChunk: 8\n");
            fw.write("\n");
            fw.write("# Spawn a Zombie Pigman near the house center during generation\n");
            fw.write("spawnPigZombie: true\n");
            fw.write("\n");
            fw.write("# Block IDs (HouseFeature mapping)\n");
            fw.write("# stoneBrick -> cobblestone (4)\n");
            fw.write("# mossyStoneBrick -> mossy cobblestone (48)\n");
            fw.write("blockIdCobblestone: 4\n");
            fw.write("blockIdMossyCobblestone: 48\n");
            fw.write("blockIdWoodPlanks: 5\n");
            fw.write("blockIdDoorWood: 64\n");
            fw.write("blockIdGlass: 20\n");
            fw.write("blockIdTorch: 50\n");
            fw.write("\n");
            fw.write("# PigZombie repopulation system\n");
            fw.write("repopEnabled: true\n");
            fw.write("repopCheckIntervalTicks: 1200\n");
            fw.write("repopChanceDivisor: 6\n");
            fw.write("repopSearchRadius: 12\n");
            fw.write("repopLogSpawns: true\n");
            fw.write("\n");
            fw.close();
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Failed to write default config: " + t);
        }
    }

    private void parseConfig(File cfgFile) {
        allowedWorldNamesLower.clear();

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(cfgFile));
            String line;
            while ((line = br.readLine()) != null) {
                line = stripComments(line).trim();
                if (line.length() == 0) continue;

                int colon = line.indexOf(':');
                if (colon <= 0) continue;

                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();

                if (key.equalsIgnoreCase("allowedWorlds")) {
                    parseAllowedWorlds(value);
                } else if (key.equalsIgnoreCase("chanceDivisor")) {
                    chanceDivisor = parseInt(value, chanceDivisor);
                } else if (key.equalsIgnoreCase("attemptsPerChunk")) {
                    attemptsPerChunk = parseInt(value, attemptsPerChunk);
                } else if (key.equalsIgnoreCase("spawnPigZombie")) {
                    spawnPigZombie = parseBool(value, spawnPigZombie);
                } else if (key.equalsIgnoreCase("blockIdCobblestone")) {
                    idCobble = parseInt(value, idCobble);
                } else if (key.equalsIgnoreCase("blockIdMossyCobblestone")) {
                    idMossy = parseInt(value, idMossy);
                } else if (key.equalsIgnoreCase("blockIdWoodPlanks")) {
                    idWood = parseInt(value, idWood);
                } else if (key.equalsIgnoreCase("blockIdDoorWood")) {
                    idDoor = parseInt(value, idDoor);
                } else if (key.equalsIgnoreCase("blockIdGlass")) {
                    idGlass = parseInt(value, idGlass);
                } else if (key.equalsIgnoreCase("blockIdTorch")) {
                    idTorch = parseInt(value, idTorch);
                } else if (key.equalsIgnoreCase("repopEnabled")) {
                    repopEnabled = parseBool(value, repopEnabled);
                } else if (key.equalsIgnoreCase("repopCheckIntervalTicks")) {
                    repopCheckIntervalTicks = parseInt(value, repopCheckIntervalTicks);
                } else if (key.equalsIgnoreCase("repopChanceDivisor")) {
                    repopChanceDivisor = parseInt(value, repopChanceDivisor);
                } else if (key.equalsIgnoreCase("repopSearchRadius")) {
                    repopSearchRadius = parseInt(value, repopSearchRadius);
                } else if (key.equalsIgnoreCase("repopLogSpawns")) {
                    repopLogSpawns = parseBool(value, repopLogSpawns);
                }
            }
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Failed to parse config; using defaults. " + t);
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }

        if (chanceDivisor <= 0) chanceDivisor = 15;
        if (attemptsPerChunk <= 0) attemptsPerChunk = 8;
        if (repopCheckIntervalTicks < 20) repopCheckIntervalTicks = 20;
        if (repopChanceDivisor <= 0) repopChanceDivisor = 1;
        if (repopSearchRadius <= 0) repopSearchRadius = 12;
    }

    private String stripComments(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) return line.substring(0, hash);
        return line;
    }

    private void parseAllowedWorlds(String value) {
        value = value.trim();
        if (value.equals("[]")) return;

        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1).trim();
            if (inner.length() == 0) return;

            String[] parts = inner.split(",");
            for (int i = 0; i < parts.length; i++) {
                String name = parts[i].trim();
                if (name.length() == 0) continue;

                if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
                    name = name.substring(1, name.length() - 1);
                }

                allowedWorldNamesLower.add(name.toLowerCase());
            }
        }
    }

    private int parseInt(String s, int fallback) {
        try {
            s = s.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private boolean parseBool(String s, boolean fallback) {
        try {
            s = s.trim().toLowerCase();
            if (s.equals("true")) return true;
            if (s.equals("false")) return false;
            return fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ----------------------------
    // Registry I/O
    // ----------------------------

    private void loadHouseRegistry() {
        if (housesFile == null) return;
        if (!housesFile.exists()) return;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(housesFile));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                HouseRecord rec = HouseRecord.fromLine(line);
                if (rec == null) continue;
                String key = rec.key();
                if (houseRecordKeys.contains(key)) continue;
                houseRecordKeys.add(key);
                houseRecords.add(rec);
            }
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Failed to read houses.txt: " + t);
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private void appendHouseRecord(HouseRecord rec) {
        if (rec == null || housesFile == null) return;
        String key = rec.key();
        if (houseRecordKeys.contains(key)) return;

        houseRecordKeys.add(key);
        houseRecords.add(rec);

        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(housesFile, true));
            bw.write(rec.toLine());
            bw.newLine();
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Failed to append to houses.txt: " + t);
        } finally {
            try { if (bw != null) bw.close(); } catch (Throwable ignored) {}
        }
    }

    private void logSpawn(String msg) {
        if (!repopLogSpawns || spawnLogFile == null) return;
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(spawnLogFile, true));
            bw.write(msg);
            bw.newLine();
        } catch (Throwable ignored) {
        } finally {
            try { if (bw != null) bw.close(); } catch (Throwable ignored2) {}
        }
    }

    // ----------------------------
    // Repopulation task
    // ----------------------------

    private void startRepopTask() {
        if (!repopEnabled) return;

        try {
            // Beta Bukkit scheduler exists on Server
            getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
                public void run() {
                    runRepopPass();
                }
            }, repopCheckIntervalTicks, repopCheckIntervalTicks);
        } catch (Throwable t) {
            System.out.println("[BetaVillageHousesPlugin] Could not start repop task: " + t);
        }
    }

    private void runRepopPass() {
        if (!repopEnabled) return;
        if (houseRecords.isEmpty()) return;

        // Avoid heavy per-tick scanning: we scan a subset per pass if huge
        // (simple throttle: cap per pass)
        int maxPerPass = 200;
        int count = 0;

        for (int i = 0; i < houseRecords.size(); i++) {
            HouseRecord rec = (HouseRecord) houseRecords.get(i);

            World world = getServer().getWorld(rec.worldName);
            if (world == null) continue;
            if (world.getEnvironment() == World.Environment.NETHER) continue;

            int chunkX = rec.x >> 4;
            int chunkZ = rec.z >> 4;

            if (!isChunkLoadedSafe(world, chunkX, chunkZ)) continue;

            // Is there already a PigZombie nearby?
            if (hasPigZombieNearby(world, rec.x, rec.y, rec.z, repopSearchRadius)) {
                continue;
            }

            // Occasionally respawn
            Random r = new Random();
            if (r.nextInt(repopChanceDivisor) != 0) {
                continue;
            }

            trySpawnPigZombie(world, rec.x, rec.y, rec.z);

            count++;
            if (count >= maxPerPass) break;
        }
    }

    private boolean isChunkLoadedSafe(World world, int cx, int cz) {
        try {
            // Try World#isChunkLoaded(int,int) if present
            try {
                java.lang.reflect.Method m = world.getClass().getMethod("isChunkLoaded", int.class, int.class);
                Object result = m.invoke(world, new Object[] { new Integer(cx), new Integer(cz) });
                if (result instanceof Boolean) {
                    return ((Boolean) result).booleanValue();
                }
            } catch (Throwable ignored) {}

            // Fallback: if getChunkAt works, assume loaded (some builds auto-load)
            world.getChunkAt(cx, cz);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasPigZombieNearby(World world, int x, int y, int z, int radius) {
        int r2 = radius * radius;

        try {
            // Use reflection to avoid hard dependency on entity interfaces across forks
            java.lang.reflect.Method getEntities = world.getClass().getMethod("getEntities", new Class[] { });
            Object entitiesObj = getEntities.invoke(world, new Object[] { });

            if (!(entitiesObj instanceof List)) return false;
            List entities = (List) entitiesObj;

            for (int i = 0; i < entities.size(); i++) {
                Object ent = entities.get(i);
                if (ent == null) continue;

                // Identify PigZombie by class name (stable across old Bukkit)
                String simple = ent.getClass().getSimpleName();
                if (!"PigZombie".equals(simple) && !"CraftPigZombie".equals(simple)) {
                    continue;
                }

                // Get location
                try {
                    java.lang.reflect.Method getLocation = ent.getClass().getMethod("getLocation", new Class[] { });
                    Object locObj = getLocation.invoke(ent, new Object[] { });
                    if (!(locObj instanceof Location)) continue;

                    Location loc = (Location) locObj;
                    int dx = loc.getBlockX() - x;
                    int dy = loc.getBlockY() - y;
                    int dz = loc.getBlockZ() - z;

                    int dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 <= r2) return true;
                } catch (Throwable ignoredLoc) {}
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private void trySpawnPigZombie(World world, int x, int y, int z) {
        try {
            Location loc = new Location(world, x + 0.5, y + 0.5, z + 0.5);
            Object spawned = null;

            try {
                java.lang.reflect.Method m = world.getClass().getMethod("spawnCreature", Location.class, CreatureType.class);
                spawned = m.invoke(world, new Object[] { loc, CreatureType.PIG_ZOMBIE });
            } catch (Throwable ignoredSig) {
                world.spawnCreature(loc, CreatureType.PIG_ZOMBIE);
            }

            logSpawn("Spawned PigZombie at " + world.getName() + " " + x + " " + y + " " + z);

            // Try to prevent distance despawn if API exists
            if (spawned != null) {
                try {
                    java.lang.reflect.Method setRemove = spawned.getClass().getMethod("setRemoveWhenFarAway", boolean.class);
                    setRemove.invoke(spawned, new Object[] { Boolean.FALSE });
                } catch (Throwable ignoredNoMethod) {}
            }
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------
    // Populator (pk system)
    // ----------------------------

    private final class VillageHousePopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random ignoredRandom, Chunk chunk) {
            if (world.getEnvironment() == World.Environment.NETHER) return;

            // pk RNG seeding
            long worldSeed = world.getSeed();
            Random rand = new Random(worldSeed);

            long oddA = (rand.nextLong() / 2L) * 2L + 1L;
            long oddB = (rand.nextLong() / 2L) * 2L + 1L;

            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            long chunkSeed = ((long)chunkX * oddA + (long)chunkZ * oddB) ^ worldSeed;
            rand.setSeed(chunkSeed);

            if (rand.nextInt(chanceDivisor) != 0) return;

            int baseX = (chunkX << 4);
            int baseZ = (chunkZ << 4);

            for (int i = 0; i < attemptsPerChunk; i++) {
                int x = baseX + rand.nextInt(32) + 8;
                int z = baseZ + rand.nextInt(32) + 8;
                int y = world.getHighestBlockYAt(x, z);

                placeHouseFeature(world, rand, x, y, z);
            }
        }
    }

    // ----------------------------
    // HouseFeature.cpp faithful port + your fixed glass spacing/door safety
    // ----------------------------

    private boolean placeHouseFeature(World world, Random random, int x, int y, int z) {
        while (y > 0 && !isBlocksMotion(world, x, y - 1, z)) {
            y--;
        }
        if (y <= 1) return false;

        int w = random.nextInt(7) + 7;
        int h = 4 + random.nextInt(3) / 2;
        int d = random.nextInt(7) + 7;

        int x0 = x - w / 2;
        int y0 = y;
        int z0 = z - d / 2;

        int doorSide = random.nextInt(4);
        if (doorSide < 2) d += 2;
        else w += 2;

        for (int xx = x0; xx < x0 + w; xx++) {
            for (int zz = z0; zz < z0 + d; zz++) {
                if (!isBlocksMotion(world, xx, y - 1, zz)) return false;
                if (isIce(world, xx, y - 1, zz)) return false;

                boolean ok = false;
                if (doorSide == 0 && xx < x0 + 2) ok = true;
                if (doorSide == 1 && xx > x0 + w - 1 - 2) ok = true;
                if (doorSide == 2 && zz < z0 + 2) ok = true;
                if (doorSide == 3 && zz > z0 + d - 1 - 2) ok = true;

                int t = world.getBlockAt(xx, y, zz).getTypeId();

                if (ok) {
                    if (t != 0) return false;
                } else {
                    if (t == idCobble || t == idMossy) return false;
                }
            }
        }

        if (doorSide == 0) { x0++; w--; }
        else if (doorSide == 1) { w--; }
        else if (doorSide == 2) { z0++; d--; }
        else if (doorSide == 3) { d--; }

        int xx0 = x0;
        int xx1 = x0 + w - 1;
        int zz0 = z0;
        int zz1 = z0 + d - 1;

        if (doorSide >= 2) { xx0++; xx1--; }
        else { zz0++; zz1--; }

        for (int xx = x0; xx < x0 + w; xx++) {
            for (int zz = z0; zz < z0 + d; zz++) {
                int ho = h;

                int d1 = zz - z0;
                int d2 = (z0 + d - 1) - zz;
                if (doorSide < 2) {
                    d1 = xx - x0;
                    d2 = (x0 + w - 1) - xx;
                }

                if (d2 < d1) d1 = d2;
                h += d1;

                for (int yy = y0 - 1; yy < y0 + h; yy++) {
                    int material = -1;

                    if (yy == y0 + h - 1) {
                        material = idWood;
                    } else if (xx >= xx0 && xx <= xx1 && zz >= zz0 && zz <= zz1) {
                        material = 0;
                        if (yy == y0 - 1 || yy == y0 + h - 1 || xx == xx0 || zz == zz0 || xx == xx1 || zz == zz1) {
                            if (yy <= y0 + random.nextInt(3)) material = idMossy;
                            else material = idCobble;
                        }
                    }

                    if (material >= 0) {
                        world.getBlockAt(xx, yy, zz).setTypeId(material, false);
                    }
                }

                h = ho;
            }
        }

        // Door placement
        int doorX = x0 + random.nextInt(w - 4) + 2;
        int doorZ = z0 + random.nextInt(d - 4) + 2;
        if (doorSide == 0) doorX = x0;
        if (doorSide == 1) doorX = x0 + w - 1;
        if (doorSide == 2) doorZ = z0;
        if (doorSide == 3) doorZ = z0 + d - 1;

        world.getBlockAt(doorX, y0, doorZ).setTypeId(0, false);
        world.getBlockAt(doorX, y0 + 1, doorZ).setTypeId(0, false);

        int dir = 0;
        if (doorSide == 0) dir = 0;
        if (doorSide == 2) dir = 1;
        if (doorSide == 1) dir = 2;
        if (doorSide == 3) dir = 3;

        placeDoorLikeDoorItem(world, doorX, y0, doorZ, dir);

        // Glass placement (safe + spacing)
        for (int i = 0; i < (w * 2 + d * 2) * 3; i++) {
            int gx = x0 + random.nextInt(w - 4) + 2;
            int gz = z0 + random.nextInt(d - 4) + 2;
            int side = random.nextInt(4);

            if (side == 0) gx = xx0;
            if (side == 1) gx = xx1;
            if (side == 2) gz = zz0;
            if (side == 3) gz = zz1;

            int targetId = world.getBlockAt(gx, y0 + 1, gz).getTypeId();
            int belowId  = world.getBlockAt(gx, y0, gz).getTypeId();
            if (targetId == idDoor || belowId == idDoor) continue;

            if (!isSolidBlockingTile(world, gx, y0 + 1, gz)) continue;

            boolean onXWall = (gx == xx0 || gx == xx1);
            boolean onZWall = (gz == zz0 || gz == zz1);

            if (onXWall) {
                int left = world.getBlockAt(gx, y0 + 1, gz - 1).getTypeId();
                int right = world.getBlockAt(gx, y0 + 1, gz + 1).getTypeId();
                if (left == idGlass || left == idDoor) continue;
                if (right == idGlass || right == idDoor) continue;
            } else if (onZWall) {
                int left = world.getBlockAt(gx - 1, y0 + 1, gz).getTypeId();
                int right = world.getBlockAt(gx + 1, y0 + 1, gz).getTypeId();
                if (left == idGlass || left == idDoor) continue;
                if (right == idGlass || right == idDoor) continue;
            }

            int count = 0;
            if (isSolidBlockingTile(world, gx - 1, y0 + 1, gz) && isSolidBlockingTile(world, gx + 1, y0 + 1, gz)) count++;
            if (isSolidBlockingTile(world, gx, y0 + 1, gz - 1) && isSolidBlockingTile(world, gx, y0 + 1, gz + 1)) count++;
            if (count == 1) {
                world.getBlockAt(gx, y0 + 1, gz).setTypeId(idGlass, false);
            }
        }

        // Torch placement
        int ww = xx1 - xx0;
        int dd = zz1 - zz0;
        for (int i = 0; i < (ww * 2 + dd * 2); i++) {
            int tx = xx0 + random.nextInt(ww - 1) + 1;
            int tz = zz0 + random.nextInt(dd - 1) + 1;

            if (world.getBlockAt(tx, y0 + 2, tz).getTypeId() == 0) {
                int count = 0;
                if (isSolidBlockingTile(world, tx - 1, y0 + 2, tz)) count++;
                if (isSolidBlockingTile(world, tx + 1, y0 + 2, tz)) count++;
                if (isSolidBlockingTile(world, tx, y0 + 2, tz - 1)) count++;
                if (isSolidBlockingTile(world, tx, y0 + 2, tz + 1)) count++;

                if (count == 1) {
                    world.getBlockAt(tx, y0 + 2, tz).setTypeId(idTorch, false);
                }
            }
        }

        // Record house center for repop system (always, even if spawn is off)
        int centerX = (int) (x0 + w / 2.0);
        int centerY = y0;
        int centerZ = (int) (z0 + d / 2.0);
        appendHouseRecord(new HouseRecord(world.getName(), centerX, centerY, centerZ));

        // Attempt initial spawn (may fail; repop system handles it later)
        if (spawnPigZombie) {
            trySpawnPigZombie(world, centerX, centerY, centerZ);
        }

        return true;
    }

    // ----------------------------
    // DoorItem::place equivalent
    // ----------------------------

    private void placeDoorLikeDoorItem(World world, int x, int y, int z, int dir) {
        int dx = 0, dz = 0;
        if (dir == 0) dz = 1;
        if (dir == 1) dx = -1;
        if (dir == 2) dz = -1;
        if (dir == 3) dx = 1;

        int leftSolid = 0;
        int rightSolid = 0;

        if (isSolidBlockingTile(world, x - dx, y, z - dz)) leftSolid++;
        if (isSolidBlockingTile(world, x - dx, y + 1, z - dz)) leftSolid++;

        if (isSolidBlockingTile(world, x + dx, y, z + dz)) rightSolid++;
        if (isSolidBlockingTile(world, x + dx, y + 1, z + dz)) rightSolid++;

        boolean leftDoor = (world.getBlockAt(x - dx, y, z - dz).getTypeId() == idDoor)
                        || (world.getBlockAt(x - dx, y + 1, z - dz).getTypeId() == idDoor);

        boolean rightDoor = (world.getBlockAt(x + dx, y, z + dz).getTypeId() == idDoor)
                         || (world.getBlockAt(x + dx, y + 1, z + dz).getTypeId() == idDoor);

        int data = dir & 3;

        boolean flip = false;
        if (leftDoor && !rightDoor) flip = true;
        else if (rightSolid > leftSolid) flip = true;

        if (flip) {
            data = ((data - 1) & 3) + 4;
        }

        setTypeIdAndDataSafe(world, x, y, z, idDoor, (byte) data);
        setTypeIdAndDataSafe(world, x, y + 1, z, idDoor, (byte) (data + 8));
    }

    private void setTypeIdAndDataSafe(World world, int x, int y, int z, int typeId, byte data) {
        Block block = world.getBlockAt(x, y, z);
        try {
            block.getClass().getMethod("setTypeIdAndData", int.class, byte.class, boolean.class)
                    .invoke(block, new Object[] { Integer.valueOf(typeId), Byte.valueOf(data), Boolean.FALSE });
        } catch (Throwable t) {
            block.setTypeId(typeId, false);
        }
    }

    // ----------------------------
    // Material helpers approximating Level/Material calls
    // ----------------------------

    private boolean isBlocksMotion(World world, int x, int y, int z) {
        int id = world.getBlockAt(x, y, z).getTypeId();
        if (id == 0) return false;
        if (id == 8 || id == 9 || id == 10 || id == 11) return false;
        return true;
    }

    private boolean isIce(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getTypeId() == 79;
    }

    private boolean isSolidBlockingTile(World world, int x, int y, int z) {
        int id = world.getBlockAt(x, y, z).getTypeId();
        if (id == 0) return false;
        if (id == 8 || id == 9 || id == 10 || id == 11) return false;
        return true;
    }
}