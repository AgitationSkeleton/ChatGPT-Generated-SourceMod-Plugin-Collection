package tron;

import java.io.*;
import java.util.*;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TronGrid - TRON: Legacy canyon-city Grid world generator for Minecraft Beta 1.7.3 (Poseidon/Bukkit)
 * Author: ChatGPT
 *
 * v3 goals:
 * - Bias toward "urban canyon" aesthetic (few huge masses, narrow corridors, strong vertical framing).
 * - Seams and edges matter: glowstone + lapis used as thin insets/bands, not large surfaces.
 * - Zoning via deterministic districts: CanyonCity, PlazaFields, PlatformFields, Outlands, Void.
 * - Flynn's Arcade: very rare human-scale landmark embedded in city.
 *
 * Notes:
 * - Uses legacy ChunkGenerator byte[] generation.
 * - Avoids Bukkit configuration classes (uses config.properties).
 * - Populators only write inside their current chunk slice.
 */
public class TronGrid extends JavaPlugin {

    // ---- Block IDs (Beta 1.7.3) ----
    private static final int AIR = 0;
    private static final int BEDROCK = 7;
    private static final int STONE = 1;
    private static final int COBBLE = 4;
    private static final int WOOD_PLANKS = 5;
    private static final int GLASS = 20;
    private static final int LAPIS_BLOCK = 22;
    private static final int OBSIDIAN = 49;
    private static final int TORCH = 50;
    private static final int CHEST = 54;
    private static final int SNOW_LAYER = 78;
    private static final int GLOWSTONE = 89;
    private static final int IRON_BLOCK = 42;
    private static final int DIAMOND_BLOCK = 57;
    private static final int LAVA = 11;

    // ---- Item IDs (Beta 1.7.3) ----
    private static final int IT_APPLE = 260;
    private static final int IT_BREAD = 297;
    private static final int IT_ARROW = 262;
    private static final int IT_IRON = 265;
    private static final int IT_GOLD = 266;
    private static final int IT_REDSTONE = 331;
    private static final int IT_GLOW_DUST = 348;
    private static final int IT_COAL = 263;
    private static final int IT_DIAMOND = 264;

    private final Properties props = new Properties();

    // Base plane
    private int floorY = 32;
    private int subfloorDepth = 4;

    // Circuits (inlaid)
    private int majorCell = 64;
    private int minorCell = 16;
    private double minorCircuitChance = 0.62; // per major cell

    // District zoning
    private int districtSize = 320; // larger = more obvious macro-zones
    private double districtActiveChance = 0.70;
    private double voidChance = 0.06;
    private double outlandsChance = 0.16;
    private double platformChance = 0.12;
    private double plazaChance = 0.18; // within city

    // Canyon city controls
    private double canyonDistrictChance = 0.62; // city districts become canyon-style
    private int corridorWidthMin = 14;
    private int corridorWidthMax = 26;

    private int massFootprintMin = 18;
    private int massFootprintMax = 44;

    // Heights are tiered (bands)
    private int[] heightBands = new int[] { 48, 64, 80, 96, 112 };
    private double tallBandChance = 0.55;

    // Seams
    private double lapisSeamChance = 0.33;      // per building edge run
    private double glowSeamChance = 0.18;       // rarer than lapis
    private int seamSpacing = 3;                // vertical dash spacing

    // Skyways
    private double skywayChance = 0.28;
    private int skywayMinY = 50;
    private int skywayMaxY = 86;

    // Trenches / energy
    private double trenchChance = 0.28;         // lower than earlier; used as rare “design scars”
    private int trenchDepthMin = 10;
    private int trenchDepthMax = 24;

    private double energyPoolChance = 0.18;     // rarer and more “special”

    // Flynn’s Arcade
    private double flynnsArcadeChance = 0.010;  // per active city district (very rare)

    // Loot
    private double lootChestChance = 0.55;

    private enum DistrictType {
        CITY, OUTLANDS, PLATFORM, VOID
    }

    @Override
    public void onEnable() {
        loadProps();
        getServer().getLogger().info("[TronGrid] Enabled (v3). Generator name: TronGrid");
    }

    @Override
    public void onDisable() {
        getServer().getLogger().info("[TronGrid] Disabled.");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new TronGridGenerator();
    }

    // -------------------------------------------------------------------------
    // Config (config.properties)
    // -------------------------------------------------------------------------
    private void loadProps() {
        File dir = getDataFolder();
        if (!dir.exists()) dir.mkdirs();

        File cfg = new File(dir, "config.properties");
        if (!cfg.exists()) writeDefaultProps(cfg);

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cfg);
            props.load(fis);

            floorY = getInt("floorY", floorY);
            subfloorDepth = getInt("subfloorDepth", subfloorDepth);

            majorCell = getInt("majorCell", majorCell);
            minorCell = getInt("minorCell", minorCell);
            minorCircuitChance = getDouble("minorCircuitChance", minorCircuitChance);

            districtSize = getInt("districtSize", districtSize);
            districtActiveChance = getDouble("districtActiveChance", districtActiveChance);
            voidChance = getDouble("voidChance", voidChance);
            outlandsChance = getDouble("outlandsChance", outlandsChance);
            platformChance = getDouble("platformChance", platformChance);
            plazaChance = getDouble("plazaChance", plazaChance);

            canyonDistrictChance = getDouble("canyonDistrictChance", canyonDistrictChance);
            corridorWidthMin = getInt("corridorWidthMin", corridorWidthMin);
            corridorWidthMax = getInt("corridorWidthMax", corridorWidthMax);

            massFootprintMin = getInt("massFootprintMin", massFootprintMin);
            massFootprintMax = getInt("massFootprintMax", massFootprintMax);

