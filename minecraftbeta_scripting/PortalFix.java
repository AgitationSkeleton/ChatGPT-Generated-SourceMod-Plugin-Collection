import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

public class PortalFix extends JavaPlugin implements Listener {

    private static final Material PORTAL_MATERIAL = Material.PORTAL;
    private static final Material FRAME_MATERIAL = Material.OBSIDIAN;

    private String primaryWorldName = "world";
    private String primaryNetherWorldName = "world_nether";

    private Configuration dataConfig;

    private final Map<String, String> previousWorldName = new HashMap<String, String>();
    private final Map<String, Location> lastOtherWorldLocation = new HashMap<String, Location>();
    private final Map<String, Location> lastNetherLocation = new HashMap<String, Location>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        detectPrimaryWorldFromServerProperties();
        primaryNetherWorldName = primaryWorldName + "_nether";

        loadPlayerData();

        System.out.println("[PortalFix] Enabled. Primary world = " + primaryWorldName
                + ", primary nether = " + primaryNetherWorldName);
    }

    @Override
    public void onDisable() {
        saveAllPlayerData();
        System.out.println("[PortalFix] Disabled.");
    }

    // ------------------------------------------------------------------------
    // Primary world detection (server.properties)
    // ------------------------------------------------------------------------

    private void detectPrimaryWorldFromServerProperties() {
        File propsFile = new File("server.properties");
        if (!propsFile.exists()) {
            if (!getServer().getWorlds().isEmpty()) {
                primaryWorldName = getServer().getWorlds().get(0).getName();
            } else {
                primaryWorldName = "world";
            }
            return;
        }

        Properties props = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(propsFile);
            props.load(in);
            String levelName = props.getProperty("level-name");
            if (levelName != null && levelName.trim().length() > 0) {
                primaryWorldName = levelName.trim();
            } else {
                if (!getServer().getWorlds().isEmpty()) {
                    primaryWorldName = getServer().getWorlds().get(0).getName();
                } else {
                    primaryWorldName = "world";
                }
            }
        } catch (IOException e) {
            System.out.println("[PortalFix] Could not read server.properties: " + e.getMessage());
            if (!getServer().getWorlds().isEmpty()) {
                primaryWorldName = getServer().getWorlds().get(0).getName();
            } else {
                primaryWorldName = "world";
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Persistent player data (data.yml)
    // ------------------------------------------------------------------------

    private void loadPlayerData() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(dataFolder, "data.yml");
        dataConfig = new Configuration(dataFile);
        dataConfig.load();

        for (String playerName : dataConfig.getKeys("players")) {
            String base = "players." + playerName;

            String prev = dataConfig.getString(base + ".previous-world", null);
            if (prev != null) {
                previousWorldName.put(playerName, prev);
            }

            Location other = readLocation(base + ".last-other");
            if (other != null) {
                lastOtherWorldLocation.put(playerName, other);
            }

            Location nether = readLocation(base + ".last-nether");
            if (nether != null) {
                lastNetherLocation.put(playerName, nether);
            }
        }
    }

    private Location readLocation(String path) {
        String worldName = dataConfig.getString(path + ".world", null);
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = dataConfig.getDouble(path + ".x", 0.0);
        double y = dataConfig.getDouble(path + ".y", 64.0);
        double z = dataConfig.getDouble(path + ".z", 0.0);
        float yaw = (float) dataConfig.getDouble(path + ".yaw", 0.0);
        float pitch = (float) dataConfig.getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void writeLocation(String path, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            dataConfig.removeProperty(path);
            return;
        }
        dataConfig.setProperty(path + ".world", loc.getWorld().getName());
        dataConfig.setProperty(path + ".x", loc.getX());
        dataConfig.setProperty(path + ".y", loc.getY());
        dataConfig.setProperty(path + ".z", loc.getZ());
        dataConfig.setProperty(path + ".yaw", loc.getYaw());
        dataConfig.setProperty(path + ".pitch", loc.getPitch());
    }

    private void savePlayerData(String playerName) {
        if (dataConfig == null) {
            return;
        }
        String base = "players." + playerName;

        String prev = previousWorldName.get(playerName);
        if (prev != null) {
            dataConfig.setProperty(base + ".previous-world", prev);
        } else {
            dataConfig.removeProperty(base + ".previous-world");
        }

        writeLocation(base + ".last-other", lastOtherWorldLocation.get(playerName));
        writeLocation(base + ".last-nether", lastNetherLocation.get(playerName));

        dataConfig.save();
    }

    private void saveAllPlayerData() {
        if (dataConfig == null) {
            return;
        }
        for (String playerName : previousWorldName.keySet()) {
            savePlayerData(playerName);
        }
        for (String playerName : lastOtherWorldLocation.keySet()) {
            savePlayerData(playerName);
        }
        for (String playerName : lastNetherLocation.keySet()) {
            savePlayerData(playerName);
        }
    }

    // ------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.getBlockX() == to.getBlockX() &&
            from.getBlockY() == to.getBlockY() &&
            from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        String playerName = player.getName();

        Block toBlock = to.getBlock();
        Block fromBlock = from.getBlock();

        if (toBlock.getType() != PORTAL_MATERIAL) {
            return;
        }
        if (fromBlock.getType() == PORTAL_MATERIAL) {
            return;
        }

        if (!isInObsidianPortalFrame(to)) {
            return;
        }

        World currentWorld = player.getWorld();
        String worldName = currentWorld.getName();

        // Default overworld: record vanilla nether origin, but do NOT teleport.
        if (worldName.equalsIgnoreCase(primaryWorldName)) {
            previousWorldName.put(playerName, primaryWorldName);
            lastOtherWorldLocation.put(playerName, from.clone());
            savePlayerData(playerName);
            return;
        }

        // Default nether: try to return to previous non-default world.
        if (worldName.equalsIgnoreCase(primaryNetherWorldName)) {
            handleNetherToPreviousWorld(player, from, currentWorld);
            return;
        }

        // Any other world: custom route to primary nether.
        handleOtherWorldToNether(player, from, currentWorld);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        savePlayerData(name);
    }

    // ------------------------------------------------------------------------
    // Teleport logic
    // ------------------------------------------------------------------------

    private void handleOtherWorldToNether(Player player, Location from, World currentWorld) {
        String playerName = player.getName();
        String worldName = currentWorld.getName();

        previousWorldName.put(playerName, worldName);
        lastOtherWorldLocation.put(playerName, from.clone());

        World netherWorld = Bukkit.getWorld(primaryNetherWorldName);
        if (netherWorld == null) {
            player.sendMessage("Nether world '" + primaryNetherWorldName + "' does not exist.");
            return;
        }

        Location baseTarget = lastNetherLocation.get(playerName);
        if (baseTarget == null || baseTarget.getWorld() != netherWorld) {
            baseTarget = netherWorld.getSpawnLocation();
        }

        Location safeTarget = findSafeLocationNear(baseTarget);
        safeTarget.setYaw(player.getLocation().getYaw());
        safeTarget.setPitch(player.getLocation().getPitch());

        player.teleport(safeTarget);
        lastNetherLocation.put(playerName, safeTarget.clone());

        ensureArrivalPortalAt(safeTarget);
        savePlayerData(playerName);
    }

    private void handleNetherToPreviousWorld(Player player, Location from, World currentWorld) {
        String playerName = player.getName();

        String originWorldName = previousWorldName.get(playerName);
        if (originWorldName == null) {
            return;
        }

        if (originWorldName.equalsIgnoreCase(primaryWorldName)) {
            return;
        }

        World originWorld = Bukkit.getWorld(originWorldName);
        if (originWorld == null) {
            player.sendMessage("Origin world '" + originWorldName + "' no longer exists.");
            return;
        }

        lastNetherLocation.put(playerName, from.clone());

        Location baseTarget = lastOtherWorldLocation.get(playerName);
        if (baseTarget == null || baseTarget.getWorld() != originWorld) {
            baseTarget = originWorld.getSpawnLocation();
        }

        Location safeTarget = findSafeLocationNear(baseTarget);
        safeTarget.setYaw(player.getLocation().getYaw());
        safeTarget.setPitch(player.getLocation().getPitch());

        player.teleport(safeTarget);
        lastOtherWorldLocation.put(playerName, safeTarget.clone());

        ensureArrivalPortalAt(safeTarget);
        savePlayerData(playerName);
    }

    // ------------------------------------------------------------------------
    // Safe-location + arrival-portal creation
    // ------------------------------------------------------------------------

    private Location findSafeLocationNear(Location base) {
        World world = base.getWorld();
        if (world == null) {
            return base;
        }

        int bx = base.getBlockX();
        int bz = base.getBlockZ();
        int baseY = base.getBlockY();

        if (isSafeStandLocation(world, bx, baseY, bz)) {
            return new Location(world, bx + 0.5, baseY, bz + 0.5, base.getYaw(), base.getPitch());
        }

        int maxY = 127;
        int searchTop = Math.min(maxY, baseY + 10);
        int searchBottom = Math.max(1, baseY - 10);

        for (int y = searchTop; y >= searchBottom; y--) {
            if (isSafeStandLocation(world, bx, y, bz)) {
                return new Location(world, bx + 0.5, y, bz + 0.5, base.getYaw(), base.getPitch());
            }
        }

        Location spawn = world.getSpawnLocation();
        return spawn;
    }

    private boolean isSafeStandLocation(World world, int x, int y, int z) {
        if (y < 1 || y > 125) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);

        if (!isPassable(feet) || !isPassable(head)) {
            return false;
        }
        if (feet.getType() == PORTAL_MATERIAL || head.getType() == PORTAL_MATERIAL) {
            return false;
        }

        if (!isSolidGround(below)) {
            return false;
        }

        return true;
    }

    private boolean isPassable(Block block) {
        Material type = block.getType();
        return type == Material.AIR || type == Material.SNOW;
    }

    private boolean isSolidGround(Block block) {
        Material type = block.getType();
        if (type == Material.AIR ||
            type == Material.WATER ||
            type == Material.STATIONARY_WATER ||
            type == Material.LAVA ||
            type == Material.STATIONARY_LAVA ||
            type == PORTAL_MATERIAL) {
            return false;
        }
        return true;
    }

    private void ensureArrivalPortalAt(Location loc) {
        if (hasNearbyObsidianPortal(loc, 8)) {
            return;
        }
        createArrivalPortalBehind(loc);
    }

    private boolean hasNearbyObsidianPortal(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return false;
        }

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int minY = Math.max(1, cy - radius);
        int maxY = Math.min(127, cy + radius);

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == PORTAL_MATERIAL) {
                        if (isInObsidianPortalFrame(b.getLocation())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void createArrivalPortalBehind(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();

        float yaw = loc.getYaw();
        while (yaw < 0) {
            yaw += 360.0F;
        }
        yaw = yaw % 360.0F;

        String facing;
        if (yaw >= 45 && yaw < 135) {
            facing = "WEST";
        } else if (yaw >= 135 && yaw < 225) {
            facing = "NORTH";
        } else if (yaw >= 225 && yaw < 315) {
            facing = "EAST";
        } else {
            facing = "SOUTH";
        }

        String behind;
        if (facing.equals("NORTH")) {
            behind = "SOUTH";
        } else if (facing.equals("SOUTH")) {
            behind = "NORTH";
        } else if (facing.equals("EAST")) {
            behind = "WEST";
        } else {
            behind = "EAST";
        }

        int baseX;
        int baseY = py - 2;
        int baseZ;

        if (behind.equals("NORTH")) {
            baseX = px - 1;
            baseZ = pz - 2;
            createObsidianPortalX(world, baseX, baseY, baseZ);
        } else if (behind.equals("SOUTH")) {
            baseX = px - 1;
            baseZ = pz + 2;
            createObsidianPortalX(world, baseX, baseY, baseZ);
        } else if (behind.equals("EAST")) {
            baseX = px + 2;
            baseZ = pz - 1;
            createObsidianPortalZ(world, baseX, baseY, baseZ);
        } else { // WEST
            baseX = px - 2;
            baseZ = pz - 1;
            createObsidianPortalZ(world, baseX, baseY, baseZ);
        }
    }

    // ------------------------------------------------------------------------
    // Obsidian frame detection + creation (4x5 standard portals)
    // ------------------------------------------------------------------------

    private boolean isInObsidianPortalFrame(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }

        int lx = loc.getBlockX();
        int ly = loc.getBlockY();
        int lz = loc.getBlockZ();

        Block here = world.getBlockAt(lx, ly, lz);
        if (here.getType() != PORTAL_MATERIAL) {
            return false;
        }

        // Orientation 1: X-Y plane, z fixed
        int baseZ = lz;
        for (int baseX = lx - 3; baseX <= lx; baseX++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lx >= baseX + 1 && lx <= baseX + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidObsidianFrameX(world, baseX, baseY, baseZ)) {
                        return true;
                    }
                }
            }
        }

        // Orientation 2: Z-Y plane, x fixed
        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidObsidianFrameZ(world, baseX, baseY, baseZCandidate)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isValidObsidianFrameX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                boolean isCorner = (dx == 0 || dx == 3) && (dy == 0 || dy == 4);
                boolean isEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isEdge) {
                    if (block.getType() != FRAME_MATERIAL) {
                        return false;
                    }
                } else {
                    Material type = block.getType();
                    if (type != PORTAL_MATERIAL && type != Material.AIR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isValidObsidianFrameZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                boolean isCorner = (dz == 0 || dz == 3) && (dy == 0 || dy == 4);
                boolean isEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isEdge) {
                    if (block.getType() != FRAME_MATERIAL) {
                        return false;
                    }
                } else {
                    Material type = block.getType();
                    if (type != PORTAL_MATERIAL && type != Material.AIR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Create a full obsidian portal in the X–Y plane (z fixed),
     * 4 wide by 5 tall with a 2×3 portal interior.
     *
     * We build the frame first, then fill the interior one tick later
     * to avoid block-updates clearing the portal.
     */
    private void createObsidianPortalX(final World world, final int baseX, final int baseY, final int baseZ) {
        // Pass 1: build only the obsidian frame
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                boolean isEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);
                if (isEdge) {
                    block.setType(FRAME_MATERIAL);
                }
            }
        }

        // Pass 2 (next tick): fill the interior with portal blocks
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                for (int dx = 0; dx < 4; dx++) {
                    for (int dy = 0; dy < 5; dy++) {
                        boolean isEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);
                        if (!isEdge) {
                            Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                            block.setType(PORTAL_MATERIAL);
                            block.setData((byte) 1); // orientation along X
                        }
                    }
                }
            }
        }, 1L);
    }

    /**
     * Create a full obsidian portal in the Z–Y plane (x fixed),
     * 4 wide by 5 tall with a 2×3 portal interior.
     *
     * Same two-pass approach as X-orientation.
     */
    private void createObsidianPortalZ(final World world, final int baseX, final int baseY, final int baseZ) {
        // Pass 1: build only the obsidian frame
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                boolean isEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);
                if (isEdge) {
                    block.setType(FRAME_MATERIAL);
                }
            }
        }

        // Pass 2 (next tick): fill the interior with portal blocks
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                for (int dz = 0; dz < 4; dz++) {
                    for (int dy = 0; dy < 5; dy++) {
                        boolean isEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);
                        if (!isEdge) {
                            Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                            block.setType(PORTAL_MATERIAL);
                            block.setData((byte) 2); // orientation along Z
                        }
                    }
                }
            }
        }, 1L);
    }
}
