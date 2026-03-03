import java.util.List;
import java.util.Random;

import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class AlphaFloatingIslands extends JavaPlugin {

    @Override
    public void onEnable() {
        System.out.println("[AlphaFloatingIslands] Enabled.");
    }

    @Override
    public void onDisable() {
        System.out.println("[AlphaFloatingIslands] Disabled.");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        // Multiverse passes the "id" portion after the generator name, e.g. AlphaFloatingIslands:o14
        // We support:
        //   o16 (default) - floatingIslandNoise octaves = 16
        //   o14           - floatingIslandNoise octaves = 14
        int floatingIslandNoiseOctaves = 16;

        if (id != null) {
            String lower = id.toLowerCase();
            if (lower.contains("o14") || lower.contains("oct14") || lower.contains("14")) {
                floatingIslandNoiseOctaves = 14;
            } else if (lower.contains("o16") || lower.contains("oct16") || lower.contains("16")) {
                floatingIslandNoiseOctaves = 16;
            }
        }

        return new AlphaFloatingIslandsGenerator(floatingIslandNoiseOctaves);
    }

    public static final class AlphaFloatingIslandsGenerator extends ChunkGenerator {

        // Beta 1.7.3 block IDs
        private static final byte BLOCK_AIR = 0;
        private static final byte BLOCK_STONE = 1;
        private static final byte BLOCK_GRASS = 2;
        private static final byte BLOCK_DIRT = 3;
        private static final byte BLOCK_SAND = 12;
        private static final byte BLOCK_GRAVEL = 13;

        private static final int WORLD_HEIGHT = 128;
        private static final int SEA_LEVEL = 64; // kept for surface heuristics only (no water is ever placed)

        private final int floatingIslandNoiseOctaves;

        // Noise instances are created per-world-seed (lazy init)
        private long initializedSeed = Long.MIN_VALUE;

        private NoiseGeneratorOctaves noiseA;                 // 10
        private NoiseGeneratorOctaves noiseB;                 // 16
        private NoiseGeneratorOctaves noiseK;                 // 16
        private NoiseGeneratorOctaves noiseL;                 // 16
        private NoiseGeneratorOctaves noiseM;                 // 8
        private NoiseGeneratorOctaves noiseN;                 // 4
        private NoiseGeneratorOctaves noisePerlin3;           // 4
        private NoiseGeneratorOctaves floatingIslandScale;    // 10
        private NoiseGeneratorOctaves floatingIslandNoise;    // 14 or 16

        // Reused buffers (avoid churn)
        private double[] density;
        private double[] sandNoiseR;
        private double[] sandNoiseS;
        private double[] depthBuffer;

        // density builder buffers (reused)
        private double[] bufPnr;
        private double[] bufAr;
        private double[] bufBr;
        private double[] bufSr;
        private double[] bufDr;
        private double[] bufFIs;
        private double[] bufFI;

        public AlphaFloatingIslandsGenerator(int floatingIslandNoiseOctaves) {
            this.floatingIslandNoiseOctaves = floatingIslandNoiseOctaves;
        }

        @Override
        public byte[] generate(World world, Random ignoredByBukkit, int chunkX, int chunkZ) {
            initIfNeeded(world.getSeed());

            byte[] blocks = new byte[16 * 16 * WORLD_HEIGHT];

            // Stone/air only
            generateRawTerrain(chunkX, chunkZ, blocks);

            // Surface pass: grass/dirt/sand/gravel only; NO WATER FILL.
            applySurfaceNoWater(chunkX, chunkZ, blocks);

            return blocks;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            return java.util.Collections.emptyList();
        }

        private void initIfNeeded(long seed) {
            if (initializedSeed == seed) {
                return;
            }
            initializedSeed = seed;

            Random rand = new Random(seed);

            noiseK = new NoiseGeneratorOctaves(rand, 16);
            noiseL = new NoiseGeneratorOctaves(rand, 16);
            noiseM = new NoiseGeneratorOctaves(rand, 8);
            noiseN = new NoiseGeneratorOctaves(rand, 4);
            noisePerlin3 = new NoiseGeneratorOctaves(rand, 4);

            noiseA = new NoiseGeneratorOctaves(rand, 10);
            noiseB = new NoiseGeneratorOctaves(rand, 16);

            floatingIslandScale = new NoiseGeneratorOctaves(rand, 10);
            floatingIslandNoise = new NoiseGeneratorOctaves(rand, floatingIslandNoiseOctaves);
        }

        private void generateRawTerrain(int chunkX, int chunkZ, byte[] blocks) {
            // Alpha-style density grid:
            // cellSize=4 -> xSize=zSize=5; ySize=17 (128/8 + 1)
            final int cellSize = 4;
            final int xSize = cellSize + 1;   // 5
            final int zSize = cellSize + 1;   // 5
            final int ySize = 17;

            density = generateDensities(density, chunkX * cellSize, 0, chunkZ * cellSize, xSize, ySize, zSize);

            // Trilinear interpolation into 16x128x16
            for (int xCell = 0; xCell < cellSize; xCell++) {
                for (int zCell = 0; zCell < cellSize; zCell++) {
                    for (int yCell = 0; yCell < 16; yCell++) {

                        final double yLerpStep = 0.125D; // 1/8

                        double d000 = density[((xCell + 0) * zSize + (zCell + 0)) * ySize + (yCell + 0)];
                        double d001 = density[((xCell + 0) * zSize + (zCell + 0)) * ySize + (yCell + 1)];
                        double d100 = density[((xCell + 1) * zSize + (zCell + 0)) * ySize + (yCell + 0)];
                        double d101 = density[((xCell + 1) * zSize + (zCell + 0)) * ySize + (yCell + 1)];

                        double d010 = density[((xCell + 0) * zSize + (zCell + 1)) * ySize + (yCell + 0)];
                        double d011 = density[((xCell + 0) * zSize + (zCell + 1)) * ySize + (yCell + 1)];
                        double d110 = density[((xCell + 1) * zSize + (zCell + 1)) * ySize + (yCell + 0)];
                        double d111 = density[((xCell + 1) * zSize + (zCell + 1)) * ySize + (yCell + 1)];

                        double d000Step = (d001 - d000) * yLerpStep;
                        double d100Step = (d101 - d100) * yLerpStep;
                        double d010Step = (d011 - d010) * yLerpStep;
                        double d110Step = (d111 - d110) * yLerpStep;

                        for (int ySub = 0; ySub < 8; ySub++) {
                            final double xzLerpStep = 0.25D; // 1/4

                            double d00 = d000;
                            double d10 = d100;
                            double d00Step = (d010 - d000) * xzLerpStep;
                            double d10Step = (d110 - d100) * xzLerpStep;

                            for (int xSub = 0; xSub < 4; xSub++) {
                                int baseX = xCell * 4 + xSub;
                                int baseY = yCell * 8 + ySub;
                                int baseZ = zCell * 4;

                                int index = (baseX << 11) | (baseZ << 7) | baseY; // x*2048 + z*128 + y
                                int yStride = 128;

                                double d0 = d00;
                                double d1Step = (d10 - d00) * xzLerpStep;

                                for (int zSub = 0; zSub < 4; zSub++) {
                                    blocks[index] = (d0 > 0.0D) ? BLOCK_STONE : BLOCK_AIR;
                                    index += yStride;
                                    d0 += d1Step;
                                }

                                d00 += d00Step;
                                d10 += d10Step;
                            }

                            d000 += d000Step;
                            d100 += d100Step;
                            d010 += d010Step;
                            d110 += d110Step;
                        }
                    }
                }
            }
        }

        private void applySurfaceNoWater(int chunkX, int chunkZ, byte[] blocks) {
            // This is the Alpha/Beta surface pass pattern, but:
            // - NO sea-level water fill
            // - NO bedrock placement (we never place it)
            // - NO forced "below sea level = dirt" conversion

            final double scale = 0.03125D;

            sandNoiseR = noiseN.generateNoiseOctaves(
                    sandNoiseR,
                    chunkX * 16.0D, chunkZ * 16.0D, 0.0D,
                    16, 16, 1,
                    scale, scale, 1.0D
            );

            sandNoiseS = noiseN.generateNoiseOctaves(
                    sandNoiseS,
                    chunkZ * 16.0D, 109.0134D, chunkX * 16.0D,
                    16, 1, 16,
                    scale, 1.0D, scale
            );

            depthBuffer = noisePerlin3.generateNoiseOctaves(
                    depthBuffer,
                    chunkX * 16.0D, chunkZ * 16.0D, 0.0D,
                    16, 16, 1,
                    scale * 2.0D, scale * 2.0D, scale * 2.0D
            );

            Random rand = new Random((((long) chunkX) * 341873128712L) + (((long) chunkZ) * 132897987541L));

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    boolean gravelPatch = (sandNoiseR[x + z * 16] + rand.nextDouble() * 0.2D) > 0.0D;
                    boolean sandPatch = (sandNoiseS[x + z * 16] + rand.nextDouble() * 0.2D) > 3.0D;

                    int surfaceDepth = (int) (depthBuffer[x + z * 16] / 3.0D + 3.0D + rand.nextDouble() * 0.25D);

                    int remaining = -1;
                    byte top = BLOCK_GRASS;
                    byte filler = BLOCK_DIRT;

                    for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                        int idx = (x * 16 + z) * WORLD_HEIGHT + y;
                        int cur = blocks[idx] & 0xFF;

                        if (cur == 0) {
                            remaining = -1;
                            continue;
                        }

                        if (cur != (BLOCK_STONE & 0xFF)) {
                            continue;
                        }

                        if (remaining == -1) {
                            if (surfaceDepth <= 0) {
                                top = BLOCK_AIR;
                                filler = BLOCK_STONE;
                            } else if (y >= SEA_LEVEL - 4 && y <= SEA_LEVEL + 1) {
                                // Keep the original patch logic; it affects only the surface mix.
                                top = BLOCK_GRASS;
                                filler = BLOCK_DIRT;

                                if (sandPatch) {
                                    top = BLOCK_AIR;
                                    filler = BLOCK_GRAVEL;
                                }
                                if (sandPatch) {
                                    filler = BLOCK_GRAVEL;
                                }

                                if (gravelPatch) {
                                    top = BLOCK_SAND;
                                }
                                if (gravelPatch) {
                                    filler = BLOCK_SAND;
                                }
                            }

                            // IMPORTANT: No water fill and no "below sea level" special-case.
                            remaining = surfaceDepth;

                            if (y >= SEA_LEVEL - 1) {
                                blocks[idx] = top;
                            } else {
                                blocks[idx] = filler;
                            }
                        } else if (remaining > 0) {
                            remaining--;
                            blocks[idx] = filler;
                        }
                    }
                }
            }
        }

        private double[] generateDensities(double[] out, int x, int y, int z, int xSize, int ySize, int zSize) {
            if (out == null) {
                out = new double[xSize * ySize * zSize];
            }

            final double baseScaleX = 684.412D;
            final double baseScaleZ = 684.412D;

            // floating island control noises (2D effectively)
            bufFIs = floatingIslandScale.generateNoiseOctaves(
                    bufFIs,
                    x, z, y,
                    xSize, 1, zSize,
                    1.0D, 0.0D, 1.0D
            );

            bufFI = floatingIslandNoise.generateNoiseOctaves(
                    bufFI,
                    x, z, y,
                    xSize, 1, zSize,
                    500.0D, 0.0D, 500.0D
            );

            bufSr = noiseA.generateNoiseOctaves(
                    bufSr,
                    x, z, y,
                    xSize, 1, zSize,
                    1.0D, 0.0D, 1.0D
            );

            bufDr = noiseB.generateNoiseOctaves(
                    bufDr,
                    x, z, y,
                    xSize, 1, zSize,
                    100.0D, 0.0D, 100.0D
            );

            bufPnr = noiseM.generateNoiseOctaves(
                    bufPnr,
                    x, z, y,
                    xSize, ySize, zSize,
                    baseScaleX / 80.0D, baseScaleZ / 160.0D, baseScaleX / 80.0D
            );

            bufAr = noiseK.generateNoiseOctaves(
                    bufAr,
                    x, z, y,
                    xSize, ySize, zSize,
                    baseScaleX, baseScaleZ, baseScaleX
            );

            bufBr = noiseL.generateNoiseOctaves(
                    bufBr,
                    x, z, y,
                    xSize, ySize, zSize,
                    baseScaleX, baseScaleZ, baseScaleX
            );

            int idx2D = 0;
            int idx3D = 0;

            for (int xPos = 0; xPos < xSize; xPos++) {
                for (int zPos = 0; zPos < zSize; zPos++) {

                    double islandScale = (bufFIs[idx2D] + 256.0D) / 512.0D;
                    if (islandScale > 1.0D) islandScale = 1.0D;

                    double islandAbs = bufFI[idx2D] / 8000.0D;
                    if (islandAbs < 0.0D) islandAbs = -islandAbs;
                    islandAbs += 0.5D;

                    if (islandScale < 0.0D) islandScale = 0.0D;
                    islandScale += 0.5D;

                    islandAbs = islandAbs * ySize / 1.0D;

                    double sr = (bufSr[idx2D] + 256.0D) / 512.0D;
                    if (sr > 1.0D) sr = 1.0D;

                    double cutoffY = islandAbs / islandScale;

                    double dr = bufDr[idx2D] / 8000.0D;
                    if (dr < 0.0D) dr = -dr;
                    dr = dr * 3.0D - 3.0D;

                    if (dr < 0.0D) {
                        dr /= 2.0D;
                        if (dr < -1.0D) dr = -1.0D;
                        dr /= 1.4D;
                        dr /= 2.0D;
                        sr = 0.0D;
                    } else {
                        if (dr > 1.0D) dr = 1.0D;
                        dr /= 6.0D;
                    }

                    sr += 0.5D;

                    dr = dr * ySize / 16.0D;

                    double mid = (ySize / 2.0D) + dr * 4.0D;

                    idx2D++;

                    for (int yPos = 0; yPos < ySize; yPos++) {
                        double dy = (yPos - mid) * 12.0D / sr;
                        if (dy < 0.0D) dy *= 4.0D;

                        double aVal = bufAr[idx3D] / 512.0D;
                        double bVal = bufBr[idx3D] / 512.0D;

                        double blend = (bufPnr[idx3D] / 10.0D + 1.0D) / 2.0D;

                        double shape;
                        if (blend < 0.0D) {
                            shape = aVal;
                        } else if (blend > 1.0D) {
                            shape = bVal;
                        } else {
                            shape = aVal + (bVal - aVal) * blend;
                        }

                        shape -= dy;

                        // Upper fade to -10 near top
                        if (yPos > ySize - 4) {
                            double f = (yPos - (ySize - 4)) / 3.0D;
                            shape = shape * (1.0D - f) + (-10.0D) * f;
                        }

                        // Lower void carving using cutoffY (from floating island noise scaling)
                        if (yPos < cutoffY) {
                            double f = (cutoffY - yPos) / 4.0D;
                            if (f < 0.0D) f = 0.0D;
                            if (f > 1.0D) f = 1.0D;
                            shape = shape * (1.0D - f) + (-10.0D) * f;
                        }

                        out[idx3D++] = shape;
                    }
                }
            }

            return out;
        }
    }

    // --------------------------
    // Noise implementation
    // --------------------------

    private static final class NoiseGeneratorOctaves {
        private final NoiseGeneratorImproved[] generators;
        private final int octaves;

        NoiseGeneratorOctaves(Random rand, int octaves) {
            this.octaves = octaves;
            this.generators = new NoiseGeneratorImproved[octaves];
            for (int i = 0; i < octaves; i++) {
                this.generators[i] = new NoiseGeneratorImproved(rand);
            }
        }

        double[] generateNoiseOctaves(
                double[] out,
                double x, double y, double z,
                int xSize, int ySize, int zSize,
                double xScale, double yScale, double zScale
        ) {
            int totalSize = xSize * ySize * zSize;
            if (out == null || out.length < totalSize) {
                out = new double[totalSize];
            } else {
                for (int i = 0; i < totalSize; i++) {
                    out[i] = 0.0D;
                }
            }

            double frequency = 1.0D;

            for (int octave = 0; octave < this.octaves; octave++) {
                double sx = xScale * frequency;
                double sy = yScale * frequency;
                double sz = zScale * frequency;

                generators[octave].populateNoiseArray(out, x, y, z, xSize, ySize, zSize, sx, sy, sz, frequency);

                frequency /= 2.0D;
            }

            return out;
        }
    }

    private static final class NoiseGeneratorImproved {
        private final int[] permutations = new int[512];

        NoiseGeneratorImproved(Random rand) {
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) {
                p[i] = i;
            }
            for (int i = 0; i < 256; i++) {
                int j = rand.nextInt(256 - i) + i;
                int tmp = p[i];
                p[i] = p[j];
                p[j] = tmp;
            }
            for (int i = 0; i < 512; i++) {
                permutations[i] = p[i & 255];
            }
        }

        void populateNoiseArray(
                double[] out,
                double x, double y, double z,
                int xSize, int ySize, int zSize,
                double xScale, double yScale, double zScale,
                double amplitudeScale
        ) {
            int idx = 0;
            double invAmp = 1.0D / amplitudeScale;

            for (int xi = 0; xi < xSize; xi++) {
                double fx = (x + xi) * xScale;
                int X = fastFloor(fx) & 255;
                double xFrac = fx - fastFloor(fx);
                double u = fade(xFrac);

                for (int zi = 0; zi < zSize; zi++) {
                    double fz = (z + zi) * zScale;
                    int Z = fastFloor(fz) & 255;
                    double zFrac = fz - fastFloor(fz);
                    double w = fade(zFrac);

                    for (int yi = 0; yi < ySize; yi++) {
                        double fy = (y + yi) * yScale;
                        int Y = fastFloor(fy) & 255;
                        double yFrac = fy - fastFloor(fy);
                        double v = fade(yFrac);

                        int A = permutations[X] + Y;
                        int AA = permutations[A] + Z;
                        int AB = permutations[A + 1] + Z;

                        int B = permutations[X + 1] + Y;
                        int BA = permutations[B] + Z;
                        int BB = permutations[B + 1] + Z;

                        double x1 = lerp(u,
                                grad(permutations[AA], xFrac, yFrac, zFrac),
                                grad(permutations[BA], xFrac - 1.0D, yFrac, zFrac)
                        );
                        double x2 = lerp(u,
                                grad(permutations[AB], xFrac, yFrac - 1.0D, zFrac),
                                grad(permutations[BB], xFrac - 1.0D, yFrac - 1.0D, zFrac)
                        );
                        double y1 = lerp(v, x1, x2);

                        double x3 = lerp(u,
                                grad(permutations[AA + 1], xFrac, yFrac, zFrac - 1.0D),
                                grad(permutations[BA + 1], xFrac - 1.0D, yFrac, zFrac - 1.0D)
                        );
                        double x4 = lerp(u,
                                grad(permutations[AB + 1], xFrac, yFrac - 1.0D, zFrac - 1.0D),
                                grad(permutations[BB + 1], xFrac - 1.0D, yFrac - 1.0D, zFrac - 1.0D)
                        );
                        double y2 = lerp(v, x3, x4);

                        double value = lerp(w, y1, y2);

                        out[idx++] += value * invAmp;
                    }
                }
            }
        }

        private static int fastFloor(double d) {
            int i = (int) d;
            return d < i ? i - 1 : i;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = (h < 8) ? x : y;
            double v = (h < 4) ? y : ((h == 12 || h == 14) ? x : z);
            return (((h & 1) == 0) ? u : -u) + (((h & 2) == 0) ? v : -v);
        }
    }
}