            tallBandChance = getDouble("tallBandChance", tallBandChance);

            lapisSeamChance = getDouble("lapisSeamChance", lapisSeamChance);
            glowSeamChance = getDouble("glowSeamChance", glowSeamChance);
            seamSpacing = getInt("seamSpacing", seamSpacing);

            skywayChance = getDouble("skywayChance", skywayChance);
            skywayMinY = getInt("skywayMinY", skywayMinY);
            skywayMaxY = getInt("skywayMaxY", skywayMaxY);

            trenchChance = getDouble("trenchChance", trenchChance);
            trenchDepthMin = getInt("trenchDepthMin", trenchDepthMin);
            trenchDepthMax = getInt("trenchDepthMax", trenchDepthMax);

            energyPoolChance = getDouble("energyPoolChance", energyPoolChance);

            flynnsArcadeChance = getDouble("flynnsArcadeChance", flynnsArcadeChance);
            lootChestChance = getDouble("lootChestChance", lootChestChance);

            // clamps
            if (floorY < 8) floorY = 8;
            if (floorY > 100) floorY = 100;
            if (subfloorDepth < 1) subfloorDepth = 1;
            if (subfloorDepth > 16) subfloorDepth = 16;

            if (majorCell < 16) majorCell = 16;
            if (minorCell < 4) minorCell = 4;
            if (minorCell > majorCell) minorCell = majorCell;

            if (districtSize < 128) districtSize = 128;
            if (districtSize > 1024) districtSize = 1024;

            if (corridorWidthMin < 8) corridorWidthMin = 8;
            if (corridorWidthMax < corridorWidthMin) corridorWidthMax = corridorWidthMin;

            if (massFootprintMin < 12) massFootprintMin = 12;
            if (massFootprintMax < massFootprintMin) massFootprintMax = massFootprintMin;

            if (seamSpacing < 2) seamSpacing = 2;
            if (seamSpacing > 8) seamSpacing = 8;

