import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public class InfdevEsqueGen extends JavaPlugin {

    private static final Logger log = Logger.getLogger("Minecraft");

    @Override
    public void onEnable() {
        log.info("[InfdevEsqueGen] Enabled.");
    }

    @Override
    public void onDisable() {
        log.info("[InfdevEsqueGen] Disabled.");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new InfdevChunkGenerator();
    }

    // ========================================================================
    // Main Chunk Generator
    // ========================================================================

    public static class InfdevChunkGenerator extends ChunkGenerator {

        private static final int WORLD_HEIGHT = 128;
        private static final int SEA_LEVEL = 64;

        @Override
        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];

            long seed = world.getSeed();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;

                    int groundHeight = getColumnHeight(seed, worldX, worldZ);

                    if (groundHeight < 4) {
                        groundHeight = 4;
                    }
                    if (groundHeight > WORLD_HEIGHT - 2) {
                        groundHeight = WORLD_HEIGHT - 2;
                    }

                    // Bedrock
                    setBlock(blocks, x, 0, z, (byte) Material.BEDROCK.getId());

                    // Stone + dirt + grass / sand / gravel
                    for (int y = 1; y <= groundHeight; y++) {
                        if (y < groundHeight - 4) {
                            setBlock(blocks, x, y, z, (byte) Material.STONE.getId());
                        } else if (y < groundHeight) {
                            setBlock(blocks, x, y, z, (byte) Material.DIRT.getId());
                        } else {
                            // Top block
                            if (groundHeight <= SEA_LEVEL + 1) {
                                // Beaches and ocean floors
                                if (groundHeight <= SEA_LEVEL - 6) {
                                    if ((worldX + worldZ) % 5 == 0) {
                                        setBlock(blocks, x, y, z, (byte) Material.GRAVEL.getId());
                                    } else {
                                        setBlock(blocks, x, y, z, (byte) Material.SAND.getId());
                                    }
                                } else {
                                    setBlock(blocks, x, y, z, (byte) Material.SAND.getId());
                                }
                            } else {
                                setBlock(blocks, x, y, z, (byte) Material.GRASS.getId());
                            }
                        }
                    }

                    // Water column up to sea level
                    if (groundHeight < SEA_LEVEL) {
                        for (int y = groundHeight + 1; y <= SEA_LEVEL; y++) {
                            setBlock(blocks, x, y, z, (byte) Material.WATER.getId());
                        }
                    }
                }
            }

            return blocks;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> populators = new ArrayList<BlockPopulator>();
            populators.add(new CavePopulator());
            populators.add(new OrePopulator());
            populators.add(new TreePopulator());
            populators.add(new DungeonPopulator());
            populators.add(new IndevHousePopulator());
            populators.add(new BrickPyramidPopulator());
            return populators;
        }

        // --------------------------------------------------------------------
        // Block helpers
        // --------------------------------------------------------------------

        private static void setBlock(byte[] blocks, int x, int y, int z, byte id) {
            if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= WORLD_HEIGHT) {
                return;
            }
            int index = (x * 16 + z) * WORLD_HEIGHT + y;
            blocks[index] = id;
        }

        // --------------------------------------------------------------------
        // Height function: more extreme mountains/cliffs (Infdev-ish)
        // --------------------------------------------------------------------

        private static int getColumnHeight(long worldSeed, int x, int z) {
            // Very large-scale: continents vs oceans
            double elevation = octaveNoise(worldSeed + 11L, x / 2048.0, z / 2048.0, 4, 0.5);

            // Mid-scale: where rough, mountainous terrain is allowed
            double roughness = octaveNoise(worldSeed + 27L, x / 512.0, z / 512.0, 4, 0.5);

            // Mountain detail: ridges and tall peaks
            double mountain = octaveNoise(worldSeed + 91L, x / 128.0, z / 128.0, 4, 0.5);

            // Small-scale bumps / cliffs
            double detail = octaveNoise(worldSeed + 51L, x / 32.0, z / 32.0, 4, 0.5);

            // Shape elevation into oceans/islands
            double baseHeight;
            if (elevation < -0.6) {
                baseHeight = SEA_LEVEL - 24 + elevation * 12.0;  // deep oceans
            } else if (elevation < 0.0) {
                baseHeight = SEA_LEVEL - 8 + elevation * 8.0;   // shallower seas / coasts
            } else {
                baseHeight = SEA_LEVEL + elevation * 36.0;      // inland hills
            }

            // Mountain factor: only positive mountain noise makes big peaks.
            double mountainFactor = Math.max(0.0, mountain - 0.05); // threshold so it’s patchy
            // Stronger mountains: up to ~+48 blocks
            double mountainHeight = mountainFactor * (30.0 + (roughness + 1.0) * 9.0);

            // Detail: stronger where mountains exist, for cliffs/steps
            double detailStrength = 6.0 + mountainFactor * 10.0;
            double detailOffset = detail * detailStrength;

            double height = baseHeight + mountainHeight + detailOffset;

            // Soft clamp into world range
            if (height < 16.0) {
                height = 16.0 + (height - 16.0) * 0.5;
            }
            if (height > 118.0) {
                height = 118.0 + (height - 118.0) * 0.25;
            }

            return (int) Math.round(height);
        }

        private static double octaveNoise(long seed, double x, double z, int octaves, double persistence) {
            double total = 0.0;
            double max = 0.0;
            double frequency = 1.0;
            double amplitude = 1.0;

            for (int i = 0; i < octaves; i++) {
                total += interpolatedNoise(seed + i * 57L, x * frequency, z * frequency) * amplitude;
                max += amplitude;
                amplitude *= persistence;
                frequency *= 2.0;
            }

            if (max == 0.0) {
                return 0.0;
            }
            return total / max;
        }

        private static double interpolatedNoise(long seed, double x, double z) {
            int intX = (int) Math.floor(x);
            double fracX = x - intX;
            int intZ = (int) Math.floor(z);
            double fracZ = z - intZ;

            double v1 = smoothNoise(seed, intX, intZ);
            double v2 = smoothNoise(seed, intX + 1, intZ);
            double v3 = smoothNoise(seed, intX, intZ + 1);
            double v4 = smoothNoise(seed, intX + 1, intZ + 1);

            double i1 = cosineInterpolate(v1, v2, fracX);
            double i2 = cosineInterpolate(v3, v4, fracX);

            return cosineInterpolate(i1, i2, fracZ);
        }

        private static double smoothNoise(long seed, int x, int z) {
            double corners = (valueNoise(seed, x - 1, z - 1) +
                              valueNoise(seed, x + 1, z - 1) +
                              valueNoise(seed, x - 1, z + 1) +
                              valueNoise(seed, x + 1, z + 1)) / 16.0;

            double sides = (valueNoise(seed, x - 1, z) +
                            valueNoise(seed, x + 1, z) +
                            valueNoise(seed, x, z - 1) +
                            valueNoise(seed, x, z + 1)) / 8.0;

            double center = valueNoise(seed, x, z) / 4.0;

            return corners + sides + center;
        }

        private static double valueNoise(long seed, int x, int z) {
            long n = seed;
            n ^= (long) x * 341873128712L;
            n ^= (long) z * 132897987541L;
            n = (n << 13) ^ n;
            long nn = (n * (n * n * 15731L + 789221L) + 1376312589L);
            return 1.0 - ((double) (nn & 0x7fffffff) / 1073741824.0); // [-1, 1]
        }

        private static double cosineInterpolate(double a, double b, double blend) {
            double theta = blend * Math.PI;
            double f = (1.0 - Math.cos(theta)) * 0.5;
            return a * (1.0 - f) + b * f;
        }
    }

    // ========================================================================
    // Cave Populator (simple blob caves, chunk-local)
    // ========================================================================

    public static class CavePopulator extends BlockPopulator {

        private static final int WORLD_HEIGHT = 128;

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int attempts = 2 + random.nextInt(3); // 2–4 cave blobs per chunk

            int worldXBase = chunk.getX() * 16;
            int worldZBase = chunk.getZ() * 16;

            for (int i = 0; i < attempts; i++) {
                int cx = random.nextInt(16);
                int cz = random.nextInt(16);
                int cy = 20 + random.nextInt(40); // mid-depth caves

                int radius = 3 + random.nextInt(4); // radius 3–6

                carveSphere(world, worldXBase, worldZBase, cx, cy, cz, radius);
            }
        }

        private void carveSphere(World world, int worldXBase, int worldZBase,
                                 int cx, int cy, int cz, int radius) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int y = cy - radius; y <= cy + radius; y++) {
                    for (int z = cz - radius; z <= cz + radius; z++) {
                        if (x < 0 || x >= 16 || z < 0 || z >= 16) {
                            continue;
                        }
                        if (y <= 1 || y >= WORLD_HEIGHT - 2) {
                            continue;
                        }
                        double dx = x - cx;
                        double dy = y - cy;
                        double dz = z - cz;
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                            Block block = world.getBlockAt(worldXBase + x, y, worldZBase + z);
                            Material type = block.getType();
                            if (type == Material.STONE ||
                                type == Material.DIRT ||
                                type == Material.GRAVEL) {
                                block.setType(Material.AIR);
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // Ore Populator (chunk-local Infdev-ish ore bands)
    // ========================================================================

    public static class OrePopulator extends BlockPopulator {

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            generateOre(world, random, chunkX, chunkZ, 20, 16, 0, 64, Material.COAL_ORE);
            generateOre(world, random, chunkX, chunkZ, 20, 8, 0, 64, Material.IRON_ORE);
            generateOre(world, random, chunkX, chunkZ, 2, 8, 0, 32, Material.GOLD_ORE);
            generateOre(world, random, chunkX, chunkZ, 8, 7, 0, 16, Material.REDSTONE_ORE);
            generateOre(world, random, chunkX, chunkZ, 1, 7, 0, 16, Material.DIAMOND_ORE);
            generateOre(world, random, chunkX, chunkZ, 1, 6, 0, 32, Material.LAPIS_ORE);
        }

        private void generateOre(World world,
                                 Random random,
                                 int chunkX,
                                 int chunkZ,
                                 int attempts,
                                 int veinSize,
                                 int minY,
                                 int maxY,
                                 Material oreType) {
            int heightRange = maxY - minY + 1;
            if (heightRange <= 0) {
                return;
            }

            int baseBlockX = (chunkX << 4) + 1;
            int baseBlockZ = (chunkZ << 4) + 1;

            for (int i = 0; i < attempts; i++) {
                int startX = baseBlockX + random.nextInt(14);
                int startZ = baseBlockZ + random.nextInt(14);
                int startY = minY + random.nextInt(heightRange);

                carveOreVein(world, random, chunkX, chunkZ, startX, startY, startZ, veinSize, oreType);
            }
        }

        private void carveOreVein(World world,
                                  Random random,
                                  int chunkX,
                                  int chunkZ,
                                  int startX,
                                  int startY,
                                  int startZ,
                                  int veinSize,
                                  Material oreType) {

            int currentX = startX;
            int currentY = startY;
            int currentZ = startZ;

            int maxHeight = world.getMaxHeight();

            for (int i = 0; i < veinSize; i++) {
                if ((currentX >> 4) != chunkX || (currentZ >> 4) != chunkZ) {
                    break;
                }
                if (currentY <= 0 || currentY >= maxHeight) {
                    break;
                }

                Block block = world.getBlockAt(currentX, currentY, currentZ);
                if (block.getType() == Material.STONE) {
                    block.setType(oreType);
                }

                currentX += random.nextInt(3) - 1;
                currentY += random.nextInt(3) - 1;
                currentZ += random.nextInt(3) - 1;
            }
        }
    }

    // ========================================================================
    // Tree Populator (simple old-style trees)
    // ========================================================================

    public static class TreePopulator extends BlockPopulator {

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int treeCount = random.nextInt(6); // 0–5 trees per chunk

            int worldXBase = chunk.getX() * 16;
            int worldZBase = chunk.getZ() * 16;

            for (int i = 0; i < treeCount; i++) {
                int x = worldXBase + random.nextInt(16);
                int z = worldZBase + random.nextInt(16);

                int y = getHighestSolidY(world, x, z);
                if (y <= 0) {
                    continue;
                }

                Block soil = world.getBlockAt(x, y, z);
                if (soil.getType() != Material.GRASS && soil.getType() != Material.DIRT) {
                    continue;
                }

                generateBasicTree(world, random, x, y + 1, z);
            }
        }

        private static int getHighestSolidY(World world, int x, int z) {
            for (int y = world.getMaxHeight() - 1; y >= 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES && type != Material.WATER) {
                    return y;
                }
            }
            return -1;
        }

        private static void generateBasicTree(World world, Random random, int x, int y, int z) {
            int height = 4 + random.nextInt(2); // 4–5 block tall trunk

            for (int i = 0; i < height; i++) {
                Block trunkBlock = world.getBlockAt(x, y + i, z);
                Material type = trunkBlock.getType();
                if (type == Material.AIR || type == Material.LEAVES) {
                    trunkBlock.setType(Material.LOG);
                }
            }

            int leafBaseY = y + height - 2;
            int leafTopY = y + height;

            for (int yy = leafBaseY; yy <= leafTopY; yy++) {
                int radius = (yy == leafTopY) ? 1 : 2;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && radius == 2) {
                            continue;
                        }
                        Block leafBlock = world.getBlockAt(x + dx, yy, z + dz);
                        if (leafBlock.getType() == Material.AIR) {
                            leafBlock.setType(Material.LEAVES);
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // Dungeon Populator (simple cobble/mossy rooms, chunk-local)
    // ========================================================================

    public static class DungeonPopulator extends BlockPopulator {

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            if (random.nextInt(200) != 0) {
                return;
            }

            int worldXBase = chunk.getX() * 16;
            int worldZBase = chunk.getZ() * 16;

            int localX = 4 + random.nextInt(8); // 4..11
            int localZ = 4 + random.nextInt(8); // 4..11

            int x = worldXBase + localX;
            int z = worldZBase + localZ;
            int y = 15 + random.nextInt(25); // underground

            generateDungeon(world, random, x, y, z);
        }

        private void generateDungeon(World world, Random random, int centerX, int centerY, int centerZ) {
            int halfX = 4;
            int halfZ = 4;
            int floorY = centerY;
            int ceilingY = centerY + 4;

            for (int x = centerX - halfX; x <= centerX + halfX; x++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    for (int z = centerZ - halfZ; z <= centerZ + halfZ; z++) {
                        boolean isWall = (x == centerX - halfX || x == centerX + halfX ||
                                          z == centerZ - halfZ || z == centerZ + halfZ);
                        boolean isFloor = (y == floorY);
                        boolean isCeiling = (y == ceilingY);

                        if (isFloor || isWall || isCeiling) {
                            Material type = Material.COBBLESTONE;
                            if (isFloor || isWall) {
                                if (random.nextInt(4) == 0) {
                                    type = Material.MOSSY_COBBLESTONE;
                                }
                            }
                            world.getBlockAt(x, y, z).setType(type);
                        } else {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            }

            Block spawner = world.getBlockAt(centerX, floorY + 1, centerZ);
            spawner.setType(Material.MOB_SPAWNER);

            Block chest1 = world.getBlockAt(centerX - halfX + 1, floorY + 1, centerZ - halfZ + 1);
            Block chest2 = world.getBlockAt(centerX + halfX - 1, floorY + 1, centerZ + halfZ - 1);
            chest1.setType(Material.CHEST);
            chest2.setType(Material.CHEST);
        }
    }

    // ========================================================================
    // Indev House Populator (7x7x7 starting houses, chunk-local)
    // ========================================================================

    public static class IndevHousePopulator extends BlockPopulator {

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            if (random.nextInt(800) != 0) {
                return;
            }

            int worldXBase = chunk.getX() * 16;
            int worldZBase = chunk.getZ() * 16;

            int localX = 4 + random.nextInt(8); // 4..11
            int localZ = 4 + random.nextInt(8); // 4..11

            int x = worldXBase + localX;
            int z = worldZBase + localZ;

            int groundY = getHighestSolidY(world, x, z);
            if (groundY <= 0 || groundY >= world.getMaxHeight() - 10) {
                return;
            }

            generateIndevHouse(world, random, x, groundY + 1, z);
        }

        private static int getHighestSolidY(World world, int x, int z) {
            for (int y = world.getMaxHeight() - 1; y >= 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES && type != Material.WATER) {
                    return y;
                }
            }
            return -1;
        }

        private void generateIndevHouse(World world, Random random, int originX, int baseY, int originZ) {
            int half = 3;
            int floorY = baseY;
            int ceilingY = baseY + 6;
            int interiorCeilingY = baseY + 5;

            boolean mossyVariant = random.nextBoolean();

            Material wallMat;
            Material roofMat;
            Material floorMat;

            if (mossyVariant) {
                wallMat = Material.MOSSY_COBBLESTONE;
                roofMat = Material.MOSSY_COBBLESTONE;
                floorMat = Material.MOSSY_COBBLESTONE;
            } else {
                wallMat = Material.WOOD;
                roofMat = Material.WOOD;
                floorMat = Material.STONE;
            }

            for (int x = originX - half; x <= originX + half; x++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    for (int z = originZ - half; z <= originZ + half; z++) {
                        boolean onEdgeX = (x == originX - half || x == originX + half);
                        boolean onEdgeZ = (z == originZ - half || z == originZ + half);
                        boolean isWall = (onEdgeX || onEdgeZ) && y > floorY && y <= interiorCeilingY;
                        boolean isFloor = (y == floorY);
                        boolean isRoof = (y == ceilingY);

                        if (isFloor) {
                            world.getBlockAt(x, y, z).setType(floorMat);
                        } else if (isWall) {
                            world.getBlockAt(x, y, z).setType(wallMat);
                        } else if (isRoof) {
                            world.getBlockAt(x, y, z).setType(roofMat);
                        } else {
                            world.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                    }
                }
            }

            world.getBlockAt(originX, floorY + 1, originZ + half).setType(Material.AIR);
            world.getBlockAt(originX, floorY + 2, originZ + half).setType(Material.AIR);
            world.getBlockAt(originX, floorY + 3, originZ + half).setType(Material.AIR);

            world.getBlockAt(originX - 2, floorY + 3, originZ).setType(Material.TORCH);
            world.getBlockAt(originX + 2, floorY + 3, originZ).setType(Material.TORCH);

            world.getBlockAt(originX - 2, floorY + 1, originZ - 2).setType(Material.CHEST);
        }
    }

    // ========================================================================
    // Brick Pyramid Populator (chunk-local brick pyramids)
    // ========================================================================

    public static class BrickPyramidPopulator extends BlockPopulator {

        @Override
        public void populate(World world, Random random, Chunk chunk) {
            if (random.nextInt(3000) != 0) {
                return;
            }

            int worldXBase = chunk.getX() * 16;
            int worldZBase = chunk.getZ() * 16;

            int centerX = worldXBase + 8;
            int centerZ = worldZBase + 8;

            int groundY = getAverageGroundY(world, centerX, centerZ);
            if (groundY <= 0 || groundY >= world.getMaxHeight() - 16) {
                return;
            }

            generateBrickPyramid(world, centerX, groundY + 1, centerZ);
        }

        private static int getAverageGroundY(World world, int centerX, int centerZ) {
            int sum = 0;
            int count = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int y = getHighestSolidY(world, centerX + dx, centerZ + dz);
                    if (y > 0) {
                        sum += y;
                        count++;
                    }
                }
            }
            if (count == 0) return -1;
            return sum / count;
        }

        private static int getHighestSolidY(World world, int x, int z) {
            for (int y = world.getMaxHeight() - 1; y >= 0; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.LEAVES && type != Material.WATER) {
                    return y;
                }
            }
            return -1;
        }

        private void generateBrickPyramid(World world, int centerX, int baseY, int centerZ) {
            int halfBase = 7; // 15x15 base
            int height = 8;

            for (int level = 0; level < height; level++) {
                int y = baseY + level;
                int radius = halfBase - level;
                if (radius < 0) {
                    break;
                }

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        world.getBlockAt(centerX + dx, y, centerZ + dz).setType(Material.BRICK);
                    }
                }
            }
        }
    }
}
