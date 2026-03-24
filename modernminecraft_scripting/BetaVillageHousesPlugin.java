import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Door.Hinge;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

/**
 * BetaVillageHousesPlugin (Spigot 1.21.10 port)
 *
 * Original behavior summary (from Beta-era plugin):
 * - Adds a BlockPopulator to selected non-NETHER worlds.
 * - Uses "pk" style per-chunk RNG seeding to decide whether to attempt house placement in a chunk.
 * - Attempts multiple house placements per triggered chunk using a HouseFeature-like algorithm.
 * - Records each generated house center to houses.txt (world,x,y,z).
 * - Optionally spawns a Zombified Piglin at the house center and periodically repopulates if absent.
 *
 * Author: ChatGPT (modified by ChatGPT)
 */
public class BetaVillageHousesPlugin extends JavaPlugin implements Listener {

    // ----------------------------
    // Config / defaults
    // ----------------------------

    // If empty => allow all non-NETHER worlds
    private final Set<String> allowedWorldNamesLower = new HashSet<>();

    // pk behavior
    private int chanceDivisor = 15;     // random.nextInt(15) == 0
    private int attemptsPerChunk = 8;   // 8 attempts when triggered

    private boolean spawnPigZombie = true;

    // Block materials (modern)
    private Material matCobble = Material.COBBLESTONE;
    private Material matMossy = Material.MOSSY_COBBLESTONE;
    private Material matWood = Material.OAK_PLANKS; // roof
    private Material matDoor = Material.OAK_DOOR;
    private Material matGlass = Material.GLASS;
    private Material matTorch = Material.TORCH;

    // Repopulation system config
    private boolean repopEnabled = true;
    private int repopCheckIntervalTicks = 1200; // 60s
    private int repopChanceDivisor = 6;
    private int repopSearchRadius = 12;
    private boolean repopLogSpawns = true;

    private final BlockPopulator populator = new VillageHousePopulator();
    private BukkitTask repopTask;