            if (skywayMinY < floorY + 10) skywayMinY = floorY + 10;
            if (skywayMaxY < skywayMinY + 8) skywayMaxY = skywayMinY + 8;
            if (skywayMaxY > 120) skywayMaxY = 120;

        } catch (Exception ex) {
            getServer().getLogger().warning("[TronGrid] Failed to read config.properties; using defaults: " + ex.getMessage());
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        }
    }

    private void writeDefaultProps(File cfg) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cfg);
            StringBuilder sb = new StringBuilder();
            sb.append("# TronGrid v3 config (Beta 1.7.3)\n");
            sb.append("floorY=").append(floorY).append("\n");
            sb.append("subfloorDepth=").append(subfloorDepth).append("\n\n");

            sb.append("# Circuits (inlaid)\n");
            sb.append("majorCell=").append(majorCell).append("\n");
            sb.append("minorCell=").append(minorCell).append("\n");
            sb.append("minorCircuitChance=").append(minorCircuitChance).append("\n\n");

            sb.append("# District zoning\n");
            sb.append("districtSize=").append(districtSize).append("\n");
            sb.append("districtActiveChance=").append(districtActiveChance).append("\n");
            sb.append("voidChance=").append(voidChance).append("\n");
            sb.append("outlandsChance=").append(outlandsChance).append("\n");
            sb.append("platformChance=").append(platformChance).append("\n");
            sb.append("plazaChance=").append(plazaChance).append("\n\n");

            sb.append("# Canyon city look\n");
            sb.append("canyonDistrictChance=").append(canyonDistrictChance).append("\n");
            sb.append("corridorWidthMin=").append(corridorWidthMin).append("\n");
            sb.append("corridorWidthMax=").append(corridorWidthMax).append("\n");
            sb.append("massFootprintMin=").append(massFootprintMin).append("\n");
            sb.append("massFootprintMax=").append(massFootprintMax).append("\n");
            sb.append("tallBandChance=").append(tallBandChance).append("\n\n");

            sb.append("# Seams & accents\n");
            sb.append("lapisSeamChance=").append(lapisSeamChance).append("\n");
            sb.append("glowSeamChance=").append(glowSeamChance).append("\n");
            sb.append("seamSpacing=").append(seamSpacing).append("\n\n");

            sb.append("# Skyways\n");
            sb.append("skywayChance=").append(skywayChance).append("\n");
            sb.append("skywayMinY=").append(skywayMinY).append("\n");
            sb.append("skywayMaxY=").append(skywayMaxY).append("\n\n");

            sb.append("# Trenches & energy\n");
            sb.append("trenchChance=").append(trenchChance).append("\n");
            sb.append("trenchDepthMin=").append(trenchDepthMin).append("\n");
            sb.append("trenchDepthMax=").append(trenchDepthMax).append("\n");
            sb.append("energyPoolChance=").append(energyPoolChance).append("\n\n");

            sb.append("# Flynn's Arcade\n");
            sb.append("flynnsArcadeChance=").append(flynnsArcadeChance).append("\n\n");

            sb.append("# Loot\n");
            sb.append("lootChestChance=").append(lootChestChance).append("\n");

            fos.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            getServer().getLogger().warning("[TronGrid] Failed to write default config.properties: " + ex.getMessage());
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception ignored) { return def; }
    }

    private double getDouble(String key, double def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try { return Double.parseDouble(v.trim()); } catch (Exception ignored) { return def; }
    }

    // -------------------------------------------------------------------------
    // Generator: flat obsidian plane w/ stone under + bedrock at 0
    // -------------------------------------------------------------------------
    private final class TronGridGenerator extends ChunkGenerator {
        @Override
        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            byte[] blocks = new byte[16 * 16 * 128];

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    setBlock(blocks, x, 0, z, BEDROCK);

                    int stoneStart = Math.max(1, floorY - subfloorDepth);
                    for (int y = 1; y < floorY; y++) {
                        setBlock(blocks, x, y, z, STONE);
                    }

                    // surface
                    setBlock(blocks, x, floorY, z, OBSIDIAN);

                    // air above default (already 0)
                }
            }

            return blocks;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> pops = new ArrayList<BlockPopulator>();
            pops.add(new CircuitPopulator());
            pops.add(new TrenchEnergyPopulator());
            pops.add(new StructuresPopulator());
            pops.add(new OutlandsPopulator());
            return pops;
        }
		
		@Override
		public boolean canSpawn(World world, int x, int z) {
			// Prefer spawning on the floor plane where the player has 2 blocks of headroom.
			int y = floorY;
		
			int below = world.getBlockAt(x, y, z).getTypeId();
			if (below == AIR) return false;
		
			int head1 = world.getBlockAt(x, y + 1, z).getTypeId();
			int head2 = world.getBlockAt(x, y + 2, z).getTypeId();
			if (head1 != AIR) return false;
			if (head2 != AIR) return false;
		
			// Avoid lava/energy pool surfaces just in case (shouldn’t be at floorY normally, but safe).
			if (below == LAVA) return false;
		
			// If we ever change the floor material in some districts, still allow solid blocks.
			return true;
		}

        private void setBlock(byte[] blocks, int x, int y, int z, int typeId) {
            if (y < 0 || y >= 128) return;
            blocks[(x * 16 + z) * 128 + y] = (byte) (typeId & 0xFF);
        }
    }
	
    // -------------------------------------------------------------------------
    // District selection helpers (deterministic per district)
    // -------------------------------------------------------------------------
    private DistrictType getDistrictType(long seed, int districtX, int districtZ) {
        long h = hash64(seed ^ 0xBADC0FFEE1234L, districtX, districtZ);
        double r = u01(h);

        if (r < voidChance) return DistrictType.VOID;

        r -= voidChance;
        if (r < outlandsChance) return DistrictType.OUTLANDS;

        r -= outlandsChance;
        if (r < platformChance) return DistrictType.PLATFORM;

        return DistrictType.CITY;
    }

    private boolean isDistrictActive(long seed, int districtX, int districtZ) {
        long h = hash64(seed ^ 0x13579BDF2468L, districtX, districtZ);
        return u01(h) <= districtActiveChance;
    }

    private boolean isCityPlazaDistrict(long seed, int districtX, int districtZ) {
        long h = hash64(seed ^ 0xC0FFEE123456L, districtX, districtZ);
        return u01(h) < plazaChance;
    }

    private boolean isCanyonCityDistrict(long seed, int districtX, int districtZ) {
        long h = hash64(seed ^ 0xDEADBEEF0011L, districtX, districtZ);
        return u01(h) < canyonDistrictChance;
    }

    // -------------------------------------------------------------------------
    // Populator: inlaid circuits
    // - Glowstone is placed at floorY (replacing obsidian) = sunken/inlaid look.
    // - Occasional small “pips” of glass above major intersections.
    // -------------------------------------------------------------------------
    private final class CircuitPopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            long seed = world.getSeed();

            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = startX + dx;
                    int z = startZ + dz;

                    boolean major = (mod(x, majorCell) == 0) || (mod(z, majorCell) == 0);
                    boolean minor = (mod(x, minorCell) == 0) || (mod(z, minorCell) == 0);

                    if (!major && !minor) continue;

                    if (!major) {
                        // per-major-cell variation so minor lines sometimes die out
                        int cellX = floorDiv(x, majorCell);
                        int cellZ = floorDiv(z, majorCell);
                        long h = hash64(seed ^ 0xA1B2C3D4L, cellX, cellZ);
                        if (u01(h) > minorCircuitChance) continue;
                    }

                    // Inlaid at floorY (sunken look)
                    setTypeIdInChunk(world, x, floorY, z, chunkX, chunkZ, GLOWSTONE);

                    // tiny “stud” at major intersections (glass cap)
                    if (major && mod(x, majorCell) == 0 && mod(z, majorCell) == 0) {
                        long h2 = hash64(seed ^ 0x55AA7711L, x, z);
                        if ((h2 & 3L) == 0L) {
                            setTypeIdInChunk(world, x, floorY + 1, z, chunkX, chunkZ, GLASS);
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Populator: rare symmetrical trenches + rare energy pools
    // -------------------------------------------------------------------------
    private final class TrenchEnergyPopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            long seed = world.getSeed();

            int districtX = floorDiv(startX, districtSize);
            int districtZ = floorDiv(startZ, districtSize);

            DistrictType type = getDistrictType(seed, districtX, districtZ);
            if (type == DistrictType.VOID) return;

            long dHash = hash64(seed ^ 0x7777AAAAL, districtX, districtZ);
            Random rnd = new Random(dHash ^ 0x1234ABCDL);

            if (!isDistrictActive(seed, districtX, districtZ)) return;

            // Trenches (rare)
            if (rnd.nextDouble() < trenchChance) {
                boolean alongX = rnd.nextBoolean();
                int depth = trenchDepthMin + rnd.nextInt(Math.max(1, trenchDepthMax - trenchDepthMin + 1));

                // align trench center to major grid to keep symmetry
                int trenchLine;
                if (alongX) {
                    trenchLine = (districtX * districtSize) + (districtSize / 2) + rnd.nextInt(majorCell) - (majorCell / 2);
                    trenchLine = trenchLine - mod(trenchLine, minorCell);
                    carveTrenchSlice(world, chunkX, chunkZ, trenchLine, alongX, depth);
                } else {
                    trenchLine = (districtZ * districtSize) + (districtSize / 2) + rnd.nextInt(majorCell) - (majorCell / 2);
                    trenchLine = trenchLine - mod(trenchLine, minorCell);
                    carveTrenchSlice(world, chunkX, chunkZ, trenchLine, alongX, depth);
                }
            }

            // Energy pools (very occasional “special” nodes)
            if (rnd.nextDouble() < energyPoolChance) {
                int cx = (districtX * districtSize) + 64 + rnd.nextInt(Math.max(1, districtSize - 128));
                int cz = (districtZ * districtSize) + 64 + rnd.nextInt(Math.max(1, districtSize - 128));
                buildEnergyPoolSlice(world, seed ^ 0xE11E11E1L, chunkX, chunkZ, cx, floorY, cz);
            }
        }

        private void carveTrenchSlice(World world, int chunkX, int chunkZ, int line, boolean alongX, int depth) {
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            // trench width is narrow (matches “designed scar”)
            int halfW = 2;

            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = startX + dx;
                    int z = startZ + dz;

                    int d = alongX ? Math.abs(z - line) : Math.abs(x - line);
                    if (d > halfW) continue;

                    for (int i = 0; i <= depth; i++) {
                        int y = floorY - i;
                        if (y < 2) continue;
                        setTypeIdInChunk(world, x, y, z, chunkX, chunkZ, AIR);
                    }

                    // thin glowing edge at top rim
                    if (d == halfW) {
                        setTypeIdInChunk(world, x, floorY, z, chunkX, chunkZ, GLOWSTONE);
                    }
                }
            }
        }

        private void buildEnergyPoolSlice(World world, long seed, int chunkX, int chunkZ, int cx, int y, int cz) {
            // small 9x9 “node”
            int r = 4;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    int dx = x - cx;
                    int dz = z - cz;
                    int dist2 = dx * dx + dz * dz;
                    if (dist2 > (r * r)) continue;

                    // carve shallow bowl
                    setIfInChunk(world, chunkX, chunkZ, x, y, z, AIR);
                    setIfInChunk(world, chunkX, chunkZ, x, y - 1, z, GLASS);

                    // random “hot” nodes (lava under glass)
                    long h = hash64(seed ^ 0xCAFEBABEL, x, z);
                    if ((h & 15L) == 0L) {
                        setIfInChunk(world, chunkX, chunkZ, x, y - 2, z, LAVA);
                    } else {
                        setIfInChunk(world, chunkX, chunkZ, x, y - 2, z, GLOWSTONE);
                    }
                }
            }

            // ring border in iron
            for (int x = cx - r - 1; x <= cx + r + 1; x++) {
                setIfInChunk(world, chunkX, chunkZ, x, y, cz - r - 1, IRON_BLOCK);
                setIfInChunk(world, chunkX, chunkZ, x, y, cz + r + 1, IRON_BLOCK);
            }
            for (int z = cz - r - 1; z <= cz + r + 1; z++) {
                setIfInChunk(world, chunkX, chunkZ, cx - r - 1, y, z, IRON_BLOCK);
                setIfInChunk(world, chunkX, chunkZ, cx + r + 1, y, z, IRON_BLOCK);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Populator: city masses (canyon), plazas, platform fields, plus Flynn's Arcade
    // -------------------------------------------------------------------------
    private final class StructuresPopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            long seed = world.getSeed();
            int districtX = floorDiv(startX, districtSize);
            int districtZ = floorDiv(startZ, districtSize);

            DistrictType dtype = getDistrictType(seed, districtX, districtZ);
            if (dtype == DistrictType.VOID) return;
            if (!isDistrictActive(seed, districtX, districtZ)) return;

            long dHash = hash64(seed ^ 0x0F0E0D0CL, districtX, districtZ);
            Random rnd = new Random(dHash ^ 0xABCDEF1234L);

            int originX = districtX * districtSize;
            int originZ = districtZ * districtSize;

            if (dtype == DistrictType.PLATFORM) {
                buildPlatformFieldsSlice(world, seed ^ 0x22224444L, chunkX, chunkZ, originX, originZ, rnd);
                return;
            }

            if (dtype == DistrictType.CITY) {
                boolean plaza = isCityPlazaDistrict(seed, districtX, districtZ);

                if (plaza) {
                    buildMegaPlazaSlice(world, seed ^ 0x9999AAAA, chunkX, chunkZ, originX, originZ, rnd);
                } else if (isCanyonCityDistrict(seed, districtX, districtZ)) {
                    buildCanyonCitySlice(world, seed ^ 0x5555BBBB, chunkX, chunkZ, originX, originZ, rnd);
                } else {
                    // fallback: sparse masses
                    buildSparseMassesSlice(world, seed ^ 0x11113333L, chunkX, chunkZ, originX, originZ, rnd);
                }

                // Flynn's Arcade: very rare per active city district
                if (rnd.nextDouble() < flynnsArcadeChance) {
                    int ax = originX + (districtSize / 2) + rnd.nextInt(80) - 40;
                    int az = originZ + (districtSize / 2) + rnd.nextInt(80) - 40;
                    buildFlynnsArcadeSlice(world, seed ^ 0xF17AA5L, chunkX, chunkZ, ax, floorY, az);
                }

                return;
            }
        }

        // ---- Canyon City: two or three huge “walls” framing narrow corridors ----
        private void buildCanyonCitySlice(World world, long seed, int chunkX, int chunkZ, int originX, int originZ, Random rnd) {
            boolean alongX = rnd.nextBoolean(); // corridor axis
            int corridorWidth = corridorWidthMin + rnd.nextInt(Math.max(1, corridorWidthMax - corridorWidthMin + 1));

            // corridor centerline aligned to major grid for legibility
            int center = (alongX ? (originZ + districtSize / 2) : (originX + districtSize / 2));
            center = center - mod(center, minorCell);

            // build two primary walls
            int wallOffset = corridorWidth / 2 + 4;
            int wallA = center - wallOffset;
            int wallB = center + wallOffset;

            buildWallMassSlice(world, seed ^ 0xAAAABBBBL, chunkX, chunkZ, originX, originZ, alongX, wallA, rnd);
            buildWallMassSlice(world, seed ^ 0xCCCCDDDDL, chunkX, chunkZ, originX, originZ, alongX, wallB, rnd);

            // occasional third mass to create choke-points / framing
            if (rnd.nextDouble() < 0.35) {
                int wallC = center + (rnd.nextBoolean() ? (wallOffset + 10) : -(wallOffset + 10));
                buildWallMassSlice(world, seed ^ 0xEEEE1111L, chunkX, chunkZ, originX, originZ, alongX, wallC, rnd);
            }

            // skyways: connect across corridor at a mid-high band
            if (rnd.nextDouble() < skywayChance) {
                int y = chooseSkywayY(rnd);
                int spanMin = 24;
                int spanMax = 56;
                int span = spanMin + rnd.nextInt(spanMax - spanMin + 1);

                int bx = originX + 64 + rnd.nextInt(Math.max(1, districtSize - 128));
                int bz = originZ + 64 + rnd.nextInt(Math.max(1, districtSize - 128));

                buildSkywaySlice(world, seed ^ 0x5B1A7L, chunkX, chunkZ, bx, y, bz, alongX, span);
            }
        }

        private void buildWallMassSlice(World world, long seed, int chunkX, int chunkZ, int originX, int originZ, boolean corridorAlongX, int wallLine, Random rnd) {
            // Massive slab: long in one axis, thick in the other, with carving + seams
            int longLen = 140 + rnd.nextInt(120); // 140..259
            int thick = 18 + rnd.nextInt(26);     // 18..43

            int halfLong = longLen / 2;
            int halfThick = thick / 2;

            int centerLong = (corridorAlongX ? (originX + districtSize / 2) : (originZ + districtSize / 2));
            centerLong += rnd.nextInt(120) - 60;
            centerLong = centerLong - mod(centerLong, minorCell);

            int cx = corridorAlongX ? centerLong : wallLine;
            int cz = corridorAlongX ? wallLine : centerLong;

            int minX = cx - (corridorAlongX ? halfLong : halfThick);
            int maxX = cx + (corridorAlongX ? halfLong : halfThick);
            int minZ = cz - (corridorAlongX ? halfThick : halfLong);
            int maxZ = cz + (corridorAlongX ? halfThick : halfLong);

            int height = chooseHeightBand(rnd);

            buildCarvedMassSlice(world, seed, chunkX, chunkZ, minX, maxX, floorY + 1, floorY + height, minZ, maxZ, rnd);
        }

        // ---- Sparse masses (fallback city) ----
        private void buildSparseMassesSlice(World world, long seed, int chunkX, int chunkZ, int originX, int originZ, Random rnd) {
            int count = 2 + rnd.nextInt(4); // 2..5
            for (int i = 0; i < count; i++) {
                int footprint = massFootprintMin + rnd.nextInt(Math.max(1, massFootprintMax - massFootprintMin + 1));
                int half = footprint / 2;

                int cx = originX + 64 + rnd.nextInt(Math.max(1, districtSize - 128));
                int cz = originZ + 64 + rnd.nextInt(Math.max(1, districtSize - 128));

                int height = chooseHeightBand(rnd);

                buildCarvedMassSlice(world, seed ^ (i * 1337L), chunkX, chunkZ,
                        cx - half, cx + half, floorY + 1, floorY + height, cz - half, cz + half, rnd);
            }
        }

        // ---- Mega Plaza: mostly empty, strong inlaid linework + a few frame masses ----
        private void buildMegaPlazaSlice(World world, long seed, int chunkX, int chunkZ, int originX, int originZ, Random rnd) {
            // draw a few big “avenue lines” across the district at floorY (inlaid)
            int lines = 3 + rnd.nextInt(3); // 3..5
            for (int i = 0; i < lines; i++) {
                boolean alongX = rnd.nextBoolean();
                int pos = (alongX ? (originZ + 64 + rnd.nextInt(districtSize - 128)) : (originX + 64 + rnd.nextInt(districtSize - 128)));
                pos = pos - mod(pos, minorCell);

                drawInlaidLineSlice(world, chunkX, chunkZ, alongX, pos);
            }

            // frame masses at edges (canyon hint without filling)
            int frames = 2 + rnd.nextInt(3);
            for (int i = 0; i < frames; i++) {
                int footprint = 22 + rnd.nextInt(30);
                int half = footprint / 2;

                int cx = (i % 2 == 0) ? (originX + 48) : (originX + districtSize - 48);
                int cz = originZ + 64 + rnd.nextInt(Math.max(1, districtSize - 128));

                int height = chooseHeightBand(rnd);
                buildCarvedMassSlice(world, seed ^ (0xABCDL + i), chunkX, chunkZ,
                        cx - half, cx + half, floorY + 1, floorY + height, cz - half, cz + half, rnd);
            }
        }

        private void drawInlaidLineSlice(World world, int chunkX, int chunkZ, boolean alongX, int linePos) {
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = startX + dx;
                    int z = startZ + dz;
                    int d = alongX ? Math.abs(z - linePos) : Math.abs(x - linePos);
                    if (d != 0) continue;
                    setTypeIdInChunk(world, x, floorY, z, chunkX, chunkZ, GLOWSTONE);
                }
            }
        }

        // ---- Platform Fields: layered slabs (TRON 1982/Uprising vibe) ----
        private void buildPlatformFieldsSlice(World world, long seed, int chunkX, int chunkZ, int originX, int originZ, Random rnd) {
            int layers = 2 + rnd.nextInt(3); // 2..4
            for (int i = 0; i < layers; i++) {
                int y = floorY + 10 + i * 12 + rnd.nextInt(4);
                int slabSize = 120 + rnd.nextInt(140);
                int half = slabSize / 2;

                int cx = originX + districtSize / 2;
                int cz = originZ + districtSize / 2;

                buildThinPlatformSlice(world, seed ^ (i * 9999L), chunkX, chunkZ, cx - half, cx + half, y, cz - half, cz + half, rnd);
            }
        }

        private void buildThinPlatformSlice(World world, long seed, int chunkX, int chunkZ, int minX, int maxX, int y, int minZ, int maxZ, Random rnd) {
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = startX + dx;
                    int z = startZ + dz;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

                    // sparse “holes” to avoid perfect rectangles
                    long h = hash64(seed, x, z);
                    if ((h & 31L) == 0L) continue;

                    setTypeIdInChunk(world, x, y, z, chunkX, chunkZ, OBSIDIAN);

                    // edge outlines (lapis or glowstone)
                    boolean edge = (x == minX || x == maxX || z == minZ || z == maxZ);
                    if (edge) {
                        int t = ((h & 3L) == 0L) ? GLOWSTONE : LAPIS_BLOCK;
                        setTypeIdInChunk(world, x, y, z, chunkX, chunkZ, t);
                    }
                }
            }
        }

        // ---- Core mass builder: obsidian slab + carving + seam accents (lapis/glow) ----
        private void buildCarvedMassSlice(World world, long seed, int chunkX, int chunkZ,
                                          int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Random rnd) {

            // 1) Lay down the mass (solid obsidian)
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        setIfInChunk(world, chunkX, chunkZ, x, y, z, OBSIDIAN);

                        // very occasional iron banding (thin)
                        if ((y - minY) % 24 == 0 && (x == minX || x == maxX || z == minZ || z == maxZ)) {
                            setIfInChunk(world, chunkX, chunkZ, x, y, z, IRON_BLOCK);
                        }
                    }
                }
            }

            // 2) Carve vertical slots (canyon “windows” / channels)
            int slots = 2 + rnd.nextInt(4); // 2..5
            for (int i = 0; i < slots; i++) {
                int slotW = 2 + rnd.nextInt(3);  // 2..4
                int slotH = 18 + rnd.nextInt(30);
                int sx = minX + 2 + rnd.nextInt(Math.max(1, (maxX - minX) - 4));
                int sz = minZ + 2 + rnd.nextInt(Math.max(1, (maxZ - minZ) - 4));

                int top = Math.min(maxY - 2, minY + slotH);

                for (int x = sx; x < sx + slotW; x++) {
                    for (int y = minY + 2; y <= top; y++) {
                        // carve on a face: pick nearest edge
                        int z = (Math.abs(sz - minZ) < Math.abs(sz - maxZ)) ? minZ : maxZ;
                        setIfInChunk(world, chunkX, chunkZ, x, y, z, AIR);
                        // seam lighting inside carve
                        if ((y % seamSpacing) == 0) {
                            int seam = (rnd.nextDouble() < glowSeamChance) ? GLOWSTONE : LAPIS_BLOCK;
                            setIfInChunk(world, chunkX, chunkZ, x, y, z + ((z == minZ) ? 1 : -1), seam);
                        }
                    }
                }
            }

            // 3) Horizontal recessed bands (the “wrap” lines)
            int bands = 2 + rnd.nextInt(3);
            for (int i = 0; i < bands; i++) {
                int by = minY + 10 + rnd.nextInt(Math.max(1, (maxY - minY) - 20));
                by = by - mod(by, 6);

                // carve a 1-block recess ring and place lapis/glow at edges
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        boolean edge = (x == minX || x == maxX || z == minZ || z == maxZ);
                        if (!edge) continue;

                        setIfInChunk(world, chunkX, chunkZ, x, by, z, AIR);

                        // accent just behind the recess
                        if (rnd.nextDouble() < lapisSeamChance) {
                            int ax = (x == minX) ? (minX + 1) : (x == maxX) ? (maxX - 1) : x;
                            int az = (z == minZ) ? (minZ + 1) : (z == maxZ) ? (maxZ - 1) : z;
                            int t = (rnd.nextDouble() < glowSeamChance) ? GLOWSTONE : LAPIS_BLOCK;
                            setIfInChunk(world, chunkX, chunkZ, ax, by, az, t);
                        }
                    }
                }
            }

            // 4) Occasional loot node (hidden “maintenance pocket”)
            long h = hash64(seed ^ 0x41414141L, minX ^ maxX, minZ ^ maxZ);
            if (u01(h) < lootChestChance && rnd.nextDouble() < 0.25) {
                int cx = (minX + maxX) / 2;
                int cz = (minZ + maxZ) / 2;

                // small pocket near base
                for (int x = cx - 2; x <= cx + 2; x++) {
                    for (int z = cz - 2; z <= cz + 2; z++) {
                        for (int y = minY + 1; y <= minY + 4; y++) {
                            setIfInChunk(world, chunkX, chunkZ, x, y, z, AIR);
                        }
                    }
                }
                setIfInChunk(world, chunkX, chunkZ, cx, minY, cz, IRON_BLOCK);
                placeLootChestIfInChunk(world, seed ^ 0xC11E57L, chunkX, chunkZ, cx, minY + 1, cz);
            }
        }

        // ---- Skyway (thin bridge with seam lighting) ----
        private void buildSkywaySlice(World world, long seed, int chunkX, int chunkZ, int cx, int y, int cz, boolean corridorAlongX, int span) {
            // Bridge spans perpendicular to corridor axis
            boolean alongX = !corridorAlongX;

            int half = span / 2;
            int min = -half;
            int max = half;

            for (int i = min; i <= max; i++) {
                int x = alongX ? (cx + i) : cx;
                int z = alongX ? cz : (cz + i);

                // deck
                setIfInChunk(world, chunkX, chunkZ, x, y, z, OBSIDIAN);

                // rails
                if (i == min || i == max) {
                    setIfInChunk(world, chunkX, chunkZ, x, y + 1, z, IRON_BLOCK);
                } else if (i % 6 == 0) {
                    setIfInChunk(world, chunkX, chunkZ, x, y + 1, z, GLASS);
                }

                // seam line down center
                if ((i % 4) == 0) {
                    int t = ((hash64(seed, x, z) & 3L) == 0L) ? GLOWSTONE : LAPIS_BLOCK;
                    setIfInChunk(world, chunkX, chunkZ, x, y, z, t);
                }
            }
        }

        private int chooseHeightBand(Random rnd) {
            // bias to taller bands sometimes
            if (rnd.nextDouble() < tallBandChance) {
                return heightBands[2 + rnd.nextInt(heightBands.length - 2)];
            }
            return heightBands[rnd.nextInt(Math.min(3, heightBands.length))];
        }

        private int chooseSkywayY(Random rnd) {
            int y = skywayMinY + rnd.nextInt(Math.max(1, (skywayMaxY - skywayMinY + 1)));
            // snap to a pleasant banding
            y = y - mod(y, 4);
            return y;
        }

        // ---- Flynn’s Arcade (human-scale “wrong” landmark) ----
        private void buildFlynnsArcadeSlice(World world, long seed, int chunkX, int chunkZ, int cx, int baseY, int cz) {
            // footprint ~ 17x13
            int w = 17;
            int d = 13;
            int h = 7;

            int minX = cx - w / 2;
            int maxX = minX + w - 1;
            int minZ = cz - d / 2;
            int maxZ = minZ + d - 1;

            int y0 = baseY + 1;

            // floor (planks = “human”)
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    setIfInChunk(world, chunkX, chunkZ, x, y0, z, WOOD_PLANKS);
                }
            }

            // walls (cobble/stone mix)
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = (x == minX || x == maxX || z == minZ || z == maxZ);
                    if (!edge) continue;

                    for (int y = 1; y <= h; y++) {
                        int t = ((x + z + y) % 5 == 0) ? STONE : COBBLE;
                        setIfInChunk(world, chunkX, chunkZ, x, y0 + y, z, t);
                    }
                }
            }

            // windows (glass strips)
            for (int x = minX + 2; x <= maxX - 2; x++) {
                setIfInChunk(world, chunkX, chunkZ, x, y0 + 3, minZ, GLASS);
                setIfInChunk(world, chunkX, chunkZ, x, y0 + 3, maxZ, GLASS);
            }

            // door opening
            int doorX = cx;
            for (int y = 1; y <= 3; y++) {
                setIfInChunk(world, chunkX, chunkZ, doorX, y0 + y, minZ, AIR);
                setIfInChunk(world, chunkX, chunkZ, doorX, y0 + y, minZ + 1, AIR);
            }

            // roof (flat)
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    setIfInChunk(world, chunkX, chunkZ, x, y0 + h + 1, z, STONE);
                }
            }

            // “FLYNN” marquee: iron blocks with glow behind
            String sign = "FLYNN";
            int sx = cx - (sign.length() * 2);
            int sy = y0 + h;
            int sz = minZ - 1;

            for (int i = 0; i < sign.length(); i++) {
                int px = sx + i * 4;
                // simple block letters as vertical strokes
                setIfInChunk(world, chunkX, chunkZ, px, sy, sz, IRON_BLOCK);
                setIfInChunk(world, chunkX, chunkZ, px, sy - 1, sz, IRON_BLOCK);
                setIfInChunk(world, chunkX, chunkZ, px, sy - 2, sz, IRON_BLOCK);

                // glow backlight
                setIfInChunk(world, chunkX, chunkZ, px, sy - 1, sz - 1, GLOWSTONE);
            }

            // inside: a few “arcade machines” (obsidian columns with glow)
            for (int i = 0; i < 6; i++) {
                int mx = minX + 2 + (i % 3) * 5;
                int mz = minZ + 3 + (i / 3) * 5;

                for (int y = 1; y <= 3; y++) setIfInChunk(world, chunkX, chunkZ, mx, y0 + y, mz, OBSIDIAN);
                setIfInChunk(world, chunkX, chunkZ, mx, y0 + 2, mz + 1, GLOWSTONE);
            }

            // loot chest tucked in back room corner
            placeLootChestIfInChunk(world, seed ^ 0xF1A77L, chunkX, chunkZ, maxX - 2, y0 + 1, maxZ - 2);
        }
    }

    // -------------------------------------------------------------------------
    // Outlands: stone/snow, sparse broken circuits, occasional low ruins
    // -------------------------------------------------------------------------
    private final class OutlandsPopulator extends BlockPopulator {
        @Override
        public void populate(World world, Random random, Chunk chunk) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;

            long seed = world.getSeed();
            int districtX = floorDiv(startX, districtSize);
            int districtZ = floorDiv(startZ, districtSize);

            if (getDistrictType(seed, districtX, districtZ) != DistrictType.OUTLANDS) return;
            if (!isDistrictActive(seed, districtX, districtZ)) return;

            long dHash = hash64(seed ^ 0x0A71A0D5L, districtX, districtZ);
            Random rnd = new Random(dHash ^ 0xA5A5A5A5L);

            // convert surface obsidian -> stone, add snow layer sometimes
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = startX + dx;
                    int z = startZ + dz;

                    setTypeIdInChunk(world, x, floorY, z, chunkX, chunkZ, STONE);

                    // snowy patches
                    if (rnd.nextInt(6) == 0) {
                        setTypeIdInChunk(world, x, floorY + 1, z, chunkX, chunkZ, SNOW_LAYER);
                    }

                    // broken circuits: erase some glowstone to “dead lines”
                    if (world.getBlockAt(x, floorY, z).getTypeId() == GLOWSTONE) {
                        if (rnd.nextInt(3) == 0) {
                            setTypeIdInChunk(world, x, floorY, z, chunkX, chunkZ, STONE);
                        }
                    }
                }
            }

            // occasional low ruin chunk-local
            if (rnd.nextDouble() < 0.10) {
                int cx = startX + 4 + rnd.nextInt(8);
                int cz = startZ + 4 + rnd.nextInt(8);

                for (int x = cx - 3; x <= cx + 3; x++) {
                    for (int z = cz - 3; z <= cz + 3; z++) {
                        if ((x + z) % 3 == 0) continue;
                        setIfInChunk(world, chunkX, chunkZ, x, floorY + 1, z, COBBLE);
                        if ((x + z) % 5 == 0) setIfInChunk(world, chunkX, chunkZ, x, floorY + 2, z, COBBLE);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Loot helpers
    // -------------------------------------------------------------------------
    private void placeLootChestIfInChunk(World world, long seed, int chunkX, int chunkZ, int x, int y, int z) {
        if (!isInChunk(x, z, chunkX, chunkZ)) return;
        if (y < 2 || y >= 127) return;

        Block b = world.getBlockAt(x, y, z);
        if (b.getTypeId() != AIR && b.getTypeId() != CHEST) return;

        b.setTypeId(CHEST);
        BlockState st = b.getState();
        if (!(st instanceof Chest)) return;

        Chest chest = (Chest) st;
        Random rnd = new Random(seed ^ hash64(seed, x, z));

        // clear
        for (int i = 0; i < chest.getInventory().getSize(); i++) {
            chest.getInventory().setItem(i, null);
        }

        // fill a few slots
        int rolls = 3 + rnd.nextInt(4);
        for (int i = 0; i < rolls; i++) {
            int slot = rnd.nextInt(chest.getInventory().getSize());
            ItemStack it = pickLoot(rnd);
            chest.getInventory().setItem(slot, it);
        }

        chest.update();
    }

    private ItemStack pickLoot(Random rnd) {
        int r = rnd.nextInt(100);

        if (r < 18) return new ItemStack(IT_BREAD, 1 + rnd.nextInt(3));
        if (r < 34) return new ItemStack(IT_APPLE, 1 + rnd.nextInt(3));
        if (r < 50) return new ItemStack(IT_COAL, 2 + rnd.nextInt(10));
        if (r < 68) return new ItemStack(IT_IRON, 1 + rnd.nextInt(6));
        if (r < 80) return new ItemStack(IT_GOLD, 1 + rnd.nextInt(4));
        if (r < 90) return new ItemStack(IT_REDSTONE, 2 + rnd.nextInt(10));
        if (r < 97) return new ItemStack(IT_GLOW_DUST, 2 + rnd.nextInt(8));
        return new ItemStack(IT_DIAMOND, 1);
    }

    // -------------------------------------------------------------------------
    // Low-level helpers
    // -------------------------------------------------------------------------
    private static boolean isInChunk(int x, int z, int chunkX, int chunkZ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        return (x >= startX && x < startX + 16 && z >= startZ && z < startZ + 16);
    }

    private static void setIfInChunk(World world, int chunkX, int chunkZ, int x, int y, int z, int typeId) {
        if (!isInChunk(x, z, chunkX, chunkZ)) return;
        if (y < 1 || y >= 127) return;
        Block b = world.getBlockAt(x, y, z);
        if (b.getTypeId() != typeId) b.setTypeId(typeId);
    }

    private static void setTypeIdInChunk(World world, int x, int y, int z, int chunkX, int chunkZ, int typeId) {
        if (!isInChunk(x, z, chunkX, chunkZ)) return;
        if (y < 1 || y >= 127) return;
        Block b = world.getBlockAt(x, y, z);
        if (b.getTypeId() != typeId) b.setTypeId(typeId);
    }

    private static int mod(int a, int m) {
        int r = a % m;
        return (r < 0) ? r + m : r;
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    private static long hash64(long seed, int a, int b) {
        long x = seed;
        x ^= (long) a * 0x9E3779B97F4A7C15L;
        x ^= (long) b * 0xC2B2AE3D27D4EB4FL;
        x ^= (x >>> 33);
        x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33);
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= (x >>> 33);
        return x;
    }

    private static double u01(long h) {
        long v = (h >>> 11) & 0x1FFFFFFFFFFFFFL;
        return v / (double) 0x20000000000000L;
    }
}
