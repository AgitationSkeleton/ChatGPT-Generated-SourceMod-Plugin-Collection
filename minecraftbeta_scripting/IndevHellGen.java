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

    // Mob spawn tuning
    private static final int MOB_SCAN_RADIUS = 64;
    private static final int MAX_LIVING_NEARBY = 40;
    private static final int SPAWN_ATTEMPTS_PER_PLAYER = 4;
    private static final int SPAWN_RADIUS = 24;
    private static final int MIN_SPAWN_DISTANCE = 8;

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
     * - Passive mobs on dirt regardless of light level.
     * - Occasional pig zombies, ghasts, MONSTER (id 49), giants, and wolves.
     * - Rare spider jockeys.
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

        int spawnX = playerLoc.getBlockX() + offsetX;
        int spawnZ = playerLoc.getBlockZ() + offsetZ;

        // Do not spawn too close to the player
        if (Math.abs(offsetX) < MIN_SPAWN_DISTANCE && Math.abs(offsetZ) < MIN_SPAWN_DISTANCE) {
            return;
        }

        int surfaceY = findTopDirtY(world, spawnX, spawnZ);
        if (surfaceY <= 0) {
            return;
        }

        Block ground = world.getBlockAt(spawnX, surfaceY, spawnZ);
        if (ground.getType() != Material.DIRT) {
            return;
        }

        Block above1 = world.getBlockAt(spawnX, surfaceY + 1, spawnZ);
        Block above2 = world.getBlockAt(spawnX, surfaceY + 2, spawnZ);

        if (above1.getType() != Material.AIR || above2.getType() != Material.AIR) {
            return;
        }

        Location spawnLoc = new Location(world, spawnX + 0.5, surfaceY + 1, spawnZ + 0.5);

        int roll = spawnRandom.nextInt(100);

        if (roll < 65) {
            // Mostly passive animals
            CreatureType passiveType = pickPassiveCreature();
            world.spawnCreature(spawnLoc, passiveType);
            return;
        }

        // Hostile / Nether / special mobs
        int specialRoll = spawnRandom.nextInt(100);

        // 10% of hostile spawns = spider jockey
        if (specialRoll < 10) {
            spawnSpiderJockey(world, spawnLoc);
            return;
        }

        // 5% of hostile spawns = giant
        if (specialRoll >= 10 && specialRoll < 15) {
            try {
                world.spawnCreature(spawnLoc, CreatureType.GIANT);
            } catch (Throwable t) {
                // Older Bukkit builds might not support GIANT; fail silently.
            }
            return;
        }

        CreatureType hostileType = pickHellCreature(surfaceY);
        if (hostileType != null) {
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
            // Slightly rarer wolves, but still "passive" in terms of environment spawning
            return CreatureType.WOLF;
        }
    }

    private CreatureType pickHellCreature(int surfaceY) {
        int r = spawnRandom.nextInt(100);

        if (r < 40) {
            return CreatureType.PIG_ZOMBIE;
        } else if (r < 65) {
            // Old generic Monster (id 49)
            return CreatureType.MONSTER;
        } else if (r < 85) {
            // Spider as a regular hostile spawn (non-jockey)
            return CreatureType.SPIDER;
        } else {
            // Ghasts prefer a bit of headroom; if we are too low, fall back
            if (surfaceY > 48) {
                return CreatureType.GHAST;
            } else {
                return CreatureType.PIG_ZOMBIE;
            }
        }
    }

    private int findTopDirtY(World world, int x, int z) {
        for (int y = 127; y > 0; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.DIRT) {
                return y;
            }
            if (type != Material.AIR && type != Material.LEAVES) {
                // Hit something else solid before dirt
                return -1;
            }
        }
        return -1;
    }

    /**
     * Indev-style Hell chunk generator:
     * - Nether dimension (set via Multiverse as ENV=NETHER)
     * - Lava ocean at y < LAVA_LEVEL
     * - Overworld-ish terrain above lava
     * - Top layer is always dirt (no grass)
     */
    public static class IndevHellChunkGenerator extends ChunkGenerator {

        private static final int WORLD_HEIGHT = 128;
        private static final int LAVA_LEVEL   = 32;
        private static final int BASE_HEIGHT  = 64;

        private final byte bedrockId = (byte) Material.BEDROCK.getId();
        private final byte stoneId   = (byte) Material.STONE.getId();
        private final byte dirtId    = (byte) Material.DIRT.getId();
        private final byte lavaId    = (byte) Material.STATIONARY_LAVA.getId();

        @Override
        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];

            long seed = world.getSeed();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;

                    double heightNoise = getHeightNoise(worldX, worldZ, seed);
                    int terrainHeight = (int) (BASE_HEIGHT + heightNoise);

                    if (terrainHeight < LAVA_LEVEL + 4) {
                        terrainHeight = LAVA_LEVEL + 4;
                    }
                    if (terrainHeight > WORLD_HEIGHT - 4) {
                        terrainHeight = WORLD_HEIGHT - 4;
                    }

                    // y = 0: bedrock
                    setBlock(blocks, x, 0, z, bedrockId);

                    // y = 1 .. LAVA_LEVEL-1: lava ocean
                    for (int y = 1; y < LAVA_LEVEL; y++) {
                        setBlock(blocks, x, y, z, lavaId);
                    }

                    // y = LAVA_LEVEL .. terrainHeight: solid ground
                    for (int y = LAVA_LEVEL; y <= terrainHeight; y++) {
                        int depthFromTop = terrainHeight - y;

                        if (depthFromTop == 0) {
                            // Very top block: dirt (no grass)
                            setBlock(blocks, x, y, z, dirtId);
                        } else if (depthFromTop <= 3) {
                            // Top soil: dirt
                            setBlock(blocks, x, y, z, dirtId);
                        } else {
                            // Bulk stone
                            setBlock(blocks, x, y, z, stoneId);
                        }
                    }
                }
            }

            return blocks;
        }

        private double getHeightNoise(int worldX, int worldZ, long seed) {
            double x = worldX / 32.0;
            double z = worldZ / 32.0;

            double h1 = Math.sin(x) + Math.cos(z);
            double h2 = Math.sin(x * 0.7 + z * 0.3 + seed * 0.000001) * 2.5;
            double h3 = Math.cos(x * 0.2 - z * 0.4 - seed * 0.000001) * 3.0;

            return h1 * 3.0 + h2 + h3;
        }

        private void setBlock(byte[] blocks, int x, int y, int z, byte id) {
            if (y < 0 || y >= WORLD_HEIGHT) {
                return;
            }
            int index = (x * 16 + z) * WORLD_HEIGHT + y;
            blocks[index] = id;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> list = new ArrayList<BlockPopulator>();
            // Order matters a bit: carve dungeons first, then ores, then trees / surface
            list.add(new IndevHellDungeonPopulator());
            list.add(new IndevHellOrePopulator());
            list.add(new IndevHellTreePopulator());
            list.add(new IndevHellSurfacePopulator());
            return list;
        }
    }

    /**
     * Ore generation populator.
     * Replaces stone with ore veins at roughly appropriate levels.
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
     * Simple overworld-style tree populator for the dirt islands.
     */
    public static class IndevHellTreePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;
        private static final int LAVA_LEVEL   = 32;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int trees = 2 + random.nextInt(4); // 2–5 trees per chunk

            for (int i = 0; i < trees; i++) {
                int localX = random.nextInt(16);
                int localZ = random.nextInt(16);

                int worldX = chunk.getX() * 16 + localX;
                int worldZ = chunk.getZ() * 16 + localZ;

                int surfaceY = findSurfaceY(world, worldX, worldZ);
                if (surfaceY <= LAVA_LEVEL + 2 || surfaceY >= WORLD_HEIGHT - 8) {
                    continue;
                }

                Block ground = world.getBlockAt(worldX, surfaceY, worldZ);
                if (ground.getType() != Material.DIRT) {
                    continue;
                }

                generateSimpleTree(world, random, worldX, surfaceY + 1, worldZ);
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

        private void generateSimpleTree(World world, Random random, int x, int y, int z) {
            int trunkHeight = 4 + random.nextInt(3); // 4–6 high trunk

            for (int i = 0; i < trunkHeight; i++) {
                Block block = world.getBlockAt(x, y + i, z);
                Material type = block.getType();
                if (type == Material.AIR || type == Material.LEAVES) {
                    block.setType(Material.LOG);
                }
            }

            int topY = y + trunkHeight;

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (dist > 3) {
                            continue;
                        }

                        Block block = world.getBlockAt(x + dx, topY + dy, z + dz);
                        if (block.getType() == Material.AIR) {
                            block.setType(Material.LEAVES);
                        }
                    }
                }
            }
        }
    }

    /**
     * Surface decoration:
     * - Mushrooms and flowers on dirt.
     * - Sand and gravel near lava shorelines.
     */
    public static class IndevHellSurfacePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;
        private static final int LAVA_LEVEL   = 32;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int baseX = chunk.getX() * 16;
            int baseZ = chunk.getZ() * 16;

            // Decorate dirt surfaces with mushrooms and flowers
            int decorTries = 10 + random.nextInt(10); // 10–19 attempts
            for (int i = 0; i < decorTries; i++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);

                int surfaceY = findSurfaceY(world, worldX, worldZ);
                if (surfaceY <= 0 || surfaceY >= WORLD_HEIGHT - 2) {
                    continue;
                }

                Block ground = world.getBlockAt(worldX, surfaceY, worldZ);
                Block above = world.getBlockAt(worldX, surfaceY + 1, worldZ);

                if (ground.getType() != Material.DIRT || above.getType() != Material.AIR) {
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

            // Sand / gravel along lava shorelines
            int shoreTries = 20;
            for (int i = 0; i < shoreTries; i++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);

                int y = LAVA_LEVEL + random.nextInt(6); // Just above lava level
                if (y >= WORLD_HEIGHT) {
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

                if (random.nextBoolean()) {
                    block.setType(Material.SAND);
                } else {
                    block.setType(Material.GRAVEL);
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
}
