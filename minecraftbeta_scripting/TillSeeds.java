import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TillSeeds (Minecraft Beta 1.7.3 / Poseidon)
 * Author: ChatGPT
 */
public final class TillSeeds extends JavaPlugin implements Listener {

    // --- Block IDs (Beta 1.7.3) ---
    private static final int BLOCK_COBWEB = 30;
    private static final int BLOCK_TALL_GRASS = 31; // data: 0,1,2
    private static final int BLOCK_DEAD_BUSH = 32;
    private static final int BLOCK_GRASS = 2;
    private static final int BLOCK_FARMLAND = 60;

    // --- Item IDs ---
    private static final int ITEM_SHEARS = 359;
    private static final int ITEM_SEEDS = 295;

    // --- Config keys ---
    private static final String KEY_SHEAR_COBWEB = "shears.collectCobweb";
    private static final String KEY_SHEAR_TALL_GRASS = "shears.collectTallGrass";
    private static final String KEY_SHEAR_DEAD_BUSH = "shears.collectDeadBush";

    private static final String KEY_HOE_SEEDS_ENABLED = "hoeTilling.seedDropEnabled";
    private static final String KEY_HOE_SEEDS_CHANCE = "hoeTilling.seedDropChance"; // 0.125 = 12.5%

    private static final String KEY_DISABLE_TALL_GRASS_NEW_CHUNKS = "worldgen.preventTallGrassInNewChunks";

    // New simplified anti-trample toggle
    private static final String KEY_FARMLAND_PREVENT_TRAMPLE = "farmland.preventTrample";
    private static final boolean DEF_FARMLAND_PREVENT_TRAMPLE = true;

    // Back-compat with the prior two-key approach (if present in existing config)
    private static final String KEY_FARMLAND_NO_TRAMPLE_WALK_OLD = "farmland.preventTrampleWalking";
    private static final String KEY_FARMLAND_NO_TRAMPLE_JUMP_OLD = "farmland.preventTrampleJumping";

    // --- Defaults ---
    private static final boolean DEF_SHEAR_COBWEB = true;
    private static final boolean DEF_SHEAR_TALL_GRASS = true;
    private static final boolean DEF_SHEAR_DEAD_BUSH = true;

    private static final boolean DEF_HOE_SEEDS_ENABLED = true;
    private static final double DEF_HOE_SEEDS_CHANCE = 0.125; // 12.5%

    private static final boolean DEF_DISABLE_TALL_GRASS_NEW_CHUNKS = false; // off by default

    private final Random rng = new Random();

    private File configFile;
    private Map<String, String> config = new HashMap<String, String>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        ensureConfig();
        loadConfig();

