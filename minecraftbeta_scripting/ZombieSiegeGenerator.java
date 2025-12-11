import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class ZombieSiegeGenerator extends JavaPlugin {

    public void onEnable() {
        System.out.println("[ZombieSiegeGenerator] Enabled.");
    }

    public void onDisable() {
        System.out.println("[ZombieSiegeGenerator] Disabled.");
    }

    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (id == null) {
            id = "";
        }
        if (id.length() == 0 || id.equalsIgnoreCase("zombiesiege")) {
            return new SiegeChunkGenerator();
        }
        return null;
    }

    // ------------------------------------------------------------
    // Actual generator implementation
    // ------------------------------------------------------------
    public static class SiegeChunkGenerator extends ChunkGenerator {

        private static final int WORLD_HEIGHT = 128;

        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            byte[] blocks = new byte[16 * 16 * WORLD_HEIGHT];

            // Perfectly flat:
            // y = 0         : bedrock
            // y = 1..59     : stone
            // y = 60..62    : dirt
            // y = 63        : grass
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {

                    setBlock(blocks, x, 0, z, (byte) Material.BEDROCK.getId());

                    for (int y = 1; y <= 59; y++) {
                        setBlock(blocks, x, y, z, (byte) Material.STONE.getId());
                    }

                    for (int y = 60; y <= 62; y++) {
                        setBlock(blocks, x, y, z, (byte) Material.DIRT.getId());
                    }

                    setBlock(blocks, x, 63, z, (byte) Material.GRASS.getId());
                }
            }

            return blocks;
        }

        private void setBlock(byte[] blocks, int x, int y, int z, byte blockId) {
            if (y < 0 || y >= WORLD_HEIGHT) return;
            int index = (x * 16 + z) * WORLD_HEIGHT + y;
            blocks[index] = blockId;
        }

        public List<BlockPopulator> getDefaultPopulators(World world) {
            List<BlockPopulator> list = new ArrayList<BlockPopulator>();
            list.add(new IndevHousePopulator());
            list.add(new TreeAndFlowerPopulator());
            return list;
        }

        // -------------------------------------------
        // Indev-style houses with loot
        // -------------------------------------------
                // -------------------------------------------
        // Indev-style houses with loot
        // -------------------------------------------
        private static class IndevHousePopulator extends BlockPopulator {
            public void populate(World world, Random rnd, Chunk chunk) {
                // ~1 in 512 chunks
                if (rnd.nextInt(512) != 0) {
                    return;
                }

                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();

                int baseX = (chunkX << 4) + 4 + rnd.nextInt(8);
                int baseZ = (chunkZ << 4) + 4 + rnd.nextInt(8);

                // flat world: grass at 63, so floor at 63
                int floorY = 63;
                int size = 7;
                int height = 5;

                // true  -> all mossy cobble (floor, walls, ceiling)
                // false -> wood walls/ceiling, stone floor
                boolean mossyHouse = rnd.nextBoolean();

                // Build shell
                for (int x = 0; x < size; x++) {
                    for (int z = 0; z < size; z++) {
                        for (int dy = 0; dy <= height; dy++) {
                            int wx = baseX + x;
                            int wy = floorY + dy;
                            int wz = baseZ + z;

                            boolean isWall = (x == 0 || x == size - 1 || z == 0 || z == size - 1);
                            boolean isFloor = (dy == 0);
                            boolean isCeiling = (dy == height);

                            Block b = world.getBlockAt(wx, wy, wz);

                            if (isFloor) {
                                if (mossyHouse) {
                                    b.setType(Material.MOSSY_COBBLESTONE);
                                } else {
                                    b.setType(Material.STONE);
                                }
                            } else if (isCeiling) {
                                if (mossyHouse) {
                                    b.setType(Material.MOSSY_COBBLESTONE);
                                } else {
                                    b.setType(Material.WOOD);
                                }
                            } else if (isWall) {
                                if (mossyHouse) {
                                    b.setType(Material.MOSSY_COBBLESTONE);
                                } else {
                                    b.setType(Material.WOOD);
                                }
                            } else {
                                b.setType(Material.AIR);
                            }
                        }
                    }
                }

                // 1x2 doorway on front side (z == 0 row relative to house),
                // at floorY+1 and floorY+2, centered horizontally.
                int doorX = baseX + size / 2;  // center
                int doorZ = baseZ;             // front side
                world.getBlockAt(doorX, floorY + 1, doorZ).setType(Material.AIR);
                world.getBlockAt(doorX, floorY + 2, doorZ).setType(Material.AIR);

                // Torches on left and right interior walls instead of front
                int centerZ = baseZ + size / 2; // middle of room in Z
                int leftX = baseX + 1;          // one in from west wall
                int rightX = baseX + size - 2;  // one in from east wall

                Block leftTorch = world.getBlockAt(leftX, floorY + 1, centerZ);
                Block rightTorch = world.getBlockAt(rightX, floorY + 1, centerZ);
                leftTorch.setType(Material.TORCH);
                rightTorch.setType(Material.TORCH);

                // Four chests, backs against each wall, fronts facing inward
                // North wall (front): just inside at z = baseZ + 1
                placeLootChest(world, rnd, baseX + size / 2, floorY + 1, baseZ + 1);

                // South wall (back): z = baseZ + size - 2
                placeLootChest(world, rnd, baseX + size / 2, floorY + 1, baseZ + size - 2);

                // West wall: x = baseX + 1
                placeLootChest(world, rnd, baseX + 1, floorY + 1, baseZ + size / 2);

                // East wall: x = baseX + size - 2
                placeLootChest(world, rnd, baseX + size - 2, floorY + 1, baseZ + size / 2);
            }

            private void placeLootChest(World world, Random rnd, int x, int y, int z) {
                Block b = world.getBlockAt(x, y, z);
                b.setType(Material.CHEST);

                if (!(b.getState() instanceof Chest)) return;
                Chest chest = (Chest) b.getState();
                Inventory inv = chest.getInventory();

                Material[] goodStuff = new Material[] {
                    Material.IRON_SWORD,
                    Material.BOW,
                    Material.ARROW,
                    Material.IRON_PICKAXE,
                    Material.IRON_AXE,
                    Material.IRON_SPADE,
                    Material.IRON_HELMET,
                    Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS,
                    Material.IRON_BOOTS,
                    Material.APPLE,
                    Material.BREAD,
                    Material.TORCH,
                    Material.COBBLESTONE,
                    Material.WOOD,
                    Material.WOOL,
                    Material.TNT
                };

                int slotsToFill = 12 + rnd.nextInt(13); // 12–24 slots
                int size = inv.getSize();

                for (int i = 0; i < slotsToFill; i++) {
                    int slot = rnd.nextInt(size);
                    Material m = goodStuff[rnd.nextInt(goodStuff.length)];
                    int amount;

                    if (m == Material.ARROW || m == Material.TORCH) {
                        amount = 16 + rnd.nextInt(33);
                    } else if (m == Material.TNT || m == Material.WOOL) {
                        amount = 4 + rnd.nextInt(13);
                    } else if (m == Material.COBBLESTONE || m == Material.WOOD) {
                        amount = 32 + rnd.nextInt(33);
                    } else {
                        amount = 1;
                    }

                    inv.setItem(slot, new ItemStack(m, amount));
                }
            }
        }

        // -------------------------------------------
        // Trees and flowers on flat grass at y=63
        // -------------------------------------------
        private static class TreeAndFlowerPopulator extends BlockPopulator {
            public void populate(World world, Random rnd, Chunk chunk) {
                int baseX = chunk.getX() << 4;
                int baseZ = chunk.getZ() << 4;

                int groundY = 63; // we know grass layer is here

                // Trees: 2–5 attempts per chunk
                int treeAttempts = 2 + rnd.nextInt(4);
                for (int i = 0; i < treeAttempts; i++) {
                    int x = baseX + rnd.nextInt(16);
                    int z = baseZ + rnd.nextInt(16);

                    Block ground = world.getBlockAt(x, groundY, z);
                    if (ground.getType() == Material.GRASS) {
                        Block above = world.getBlockAt(x, groundY + 1, z);
                        if (above.getType() == Material.AIR) {
                            world.generateTree(new Location(world, x, groundY + 1, z), TreeType.TREE);
                        }
                    }
                }

                // Flowers: 10–20 attempts per chunk
                int flowerAttempts = 10 + rnd.nextInt(11);
                for (int i = 0; i < flowerAttempts; i++) {
                    int x = baseX + rnd.nextInt(16);
                    int z = baseZ + rnd.nextInt(16);

                    Block ground = world.getBlockAt(x, groundY, z);
                    if (ground.getType() == Material.GRASS) {
                        Block above = world.getBlockAt(x, groundY + 1, z);
                        if (above.getType() == Material.AIR) {
                            above.setType(rnd.nextBoolean() ? Material.RED_ROSE : Material.YELLOW_FLOWER);
                        }
                    }
                }
            }
        }
    }
}
