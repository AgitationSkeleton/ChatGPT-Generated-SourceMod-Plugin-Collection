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
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class MySkyblock extends JavaPlugin {

    // World & layout configuration
    private static final String SKYBLOCK_WORLD_NAME = "myskyblock";
    private static final int ISLAND_SPACING = 256;     // Distance between islands along X
    private static final int ISLAND_BASE_Y = 64;       // Bottom layer Y for the dirt/bedrock layer

    // Teleport request timing (milliseconds)
    private static final long TELEPORT_REQUEST_TIMEOUT = 15L * 1000L;
    private static final long TELEPORT_REQUEST_COOLDOWN = 15L * 1000L;

    // Reset timing (milliseconds)
    private static final long RESET_CONFIRM_TIMEOUT = 10L * 1000L;
    private static final long RESET_COOLDOWN = 5L * 60L * 1000L; // 5 minutes

    // Storage
    private final Map<String, IslandInfo> islands = new HashMap<String, IslandInfo>();
    private int nextIslandIndex = 0;

    private final Map<String, TeleportRequest> pendingTeleportRequests = new HashMap<String, TeleportRequest>();
    private final Map<String, Long> lastTeleportRequestTime = new HashMap<String, Long>();

    private final Map<String, Long> pendingResets = new HashMap<String, Long>();
    private final Map<String, Long> lastResetTime = new HashMap<String, Long>();

    private final MySkyblockBlockListener blockListener = new MySkyblockBlockListener(this);

    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        loadIslands();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Event.Type.BLOCK_BREAK, blockListener, Priority.Highest, this);

        System.out.println("[MySkyblock] Enabled. Loaded " + islands.size() + " islands.");
    }

    public void onDisable() {
        saveIslands();
        System.out.println("[MySkyblock] Disabled.");
    }

    // ------------------------------------------------------------------------
    // Chunk generator (Multiverse will call this when creating the world)
    // ------------------------------------------------------------------------
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new SkyblockGenerator();
    }

    public static class SkyblockGenerator extends ChunkGenerator {
        @Override
        public byte[] generate(World world, Random random, int chunkX, int chunkZ) {
            // Void world: all air. 16 * 16 * 128 = 32768 bytes
            return new byte[16 * 16 * 128];
        }

        @Override
        public boolean canSpawn(World world, int x, int z) {
            // We handle where players actually spawn/teleport via commands
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Command handling
    // ------------------------------------------------------------------------
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label,
                             String[] args) {

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("myskyblock")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleMySkyblockMain((Player) sender);
            return true;
        }

        if (cmd.equals("myskyblocktpr")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleTeleportRequest((Player) sender, args);
            return true;
        }

        if (cmd.equals("myskyblocktpa")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleTeleportAccept((Player) sender);
            return true;
        }

        if (cmd.equals("myskyblocktpd")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleTeleportDeny((Player) sender);
            return true;
        }

        if (cmd.equals("myskyblockmain")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleReturnToMain((Player) sender);
            return true;
        }

        if (cmd.equals("myskyblockreset")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleResetRequest((Player) sender);
            return true;
        }

        if (cmd.equals("myskyblockconfirm")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            handleResetConfirm((Player) sender);
            return true;
        }

        return false;
    }

    // /myskyblock
    private void handleMySkyblockMain(Player player) {
        World skyWorld = getServer().getWorld(SKYBLOCK_WORLD_NAME);
        if (skyWorld == null) {
            player.sendMessage("Skyblock world '" + SKYBLOCK_WORLD_NAME + "' is not loaded. Ask an admin to create it with Multiverse.");
            return;
        }

        IslandInfo island = getOrCreateIsland(player);
        if (island == null) {
            player.sendMessage("Unable to create or locate your skyblock island.");
            return;
        }

        Location spawn = island.getSpawnLocation(getServer());
        if (spawn == null) {
            player.sendMessage("Your island spawn could not be resolved.");
            return;
        }

        player.teleport(spawn);
        player.sendMessage("Teleported to your skyblock island.");
    }

    // /myskyblocktpr <player>
    private void handleTeleportRequest(Player requester, String[] args) {
        if (args.length < 1) {
            requester.sendMessage("Usage: /myskyblocktpr <player>");
            return;
        }

        String targetName = args[0];
        Player target = getServer().getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            requester.sendMessage("That player is not online.");
            return;
        }

        if (target.getName().equalsIgnoreCase(requester.getName())) {
            requester.sendMessage("You cannot request to teleport to yourself.");
            return;
        }

        // Cooldown
        long now = System.currentTimeMillis();
        Long last = lastTeleportRequestTime.get(requester.getName().toLowerCase());
        if (last != null && (now - last.longValue()) < TELEPORT_REQUEST_COOLDOWN) {
            long remaining = (TELEPORT_REQUEST_COOLDOWN - (now - last.longValue())) / 1000L;
            if (remaining < 1) {
                remaining = 1;
            }
            requester.sendMessage("You must wait " + remaining + " more seconds before sending another teleport request.");
            return;
        }

        IslandInfo targetIsland = getIslandForPlayer(target.getName());
        if (targetIsland == null) {
            requester.sendMessage("That player does not have a skyblock island yet.");
            return;
        }

        TeleportRequest request = new TeleportRequest();
        request.requesterName = requester.getName();
        request.targetName = target.getName();
        request.expiresAt = now + TELEPORT_REQUEST_TIMEOUT;

        pendingTeleportRequests.put(target.getName().toLowerCase(), request);
        lastTeleportRequestTime.put(requester.getName().toLowerCase(), now);

        requester.sendMessage("Teleport request sent to " + target.getName() + ".");
        target.sendMessage(requester.getName() + " wants to teleport to your skyblock.");
        target.sendMessage("Type /myskyblocktpa to accept or /myskyblocktpd to decline (15 seconds).");
    }

    // /myskyblocktpa
    private void handleTeleportAccept(Player target) {
        String key = target.getName().toLowerCase();
        TeleportRequest request = pendingTeleportRequests.get(key);
        if (request == null) {
            target.sendMessage("You have no pending teleport requests.");
            return;
        }

        long now = System.currentTimeMillis();
        if (now > request.expiresAt) {
            pendingTeleportRequests.remove(key);
            target.sendMessage("The teleport request has expired.");
            return;
        }

        Player requester = getServer().getPlayer(request.requesterName);
        if (requester == null || !requester.isOnline()) {
            pendingTeleportRequests.remove(key);
            target.sendMessage("The requesting player is no longer online.");
            return;
        }

        IslandInfo targetIsland = getIslandForPlayer(target.getName());
        if (targetIsland == null) {
            target.sendMessage("You do not have an island right now.");
            requester.sendMessage("Teleport failed; target does not have an island.");
            pendingTeleportRequests.remove(key);
            return;
        }

        Location spawn = targetIsland.getSpawnLocation(getServer());
        if (spawn == null) {
            target.sendMessage("Your island spawn could not be found.");
            requester.sendMessage("Teleport failed; target spawn is invalid.");
            pendingTeleportRequests.remove(key);
            return;
        }

        requester.teleport(spawn);
        requester.sendMessage("Teleported to " + target.getName() + "'s skyblock.");
        target.sendMessage("Teleport request accepted; " + requester.getName() + " has been teleported.");
        pendingTeleportRequests.remove(key);
    }

    // /myskyblocktpd
    private void handleTeleportDeny(Player target) {
        String key = target.getName().toLowerCase();
        TeleportRequest request = pendingTeleportRequests.remove(key);
        if (request == null) {
            target.sendMessage("You have no pending teleport requests.");
            return;
        }

        Player requester = getServer().getPlayer(request.requesterName);
        if (requester != null && requester.isOnline()) {
            requester.sendMessage("Your teleport request to " + target.getName() + " was declined.");
        }
        target.sendMessage("Teleport request declined.");
    }

    // /myskyblockmain
    private void handleReturnToMain(Player player) {
        if (getServer().getWorlds().isEmpty()) {
            player.sendMessage("No worlds are loaded.");
            return;
        }
        World mainWorld = getServer().getWorlds().get(0);
        player.teleport(mainWorld.getSpawnLocation());
        player.sendMessage("Teleported to the main world.");
    }

    // /myskyblockreset
    private void handleResetRequest(Player player) {
        String key = player.getName().toLowerCase();
        IslandInfo info = getIslandForPlayer(player.getName());
        if (info == null) {
            player.sendMessage("You do not have a skyblock island yet.");
            return;
        }

        long now = System.currentTimeMillis();

        Long last = lastResetTime.get(key);
        if (last != null && (now - last.longValue()) < RESET_COOLDOWN) {
            long remaining = (RESET_COOLDOWN - (now - last.longValue())) / 1000L;
            if (remaining < 1) {
                remaining = 1;
            }
            player.sendMessage("You must wait " + remaining + " more seconds before resetting your skyblock again.");
            return;
        }

        pendingResets.put(key, now + RESET_CONFIRM_TIMEOUT);
        player.sendMessage("Are you sure you want to reset your skyblock?");
        player.sendMessage("Type /myskyblockconfirm within 10 seconds to confirm.");
    }

    // /myskyblockconfirm
    private void handleResetConfirm(Player player) {
        String key = player.getName().toLowerCase();
        Long expiry = pendingResets.get(key);
        if (expiry == null) {
            player.sendMessage("You have no pending reset request.");
            return;
        }

        long now = System.currentTimeMillis();
        if (now > expiry.longValue()) {
            pendingResets.remove(key);
            player.sendMessage("Your reset request has expired.");
            return;
        }

        IslandInfo island = getIslandForPlayer(player.getName());
        if (island == null) {
            player.sendMessage("Could not find your island to reset.");
            pendingResets.remove(key);
            return;
        }

        World skyWorld = getServer().getWorld(SKYBLOCK_WORLD_NAME);
        if (skyWorld == null) {
            player.sendMessage("Skyblock world '" + SKYBLOCK_WORLD_NAME + "' is not loaded.");
            pendingResets.remove(key);
            return;
        }

        // Move player somewhere safe (main world) during reset
        World mainWorld = null;
        if (!getServer().getWorlds().isEmpty()) {
            mainWorld = getServer().getWorlds().get(0);
        }

        if (mainWorld != null) {
            player.teleport(mainWorld.getSpawnLocation());
        }

        // Rebuild the island (clear + re-place)
        buildIsland(island, true);

        // Send them back to their island
        Location spawn = island.getSpawnLocation(getServer());
        if (spawn != null) {
            player.teleport(spawn);
        }

        pendingResets.remove(key);
        lastResetTime.put(key, now);
        player.sendMessage("Your skyblock island has been reset.");
    }

    // ------------------------------------------------------------------------
    // Island management
    // ------------------------------------------------------------------------
    private IslandInfo getOrCreateIsland(Player player) {
        IslandInfo existing = getIslandForPlayer(player.getName());
        if (existing != null) {
            return existing;
        }

        World skyWorld = getServer().getWorld(SKYBLOCK_WORLD_NAME);
        if (skyWorld == null) {
            return null;
        }

        int index = nextIslandIndex++;
        int originX = index * ISLAND_SPACING;
        int originZ = 0;

        // Bedrock at pattern:
        // xxxxxx
        // xbxxxx
        // xxxxxx
        // dx = 1, dz = 1 in the top 6x6 section
        int bedrockX = originX + 1;
        int bedrockY = ISLAND_BASE_Y;
        int bedrockZ = originZ + 1;

        IslandInfo created = new IslandInfo();
        created.playerName = player.getName();
        created.worldName = SKYBLOCK_WORLD_NAME;
        created.bedrockX = bedrockX;
        created.bedrockY = bedrockY;
        created.bedrockZ = bedrockZ;
        created.index = index;

        islands.put(player.getName().toLowerCase(), created);

        buildIsland(created, true);
        return created;
    }

    private IslandInfo getIslandForPlayer(String playerName) {
        return islands.get(playerName.toLowerCase());
    }

    private void buildIsland(IslandInfo island, boolean resetExisting) {
        World world = getServer().getWorld(island.worldName);
        if (world == null) {
            return;
        }

        int originX = island.index * ISLAND_SPACING;
        int originZ = 0;

        // Make sure chunk is loaded
        world.getChunkAt(originX >> 4, originZ >> 4).load(true);

        // Clear existing area if needed (blocks + dropped items)
        int clearRadiusX = 8;
        int clearRadiusZ = 8;
        int minX = originX - 1;
        int maxX = originX + clearRadiusX;
        int minZ = originZ - 1;
        int maxZ = originZ + clearRadiusZ;
        int minY = ISLAND_BASE_Y;
        int maxY = ISLAND_BASE_Y + 20;

        if (resetExisting) {
            // Remove any dropped items in the region (prevents duping by reset)
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item)) {
                    continue;
                }
                Location loc = entity.getLocation();
                double ex = loc.getX();
                double ey = loc.getY();
                double ez = loc.getZ();

                if (ex >= minX && ex <= (maxX + 1) &&
                    ez >= minZ && ez <= (maxZ + 1) &&
                    ey >= minY && ey <= (maxY + 1)) {
                    entity.remove();
                }
            }

            // Clear blocks in the region
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        setBlockId(world, x, y, 0, z); // AIR
                    }
                }
            }
        }

        // Build the L-shaped mound:
        //
        // z = 0: xxxxxx
        // z = 1: xxxxxx
        // z = 2: xxxxxx
        // z = 3: xxx
        // z = 4: xxx
        // z = 5: xxx
        //
        // dx in [0..5], dz in [0..5], but skip dx>=3 && dz>=3.

        for (int dz = 0; dz < 6; dz++) {
            for (int dx = 0; dx < 6; dx++) {
                if (dx >= 3 && dz >= 3) {
                    continue;
                }

                int x = originX + dx;
                int z = originZ + dz;

                // Bottom layer: dirt (id=3), except the bedrock anchor
                int bottomType = 3; // DIRT
                if (x == island.bedrockX && z == island.bedrockZ) {
                    bottomType = 7; // BEDROCK
                }
                setBlockId(world, x, ISLAND_BASE_Y, bottomType, z);

                // Middle layer: sand (id=12)
                setBlockId(world, x, ISLAND_BASE_Y + 1, 12, z);

                // Top layer: grass (id=2). We'll override chest/tree spots after.
                setBlockId(world, x, ISLAND_BASE_Y + 2, 2, z);
            }
        }

        // Chest at bottom-left of the shape: (dx=0, dz=5)
        int chestX = originX;
        int chestBaseY = ISLAND_BASE_Y + 2; // grass layer
        int chestZ = originZ + 5;

        // Ensure grass where the chest used to be
        setBlockId(world, chestX, chestBaseY, 2, chestZ);

        // Place chest one block above grass
        int chestY = chestBaseY + 1;
        setBlockId(world, chestX, chestY, 54, chestZ); // CHEST

        Block chestBlock = world.getBlockAt(chestX, chestY, chestZ);
        if (chestBlock != null && chestBlock.getState() instanceof Chest) {
            Chest chest = (Chest) chestBlock.getState();
            Inventory inv = chest.getInventory();
            inv.clear();

            // 2 ice blocks (id=79)
            inv.addItem(new ItemStack(79, 2));
            // 1 lava bucket (id=327)
            inv.addItem(new ItemStack(327, 1));
            // 1 sapling (id=6)
            inv.addItem(new ItemStack(6, 1));
            // 1 sugar cane (id=338)
            inv.addItem(new ItemStack(338, 1));
            // 1 cactus (id=81)
            inv.addItem(new ItemStack(81, 1));
            // 2 bones (id=352)
            inv.addItem(new ItemStack(352, 2));

            chest.update();
        }

        // Tree at top-right: (dx=5, dz=0), simple manual oak
        int treeBaseX = originX + 5;
        int treeBaseY = ISLAND_BASE_Y + 3; // one above grass
        int treeBaseZ = originZ;

        // Trunk (logs id=17) 4 blocks tall
        for (int ty = 0; ty < 4; ty++) {
            setBlockId(world, treeBaseX, treeBaseY + ty, 17, treeBaseZ);
        }

        // Leaves (id=18) in a small blob around the top of the trunk
        int leavesCenterY = treeBaseY + 3;
        for (int lx = -2; lx <= 2; lx++) {
            for (int lz = -2; lz <= 2; lz++) {
                for (int ly = 0; ly <= 2; ly++) {
                    if (Math.abs(lx) + Math.abs(lz) + ly > 4) {
                        continue;
                    }
                    int leafX = treeBaseX + lx;
                    int leafY = leavesCenterY + ly;
                    int leafZ = treeBaseZ + lz;

                    // Skip trunk blocks
                    if (leafX == treeBaseX && leafZ == treeBaseZ &&
                        leafY >= treeBaseY && leafY <= treeBaseY + 3) {
                        continue;
                    }

                    Block existing = world.getBlockAt(leafX, leafY, leafZ);
                    if (existing.getTypeId() == 0) {
                        existing.setTypeId(18);
                    }
                }
            }
        }
    }

    private void setBlockId(World world, int x, int y, int typeId, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setTypeId(typeId);
    }

    // ------------------------------------------------------------------------
    // Persistent storage: islands.txt
    // Format: playerName;worldName;bedrockX;bedrockY;bedrockZ;index
    // ------------------------------------------------------------------------
    private void loadIslands() {
        islands.clear();
        nextIslandIndex = 0;

        File file = new File(getDataFolder(), "islands.txt");
        if (!file.exists()) {
            return;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(";");
                if (parts.length < 6) {
                    continue;
                }

                IslandInfo info = new IslandInfo();
                info.playerName = parts[0];
                info.worldName = parts[1];
                info.bedrockX = Integer.parseInt(parts[2]);
                info.bedrockY = Integer.parseInt(parts[3]);
                info.bedrockZ = Integer.parseInt(parts[4]);
                info.index = Integer.parseInt(parts[5]);

                islands.put(info.playerName.toLowerCase(), info);
                if (info.index >= nextIslandIndex) {
                    nextIslandIndex = info.index + 1;
                }
            }
        } catch (Exception e) {
            System.out.println("[MySkyblock] Failed to load islands.txt: " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void saveIslands() {
        File file = new File(getDataFolder(), "islands.txt");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file));
            writer.write("# MySkyblock island data\n");
            for (IslandInfo info : islands.values()) {
                writer.write(info.playerName + ";" +
                             info.worldName + ";" +
                             info.bedrockX + ";" +
                             info.bedrockY + ";" +
                             info.bedrockZ + ";" +
                             info.index + "\n");
            }
        } catch (Exception e) {
            System.out.println("[MySkyblock] Failed to save islands.txt: " + e.getMessage());
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------------
    // Listener
    // ------------------------------------------------------------------------
    private static class MySkyblockBlockListener extends BlockListener {
        private final MySkyblock plugin;

        public MySkyblockBlockListener(MySkyblock plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onBlockBreak(BlockBreakEvent event) {
            Block block = event.getBlock();
            if (block == null) {
                return;
            }

            World world = block.getWorld();
            if (world == null) {
                return;
            }

            if (!world.getName().equalsIgnoreCase(SKYBLOCK_WORLD_NAME)) {
                return;
            }

            if (block.getTypeId() != 7) { // BEDROCK
                return;
            }

            // If this bedrock is one of the islands' anchors, cancel the break
            for (IslandInfo info : plugin.islands.values()) {
                if (info.worldName.equalsIgnoreCase(SKYBLOCK_WORLD_NAME) &&
                    info.bedrockX == block.getX() &&
                    info.bedrockY == block.getY() &&
                    info.bedrockZ == block.getZ()) {

                    event.setCancelled(true);
                    if (event.getPlayer() != null) {
                        event.getPlayer().sendMessage("You cannot break your bedrock anchor.");
                    }
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helper data classes
    // ------------------------------------------------------------------------
    private static class IslandInfo {
        public String playerName;
        public String worldName;
        public int bedrockX;
        public int bedrockY;
        public int bedrockZ;
        public int index;

        public Location getSpawnLocation(org.bukkit.Server server) {
            World world = server.getWorld(worldName);
            if (world == null) {
                return null;
            }
            double x = bedrockX + 0.5;
            double y = bedrockY + 3.0; // 3 blocks above bedrock
            double z = bedrockZ + 0.5;
            return new Location(world, x, y, z);
        }
    }

    private static class TeleportRequest {
        public String requesterName;
        public String targetName;
        public long expiresAt;
    }
}
