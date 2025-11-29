import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class IndevHellGen extends JavaPlugin {

    // Names of worlds that are using this generator.
    private static final Set<String> hellWorlds = new HashSet<String>();

    // Mob spawn tuning (more reasonable / vanilla-like)
    private static final int MOB_SCAN_RADIUS = 80;
    private static final int MAX_LIVING_NEARBY = 50;
    private static final int SPAWN_ATTEMPTS_PER_PLAYER = 4; // was 3
    private static final int SPAWN_RADIUS = 40;
    private static final int MIN_SPAWN_DISTANCE = 24;

    private final Random spawnRandom = new Random();

    @Override
    public void onEnable() {
        // Old Bukkit on Poseidon doesn't have JavaPlugin.getLogger(), so use stdout.
        System.out.println("[IndevHellGen] Enabled.");

        // Periodic task to handle custom spawning in Indev Hell worlds.
        // Runs every 10 seconds.
        getServer().getScheduler().scheduleSyncRepeatingTask(
            this,
            new Runnable() {
                public void run() {
                    performHellWorldSpawns();
                }
            },
            20L * 10L,
            20L * 10L
        );
    }

    @Override
    public void onDisable() {
        System.out.println("[IndevHellGen] Disabled.");
    }

    /**
     * Multiverse / world creator entry point.
     * Usage (Multiverse):
     *   /mv create indevhell nether -g IndevHellGen
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        synchronized (hellWorlds) {
            hellWorlds.add(worldName);
        }
        return new IndevHellChunkGenerator();
    }

    /**
     * Periodic spawn logic for Indev Hell worlds:
     * - Passive mobs on grass/dirt regardless of light level.
     * - Overworld-style hostiles as the main danger.
     * - Pig zombies / ghasts / Monster are rarer.
     * - Giants extremely rare, spider jockeys rare.
     */
    private void performHellWorldSpawns() {
        Set<String> worldNamesCopy;
        synchronized (hellWorlds) {
            worldNamesCopy = new HashSet<String>(hellWorlds);
        }

        for (World world : getServer().getWorlds()) {
            if (!worldNamesCopy.contains(world.getName())) {
                continue;
            }

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }

            for (Player player : players) {
                if (player.isDead()) {
                    continue;
                }

                Location playerLoc = player.getLocation();

                int nearbyLiving = countNearbyLiving(world, playerLoc, MOB_SCAN_RADIUS);
                if (nearbyLiving >= MAX_LIVING_NEARBY) {
                    continue;
                }

                for (int i = 0; i < SPAWN_ATTEMPTS_PER_PLAYER; i++) {
                    trySpawnMobNearPlayer(world, playerLoc);
                }
            }
        }
    }

    private int countNearbyLiving(World world, Location center, double radius) {
        double radiusSq = radius * radius;
        int count = 0;

        for (Entity e : world.getEntities()) {
            if (!(e instanceof LivingEntity)) {
                continue;
            }
            if (e.getLocation().distanceSquared(center) <= radiusSq) {
                count++;
            }
        }

        return count;
    }

    private void trySpawnMobNearPlayer(World world, Location playerLoc) {
        int offsetX = spawnRandom.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;
        int offsetZ = spawnRandom.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;

        // Do not spawn too close to the player
        if (Math.abs(offsetX) < MIN_SPAWN_DISTANCE && Math.abs(offsetZ) < MIN_SPAWN_DISTANCE) {
            return;
        }

        int spawnX = playerLoc.getBlockX() + offsetX;
        int spawnZ = playerLoc.getBlockZ() + offsetZ;

        int surfaceY = findTopSolidY(world, spawnX, spawnZ);
        if (surfaceY <= 2) {
            return;
        }

        Block ground = world.getBlockAt(spawnX, surfaceY, spawnZ);
        Material groundType = ground.getType();

        // Only consider grass or dirt as spawn bases
        if (groundType != Material.GRASS && groundType != Material.DIRT) {
            return;
        }

        Block above1 = world.getBlockAt(spawnX, surfaceY + 1, spawnZ);
        Block above2 = world.getBlockAt(spawnX, surfaceY + 2, spawnZ);

        if (above1.getType() != Material.AIR || above2.getType() != Material.AIR) {
            return;
        }

        Location spawnLoc = new Location(world, spawnX + 0.5, surfaceY + 1, spawnZ + 0.5);

        int roll = spawnRandom.nextInt(100);

        // 70% of our custom spawns are passive, 30% hostile
        if (roll < 70) {
            CreatureType passiveType = pickPassiveCreature();
            world.spawnCreature(spawnLoc, passiveType);
            return;
        }

        // Hostile / special mobs (30% of attempts only)

        // Giants: extremely rare (about 0.05% of hostile attempts)
        if (spawnRandom.nextInt(2000) == 0) {
            try {
                world.spawnCreature(spawnLoc, CreatureType.GIANT);
            } catch (Throwable t) {
                // Ignore if GIANT not supported
            }
            return;
        }

        // Spider jockeys: rare (~0.25% of hostile attempts)
        if (spawnRandom.nextInt(400) == 0) {
            spawnSpiderJockey(world, spawnLoc);
            return;
        }

        CreatureType hostileType = pickHellCreature(surfaceY);
        if (hostileType == null) {
            return;
        }

        // Make ghasts feel further away and higher up
        if (hostileType == CreatureType.GHAST) {
            // Require fairly high surface and large horizontal distance
            if (surfaceY < 60) {
                return;
            }
            if (Math.abs(offsetX) < 32 && Math.abs(offsetZ) < 32) {
                return;
            }

            Location ghastLoc = new Location(world, spawnLoc.getX(), surfaceY + 10, spawnLoc.getZ());
            world.spawnCreature(ghastLoc, hostileType);
        } else {
            world.spawnCreature(spawnLoc, hostileType);
        }
    }

    private void spawnSpiderJockey(World world, Location loc) {
        try {
            Spider spider = (Spider) world.spawnCreature(loc, CreatureType.SPIDER);
            Skeleton skel = (Skeleton) world.spawnCreature(loc, CreatureType.SKELETON);
            spider.setPassenger(skel);
        } catch (Throwable t) {
            // If anything goes wrong, fall back to a single spider
            world.spawnCreature(loc, CreatureType.SPIDER);
        }
    }

    private CreatureType pickPassiveCreature() {
        int r = spawnRandom.nextInt(100);
        if (r < 30) {
            return CreatureType.COW;
        } else if (r < 60) {
            return CreatureType.PIG;
        } else if (r < 80) {
            return CreatureType.SHEEP;
        } else if (r < 90) {
            return CreatureType.CHICKEN;
        } else {
            // Slightly rarer wolves
            return CreatureType.WOLF;
        }
    }

    private CreatureType pickHellCreature(int surfaceY) {
        int r = spawnRandom.nextInt(100);

        // Overworld-style hostiles dominate:
        // 35% zombie, 30% skeleton, 15% spider, 10% creeper, 5% slime = 95% total
        if (r < 35) {
            return CreatureType.ZOMBIE;
        } else if (r < 65) { // +30
            return CreatureType.SKELETON;
        } else if (r < 80) { // +15
            return CreatureType.SPIDER;
        } else if (r < 90) { // +10
            return CreatureType.CREEPER;
        } else if (r < 95) { // +5
            return CreatureType.SLIME;
        }

        // Remaining 5%: "Hell specials"
        // We keep these low because Nether env already spawns pigmen/ghasts.
        int special = spawnRandom.nextInt(100);
        if (special < 40) {
            // ~2% of all hostiles = Monster; drops handled in your other plugin
            return CreatureType.MONSTER;
        } else if (special < 80) {
            // ~2% pig zombies from our spawner; Nether env will add more naturally
            return CreatureType.PIG_ZOMBIE;
        } else {
            // ~1% ghasts from our spawner, only if there's room; env will also spawn them
            if (surfaceY > 50) {
                return CreatureType.GHAST;
            } else {
                return CreatureType.PIG_ZOMBIE;
            }
        }
    }

    private int findTopSolidY(World world, int x, int z) {
        for (int y = 127; y > 0; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type != Material.AIR && type != Material.LEAVES) {
                return y;
            }
        }
        return 0;
    }

    /**
     * Indev-style Hell chunk generator:
     * - Bedrock at y=0
     * - Thin lava floor (y=1–2)
     * - Normal underground stone, caves, ores, dungeons
     * - Lava at sea level (y=64) instead of water, between islands
     * - Top layer is mostly dirt; grass will be added only near lava shorelines.
     */
    public static class IndevHellChunkGenerator extends ChunkGenerator {

        private static final int WORLD_HEIGHT = 128;
        private static final int LAVA_FLOOR_TOP = 2;
        private static final int SEA_LEVEL = 64;

        private final byte airId      = 0;
        private final byte bedrockId  = (byte) Material.BEDROCK.getId();
        private final byte stoneId    = (byte) Material.STONE.getId();
        private final byte dirtId     = (byte) Material.DIRT.getId();
        private final byte grassId    = (byte) Material.GRASS.getId();
        private final byte lavaId     = (byte) Material.STATIONARY_LAVA.getId();

        @Override
        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];

            long seed = world.getSeed();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;

                    double heightNoise = getHeightNoise(worldX, worldZ, seed);
                    int terrainHeight = (int) (SEA_LEVEL + heightNoise);

                    if (terrainHeight < 40) {
                        terrainHeight = 40;
                    }
                    if (terrainHeight > WORLD_HEIGHT - 5) {
                        terrainHeight = WORLD_HEIGHT - 5;
                    }

                    // y = 0: bedrock
                    setBlock(blocks, x, 0, z, bedrockId);

                    // y = 1..2: thin lava floor
                    for (int y = 1; y <= LAVA_FLOOR_TOP; y++) {
                        setBlock(blocks, x, y, z, lavaId);
                    }

                    // y = 3..terrainHeight: stone mass
                    for (int y = 3; y <= terrainHeight; y++) {
                        setBlock(blocks, x, y, z, stoneId);
                    }

                    // Caves / crevices in the interior stone
                    for (int y = 8; y <= terrainHeight - 4; y++) {
                        double caveNoise = getCaveNoise(worldX, y, worldZ, seed);
                        if (caveNoise > 1.9) {
                            setBlock(blocks, x, y, z, airId);
                        }
                    }

                    // Top skin: convert top few solid stone blocks into dirt (no automatic grass)
                    int dirtPlaced = 0;
                    for (int y = terrainHeight; y >= 3 && dirtPlaced < 4; y--) {
                        byte current = getBlock(blocks, x, y, z);
                        if (current == stoneId) {
                            // All top skin is dirt; grass will be added later at lava shorelines
                            setBlock(blocks, x, y, z, dirtId);
                            dirtPlaced++;
                        } else if (current != airId) {
                            // Hit something else solid (lava, etc.) – stop
                            break;
                        }
                    }

                    // Lava "ocean" at sea level: fill from terrainHeight+1 up to SEA_LEVEL
                    if (terrainHeight < SEA_LEVEL) {
                        for (int y = terrainHeight + 1; y <= SEA_LEVEL; y++) {
                            setBlock(blocks, x, y, z, lavaId);
                        }
                    }
                }
            }

            return blocks;
        }

        /**
         * Terrain height noise: islands, lava seas, cliffs, hills.
         */
        private double getHeightNoise(int worldX, int worldZ, long seed) {
            double x = worldX / 40.0;
            double z = worldZ / 40.0;

            // Large-scale variation: continents / big hills
            double h1 = Math.sin(x * 0.7 + seed * 0.000002) * 10.0
                      + Math.cos(z * 0.7 - seed * 0.000002) * 10.0;

            // Medium-scale bumps
            double h2 = Math.sin(x * 1.7 + z * 0.6) * 6.0
                      + Math.cos(z * 1.3 - x * 0.4) * 4.0;

            // Fine detail
            double h3 = Math.sin(x * 3.5 + z * 3.5 + seed * 0.000005) * 3.0;

            return h1 + h2 + h3; // roughly -23..+23
        }

        /**
         * Simple 3D cave noise: when this exceeds a threshold we carve air.
         */
        private double getCaveNoise(int worldX, int worldY, int worldZ, long seed) {
            double x = worldX / 18.0;
            double y = worldY / 14.0;
            double z = worldZ / 18.0;

            double c1 = Math.sin(x + seed * 0.00001) + Math.cos(z - seed * 0.00002);
            double c2 = Math.sin(y * 1.5 + x * 0.5) + Math.cos(y * 1.2 - z * 0.3);
            return c1 + c2;
        }

        private void setBlock(byte[] blocks, int x, int y, int z, byte id) {
            if (y < 0 || y >= WORLD_HEIGHT) {
                return;
            }
            int index = (x * 16 + z) * WORLD_HEIGHT + y;
            blocks[index] = id;
        }

        private byte getBlock(byte[] blocks, int x, int y, int z) {
            if (y < 0 || y >= WORLD_HEIGHT) {
                return 0;
            }
            int index = (x * 16 + z) * WORLD_HEIGHT + y;
            return blocks[index];
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> list = new ArrayList<BlockPopulator>();
            // Order: dungeons -> ores -> lava lakes -> trees -> surface decor -> houses
            list.add(new IndevHellDungeonPopulator());
            list.add(new IndevHellOrePopulator());
            list.add(new IndevHellLavaLakePopulator());
            list.add(new IndevHellTreePopulator());
            list.add(new IndevHellSurfacePopulator());
            list.add(new IndevHellHousePopulator());
            return list;
        }
    }

    /**
     * Ore generation populator.
     * Replaces stone with ore veins at roughly vanilla heights.
     */
    public static class IndevHellOrePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            // Coal: plenty, y 0-128
            generateOre(chunk, random, Material.COAL_ORE, 20, 0, 128, 16);

            // Iron: common, y 0-64
            generateOre(chunk, random, Material.IRON_ORE, 20, 0, 64, 8);

            // Gold: rare, y 0-32
            generateOre(chunk, random, Material.GOLD_ORE, 2, 0, 32, 8);

            // Redstone: rare, y 0-16
            generateOre(chunk, random, Material.REDSTONE_ORE, 8, 0, 16, 7);

            // Diamond: very rare, y 0-16
            generateOre(chunk, random, Material.DIAMOND_ORE, 1, 0, 16, 7);

            // Lapis: rare, y 0-32
            generateOre(chunk, random, Material.LAPIS_ORE, 1, 0, 32, 6);
        }

        private void generateOre(Chunk chunk, Random random, Material oreType,
                                 int veinCount, int minY, int maxY, int veinSize) {
            if (maxY > WORLD_HEIGHT) {
                maxY = WORLD_HEIGHT;
            }
            if (minY < 0) {
                minY = 0;
            }
            if (maxY <= minY) {
                return;
            }

            for (int i = 0; i < veinCount; i++) {
                int x = random.nextInt(16);
                int y = minY + random.nextInt(maxY - minY);
                int z = random.nextInt(16);

                carveVein(chunk, random, x, y, z, oreType, veinSize);
            }
        }

        private void carveVein(Chunk chunk, Random random, int startX, int startY, int startZ,
                               Material oreType, int veinSize) {
            int x = startX;
            int y = startY;
            int z = startZ;

            for (int i = 0; i < veinSize; i++) {
                if (y < 0 || y >= WORLD_HEIGHT) {
                    break;
                }

                Block block = chunk.getBlock(x, y, z);
                if (block.getType() == Material.STONE) {
                    block.setType(oreType);
                }

                // Random walk
                x += random.nextInt(3) - 1;
                y += random.nextInt(3) - 1;
                z += random.nextInt(3) - 1;

                if (x < 0 || x > 15 || z < 0 || z > 15) {
                    break;
                }
            }
        }
    }

    /**
     * Lava lakes / puddles on the surface.
     * Carves a shallow bowl and fills with lava, with sand/gravel/grass shores.
     */
    public static class IndevHellLavaLakePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            // Roughly 1 in 5 chunks gets a lake
            if (random.nextInt(5) != 0) {
                return;
            }

            int baseX = chunk.getX() * 16;
            int baseZ = chunk.getZ() * 16;

            int centerX = baseX + 4 + random.nextInt(8);  // keep away from chunk edges
            int centerZ = baseZ + 4 + random.nextInt(8);

            int surfaceY = findSurfaceY(world, centerX, centerZ);
            if (surfaceY <= 5 || surfaceY >= WORLD_HEIGHT - 4) {
                return;
            }

            int radius = 2 + random.nextInt(3); // 2–4 block radius

            // Carve bowl and fill with lava
            for (int dx = -radius - 1; dx <= radius + 1; dx++) {
                for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    int worldX = centerX + dx;
                    int worldZ = centerZ + dz;

                    if (dist <= radius + 0.5) {
                        // Carve 2-deep depression
                        for (int dy = 0; dy <= 2; dy++) {
                            int y = surfaceY - dy;
                            if (y <= 3) {
                                continue;
                            }
                            Block block = world.getBlockAt(worldX, y, worldZ);
                            if (dy == 0) {
                                block.setType(Material.STATIONARY_LAVA);
                            } else {
                                block.setType(Material.AIR);
                            }
                        }
                    }
                }
            }

            // Shoreline: sand / gravel / grass around the rim
            for (int dx = -radius - 2; dx <= radius + 2; dx++) {
                for (int dz = -radius - 2; dz <= radius + 2; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < radius + 0.8 || dist > radius + 2.0) {
                        continue;
                    }

                    int worldX = centerX + dx;
                    int worldZ = centerZ + dz;
                    int y = findSurfaceY(world, worldX, worldZ);
                    if (y <= 3 || y >= WORLD_HEIGHT - 1) {
                        continue;
                    }

                    Block block = world.getBlockAt(worldX, y, worldZ);
                    Material type = block.getType();
                    if (type != Material.STONE && type != Material.DIRT) {
                        continue;
                    }

                    int r = random.nextInt(100);
                    if (r < 40) {
                        block.setType(Material.SAND);
                    } else if (r < 80) {
                        block.setType(Material.GRAVEL);
                    } else {
                        // some grassy shoreline right next to lava lakes
                        block.setType(Material.GRASS);
                    }
                }
            }
        }

        private int findSurfaceY(World world, int x, int z) {
            for (int y = WORLD_HEIGHT - 1; y > 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES) {
                    return y;
                }
            }
            return 0;
        }
    }

    /**
     * Tree populator: attempts to more closely resemble normal oak tree shapes.
     */
    public static class IndevHellTreePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int trees = 2 + random.nextInt(4); // 2–5 trees per chunk

            for (int i = 0; i < trees; i++) {
                int localX = random.nextInt(16);
                int localZ = random.nextInt(16);

                int worldX = chunk.getX() * 16 + localX;
                int worldZ = chunk.getZ() * 16 + localZ;

                int surfaceY = findSurfaceY(world, worldX, worldZ);
                if (surfaceY <= 5 || surfaceY >= WORLD_HEIGHT - 10) {
                    continue;
                }

                Block ground = world.getBlockAt(worldX, surfaceY, worldZ);
                if (ground.getType() != Material.GRASS && ground.getType() != Material.DIRT) {
                    continue;
                }

                generateOakLikeTree(world, random, worldX, surfaceY + 1, worldZ);
            }
        }

        private int findSurfaceY(World world, int worldX, int worldZ) {
            for (int y = WORLD_HEIGHT - 1; y > 0; y--) {
                Material type = world.getBlockAt(worldX, y, worldZ).getType();
                if (type != Material.AIR && type != Material.LEAVES) {
                    return y;
                }
            }
            return 0;
        }

        // Simple oak-like tree: straight trunk, 3-layer leaf canopy
        private void generateOakLikeTree(World world, Random random, int x, int y, int z) {
            int trunkHeight = 4 + random.nextInt(2); // 4–5 high trunk

            // Trunk
            for (int i = 0; i < trunkHeight; i++) {
                Block block = world.getBlockAt(x, y + i, z);
                Material type = block.getType();
                if (type == Material.AIR || type == Material.LEAVES) {
                    block.setType(Material.LOG);
                }
            }

            int topY = y + trunkHeight;

            // Leaf layers:
            // top layer: +1 above trunk, radius 1
            // middle layer: at trunk top, radius 2
            // lower layer: one below, radius 1
            placeLeafLayer(world, x, topY + 1, z, 1);
            placeLeafLayer(world, x, topY, z, 2);
            placeLeafLayer(world, x, topY - 1, z, 1);
        }

        private void placeLeafLayer(World world, int centerX, int y, int centerZ, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int dist = Math.abs(dx) + Math.abs(dz);
                    if (dist > radius + 1) {
                        continue;
                    }

                    Block block = world.getBlockAt(centerX + dx, y, centerZ + dz);
                    Material type = block.getType();
                    if (type == Material.AIR) {
                        block.setType(Material.LEAVES);
                    }
                }
            }
        }
    }

    /**
     * Surface decoration:
     * - Mushrooms and flowers on grass/dirt.
     * - Sand, gravel, and some grass near lava shorelines at sea level.
     */
    public static class IndevHellSurfacePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;
        private static final int SEA_LEVEL = 64;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int baseX = chunk.getX() * 16;
            int baseZ = chunk.getZ() * 16;

            // Decorate grass/dirt surfaces with mushrooms and flowers
            int decorTries = 10 + random.nextInt(10); // 10–19 attempts
            for (int i = 0; i < decorTries; i++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);

                int surfaceY = findSurfaceY(world, worldX, worldZ);
                if (surfaceY <= 5 || surfaceY >= WORLD_HEIGHT - 2) {
                    continue;
                }

                Block ground = world.getBlockAt(worldX, surfaceY, worldZ);
                Block above = world.getBlockAt(worldX, surfaceY + 1, worldZ);

                if ((ground.getType() != Material.GRASS && ground.getType() != Material.DIRT)
                        || above.getType() != Material.AIR) {
                    continue;
                }

                int r = random.nextInt(100);

                if (r < 25) {
                    // Mushrooms
                    if (random.nextBoolean()) {
                        above.setType(Material.RED_MUSHROOM);
                    } else {
                        above.setType(Material.BROWN_MUSHROOM);
                    }
                } else if (r < 40) {
                    // Flowers
                    if (random.nextBoolean()) {
                        above.setType(Material.YELLOW_FLOWER);
                    } else {
                        above.setType(Material.RED_ROSE);
                    }
                }
            }

            // Sand / gravel / grass along the main lava ocean shoreline at sea level
            int shoreTries = 24;
            for (int i = 0; i < shoreTries; i++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);

                int y = SEA_LEVEL + random.nextInt(3) - 1; // around sea level
                if (y <= 3 || y >= WORLD_HEIGHT) {
                    continue;
                }

                Block block = world.getBlockAt(worldX, y, worldZ);
                Material type = block.getType();
                if (type != Material.STONE && type != Material.DIRT) {
                    continue;
                }

                if (!isAdjacentToLava(world, worldX, y, worldZ)) {
                    continue;
                }

                int r = random.nextInt(100);
                if (r < 35) {
                    block.setType(Material.SAND);
                } else if (r < 70) {
                    block.setType(Material.GRAVEL);
                } else {
                    // Grass right on some lava shore edges: classic Indev look
                    block.setType(Material.GRASS);
                }
            }
        }

        private int findSurfaceY(World world, int x, int z) {
            for (int y = WORLD_HEIGHT - 1; y > 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES) {
                    return y;
                }
            }
            return 0;
        }

        private boolean isAdjacentToLava(World world, int x, int y, int z) {
            Material lava = Material.STATIONARY_LAVA;

            if (world.getBlockAt(x + 1, y, z).getType() == lava) return true;
            if (world.getBlockAt(x - 1, y, z).getType() == lava) return true;
            if (world.getBlockAt(x, y, z + 1).getType() == lava) return true;
            if (world.getBlockAt(x, y, z - 1).getType() == lava) return true;

            return false;
        }
    }

    /**
     * Dungeon generator:
     * - Small cobblestone / mossy cobble room in stone.
     * - Zombie spawner in the middle.
     * - Up to two chests with simple loot.
     */
    public static class IndevHellDungeonPopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            // 0–1 dungeons per chunk, low chance overall
            if (random.nextInt(8) != 0) {
                return;
            }

            int baseX = chunk.getX() * 16;
            int baseZ = chunk.getZ() * 16;

            // Pick a random underground position
            int centerX = baseX + 4 + random.nextInt(8);  // 4..11 in chunk
            int centerZ = baseZ + 4 + random.nextInt(8);
            int centerY = 20 + random.nextInt(30);        // 20..49

            if (!isAreaStone(world, centerX, centerY, centerZ, 4, 3, 4)) {
                return;
            }

            carveDungeon(world, random, centerX, centerY, centerZ);
        }

        private boolean isAreaStone(World world, int centerX, int centerY, int centerZ,
                                    int halfX, int halfY, int halfZ) {
            for (int x = centerX - halfX; x <= centerX + halfX; x++) {
                for (int y = centerY - halfY; y <= centerY + halfY; y++) {
                    for (int z = centerZ - halfZ; z <= centerZ + halfZ; z++) {
                        if (y <= 0 || y >= WORLD_HEIGHT) {
                            return false;
                        }
                        Material type = world.getBlockAt(x, y, z).getType();
                        if (type != Material.STONE) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private void carveDungeon(World world, Random random, int centerX, int centerY, int centerZ) {
            // Room size: 7x5x7 (x,z = -3..3, y = 0..4 relative)
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 4; dy++) {
                        int x = centerX + dx;
                        int y = centerY + dy;
                        int z = centerZ + dz;

                        boolean isWall = (dx == -3 || dx == 3 || dz == -3 || dz == 3);
                        boolean isFloor = (dy == 0);
                        boolean isCeiling = (dy == 4);

                        Block block = world.getBlockAt(x, y, z);

                        if (isFloor || isCeiling || isWall) {
                            if (isFloor && random.nextInt(5) == 0) {
                                block.setType(Material.MOSSY_COBBLESTONE);
                            } else {
                                block.setType(Material.COBBLESTONE);
                            }
                        } else {
                            // Interior air
                            block.setType(Material.AIR);
                        }
                    }
                }
            }

            // Spawner in the middle, one block above floor
            Block spawnerBlock = world.getBlockAt(centerX, centerY + 1, centerZ);
            spawnerBlock.setType(Material.MOB_SPAWNER);
            try {
                CreatureSpawner spawner = (CreatureSpawner) spawnerBlock.getState();
                spawner.setCreatureType(CreatureType.ZOMBIE);
                spawner.update();
            } catch (Throwable t) {
                // Ignore if anything weird happens
            }

            // Up to two chests near walls
            placeDungeonChest(world, random, centerX - 2, centerY + 1, centerZ - 2);
            if (random.nextBoolean()) {
                placeDungeonChest(world, random, centerX + 2, centerY + 1, centerZ + 2);
            }
        }

        private void placeDungeonChest(World world, Random random, int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.AIR) {
                return;
            }

            Block below = world.getBlockAt(x, y - 1, z);
            if (below.getType() != Material.COBBLESTONE && below.getType() != Material.MOSSY_COBBLESTONE) {
                return;
            }

            block.setType(Material.CHEST);
            try {
                Chest chest = (Chest) block.getState();
                Inventory inv = chest.getInventory();
                addSimpleLoot(inv, random);
                chest.update();
            } catch (Throwable t) {
                // Fail quietly if Chest class behaves differently
            }
        }

        private void addSimpleLoot(Inventory inv, Random random) {
            int items = 3 + random.nextInt(5);

            for (int i = 0; i < items; i++) {
                ItemStack stack;
                int r = random.nextInt(100);

                if (r < 15) {
                    stack = new ItemStack(Material.SADDLE, 1);
                } else if (r < 30) {
                    stack = new ItemStack(Material.STRING, 1 + random.nextInt(4));
                } else if (r < 45) {
                    stack = new ItemStack(Material.BREAD, 1);
                } else if (r < 60) {
                    stack = new ItemStack(Material.IRON_INGOT, 1 + random.nextInt(3));
                } else if (r < 75) {
                    stack = new ItemStack(Material.REDSTONE, 2 + random.nextInt(4));
                } else if (r < 90) {
                    stack = new ItemStack(Material.WHEAT, 1 + random.nextInt(3));
                } else {
                    stack = new ItemStack(Material.SULPHUR, 1 + random.nextInt(3));
                }

                inv.addItem(stack);
            }
        }
    }

    /**
     * Semi-rare Indev-style houses:
     * - 7x7x7 cube, hollow, 2-block doorway.
     * - Either all mossy cobble, or wood planks with stone floor.
     */
    public static class IndevHellHousePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            // Very roughly 1 house per 30 chunks
            if (random.nextInt(30) != 0) {
                return;
            }

            int baseX = chunk.getX() * 16;
            int baseZ = chunk.getZ() * 16;

            int centerX = baseX + 4 + random.nextInt(8);
            int centerZ = baseZ + 4 + random.nextInt(8);

            int surfaceY = findSurfaceY(world, centerX, centerZ);
            if (surfaceY <= 5 || surfaceY >= WORLD_HEIGHT - 10) {
                return;
            }

            // Check for reasonably flat area
            if (!isAreaMostlySolid(world, centerX, surfaceY, centerZ, 3)) {
                return;
            }

            boolean mossy = random.nextBoolean();
            buildIndevHouse(world, random, centerX, surfaceY + 1, centerZ, mossy);
        }

        private int findSurfaceY(World world, int x, int z) {
            for (int y = WORLD_HEIGHT - 1; y > 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES) {
                    return y;
                }
            }
            return 0;
        }

        private boolean isAreaMostlySolid(World world, int centerX, int surfaceY, int centerZ, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block under = world.getBlockAt(centerX + dx, surfaceY, centerZ + dz);
                    Material t = under.getType();
                    if (t == Material.AIR || t == Material.STATIONARY_LAVA || t == Material.LAVA) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void buildIndevHouse(World world, Random random, int centerX, int baseY, int centerZ, boolean mossy) {
            Material wallMat;
            Material floorMat;

            if (mossy) {
                wallMat = Material.MOSSY_COBBLESTONE;
                floorMat = Material.MOSSY_COBBLESTONE;
            } else {
                wallMat = Material.WOOD;
                floorMat = Material.STONE;
            }

            // Build 7x7x7 cube: x,z = -3..3, y = 0..6 relative
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 6; dy++) {
                        int x = centerX + dx;
                        int y = baseY + dy;
                        int z = centerZ + dz;

                        boolean isWall = (dx == -3 || dx == 3 || dz == -3 || dz == 3);
                        boolean isFloor = (dy == 0);
                        boolean isCeiling = (dy == 6);

                        Block block = world.getBlockAt(x, y, z);

                        if (isFloor) {
                            block.setType(floorMat);
                        } else if (isCeiling || isWall) {
                            block.setType(wallMat);
                        } else {
                            block.setType(Material.AIR);
                        }
                    }
                }
            }

            // 2-block tall doorway on one side (east wall)
            for (int dy = 1; dy <= 2; dy++) {
                Block doorBlock = world.getBlockAt(centerX + 3, baseY + dy, centerZ);
                doorBlock.setType(Material.AIR);
            }

            // Torches inside: four at mid-height on walls
            int torchY = baseY + 3;
            world.getBlockAt(centerX - 2, torchY, centerZ).setType(Material.TORCH);
            world.getBlockAt(centerX + 2, torchY, centerZ).setType(Material.TORCH);
            world.getBlockAt(centerX, torchY, centerZ - 2).setType(Material.TORCH);
            world.getBlockAt(centerX, torchY, centerZ + 2).setType(Material.TORCH);
        }
    }
}
