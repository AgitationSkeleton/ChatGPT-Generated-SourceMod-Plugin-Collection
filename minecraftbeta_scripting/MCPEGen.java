import java.util.ArrayList;
import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MCPEGen
 *
 * A Bukkit/Poseidon world generator for Minecraft Beta 1.7.3.
 *
 * This is a direct, code-faithful port of the terrain + feature logic found in:
 *   minecraftcpp-master/handheld/src/world/level/levelgen/RandomLevelSource.*
 *   minecraftcpp-master/handheld/src/world/level/biome/BiomeSource.*
 *   minecraftcpp-master/handheld/src/world/level/levelgen/synth/*
 *
 * Notes:
 * - Beta 1.7.3 has no native biome system; this generator computes its own
 *   biome-like climate fields to reproduce the source algorithm's branching.
 * - Block IDs match Beta/Classic numeric IDs.
 */
public final class MCPEGen extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getLogger().info("[MCPEGen] enabled");
    }

    @Override
    public void onDisable() {
        getServer().getLogger().info("[MCPEGen] disabled");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        Long forcedSeed = parseForcedSeedFromId(id);
        return new MCPEChunkGenerator(forcedSeed);
    }

    private static Long parseForcedSeedFromId(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        if (trimmed.isEmpty()) return null;

        // Allow "seed=..." form.
        if (trimmed.regionMatches(true, 0, "seed=", 0, 5)) {
            trimmed = trimmed.substring(5).trim();
            if (trimmed.isEmpty()) return null;
        }

        // MCPE 0.6.1 behavior: parse signed int32 if possible, else Util::hashCode (31*x + signed byte).
        try {
            int parsed = Integer.parseInt(trimmed);
            return (long) parsed;
        } catch (NumberFormatException ignored) {
            // fall through
        }

        byte[] bytes;
        try {
            bytes = trimmed.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = trimmed.getBytes();
        }

        int hash = 0;
        for (byte b : bytes) {
            hash = hash * 31 + b;
        }
        return (long) hash;
    }

    /**
     * Chunk generator implementing the RandomLevelSource terrain and surface passes.
     */
    public static final class MCPEChunkGenerator extends ChunkGenerator {
        // World constants (match source)
        private static final int WORLD_HEIGHT = 128;
        private static final int SEA_LEVEL = 64; // Level::DEPTH - 64, with DEPTH=128

        private static final int CHUNK_HEIGHT = 8;
        private static final int CHUNK_WIDTH = 4;

        private static final float SNOW_CUTOFF = 0.5f;
        private static final float SNOW_SCALE = 0.3f;

        // Block IDs (Beta 1.7.3)
        private static final byte AIR = 0;
        private static final byte STONE = 1;
        private static final byte GRASS = 2;
        private static final byte DIRT = 3;
        private static final byte BEDROCK = 7;
        private static final byte WATER_FLOWING = 8;
        private static final byte WATER_STILL = 9;
        private static final byte LAVA_FLOWING = 10;
        private static final byte SAND = 12;
        private static final byte GRAVEL = 13;
        private static final byte COAL_ORE = 16;
        private static final byte LOG = 17;
        private static final byte LEAVES = 18;
        private static final byte LAPIS_ORE = 21;
        private static final byte SANDSTONE = 24;
        private static final byte YELLOW_FLOWER = 37;
        private static final byte RED_ROSE = 38;
        private static final byte BROWN_MUSHROOM = 39;
        private static final byte RED_MUSHROOM = 40;
        private static final byte DIAMOND_ORE = 56;
        private static final byte IRON_ORE = 15;
        private static final byte GOLD_ORE = 14;
        private static final byte REDSTONE_ORE = 73;
        private static final byte SNOW_LAYER = 78;
        private static final byte ICE = 79;
        private static final byte CACTUS = 81;
        private static final byte CLAY = 82;
        private static final byte SUGARCANE = 83;


        // Optional: force MCPE-style seed (e.g., string seed conversion) regardless of Bukkit's world seed.
        private final Long forcedSeed;

        public MCPEChunkGenerator(Long forcedSeed) {
            this.forcedSeed = forcedSeed;
        }

        private long getEffectiveSeed(World world) {
            if (forcedSeed != null) return forcedSeed.longValue();
            return world.getSeed();
        }

        @Override
        public byte[] generate(World world, java.util.Random ignored, int chunkX, int chunkZ) {
            final long seed = getEffectiveSeed(world);

            // Persistent per-world state is unnecessary; re-deriving is deterministic.
            MCPEWorldGenState state = new MCPEWorldGenState(seed);

            // Block array is x<<11 | z<<7 | y (same as source)
            byte[] blocks = new byte[16 * 16 * WORLD_HEIGHT];

            // Biome-like fields
            MCPEBiomeSource biomeSource = state.getBiomeSource();
            MCPEBiome[] biomes = biomeSource.getBiomeBlock(chunkX * 16, chunkZ * 16, 16, 16);
            float[] temperatures = biomeSource.temperatures;

            // Terrain (prepareHeights)
            prepareHeights(state, chunkX, chunkZ, blocks, temperatures);

            // Surfaces (buildSurfaces)
            buildSurfaces(state, chunkX, chunkZ, blocks, biomes);

            // Caves (LargeCaveFeature)
            carveCaves(seed, state, chunkX, chunkZ, blocks);

            return blocks;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> list = new ArrayList<BlockPopulator>();
            list.add(new MCPEPopulator());
            return list;
        }

        private static void prepareHeights(MCPEWorldGenState state, int xOffs, int zOffs, byte[] blocks, float[] temperatures) {
            int xChunks = 16 / CHUNK_WIDTH;
            int waterHeight = SEA_LEVEL;

            int xSize = xChunks + 1;
            int ySize = WORLD_HEIGHT / CHUNK_HEIGHT + 1;
            int zSize = xChunks + 1;

            float[] buffer = state.getHeights(xOffs * xChunks, 0, zOffs * xChunks, xSize, ySize, zSize);

            for (int xc = 0; xc < xChunks; xc++) {
                for (int zc = 0; zc < xChunks; zc++) {
                    for (int yc = 0; yc < WORLD_HEIGHT / CHUNK_HEIGHT; yc++) {
                        float yStep = 1.0f / (float) CHUNK_HEIGHT;
                        float s0 = buffer[((xc + 0) * zSize + (zc + 0)) * ySize + (yc + 0)];
                        float s1 = buffer[((xc + 0) * zSize + (zc + 1)) * ySize + (yc + 0)];
                        float s2 = buffer[((xc + 1) * zSize + (zc + 0)) * ySize + (yc + 0)];
                        float s3 = buffer[((xc + 1) * zSize + (zc + 1)) * ySize + (yc + 0)];

                        float s0a = (buffer[((xc + 0) * zSize + (zc + 0)) * ySize + (yc + 1)] - s0) * yStep;
                        float s1a = (buffer[((xc + 0) * zSize + (zc + 1)) * ySize + (yc + 1)] - s1) * yStep;
                        float s2a = (buffer[((xc + 1) * zSize + (zc + 0)) * ySize + (yc + 1)] - s2) * yStep;
                        float s3a = (buffer[((xc + 1) * zSize + (zc + 1)) * ySize + (yc + 1)] - s3) * yStep;

                        for (int y = 0; y < CHUNK_HEIGHT; y++) {
                            float xStep = 1.0f / (float) CHUNK_WIDTH;

                            float _s0 = s0;
                            float _s1 = s1;
                            float _s0a = (s2 - s0) * xStep;
                            float _s1a = (s3 - s1) * xStep;

                            for (int x = 0; x < CHUNK_WIDTH; x++) {
                                int offs = ((x + xc * CHUNK_WIDTH) << 11) | ((0 + zc * CHUNK_WIDTH) << 7) | (yc * CHUNK_HEIGHT + y);
                                int step = 1 << 7;
                                float zStep = 1.0f / (float) CHUNK_WIDTH;

                                float val = _s0;
                                float vala = (_s1 - _s0) * zStep;
                                for (int z = 0; z < CHUNK_WIDTH; z++) {
                                    float temp = temperatures[(xc * CHUNK_WIDTH + x) * 16 + (zc * CHUNK_WIDTH + z)];
                                    byte tileId = AIR;
                                    int yAbs = yc * CHUNK_HEIGHT + y;

                                    if (yAbs < waterHeight) {
                                        if (temp < SNOW_CUTOFF && yAbs >= waterHeight - 1) {
                                            tileId = ICE;
                                        } else {
                                            tileId = WATER_STILL;
                                        }
                                    }
                                    if (val > 0) {
                                        tileId = STONE;
                                    }

                                    blocks[offs] = tileId;
                                    offs += step;
                                    val += vala;
                                }
                                _s0 += _s0a;
                                _s1 += _s1a;
                            }

                            s0 += s0a;
                            s1 += s1a;
                            s2 += s2a;
                            s3 += s3a;
                        }
                    }
                }
            }
        }

        private static void buildSurfaces(MCPEWorldGenState state, int xOffs, int zOffs, byte[] blocks, MCPEBiome[] biomes) {
            int waterHeight = SEA_LEVEL;

            float s = 1.0f / 32.0f;
            float[] sandBuffer = state.perlinNoise2.getRegion2D(new float[16 * 16], (float) (xOffs * 16), (float) (zOffs * 16), 0.0f,
                    16, 16, 1, s, s, 1.0f);
            float[] gravelBuffer = state.perlinNoise2.getRegion3D(new float[16 * 16], (float) (xOffs * 16), 109.01340f, (float) (zOffs * 16),
                    16, 1, 16, s, 1.0f, s);
            float[] depthBuffer = state.perlinNoise3.getRegion2D(new float[16 * 16], (float) (xOffs * 16), (float) (zOffs * 16), 0.0f,
                    16, 16, 1, s * 2, s * 2, s * 2);

            MT19937Random random = state.random;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    float temp = 1.0f; // @todo in source: from BiomeSource; unused in the source buildSurfaces.
                    MCPEBiome b = biomes[x + z * 16];
                    boolean sand = (sandBuffer[x + z * 16] + random.nextFloat() * 0.2f) > 0;
                    boolean gravel = (gravelBuffer[x + z * 16] + random.nextFloat() * 0.2f) > 3;
                    int runDepth = (int) (depthBuffer[x + z * 16] / 3.0f + 3.0f + random.nextFloat() * 0.25f);

                    int run = -1;

                    byte top = b.topMaterial;
                    byte material = b.material;

                    for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                        int offs = (z * 16 + x) * WORLD_HEIGHT + y;

                        if (y <= random.nextInt(5)) {
                            blocks[offs] = BEDROCK;
                        } else {
                            int old = blocks[offs] & 0xFF;

                            if (old == 0) {
                                run = -1;
                            } else if (old == (STONE & 0xFF)) {
                                if (run == -1) {
                                    if (runDepth <= 0) {
                                        top = 0;
                                        material = STONE;
                                    } else if (y >= waterHeight - 4 && y <= waterHeight + 1) {
                                        top = b.topMaterial;
                                        material = b.material;
                                        if (gravel) {
                                            top = 0;
                                            material = GRAVEL;
                                        }
                                        if (sand) {
                                            top = SAND;
                                            material = SAND;
                                        }
                                    }

                                    if (y < waterHeight && top == 0) {
                                        if (temp < 0.15f) {
                                            top = ICE;
                                        } else {
                                            top = WATER_STILL;
                                        }
                                    }

                                    run = runDepth;
                                    if (y >= waterHeight - 1) {
                                        blocks[offs] = top;
                                    } else {
                                        blocks[offs] = material;
                                    }
                                } else if (run > 0) {
                                    run--;
                                    blocks[offs] = material;

                                    if (run == 0 && (material & 0xFF) == (SAND & 0xFF)) {
                                        run = random.nextInt(4);
                                        material = SANDSTONE;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private static void carveCaves(long effectiveSeed, MCPEWorldGenState state, int xOffs, int zOffs, byte[] blocks) {
            // Mirrors LargeFeature::apply + LargeCaveFeature::addFeature.
            final int radius = 8;
            MT19937Random rnd = new MT19937Random(effectiveSeed);

            long xScale = (long) (rnd.nextLong() / 2) * 2L + 1L;
            long zScale = (long) (rnd.nextLong() / 2) * 2L + 1L;

            for (int x = xOffs - radius; x <= xOffs + radius; x++) {
                for (int z = zOffs - radius; z <= zOffs + radius; z++) {
                    rnd.setSeed(((long) x * xScale + (long) z * zScale) ^ effectiveSeed);
                    addCavesForChunk(rnd, x, z, xOffs, zOffs, blocks, radius);
                }
            }
        }

        private static void addCavesForChunk(MT19937Random random, int x, int z, int xOffs, int zOffs, byte[] blocks, int radius) {
            int caves = random.nextInt(random.nextInt(random.nextInt(40) + 1) + 1);
            if (random.nextInt(15) != 0) caves = 0;

            for (int cave = 0; cave < caves; cave++) {
                float xCave = (float) (x * 16 + random.nextInt(16));
                float yCave = (float) (random.nextInt(random.nextInt(120) + 8));
                float zCave = (float) (z * 16 + random.nextInt(16));

                int tunnels = 1;
                if (random.nextInt(4) == 0) {
                    addRoom(random, xOffs, zOffs, blocks, xCave, yCave, zCave, radius);
                    tunnels += random.nextInt(4);
                }

                for (int i = 0; i < tunnels; i++) {
                    float yRot = random.nextFloat() * Mth.PI * 2.0f;
                    float xRot = ((random.nextFloat() - 0.5f) * 2.0f) / 8.0f;
                    float thickness = random.nextFloat() * 2.0f + random.nextFloat();
                    addTunnel(random, xOffs, zOffs, blocks, xCave, yCave, zCave, thickness, yRot, xRot, 0, 0, 1.0f, radius);
                }
            }
        }

        private static void addRoom(MT19937Random random, int xOffs, int zOffs, byte[] blocks, float xRoom, float yRoom, float zRoom, int radius) {
            addTunnel(random, xOffs, zOffs, blocks, xRoom, yRoom, zRoom, 1.0f + random.nextFloat() * 6.0f, 0.0f, 0.0f, -1, -1, 0.5f, radius);
        }

        private static void addTunnel(MT19937Random randomIn, int xOffs, int zOffs, byte[] blocks,
                                      float xCave, float yCave, float zCave,
                                      float thickness, float yRot, float xRot,
                                      int step, int dist, float yScale, int radius) {
            float xMid = (float) (xOffs * 16 + 8);
            float zMid = (float) (zOffs * 16 + 8);

            float yRota = 0.0f;
            float xRota = 0.0f;

            MT19937Random random = new MT19937Random(randomIn.nextLong());

            if (dist <= 0) {
                int max = radius * 16 - 16;
                dist = max - random.nextInt(max / 4);
            }
            boolean singleStep = false;
            if (step == -1) {
                step = dist / 2;
                singleStep = true;
            }

            int splitPoint = random.nextInt(dist / 2) + dist / 4;
            boolean steep = random.nextInt(6) == 0;

            for (; step < dist; step++) {
                float rad = 1.5f + (Mth.sin(step * Mth.PI / dist) * thickness) * 1.0f;
                float yRad = rad * yScale;

                float xc = Mth.cos(xRot);
                float xs = Mth.sin(xRot);
                xCave += Mth.cos(yRot) * xc;
                yCave += xs;
                zCave += Mth.sin(yRot) * xc;

                if (steep) {
                    xRot *= 0.92f;
                } else {
                    xRot *= 0.7f;
                }
                xRot += xRota * 0.1f;
                yRot += yRota * 0.1f;

                xRota *= 0.90f;
                yRota *= 0.75f;
                xRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
                yRota += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;

                if (!singleStep && step == splitPoint && thickness > 1.0f) {
                    addTunnel(randomIn, xOffs, zOffs, blocks, xCave, yCave, zCave,
                            random.nextFloat() * 0.5f + 0.5f, yRot - Mth.PI / 2.0f, xRot / 3.0f,
                            step, dist, 1.0f, radius);
                    addTunnel(randomIn, xOffs, zOffs, blocks, xCave, yCave, zCave,
                            random.nextFloat() * 0.5f + 0.5f, yRot + Mth.PI / 2.0f, xRot / 3.0f,
                            step, dist, 1.0f, radius);
                    return;
                }
                if (!singleStep && random.nextInt(4) == 0) continue;

                float xd = xCave - xMid;
                float zd = zCave - zMid;
                float remaining = (float) (dist - step);
                float rr = (thickness + 2.0f) + 16.0f;
                if (xd * xd + zd * zd - (remaining * remaining) > rr * rr) {
                    return;
                }

                if (xCave < xMid - 16 - rad * 2 || zCave < zMid - 16 - rad * 2 || xCave > xMid + 16 + rad * 2 || zCave > zMid + 16 + rad * 2) {
                    continue;
                }

                int x0 = (int) Math.floor(xCave - rad) - xOffs * 16 - 1;
                int x1 = (int) Math.floor(xCave + rad) - xOffs * 16 + 1;
                int y0 = (int) Math.floor(yCave - yRad) - 1;
                int y1 = (int) Math.floor(yCave + yRad) + 1;
                int z0 = (int) Math.floor(zCave - rad) - zOffs * 16 - 1;
                int z1 = (int) Math.floor(zCave + rad) - zOffs * 16 + 1;

                if (x0 < 0) x0 = 0;
                if (x1 > 16) x1 = 16;
                if (y0 < 1) y0 = 1;
                if (y1 > 120) y1 = 120;
                if (z0 < 0) z0 = 0;
                if (z1 > 16) z1 = 16;

                boolean detectedWater = false;
                for (int xx = x0; !detectedWater && xx < x1; xx++) {
                    for (int zz = z0; !detectedWater && zz < z1; zz++) {
                        for (int yy = y1 + 1; !detectedWater && yy >= y0 - 1; yy--) {
                            if (yy < 0 || yy >= 128) continue;
                            int p = ((xx * 16) + zz) * 128 + yy;
                            int block = blocks[p] & 0xFF;
                            if (block == 8 || block == 9) {
                                detectedWater = true;
                            }
                            if (yy != y0 - 1 && xx != x0 && xx != x1 - 1 && zz != z0 && zz != z1 - 1) {
                                yy = y0;
                            }
                        }
                    }
                }
                if (detectedWater) continue;

                for (int xx = x0; xx < x1; xx++) {
                    float xdn = ((xx + xOffs * 16 + 0.5f) - xCave) / rad;
                    for (int zz = z0; zz < z1; zz++) {
                        float zdn = ((zz + zOffs * 16 + 0.5f) - zCave) / rad;
                        int p = ((xx * 16) + zz) * 128 + y1;
                        boolean hasGrass = false;
                        if (xdn * xdn + zdn * zdn < 1.0f) {
                            for (int yy = y1 - 1; yy >= y0; yy--) {
                                float ydn = (yy + 0.5f - yCave) / yRad;
                                if (ydn > -0.7f && xdn * xdn + ydn * ydn + zdn * zdn < 1.0f) {
                                    int block = blocks[p] & 0xFF;
                                    if (block == 2) hasGrass = true;
                                    if (block == 1 || block == 3 || block == 2) {
                                        if (yy < 10) {
                                            blocks[p] = 10;
                                        } else {
                                            blocks[p] = 0;
                                            if (hasGrass && (blocks[p - 1] & 0xFF) == 3) {
                                                blocks[p - 1] = 2;
                                            }
                                        }
                                    }
                                }
                                p--;
                            }
                        }
                    }
                }

                if (singleStep) break;
            }
        }
    

private final class MCPEPopulator extends BlockPopulator {
        @Override
        public void populate(World world, java.util.Random ignored, Chunk chunk) {
            final long seed = MCPEChunkGenerator.this.getEffectiveSeed(world);
            final int chunkX = chunk.getX();
            final int chunkZ = chunk.getZ();

            MCPEWorldGenState state = new MCPEWorldGenState(seed);
            MCPEBiomeSource biomeSource = state.getBiomeSource();

            int xo = chunkX * 16;
            int zo = chunkZ * 16;

            MCPEBiome biome = biomeSource.getBiome(xo + 16, zo + 16);

            // Random seeding sequence matches the source.
            MT19937Random random = state.random;
            random.setSeed(seed);
            int xScale = (random.nextInt() / 2) * 2 + 1;
            int zScale = (random.nextInt() / 2) * 2 + 1;
            random.setSeed(((long) chunkX * (long) xScale + (long) chunkZ * (long) zScale) ^ seed);

            // Clay
            for (int i = 0; i < 10; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16);
                placeClay(world, random, x, y, z, 32);
            }

            // Dirt
            for (int i = 0; i < 20; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 3, 32);
            }

            // Gravel
            for (int i = 0; i < 10; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 13, 32);
            }

            // Coal
            for (int i = 0; i < 20; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 16, 16);
            }

            // Iron
            for (int i = 0; i < 20; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(64);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 15, 8);
            }

            // Gold
            for (int i = 0; i < 2; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(32);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 14, 8);
            }

            // Redstone
            for (int i = 0; i < 8; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(16);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 73, 7);
            }

            // Diamond (source calls it emeraldOre)
            for (int i = 0; i < 1; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(16);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 56, 7);
            }

            // Lapis
            for (int i = 0; i < 1; i++) {
                int x = xo + random.nextInt(16);
                int y = random.nextInt(16) + random.nextInt(16);
                int z = zo + random.nextInt(16);
                placeOre(world, random, x, y, z, 21, 6);
            }

            // Trees
            final float ss = 0.5f;
            int oFor = (int) ((state.forestNoise.getValue2D(xo * ss, zo * ss) / 8.0f + random.nextFloat() * 4.0f + 4.0f) / 3.0f);
            int forests = 0;
            if (random.nextInt(10) == 0) {
                forests += 1;
            }
            if (biome == MCPEBiome.FOREST) forests += oFor + 2;
            if (biome == MCPEBiome.RAINFOREST) forests += oFor + 2;
            if (biome == MCPEBiome.SEASONAL_FOREST) forests += oFor + 1;
            if (biome == MCPEBiome.TAIGA) forests += oFor + 1;

            if (biome == MCPEBiome.DESERT) forests -= 20;
            if (biome == MCPEBiome.TUNDRA) forests -= 20;
            if (biome == MCPEBiome.PLAINS) forests -= 20;

            for (int i = 0; i < forests; i++) {
                int x = xo + random.nextInt(16) + 8;
                int z = zo + random.nextInt(16) + 8;
                int y = world.getHighestBlockYAt(x, z);
                placeTree(world, random, x, y, z);
            }

            // Flowers
            for (int i = 0; i < 2; i++) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeFlower(world, random, x, y, z, 37);
            }
            if (random.nextInt(2) == 0) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeFlower(world, random, x, y, z, 38);
            }
            if (random.nextInt(4) == 0) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeFlower(world, random, x, y, z, 39);
            }
            if (random.nextInt(8) == 0) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeFlower(world, random, x, y, z, 40);
            }

            // Reeds
            for (int i = 0; i < 10; i++) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeReeds(world, random, x, y, z);
            }

            // Cacti
            int cacti = 0;
            if (biome == MCPEBiome.DESERT) {
                cacti += 5;
            }
            for (int i = 0; i < cacti; i++) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(128);
                int z = zo + random.nextInt(16) + 8;
                placeCactus(world, random, x, y, z);
            }

            // Springs
            for (int i = 0; i < 50; i++) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(random.nextInt(120) + 8);
                int z = zo + random.nextInt(16) + 8;
                placeSpring(world, random, x, y, z, 8);
            }
            for (int i = 0; i < 20; i++) {
                int x = xo + random.nextInt(16) + 8;
                int y = random.nextInt(random.nextInt(random.nextInt(112) + 8) + 8);
                int z = zo + random.nextInt(16) + 8;
                placeSpring(world, random, x, y, z, 10);
            }

            // Snow placement (mirrors the tail end of postProcess)
            float[] temps = biomeSource.getTemperatureBlock(xo + 8, zo + 8, 16, 16);
            for (int x = xo + 8; x < xo + 8 + 16; x++) {
                for (int z = zo + 8; z < zo + 8 + 16; z++) {
                    int xp = x - (xo + 8);
                    int zp = z - (zo + 8);
                    int y = world.getHighestBlockYAt(x, z);
                    float temp = temps[xp * 16 + zp] - (y - 64) / 64.0f * MCPEChunkGenerator.SNOW_SCALE;
                    if (temp < MCPEChunkGenerator.SNOW_CUTOFF) {
                        if (y > 0 && y < 128) {
                            Block b = world.getBlockAt(x, y, z);
                            Block below = world.getBlockAt(x, y - 1, z);
                            if (b.getTypeId() == 0 && isBlocksMotion(below.getTypeId())) {
                                if (below.getTypeId() != 79) {
                                    b.setTypeId(78);
                                }
                            }
                        }
                    }
                }
            }
        }

        private boolean isBlocksMotion(int typeId) {
            // Simplified "blocksMotion" equivalent: treat any non-air, non-fluid, non-plants as solid.
            if (typeId == 0) return false;
            if (typeId == 8 || typeId == 9 || typeId == 10 || typeId == 11) return false;
            if (typeId == 37 || typeId == 38 || typeId == 39 || typeId == 40) return false;
            if (typeId == 78) return false;
            if (typeId == 83) return false;
            return true;
        }

        // ---------------- Feature ports ----------------

        private void placeOre(World world, MT19937Random random, int x, int y, int z, int tileId, int count) {
            float dir = random.nextFloat() * Mth.PI;

            float x0 = x + 8 + Mth.sin(dir) * count / 8.0f;
            float x1 = x + 8 - Mth.sin(dir) * count / 8.0f;
            float z0 = z + 8 + Mth.cos(dir) * count / 8.0f;
            float z1 = z + 8 - Mth.cos(dir) * count / 8.0f;

            float y0 = (float) (y + random.nextInt(3) + 2);
            float y1 = (float) (y + random.nextInt(3) + 2);

            for (int D = 0; D <= count; D++) {
                float d = (float) D;
                float xx = x0 + (x1 - x0) * d / count;
                float yy = y0 + (y1 - y0) * d / count;
                float zz = z0 + (z1 - z0) * d / count;

                float ss = random.nextFloat() * count / 16.0f;
                float r = (Mth.sin(d * Mth.PI / count) + 1.0f) * ss + 1.0f;
                float hr = (Mth.sin(d * Mth.PI / count) + 1.0f) * ss + 1.0f;

                int xt0 = (int) (xx - r / 2.0f);
                int yt0 = (int) (yy - hr / 2.0f);
                int zt0 = (int) (zz - r / 2.0f);

                int xt1 = (int) (xx + r / 2.0f);
                int yt1 = (int) (yy + hr / 2.0f);
                int zt1 = (int) (zz + r / 2.0f);

                for (int x2 = xt0; x2 <= xt1; x2++) {
                    float xd = ((x2 + 0.5f) - xx) / (r / 2.0f);
                    if (xd * xd < 1.0f) {
                        for (int y2 = yt0; y2 <= yt1; y2++) {
                            float yd = ((y2 + 0.5f) - yy) / (hr / 2.0f);
                            if (xd * xd + yd * yd < 1.0f) {
                                for (int z2 = zt0; z2 <= zt1; z2++) {
                                    float zd = ((z2 + 0.5f) - zz) / (r / 2.0f);
                                    if (xd * xd + yd * yd + zd * zd < 1.0f) {
                                        if (y2 >= 0 && y2 < 128) {
                                            Block b = world.getBlockAt(x2, y2, z2);
                                            if (b.getTypeId() == 1) {
                                                b.setTypeId(tileId);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private void placeClay(World world, MT19937Random random, int x, int y, int z, int count) {
            if (y < 0 || y >= 128) return;
            int typeHere = world.getBlockAt(x, y, z).getTypeId();
            if (typeHere != 8 && typeHere != 9) return;

            float dir = random.nextFloat() * Mth.PI;

            float x0 = x + 8 + (float) Math.sin(dir) * count / 8.0f;
            float x1 = x + 8 - (float) Math.sin(dir) * count / 8.0f;
            float z0 = z + 8 + (float) Math.cos(dir) * count / 8.0f;
            float z1 = z + 8 - (float) Math.cos(dir) * count / 8.0f;

            float y0 = (float) (y + random.nextInt(3) + 2);
            float y1 = (float) (y + random.nextInt(3) + 2);

            for (int d = 0; d <= count; d++) {
                float xx = x0 + (x1 - x0) * d / count;
                float yy = y0 + (y1 - y0) * d / count;
                float zz = z0 + (z1 - z0) * d / count;

                float ss = random.nextFloat() * (float) (count >> 4);
                float r = ((float) Math.sin(d * Mth.PI / count) + 1.0f) * ss + 1.0f;
                float hr = ((float) Math.sin(d * Mth.PI / count) + 1.0f) * ss + 1.0f;

                int xt0 = (int) (xx - r / 2.0f);
                int yt0 = (int) (yy - hr / 2.0f);
                int zt0 = (int) (zz - r / 2.0f);
                int xt1 = (int) (xx + r * 0.5f);
                int yt1 = (int) (yy + hr * 0.5f);
                int zt1 = (int) (zz + r * 0.5f);

                for (int x2 = xt0; x2 <= xt1; x2++) {
                    for (int y2 = yt0; y2 <= yt1; y2++) {
                        if (y2 < 0 || y2 >= 128) continue;
                        for (int z2 = zt0; z2 <= zt1; z2++) {
                            float xd = ((x2 + 0.5f) - xx) / (r * 0.5f);
                            float yd = ((y2 + 0.5f) - yy) / (hr * 0.5f);
                            float zd = ((z2 + 0.5f) - zz) / (r * 0.5f);
                            if (xd * xd + yd * yd + zd * zd < 1.0f) {
                                Block b = world.getBlockAt(x2, y2, z2);
                                if (b.getTypeId() == 12) {
                                    b.setTypeId(82);
                                }
                            }
                        }
                    }
                }
            }
        }

        private void placeTree(World world, MT19937Random random, int x, int y, int z) {
            int treeHeight = random.nextInt(3) + 4;
            if (y < 1 || y + treeHeight + 1 > 128) return;

            boolean free = true;
            for (int yy = y; yy <= y + 1 + treeHeight; yy++) {
                int r = 1;
                if (yy == y) r = 0;
                if (yy >= y + 1 + treeHeight - 2) r = 2;
                for (int xx = x - r; xx <= x + r && free; xx++) {
                    for (int zz = z - r; zz <= z + r && free; zz++) {
                        if (yy >= 0 && yy < 128) {
                            int tt = world.getBlockAt(xx, yy, zz).getTypeId();
                            if (tt != 0 && tt != 18) {
                                free = false;
                            }
                        } else {
                            free = false;
                        }
                    }
                }
            }
            if (!free) return;

            int below = world.getBlockAt(x, y - 1, z).getTypeId();
            if ((below != 2 && below != 3) || y >= 128 - treeHeight - 1) return;

            world.getBlockAt(x, y - 1, z).setTypeId(3);

            for (int yy = y - 3 + treeHeight; yy <= y + treeHeight; yy++) {
                int yo = yy - (y + treeHeight);
                int offs = 1 - yo / 2;
                for (int xx = x - offs; xx <= x + offs; xx++) {
                    int xo = xx - x;
                    for (int zz = z - offs; zz <= z + offs; zz++) {
                        int zo = zz - z;
                        if (Math.abs(xo) == offs && Math.abs(zo) == offs && (random.nextInt(2) == 0 || yo == 0)) {
                            continue;
                        }
                        Block b = world.getBlockAt(xx, yy, zz);
                        if (b.getTypeId() == 0) {
                            b.setTypeId(18);
                        }
                    }
                }
            }

            for (int hh = 0; hh < treeHeight; hh++) {
                Block b = world.getBlockAt(x, y + hh, z);
                int t = b.getTypeId();
                if (t == 0 || t == 18) {
                    b.setTypeId(17);
                }
            }
        }

        private void placeFlower(World world, MT19937Random random, int x, int y, int z, int tileId) {
            for (int i = 0; i < 64; i++) {
                int x2 = x + random.nextInt(8) - random.nextInt(8);
                int y2 = y + random.nextInt(4) - random.nextInt(4);
                int z2 = z + random.nextInt(8) - random.nextInt(8);
                if (y2 < 1 || y2 >= 128) continue;
                Block b = world.getBlockAt(x2, y2, z2);
                if (b.getTypeId() != 0) continue;
                int below = world.getBlockAt(x2, y2 - 1, z2).getTypeId();
                if (tileId == 39 || tileId == 40) {
                    // Mushroom: allow on most solids; keep simple.
                    if (below != 0 && below != 8 && below != 9 && below != 10 && below != 11) {
                        b.setTypeId(tileId);
                    }
                } else {
                    // Flowers: require grass.
                    if (below == 2) {
                        b.setTypeId(tileId);
                    }
                }
            }
        }

        private void placeReeds(World world, MT19937Random random, int x, int y, int z) {
            for (int i = 0; i < 20; i++) {
                int x2 = x + random.nextInt(4) - random.nextInt(4);
                int y2 = y;
                int z2 = z + random.nextInt(4) - random.nextInt(4);
                if (y2 < 1 || y2 >= 128) continue;

                Block b = world.getBlockAt(x2, y2, z2);
                if (b.getTypeId() != 0) continue;

                int below = world.getBlockAt(x2, y2 - 1, z2).getTypeId();
                if (below != 2 && below != 3 && below != 12) continue;

                boolean adjWater = isWater(world.getBlockAt(x2 - 1, y2 - 1, z2).getTypeId()) ||
                        isWater(world.getBlockAt(x2 + 1, y2 - 1, z2).getTypeId()) ||
                        isWater(world.getBlockAt(x2, y2 - 1, z2 - 1).getTypeId()) ||
                        isWater(world.getBlockAt(x2, y2 - 1, z2 + 1).getTypeId());
                if (!adjWater) continue;

                int h = 2 + random.nextInt(random.nextInt(3) + 1);
                for (int yy = 0; yy < h; yy++) {
                    int yyAbs = y2 + yy;
                    if (yyAbs >= 128) break;
                    Block bb = world.getBlockAt(x2, yyAbs, z2);
                    if (bb.getTypeId() != 0) break;
                    // Basic survivability: avoid solid adjacency at the base.
                    bb.setTypeId(83);
                }
            }
        }

        private void placeCactus(World world, MT19937Random random, int x, int y, int z) {
            for (int i = 0; i < 10; i++) {
                int x2 = x + random.nextInt(8) - random.nextInt(8);
                int y2 = y + random.nextInt(4) - random.nextInt(4);
                int z2 = z + random.nextInt(8) - random.nextInt(8);
                if (y2 < 1 || y2 >= 128) continue;
                if (world.getBlockAt(x2, y2, z2).getTypeId() != 0) continue;

                int h = 1 + random.nextInt(random.nextInt(3) + 1);
                for (int yy = 0; yy < h; yy++) {
                    int yyAbs = y2 + yy;
                    if (yyAbs >= 128) break;
                    if (canCactusSurvive(world, x2, yyAbs, z2)) {
                        world.getBlockAt(x2, yyAbs, z2).setTypeId(81);
                    }
                }
            }
        }

        private boolean canCactusSurvive(World world, int x, int y, int z) {
            int below = world.getBlockAt(x, y - 1, z).getTypeId();
            if (below != 12 && below != 81) return false;

            // Must not touch solid blocks on the sides.
            if (isBlocksMotion(world.getBlockAt(x - 1, y, z).getTypeId())) return false;
            if (isBlocksMotion(world.getBlockAt(x + 1, y, z).getTypeId())) return false;
            if (isBlocksMotion(world.getBlockAt(x, y, z - 1).getTypeId())) return false;
            if (isBlocksMotion(world.getBlockAt(x, y, z + 1).getTypeId())) return false;
            return true;
        }

        private void placeSpring(World world, MT19937Random random, int x, int y, int z, int tileId) {
            if (y <= 1 || y >= 127) return;
            if (world.getBlockAt(x, y + 1, z).getTypeId() != 1) return;
            if (world.getBlockAt(x, y - 1, z).getTypeId() != 1) return;

            int here = world.getBlockAt(x, y, z).getTypeId();
            if (here != 0 && here != 1) return;

            int rockCount = 0;
            if (world.getBlockAt(x - 1, y, z).getTypeId() == 1) rockCount++;
            if (world.getBlockAt(x + 1, y, z).getTypeId() == 1) rockCount++;
            if (world.getBlockAt(x, y, z - 1).getTypeId() == 1) rockCount++;
            if (world.getBlockAt(x, y, z + 1).getTypeId() == 1) rockCount++;

            int holeCount = 0;
            if (world.getBlockAt(x - 1, y, z).getTypeId() == 0) holeCount++;
            if (world.getBlockAt(x + 1, y, z).getTypeId() == 0) holeCount++;
            if (world.getBlockAt(x, y, z - 1).getTypeId() == 0) holeCount++;
            if (world.getBlockAt(x, y, z + 1).getTypeId() == 0) holeCount++;

            if (rockCount == 3 && holeCount == 1) {
                world.getBlockAt(x, y, z).setTypeId(tileId);
            }
        }

        private boolean isWater(int typeId) {
            return typeId == 8 || typeId == 9;
        }
    }
}

    /**
     * Post-generation feature pass mirroring RandomLevelSource::postProcess.
     */
    

    // ---------------- Generator state (ported from RandomLevelSource + BiomeSource) ----------------

    private static final class MCPEWorldGenState {
        private static final int MAX_BUFFER_SIZE = 1024;

        final MT19937Random random;

        final PerlinNoise lperlinNoise1;
        final PerlinNoise lperlinNoise2;
        final PerlinNoise perlinNoise1;
        final PerlinNoise perlinNoise2;
        final PerlinNoise perlinNoise3;
        final PerlinNoise scaleNoise;
        final PerlinNoise depthNoise;
        final PerlinNoise forestNoise;

        private float[] buffer;
        private float[] pnr;
        private float[] ar;
        private float[] br;
        private float[] sr;
        private float[] dr;

        private final MCPEBiomeSource biomeSource;

        MCPEWorldGenState(long seed) {
            this.random = new MT19937Random(seed);

            this.lperlinNoise1 = new PerlinNoise(random, 16);
            this.lperlinNoise2 = new PerlinNoise(random, 16);
            this.perlinNoise1 = new PerlinNoise(random, 8);
            this.perlinNoise2 = new PerlinNoise(random, 4);
            this.perlinNoise3 = new PerlinNoise(random, 4);
            this.scaleNoise = new PerlinNoise(random, 10);
            this.depthNoise = new PerlinNoise(random, 16);
            this.forestNoise = new PerlinNoise(random, 8);

            this.buffer = new float[MAX_BUFFER_SIZE];

            MCPEBiome.initBiomes();
            this.biomeSource = new MCPEBiomeSource(seed);
        }

        MCPEBiomeSource getBiomeSource() {
            return biomeSource;
        }

        float[] getHeights(int x, int y, int z, int xSize, int ySize, int zSize) {
            int size = xSize * ySize * zSize;
            if (size > MAX_BUFFER_SIZE) {
                // Keep behavior: log equivalent omitted.
            }

            float s = 1.0f * 684.412f;
            float hs = 1.0f * 684.412f;

            float[] temperatures = biomeSource.temperatures;
            float[] downfalls = biomeSource.downfalls;

            sr = scaleNoise.getRegion2D(sr, x, z, xSize, zSize, 1.121f, 1.121f, 0.5f);
            dr = depthNoise.getRegion2D(dr, x, z, xSize, zSize, 200.0f, 200.0f, 0.5f);

            pnr = perlinNoise1.getRegion3D(pnr, (float) x, (float) y, (float) z, xSize, ySize, zSize, s / 80.0f, hs / 160.0f, s / 80.0f);
            ar = lperlinNoise1.getRegion3D(ar, (float) x, (float) y, (float) z, xSize, ySize, zSize, s, hs, s);
            br = lperlinNoise2.getRegion3D(br, (float) x, (float) y, (float) z, xSize, ySize, zSize, s, hs, s);

            int p = 0;
            int pp = 0;

            int wScale = 16 / xSize;
            for (int xx = 0; xx < xSize; xx++) {
                int xp = xx * wScale + wScale / 2;
                for (int zz = 0; zz < zSize; zz++) {
                    int zp = zz * wScale + wScale / 2;
                    float temperature = temperatures[xp * 16 + zp];
                    float downfall = downfalls[xp * 16 + zp] * temperature;
                    float dd = 1.0f - downfall;
                    dd = dd * dd;
                    dd = dd * dd;
                    dd = 1.0f - dd;

                    float scale = ((sr[pp] + 256.0f) / 512.0f);
                    scale *= dd;
                    if (scale > 1.0f) scale = 1.0f;

                    float depth = (dr[pp] / 8000.0f);
                    if (depth < 0.0f) depth = -depth * 0.3f;
                    depth = depth * 3.0f - 2.0f;

                    if (depth < 0.0f) {
                        depth = depth / 2.0f;
                        if (depth < -1.0f) depth = -1.0f;
                        depth = depth / 1.4f;
                        depth /= 2.0f;
                        scale = 0.0f;
                    } else {
                        if (depth > 1.0f) depth = 1.0f;
                        depth = depth / 8.0f;
                    }

                    if (scale < 0.0f) scale = 0.0f;
                    scale = scale + 0.5f;
                    depth = depth * ySize / 16.0f;

                    float yCenter = ySize / 2.0f + depth * 4.0f;

                    pp++;

                    for (int yy = 0; yy < ySize; yy++) {
                        float val;

                        float yOffs = (yy - yCenter) * 12.0f / scale;
                        if (yOffs < 0.0f) yOffs *= 4.0f;

                        float bb = ar[p] / 512.0f;
                        float cc = br[p] / 512.0f;

                        float v = (pnr[p] / 10.0f + 1.0f) / 2.0f;
                        if (v < 0.0f) val = bb;
                        else if (v > 1.0f) val = cc;
                        else val = bb + (cc - bb) * v;
                        val -= yOffs;

                        if (yy > ySize - 4) {
                            float slide = (yy - (ySize - 4)) / (4.0f - 1.0f);
                            val = val * (1.0f - slide) + -10.0f * slide;
                        }

                        buffer[p] = val;
                        p++;
                    }
                }
            }

            return buffer;
        }
    }

    private enum MCPEBiome {
        RAINFOREST((byte) 2, (byte) 3),
        SWAMPLAND((byte) 2, (byte) 3),
        SEASONAL_FOREST((byte) 2, (byte) 3),
        FOREST((byte) 2, (byte) 3),
        SAVANNA((byte) 2, (byte) 3),
        SHRUBLAND((byte) 2, (byte) 3),
        TAIGA((byte) 2, (byte) 3),
        DESERT((byte) 12, (byte) 12),
        PLAINS((byte) 2, (byte) 3),
        ICE_DESERT((byte) 12, (byte) 12),
        TUNDRA((byte) 2, (byte) 3);

        final byte topMaterial;
        final byte material;

        MCPEBiome(byte topMaterial, byte material) {
            this.topMaterial = topMaterial;
            this.material = material;
        }

        static void initBiomes() {
            // No-op; enum holds materials. This exists to mirror source init sequencing.
        }

        static MCPEBiome getBiome(float temperature, float downfall) {
            // Mirrors Biome::_getBiome logic.
            downfall *= temperature;
            if (temperature < 0.10f) {
                return TUNDRA;
            } else if (downfall < 0.20f) {
                if (temperature < 0.50f) {
                    return TUNDRA;
                } else if (temperature < 0.95f) {
                    return SAVANNA;
                } else {
                    return DESERT;
                }
            } else if (downfall > 0.5f && temperature < 0.7f) {
                return SWAMPLAND;
            } else if (temperature < 0.50f) {
                return TAIGA;
            } else if (temperature < 0.97f) {
                if (downfall < 0.35f) {
                    return SHRUBLAND;
                } else {
                    return FOREST;
                }
            } else {
                if (downfall < 0.45f) {
                    return PLAINS;
                } else if (downfall < 0.90f) {
                    return SEASONAL_FOREST;
                } else {
                    return RAINFOREST;
                }
            }
        }
    }

    private static final class MCPEBiomeSource {
        private static final float zoom = 2.0f * 1.0f;
        private static final float tempScale = zoom / 80.0f;
        private static final float downfallScale = zoom / 40.0f;
        private static final float noiseScale = 1.0f / 4.0f;

        private final PerlinNoise temperatureMap;
        private final PerlinNoise downfallMap;
        private final PerlinNoise noiseMap;

        float[] temperatures;
        float[] downfalls;
        float[] noises;
        MCPEBiome[] biomes;

        MCPEBiomeSource(long seed) {
            MT19937Random rndTemperature = new MT19937Random(seed * 9871L);
            MT19937Random rndDownfall = new MT19937Random(seed * 39811L);
            MT19937Random rndNoise = new MT19937Random(seed * 543321L);

            this.temperatureMap = new PerlinNoise(rndTemperature, 4);
            this.downfallMap = new PerlinNoise(rndDownfall, 4);
            this.noiseMap = new PerlinNoise(rndNoise, 2);

            this.temperatures = new float[16 * 16];
            this.downfalls = new float[16 * 16];
            this.noises = new float[16 * 16];
            this.biomes = new MCPEBiome[16 * 16];
        }

        MCPEBiome getBiome(int x, int z) {
            return getBiomeBlock(x, z, 1, 1)[0];
        }

        MCPEBiome[] getBiomeBlock(int x, int z, int w, int h) {
            // Note: Source uses (w, w) in one spot; keep behavior: it typically passes 16,16.
            temperatures = temperatureMap.getRegion2D(temperatures, x, z, w, h, tempScale, tempScale, 0.25f);
            downfalls = downfallMap.getRegion2D(downfalls, x, z, w, h, downfallScale, downfallScale, 0.3333f);
            noises = noiseMap.getRegion2D(noises, x, z, w, h, noiseScale, noiseScale, 0.588f);

            int pp = 0;
            for (int yy = 0; yy < w; yy++) {
                for (int xx = 0; xx < h; xx++) {
                    float noise = (noises[pp] * 1.1f + 0.5f);

                    float split2 = 0.01f;
                    float split1 = 1.0f - split2;
                    float temperature = (temperatures[pp] * 0.15f + 0.7f) * split1 + noise * split2;
                    split2 = 0.002f;
                    split1 = 1.0f - split2;
                    float downfall = (downfalls[pp] * 0.15f + 0.5f) * split1 + noise * split2;
                    temperature = 1.0f - ((1.0f - temperature) * (1.0f - temperature));

                    if (temperature < 0.0f) temperature = 0.0f;
                    if (downfall < 0.0f) downfall = 0.0f;
                    if (temperature > 1.0f) temperature = 1.0f;
                    if (downfall > 1.0f) downfall = 1.0f;

                    temperatures[pp] = temperature;
                    downfalls[pp] = downfall;
                    biomes[pp] = MCPEBiome.getBiome(temperature, downfall);
                    pp++;
                }
            }
            return biomes;
        }

        float[] getTemperatureBlock(int x, int z, int w, int h) {
            temperatures = temperatureMap.getRegion2D(temperatures, x, z, w, h, tempScale, tempScale, 0.25f);
            noises = noiseMap.getRegion2D(noises, x, z, w, h, noiseScale, noiseScale, 0.588f);

            int pp = 0;
            for (int yy = 0; yy < w; yy++) {
                for (int xx = 0; xx < h; xx++) {
                    float noise = (noises[pp] * 1.1f + 0.5f);
                    float split2 = 0.01f;
                    float split1 = 1.0f - split2;
                    float temperature = (temperatures[pp] * 0.15f + 0.7f) * split1 + noise * split2;
                    temperature = 1.0f - ((1.0f - temperature) * (1.0f - temperature));
                    if (temperature < 0.0f) temperature = 0.0f;
                    if (temperature > 1.0f) temperature = 1.0f;
                    temperatures[pp] = temperature;
                    pp++;
                }
            }
            return temperatures;
        }
    }

    // ---------------- Noise (ported from PerlinNoise + ImprovedNoise) ----------------

    private static final class PerlinNoise {
        private final ImprovedNoise[] noiseLevels;
        private final int levels;

        PerlinNoise(MT19937Random random, int levels) {
            this.levels = levels;
            this.noiseLevels = new ImprovedNoise[levels];
            for (int i = 0; i < levels; i++) {
                noiseLevels[i] = new ImprovedNoise(random);
            }
        }

        float getValue2D(float x, float z) {
            float value = 0.0f;
            float pow = 1.0f;
            for (int i = 0; i < levels; i++) {
                value += noiseLevels[i].getValue2D(x * pow, z * pow) / pow;
                pow /= 2.0f;
            }
            return value;
        }

        float[] getRegion3D(float[] buffer, float x, float y, float z, int xSize, int ySize, int zSize, float xScale, float yScale, float zScale) {
            int size = xSize * ySize * zSize;
            if (buffer == null || buffer.length < size) {
                buffer = new float[size];
            }
            for (int i = 0; i < size; i++) buffer[i] = 0.0f;

            float pow = 1.0f;
            for (int i = 0; i < levels; i++) {
                noiseLevels[i].add(buffer, x, y, z, xSize, ySize, zSize, xScale * pow, yScale * pow, zScale * pow, pow);
                pow /= 2.0f;
            }

            return buffer;
        }

        float[] getRegion2D(float[] buffer, float x, float z, int xSize, int zSize, float xScale, float zScale, float powIgnored) {
            return getRegion3D(buffer, x, 10.0f, z, xSize, 1, zSize, xScale, 1.0f, zScale);
        }

        float[] getRegion2D(float[] buffer, int x, int z, int xSize, int zSize, float xScale, float zScale, float powIgnored) {
            return getRegion3D(buffer, (float) x, 10.0f, (float) z, xSize, 1, zSize, xScale, 1.0f, zScale);
        }

        // Convenience overload matching MCPEGen.buildSurfaces calls
        float[] getRegion2D(float[] buffer, float x, float z, float yIgnored, int xSize, int zSize, int ySizeIgnored, float xScale, float zScale, float powIgnored) {
            return getRegion3D(buffer, x, 10.0f, z, xSize, 1, zSize, xScale, 1.0f, zScale);
        }
    }

    private static final class ImprovedNoise {
        private final int[] p = new int[512];
        private float xo;
        private float yo;
        private float zo;

        ImprovedNoise(MT19937Random random) {
            init(random);
        }

        void init(MT19937Random random) {
            xo = random.nextFloat() * 256.0f;
            yo = random.nextFloat() * 256.0f;
            zo = random.nextFloat() * 256.0f;
            for (int i = 0; i < 256; i++) {
                p[i] = i;
            }
            for (int i = 0; i < 256; i++) {
                int j = random.nextInt(256 - i) + i;
                int tmp = p[i];
                p[i] = p[j];
                p[j] = tmp;
                p[i + 256] = p[i];
            }
        }

        float getValue2D(float x, float z) {
            return noise(x, 0.0f, z);
        }

        float noise(float _x, float _y, float _z) {
            float x = _x + xo;
            float y = _y + yo;
            float z = _z + zo;

            int xf = (int) x;
            int yf = (int) y;
            int zf = (int) z;

            if (x < xf) xf--;
            if (y < yf) yf--;
            if (z < zf) zf--;

            int X = xf & 255;
            int Y = yf & 255;
            int Z = zf & 255;

            x -= xf;
            y -= yf;
            z -= zf;

            float u = x * x * x * (x * (x * 6 - 15) + 10);
            float v = y * y * y * (y * (y * 6 - 15) + 10);
            float w = z * z * z * (z * (z * 6 - 15) + 10);

            int A = p[X] + Y;
            int AA = p[A] + Z;
            int AB = p[A + 1] + Z;
            int B = p[X + 1] + Y;
            int BA = p[B] + Z;
            int BB = p[B + 1] + Z;

            return lerp(w,
                    lerp(v,
                            lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                            lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                    lerp(v,
                            lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                            lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
        }

        float lerp(float t, float a, float b) {
            return a + t * (b - a);
        }

        float grad2(int hash, float x, float z) {
            int h = hash & 15;
            float u = (1 - ((h & 8) >> 3)) * x;
            float v = h < 4 ? 0.0f : (h == 12 || h == 14) ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        float grad(int hash, float x, float y, float z) {
            int h = hash & 15;
            float u = h < 8 ? x : y;
            float v = h < 4 ? y : (h == 12 || h == 14) ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        void add(float[] buffer, float _x, float _y, float _z, int xSize, int ySize, int zSize, float xs, float ys, float zs, float pow) {
            if (ySize == 1) {
                int pp = 0;
                float scale = 1.0f / pow;
                for (int xx = 0; xx < xSize; xx++) {
                    float x = (_x + xx) * xs + xo;
                    int xf = (int) x;
                    if (x < xf) xf--;
                    int X = xf & 255;
                    x -= xf;
                    float u = x * x * x * (x * (x * 6 - 15) + 10);

                    for (int zz = 0; zz < zSize; zz++) {
                        float z = (_z + zz) * zs + zo;
                        int zf = (int) z;
                        if (z < zf) zf--;
                        int Z = zf & 255;
                        z -= zf;
                        float w = z * z * z * (z * (z * 6 - 15) + 10);

                        int A = p[X] + 0;
                        int AA = p[A] + Z;
                        int B = p[X + 1] + 0;
                        int BA = p[B] + Z;

                        float vv0 = lerp(u, grad2(p[AA], x, z), grad(p[BA], x - 1, 0.0f, z));
                        float vv2 = lerp(u, grad(p[AA + 1], x, 0.0f, z - 1), grad(p[BA + 1], x - 1, 0.0f, z - 1));
                        float val = lerp(w, vv0, vv2);
                        buffer[pp++] += val * scale;
                    }
                }
                return;
            }

            int pp = 0;
            float scale = 1.0f / pow;
            int yOld = -1;
            int A = 0, AA = 0, AB = 0, B = 0, BA = 0, BB = 0;
            float vv0 = 0, vv1 = 0, vv2 = 0, vv3 = 0;

            for (int xx = 0; xx < xSize; xx++) {
                float x = (_x + xx) * xs + xo;
                int xf = (int) x;
                if (x < xf) xf--;
                int X = xf & 255;
                x -= xf;
                float u = x * x * x * (x * (x * 6 - 15) + 10);

                for (int zz = 0; zz < zSize; zz++) {
                    float z = (_z + zz) * zs + zo;
                    int zf = (int) z;
                    if (z < zf) zf--;
                    int Z = zf & 255;
                    z -= zf;
                    float w = z * z * z * (z * (z * 6 - 15) + 10);

                    for (int yy = 0; yy < ySize; yy++) {
                        float y = (_y + yy) * ys + yo;
                        int yf = (int) y;
                        if (y < yf) yf--;
                        int Y = yf & 255;
                        y -= yf;
                        float v = y * y * y * (y * (y * 6 - 15) + 10);

                        if (yy == 0 || Y != yOld) {
                            yOld = Y;
                            A = p[X] + Y;
                            AA = p[A] + Z;
                            AB = p[A + 1] + Z;
                            B = p[X + 1] + Y;
                            BA = p[B] + Z;
                            BB = p[B + 1] + Z;
                            vv0 = lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z));
                            vv1 = lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z));
                            vv2 = lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1));
                            vv3 = lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1));
                        }

                        float v0 = lerp(v, vv0, vv1);
                        float v1 = lerp(v, vv2, vv3);
                        float val = lerp(w, v0, v1);
                        buffer[pp++] += val * scale;
                    }
                }
            }
        }
    }

    // ---------------- Math helpers (ported from util/Mth.h uses) ----------------

    private static final class Mth {
        static final float PI = 3.1415927f;

        static float sin(float v) {
            return (float) Math.sin(v);
        }

        static float cos(float v) {
            return (float) Math.cos(v);
        }
    }

    // ---------------- Random (ported from util/Random.h: MT19937) ----------------

    private static final class MT19937Random {
        private static final int N = 624;
        private static final int M = 397;
        private static final int MATRIX_A = 0x9908b0df;
        private static final int UPPER_MASK = 0x80000000;
        private static final int LOWER_MASK = 0x7fffffff;

        private final int[] mt = new int[N];
        private int mti = N + 1;
        private long seed;

        private boolean haveNextNextGaussian;
        private float nextNextGaussian;

        MT19937Random(long seed) {
            setSeed(seed);
        }

        void setSeed(long seed) {
            this.seed = seed;
            this.mti = N + 1;
            this.haveNextNextGaussian = false;
            this.nextNextGaussian = 0.0f;
            init_genrand((int) (seed & 0xffffffffL));
        }

        long getSeed() {
            return seed;
        }

        int nextInt() {
            return (genrand_int32() >>> 1);
        }

        long nextLong() {
            // Source MT19937 wrapper returns a 31-bit positive value as a "long".
            return (long) (genrand_int32() >>> 1);
        }

        int nextInt(int n) {
            if (n <= 0) return 0;
            long v = (genrand_int32() & 0xffffffffL);
            return (int) (v % (long) n);
        }

        float nextFloat() {
            return (float) nextDouble();
        }

        double nextDouble() {
            // genrand_real2: [0,1)
            long v = (genrand_int32() & 0xffffffffL);
            return (double) v * (1.0 / 4294967296.0);
        }

        float nextGaussian() {
            if (haveNextNextGaussian) {
                haveNextNextGaussian = false;
                return nextNextGaussian;
            }
            float v1, v2, s;
            do {
                v1 = 2.0f * nextFloat() - 1.0f;
                v2 = 2.0f * nextFloat() - 1.0f;
                s = v1 * v1 + v2 * v2;
            } while (s >= 1.0f || s == 0.0f);
            float multiplier = (float) Math.sqrt(-2.0f * Math.log(s) / s);
            nextNextGaussian = v2 * multiplier;
            haveNextNextGaussian = true;
            return v1 * multiplier;
        }

        private void init_genrand(int s) {
            mt[0] = s;
            for (mti = 1; mti < N; mti++) {
                int prev = mt[mti - 1];
                long x = 1812433253L * (prev ^ (prev >>> 30)) + mti;
                mt[mti] = (int) (x & 0xffffffffL);
            }
        }

        private int genrand_int32() {
            int y;
            int[] mag01 = { 0x0, MATRIX_A };

            if (mti >= N) {
                if (mti == N + 1) {
                    init_genrand(5489);
                }

                int kk;
                for (kk = 0; kk < N - M; kk++) {
                    y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                    mt[kk] = mt[kk + M] ^ (y >>> 1) ^ mag01[y & 0x1];
                }
                for (; kk < N - 1; kk++) {
                    y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                    mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ mag01[y & 0x1];
                }
                y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
                mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ mag01[y & 0x1];
                mti = 0;
            }

            y = mt[mti++];

            // Tempering
            y ^= (y >>> 11);
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= (y >>> 18);

            return y;
        }
    }
}