	private void ensureConfigOnDisk() {
		if (!getDataFolder().exists()) {
			//noinspection ResultOfMethodCallIgnored
			getDataFolder().mkdirs();
		}
	
		File configFile = new File(getDataFolder(), "config.yml");
		if (configFile.exists()) {
			return;
		}
	
		// Minimal default config written to disk (matches the values the plugin expects)
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(configFile))) {
			bw.write("# BetaVillageHousesPlugin (Spigot 1.21.10) config");
			bw.newLine();
			bw.write("# If allowedWorlds is empty, applies to ALL non-NETHER worlds.");
			bw.newLine();
			bw.write("allowedWorlds: []");
			bw.newLine();
			bw.newLine();
			bw.write("# Generation settings (matches pk system)");
			bw.newLine();
			bw.write("chanceDivisor: 15");
			bw.newLine();
			bw.write("attemptsPerChunk: 8");
			bw.newLine();
			bw.newLine();
			bw.write("# Spawn a Zombified Piglin near the house center during generation");
			bw.newLine();
			bw.write("spawnPigZombie: true");
			bw.newLine();
			bw.newLine();
			bw.write("# Block materials (use Bukkit Material names)");
			bw.newLine();
			bw.write("blockCobblestone: COBBLESTONE");
			bw.newLine();
			bw.write("blockMossyCobblestone: MOSSY_COBBLESTONE");
			bw.newLine();
			bw.write("blockWoodPlanks: OAK_PLANKS");
			bw.newLine();
			bw.write("blockDoor: OAK_DOOR");
			bw.newLine();
			bw.write("blockGlass: GLASS");
			bw.newLine();
			bw.write("blockTorch: TORCH");
			bw.newLine();
			bw.newLine();
			bw.write("# Zombified Piglin repopulation system");
			bw.newLine();
			bw.write("repopEnabled: true");
			bw.newLine();
			bw.write("repopCheckIntervalTicks: 1200");
			bw.newLine();
			bw.write("repopChanceDivisor: 6");
			bw.newLine();
			bw.write("repopSearchRadius: 12");
			bw.newLine();
			bw.write("repopLogSpawns: true");
			bw.newLine();
		} catch (Exception e) {
			getLogger().warning("Failed to create default config.yml: " + e.getMessage());
		}
	}

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

    private final List<HouseRecord> houseRecords = new ArrayList<>();
    private final Set<String> houseRecordKeys = new HashSet<>();
    private File housesFile;
    private File spawnLogFile;

    @Override
    public void onEnable() {
        ensureConfigOnDisk();
		reloadConfig();
		loadConfigValues(getConfig());

        // Setup data files
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        housesFile = new File(getDataFolder(), "houses.txt");
        spawnLogFile = new File(getDataFolder(), "pigzombie_spawns.txt");

        loadHouseRegistry();

        // Attach to already-loaded worlds
        for (World world : getServer().getWorlds()) {
            tryAttachToWorld(world);
        }

        // Listen for new worlds loading
        getServer().getPluginManager().registerEvents(this, this);

        // Start repop task
        startRepopTask();

        getLogger().info("Enabled. Loaded houses: " + houseRecords.size());
    }

    @Override
    public void onDisable() {
        if (repopTask != null) {
            repopTask.cancel();
            repopTask = null;
        }
        getLogger().info("Disabled.");
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        tryAttachToWorld(event.getWorld());
    }

    private void tryAttachToWorld(World world) {
        if (world == null) return;

        // Only overworld/other (not nether)
        if (world.getEnvironment() == World.Environment.NETHER) return;

        // allowedWorlds empty => all non-nether
        if (!allowedWorldNamesLower.isEmpty()) {
            String worldNameLower = world.getName().toLowerCase(Locale.ROOT);
            if (!allowedWorldNamesLower.contains(worldNameLower)) return;
        }

        if (!world.getPopulators().contains(populator)) {
            world.getPopulators().add(populator);
            getLogger().info("Generation enabled in world: " + world.getName());
        }
    }

    // ----------------------------
    // Config I/O
    // ----------------------------

    private void loadConfigValues(FileConfiguration cfg) {
        allowedWorldNamesLower.clear();

        List<String> allowed = cfg.getStringList("allowedWorlds");
        if (allowed != null) {
            for (String w : allowed) {
                if (w == null) continue;
                String trimmed = w.trim();
                if (trimmed.isEmpty()) continue;
                allowedWorldNamesLower.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }

        chanceDivisor = clampInt(cfg.getInt("chanceDivisor", chanceDivisor), 1, Integer.MAX_VALUE);
        attemptsPerChunk = clampInt(cfg.getInt("attemptsPerChunk", attemptsPerChunk), 1, 256);

        spawnPigZombie = cfg.getBoolean("spawnPigZombie", spawnPigZombie);

        matCobble = parseMaterial(cfg.getString("blockCobblestone", matCobble.name()), matCobble);
        matMossy = parseMaterial(cfg.getString("blockMossyCobblestone", matMossy.name()), matMossy);
        matWood  = parseMaterial(cfg.getString("blockWoodPlanks", matWood.name()), matWood);
        matDoor  = parseMaterial(cfg.getString("blockDoor", matDoor.name()), matDoor);
        matGlass = parseMaterial(cfg.getString("blockGlass", matGlass.name()), matGlass);
        matTorch = parseMaterial(cfg.getString("blockTorch", matTorch.name()), matTorch);

        repopEnabled = cfg.getBoolean("repopEnabled", repopEnabled);
        repopCheckIntervalTicks = clampInt(cfg.getInt("repopCheckIntervalTicks", repopCheckIntervalTicks), 20, Integer.MAX_VALUE);
        repopChanceDivisor = clampInt(cfg.getInt("repopChanceDivisor", repopChanceDivisor), 1, Integer.MAX_VALUE);
        repopSearchRadius = clampInt(cfg.getInt("repopSearchRadius", repopSearchRadius), 1, 512);
        repopLogSpawns = cfg.getBoolean("repopLogSpawns", repopLogSpawns);
    }

    private int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.trim(), true);
        return (m != null) ? m : fallback;
    }

    // ----------------------------
    // Registry I/O
    // ----------------------------

    private void loadHouseRegistry() {
        if (housesFile == null) return;
        if (!housesFile.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(housesFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                HouseRecord rec = HouseRecord.fromLine(line);
                if (rec == null) continue;

                String key = rec.key();
                if (houseRecordKeys.contains(key)) continue;

                houseRecordKeys.add(key);
                houseRecords.add(rec);
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to read houses.txt: " + t.getMessage());
        }
    }

    private void appendHouseRecord(HouseRecord rec) {
        if (rec == null || housesFile == null) return;

        String key = rec.key();
        if (houseRecordKeys.contains(key)) return;

        houseRecordKeys.add(key);
        houseRecords.add(rec);

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(housesFile, true))) {
            bw.write(rec.toLine());
            bw.newLine();
        } catch (Throwable t) {
            getLogger().warning("Failed to append to houses.txt: " + t.getMessage());
        }
    }

    private void logSpawn(String msg) {
        if (!repopLogSpawns || spawnLogFile == null) return;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(spawnLogFile, true))) {
            bw.write(msg);
            bw.newLine();
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------
    // Repopulation task
    // ----------------------------

    private void startRepopTask() {
        if (!repopEnabled) return;

        repopTask = getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                runRepopPass();
            } catch (Throwable t) {
                // Keep task alive; just log once per exception occurrence.
                getLogger().warning("Repop pass error: " + t.getMessage());
            }
        }, repopCheckIntervalTicks, repopCheckIntervalTicks);
    }

    private void runRepopPass() {
        if (!repopEnabled) return;
        if (houseRecords.isEmpty()) return;

        int maxPerPass = 200;
        int spawnedThisPass = 0;

        // Reuse one RNG per pass (the beta plugin re-created Random frequently; functionally this is close enough)
        Random r = new Random();

        for (int i = 0; i < houseRecords.size(); i++) {
            HouseRecord rec = houseRecords.get(i);

            World world = getServer().getWorld(rec.worldName);
            if (world == null) continue;
            if (world.getEnvironment() == World.Environment.NETHER) continue;

            int chunkX = rec.x >> 4;
            int chunkZ = rec.z >> 4;

            if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

            if (hasPigZombieNearby(world, rec.x, rec.y, rec.z, repopSearchRadius)) continue;

            if (r.nextInt(repopChanceDivisor) != 0) continue;

            trySpawnPigZombie(world, rec.x, rec.y, rec.z);

            spawnedThisPass++;
            if (spawnedThisPass >= maxPerPass) break;
        }
    }

    private boolean hasPigZombieNearby(World world, int x, int y, int z, int radius) {
        double rx = radius;
        double ry = radius;
        double rz = radius;

        Location center = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        Collection<Entity> nearby = world.getNearbyEntities(center, rx, ry, rz, (e) -> e != null && e.getType() == EntityType.ZOMBIFIED_PIGLIN);

        return nearby != null && !nearby.isEmpty();
    }

    private void trySpawnPigZombie(World world, int x, int y, int z) {
        Location loc = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        Entity ent = world.spawnEntity(loc, EntityType.ZOMBIFIED_PIGLIN);

        if (ent instanceof LivingEntity living) {
            try {
                living.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
            }
        }

        logSpawn("Spawned ZombifiedPiglin at " + world.getName() + " " + x + " " + y + " " + z);
    }

    // ----------------------------
    // Populator (pk system)
    // ----------------------------

    private final class VillageHousePopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random ignoredRandom, Chunk chunk) {
            if (world.getEnvironment() == World.Environment.NETHER) return;

            // pk RNG seeding (same math as original)
            long worldSeed = world.getSeed();
            Random rand = new Random(worldSeed);

            long oddA = (rand.nextLong() / 2L) * 2L + 1L;
            long oddB = (rand.nextLong() / 2L) * 2L + 1L;

            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            long chunkSeed = ((long) chunkX * oddA + (long) chunkZ * oddB) ^ worldSeed;
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
    // HouseFeature-like logic (faithful port + glass spacing/door safety)
    // ----------------------------

    private boolean placeHouseFeature(World world, Random random, int x, int y, int z) {
        while (y > world.getMinHeight() + 1 && !isBlocksMotion(world, x, y - 1, z)) {
            y--;
        }
        if (y <= world.getMinHeight() + 2) return false;

        int w = random.nextInt(7) + 7;
        int h = 4 + random.nextInt(3) / 2;
        int d = random.nextInt(7) + 7;

        int x0 = x - w / 2;
        int y0 = y;
        int z0 = z - d / 2;

        int doorSide = random.nextInt(4);
        if (doorSide < 2) d += 2;
        else w += 2;

        // collision / suitability check (same intent as beta version)
        for (int xx = x0; xx < x0 + w; xx++) {
            for (int zz = z0; zz < z0 + d; zz++) {
                if (!isBlocksMotion(world, xx, y - 1, zz)) return false;
                if (isIce(world, xx, y - 1, zz)) return false;

                boolean ok = false;
                if (doorSide == 0 && xx < x0 + 2) ok = true;
                if (doorSide == 1 && xx > x0 + w - 1 - 2) ok = true;
                if (doorSide == 2 && zz < z0 + 2) ok = true;
                if (doorSide == 3 && zz > z0 + d - 1 - 2) ok = true;

                Material t = world.getBlockAt(xx, y, zz).getType();

                if (ok) {
                    if (t != Material.AIR) return false;
                } else {
                    if (t == matCobble || t == matMossy) return false;
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

        // build shell + roof
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
                    Material material = null;

                    if (yy == y0 + h - 1) {
                        material = matWood;
                    } else if (xx >= xx0 && xx <= xx1 && zz >= zz0 && zz <= zz1) {
                        material = Material.AIR;
                        if (yy == y0 - 1 || yy == y0 + h - 1 || xx == xx0 || zz == zz0 || xx == xx1 || zz == zz1) {
                            if (yy <= y0 + random.nextInt(3)) material = matMossy;
                            else material = matCobble;
                        }
                    }

                    if (material != null) {
                        world.getBlockAt(xx, yy, zz).setType(material, false);
                    }
                }

                h = ho;
            }
        }

        // Door placement
        int doorX = x0 + random.nextInt(Math.max(1, w - 4)) + 2;
        int doorZ = z0 + random.nextInt(Math.max(1, d - 4)) + 2;
        if (doorSide == 0) doorX = x0;
        if (doorSide == 1) doorX = x0 + w - 1;
        if (doorSide == 2) doorZ = z0;
        if (doorSide == 3) doorZ = z0 + d - 1;

        world.getBlockAt(doorX, y0, doorZ).setType(Material.AIR, false);
        world.getBlockAt(doorX, y0 + 1, doorZ).setType(Material.AIR, false);

        int dir = 0;
        if (doorSide == 0) dir = 0;
        if (doorSide == 2) dir = 1;
        if (doorSide == 1) dir = 2;
        if (doorSide == 3) dir = 3;

        placeDoorLikeDoorItem(world, doorX, y0, doorZ, dir);

        // Glass placement (safe + spacing)
        for (int i = 0; i < (w * 2 + d * 2) * 3; i++) {
            int gx = x0 + random.nextInt(Math.max(1, w - 4)) + 2;
            int gz = z0 + random.nextInt(Math.max(1, d - 4)) + 2;
            int side = random.nextInt(4);

            if (side == 0) gx = xx0;
            if (side == 1) gx = xx1;
            if (side == 2) gz = zz0;
            if (side == 3) gz = zz1;

            Block target = world.getBlockAt(gx, y0 + 1, gz);
            Block below = world.getBlockAt(gx, y0, gz);

            if (target.getType() == matDoor || below.getType() == matDoor) continue;
            if (!isSolidBlockingTile(world, gx, y0 + 1, gz)) continue;

            boolean onXWall = (gx == xx0 || gx == xx1);
            boolean onZWall = (gz == zz0 || gz == zz1);

            if (onXWall) {
                Material left = world.getBlockAt(gx, y0 + 1, gz - 1).getType();
                Material right = world.getBlockAt(gx, y0 + 1, gz + 1).getType();
                if (left == matGlass || left == matDoor) continue;
                if (right == matGlass || right == matDoor) continue;
            } else if (onZWall) {
                Material left = world.getBlockAt(gx - 1, y0 + 1, gz).getType();
                Material right = world.getBlockAt(gx + 1, y0 + 1, gz).getType();
                if (left == matGlass || left == matDoor) continue;
                if (right == matGlass || right == matDoor) continue;
            }

            int count = 0;
            if (isSolidBlockingTile(world, gx - 1, y0 + 1, gz) && isSolidBlockingTile(world, gx + 1, y0 + 1, gz)) count++;
            if (isSolidBlockingTile(world, gx, y0 + 1, gz - 1) && isSolidBlockingTile(world, gx, y0 + 1, gz + 1)) count++;

            if (count == 1) {
                target.setType(matGlass, false);
            }
        }

        // Torch placement (interior)
        int ww = xx1 - xx0;
        int dd = zz1 - zz0;
        for (int i = 0; i < (ww * 2 + dd * 2); i++) {
            int tx = xx0 + random.nextInt(Math.max(1, ww - 1)) + 1;
            int tz = zz0 + random.nextInt(Math.max(1, dd - 1)) + 1;

            Block air = world.getBlockAt(tx, y0 + 2, tz);
            if (air.getType() == Material.AIR) {
                int count = 0;
                if (isSolidBlockingTile(world, tx - 1, y0 + 2, tz)) count++;
                if (isSolidBlockingTile(world, tx + 1, y0 + 2, tz)) count++;
                if (isSolidBlockingTile(world, tx, y0 + 2, tz - 1)) count++;
                if (isSolidBlockingTile(world, tx, y0 + 2, tz + 1)) count++;

                if (count == 1) {
                    air.setType(matTorch, false);
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
    // Door placement (DoorItem::place analogue)
    // ----------------------------

    private void placeDoorLikeDoorItem(World world, int x, int y, int z, int dir) {
        // Match the beta math: dir -> local dx/dz
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

        boolean leftDoor = (world.getBlockAt(x - dx, y, z - dz).getType() == matDoor)
                || (world.getBlockAt(x - dx, y + 1, z - dz).getType() == matDoor);

        boolean rightDoor = (world.getBlockAt(x + dx, y, z + dz).getType() == matDoor)
                || (world.getBlockAt(x + dx, y + 1, z + dz).getType() == matDoor);

        boolean flip = false;
        if (leftDoor && !rightDoor) flip = true;
        else if (rightSolid > leftSolid) flip = true;

        // In modern, flipping is best represented as a hinge side change.
        Hinge hinge = flip ? Hinge.RIGHT : Hinge.LEFT;

        BlockFace facing = switch (dir & 3) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };

        Block lower = world.getBlockAt(x, y, z);
        Block upper = world.getBlockAt(x, y + 1, z);

        lower.setType(matDoor, false);
        upper.setType(matDoor, false);

        BlockData lowerData = lower.getBlockData();
        BlockData upperData = upper.getBlockData();

        if (lowerData instanceof Door ld && upperData instanceof Door ud) {
            ld.setFacing(facing);
            ld.setHalf(Bisected.Half.BOTTOM);
            ld.setHinge(hinge);
            ld.setOpen(false);
            ld.setPowered(false);

            ud.setFacing(facing);
            ud.setHalf(Bisected.Half.TOP);
            ud.setHinge(hinge);
            ud.setOpen(false);
            ud.setPowered(false);

            lower.setBlockData(ld, false);
            upper.setBlockData(ud, false);
        }
    }

    // ----------------------------
    // Material helpers approximating Level/Material calls
    // ----------------------------

    private boolean isBlocksMotion(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        if (m == Material.AIR) return false;
        if (m == Material.WATER || m == Material.LAVA) return false;
        if (m == Material.KELP || m == Material.KELP_PLANT) return false;
        // In beta, many non-solid blocks still counted as "not motion"; but the plugin was coarse.
        return m.isSolid();
    }

    private boolean isIce(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    private boolean isSolidBlockingTile(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        if (m == Material.AIR) return false;
        if (m == Material.WATER || m == Material.LAVA) return false;
        return m.isSolid();
    }
}