        log("Enabled.");
    }

    @Override
    public void onDisable() {
        log("Disabled.");
    }

    // ---------------------------------------------------------------------
    // Shears collecting cobweb / tall grass / dead bush
    // ---------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Highest, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        ItemStack inHand = player.getItemInHand();
        if (!isShears(inHand)) return;

        Block block = event.getBlock();
        if (block == null) return;

        int typeId = block.getTypeId();
        byte data = block.getData();

        if (typeId == BLOCK_COBWEB && getBool(KEY_SHEAR_COBWEB, DEF_SHEAR_COBWEB)) {
            event.setCancelled(true);
            dropBlockItself(block, typeId, data);
            damageToolInHand(player, 1);
            return;
        }

        if (typeId == BLOCK_TALL_GRASS && getBool(KEY_SHEAR_TALL_GRASS, DEF_SHEAR_TALL_GRASS)) {
            if (data == 0 || data == 1 || data == 2) {
                event.setCancelled(true);
                dropBlockItself(block, typeId, data);
                damageToolInHand(player, 1);
                return;
            }
        }

        if (typeId == BLOCK_DEAD_BUSH && getBool(KEY_SHEAR_DEAD_BUSH, DEF_SHEAR_DEAD_BUSH)) {
            event.setCancelled(true);
            dropBlockItself(block, typeId, data);
            damageToolInHand(player, 1);
            return;
        }
    }

    private void dropBlockItself(Block block, int typeId, byte data) {
        Location loc = block.getLocation();
        World world = block.getWorld();

        block.setTypeId(0); // air

        ItemStack drop = new ItemStack(typeId, 1);
        drop.setDurability((short) (data & 0xFF));
        world.dropItemNaturally(loc, drop);
    }

    // ---------------------------------------------------------------------
    // Tall grass placement fix (cancel + manual place with metadata)
    // (May still render as data 0 in some Beta clients; this is best-effort.)
    // ---------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Highest, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!getBool(KEY_SHEAR_TALL_GRASS, DEF_SHEAR_TALL_GRASS)) return;

        ItemStack inHand = event.getItemInHand();
        if (inHand == null) return;
        if (inHand.getTypeId() != BLOCK_TALL_GRASS) return;

        byte desiredData = (byte) (inHand.getDurability() & 0xFF);
        if (desiredData != 1 && desiredData != 2) return;

        final Block placed = event.getBlockPlaced();
        if (placed == null) return;

        int existing = placed.getTypeId();
        if (!(existing == 0 || existing == BLOCK_TALL_GRASS)) return;

        event.setCancelled(true);

        setBlockIdAndData(placed, BLOCK_TALL_GRASS, desiredData);

        Player player = event.getPlayer();
        if (player != null) {
            ItemStack current = player.getItemInHand();
            if (current != null && current.getTypeId() == BLOCK_TALL_GRASS) {
                int amount = current.getAmount();
                if (amount <= 1) {
                    player.setItemInHand(null);
                } else {
                    current.setAmount(amount - 1);
                    player.setItemInHand(current);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Farmland trampling prevention (cancel + force refresh to reduce flicker)
    // ---------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Highest, ignoreCancelled = true)
    public void onFarmlandPhysical(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (clicked.getTypeId() != BLOCK_FARMLAND) return;

        if (!shouldPreventTrample()) return;

        event.setCancelled(true);

        // Force the server state back onto the client to reduce “dirt flicker”
        final Block farmland = clicked;
        final byte farmlandData = farmland.getData();

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                if (farmland.getTypeId() == BLOCK_FARMLAND) {
                    setBlockIdAndData(farmland, BLOCK_FARMLAND, farmlandData);
                }
            }
        }, 1L);

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                if (farmland.getTypeId() == BLOCK_FARMLAND) {
                    setBlockIdAndData(farmland, BLOCK_FARMLAND, farmlandData);
                }
            }
        }, 2L);
    }

    private boolean shouldPreventTrample() {
        // Prefer new single key if present; otherwise fall back to old keys if user has them.
        if (config.containsKey(KEY_FARMLAND_PREVENT_TRAMPLE)) {
            return getBool(KEY_FARMLAND_PREVENT_TRAMPLE, DEF_FARMLAND_PREVENT_TRAMPLE);
        }
        // Back-compat: if either old key is true, treat as enabled.
        boolean oldWalk = getBool(KEY_FARMLAND_NO_TRAMPLE_WALK_OLD, true);
        boolean oldJump = getBool(KEY_FARMLAND_NO_TRAMPLE_JUMP_OLD, true);
        return oldWalk || oldJump;
    }

    // ---------------------------------------------------------------------
    // Hoe tilling grass -> optional seed drop (classic behavior)
    // ---------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Monitor, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (!getBool(KEY_HOE_SEEDS_ENABLED, DEF_HOE_SEEDS_ENABLED)) return;

        Player player = event.getPlayer();
        if (player == null) return;

        ItemStack inHand = player.getItemInHand();
        if (!isHoe(inHand)) return;

        final Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        if (clicked.getTypeId() != BLOCK_GRASS) return;

        Block above = clicked.getRelative(0, 1, 0);
        if (above != null && above.getTypeId() != 0) return;

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                if (clicked.getTypeId() != BLOCK_FARMLAND) return;

                double chance = getDouble(KEY_HOE_SEEDS_CHANCE, DEF_HOE_SEEDS_CHANCE);
                if (chance <= 0.0) return;

                if (rng.nextDouble() < chance) {
                    World world = clicked.getWorld();
                    Location dropLoc = clicked.getLocation().add(0.5, 1.0, 0.5);
                    world.dropItemNaturally(dropLoc, new ItemStack(ITEM_SEEDS, 1));
                }
            }
        }, 1L);
    }

    // ---------------------------------------------------------------------
    // Option: remove tall grass (31:1 and 31:2) from newly generated chunks
    // ---------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Monitor, ignoreCancelled = true)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        if (!getBool(KEY_DISABLE_TALL_GRASS_NEW_CHUNKS, DEF_DISABLE_TALL_GRASS_NEW_CHUNKS)) return;

        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 128; y++) {
                    Block b = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (b.getTypeId() == BLOCK_TALL_GRASS) {
                        byte data = b.getData();
                        if (data == 1 || data == 2) {
                            b.setTypeId(0);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: tool detection + durability
    // ---------------------------------------------------------------------
    private boolean isShears(ItemStack stack) {
        return stack != null && stack.getTypeId() == ITEM_SHEARS;
    }

    private boolean isHoe(ItemStack stack) {
        if (stack == null) return false;
        int id = stack.getTypeId();
        return (id == 290 || id == 291 || id == 292 || id == 293 || id == 294);
    }

    private void damageToolInHand(Player player, int damageAmount) {
        ItemStack tool = player.getItemInHand();
        if (tool == null) return;

        short dur = tool.getDurability();
        dur += (short) damageAmount;
        tool.setDurability(dur);

        if (tool.getTypeId() == ITEM_SHEARS && dur >= 238) {
            player.setItemInHand(null);
        } else {
            player.setItemInHand(tool);
        }
    }

    // Uses reflection so this works across older Bukkit/Poseidon builds.
    private void setBlockIdAndData(Block block, int typeId, byte data) {
        try {
            java.lang.reflect.Method m = block.getClass().getMethod("setTypeIdAndData",
                    new Class[] { int.class, byte.class, boolean.class });
            m.invoke(block, new Object[] { Integer.valueOf(typeId), Byte.valueOf(data), Boolean.TRUE });
            return;
        } catch (Throwable ignored) {
        }

        block.setTypeId(typeId);
        block.setData(data);
    }

    // ---------------------------------------------------------------------
    // Simple key=value config handling (plugins/TillSeeds/config.properties)
    // ---------------------------------------------------------------------
    private void ensureConfig() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        configFile = new File(dataFolder, "config.properties");

        if (configFile.exists()) return;

        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(configFile));
            out.write("# TillSeeds config\n");
            out.write("# true/false and numbers allowed\n\n");

            out.write(KEY_SHEAR_COBWEB + "=" + DEF_SHEAR_COBWEB + "\n");
            out.write(KEY_SHEAR_TALL_GRASS + "=" + DEF_SHEAR_TALL_GRASS + "\n");
            out.write(KEY_SHEAR_DEAD_BUSH + "=" + DEF_SHEAR_DEAD_BUSH + "\n\n");

            out.write(KEY_HOE_SEEDS_ENABLED + "=" + DEF_HOE_SEEDS_ENABLED + "\n");
            out.write(KEY_HOE_SEEDS_CHANCE + "=" + DEF_HOE_SEEDS_CHANCE + "\n\n");

            out.write(KEY_DISABLE_TALL_GRASS_NEW_CHUNKS + "=" + DEF_DISABLE_TALL_GRASS_NEW_CHUNKS + "\n\n");

            out.write(KEY_FARMLAND_PREVENT_TRAMPLE + "=" + DEF_FARMLAND_PREVENT_TRAMPLE + "\n");
        } catch (IOException e) {
            log("Failed to write default config: " + e.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    private void loadConfig() {
        config.clear();

        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.length() == 0) continue;

                config.put(key, val);
            }
        } catch (IOException e) {
            log("Failed to read config: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
        }
    }

    private boolean getBool(String key, boolean def) {
        String v = config.get(key);
        if (v == null) return def;
        v = v.trim().toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        return def;
    }

    private double getDouble(String key, double def) {
        String v = config.get(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void log(String msg) {
        System.out.println("[TillSeeds] " + msg);
    }
}
