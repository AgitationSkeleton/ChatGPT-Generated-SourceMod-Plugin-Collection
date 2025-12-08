import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

public class SkylandsPortals extends JavaPlugin implements Listener {

    // Default skylands world name; actual value is loaded from config.yml
    private static final String DEFAULT_SKYLANDS_WORLD_NAME = "skylands";

    // Use real nether portal blocks
    private static final Material PORTAL_MATERIAL = Material.PORTAL;

    // Primary / main world name as seen by the server (first in the worlds list)
    private String primaryWorldName = "world";

    // Config + configured skylands world name
    private Configuration config;
    private String skylandsWorldName = DEFAULT_SKYLANDS_WORLD_NAME;

    // Persistent player data file
    private Configuration dataConfig;

    // Track the last non-skylands world name per player
    private final Map<String, String> previousWorldName = new HashMap<String, String>();

    // Track last safe-ish positions in each side for each player
    // These are the positions the player held BEFORE stepping into the portal.
    private final Map<String, Location> lastNormalWorldLocation = new HashMap<String, Location>();
    private final Map<String, Location> lastSkylandsLocation = new HashMap<String, Location>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // Determine what the server considers the main/primary world
        if (!getServer().getWorlds().isEmpty()) {
            primaryWorldName = getServer().getWorlds().get(0).getName();
        } else {
            primaryWorldName = "world";
        }

        // Prepare and load config.yml
        loadPluginConfig();

        // Prepare and load persistent player data
        loadPlayerData();

        System.out.println("[SkylandsPortals] enabled. Primary world = " + primaryWorldName
                + ", skylands world = " + skylandsWorldName);
    }

    @Override
    public void onDisable() {
        saveAllPlayerData();
        System.out.println("[SkylandsPortals] disabled.");
    }

    // ------------------------------------------------------------------------
    // Config handling
    // ------------------------------------------------------------------------

    private void loadPluginConfig() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File configFile = new File(dataFolder, "config.yml");
        config = new Configuration(configFile);
        config.load();

        skylandsWorldName = config.getString("skylands-world-name", DEFAULT_SKYLANDS_WORLD_NAME);

        config.setProperty("skylands-world-name", skylandsWorldName);
        config.save();
    }

    // ------------------------------------------------------------------------
    // Persistent player data
    // ------------------------------------------------------------------------

    private void loadPlayerData() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(dataFolder, "data.yml");
        dataConfig = new Configuration(dataFile);
        dataConfig.load();

        // Load players.* section
        for (String playerName : dataConfig.getKeys("players")) {
            String base = "players." + playerName;

            String prev = dataConfig.getString(base + ".previous-world", null);
            if (prev != null) {
                previousWorldName.put(playerName, prev);
            }

            Location normalLoc = readLocation(base + ".last-normal");
            if (normalLoc != null) {
                lastNormalWorldLocation.put(playerName, normalLoc);
            }

            Location skyLoc = readLocation(base + ".last-skylands");
            if (skyLoc != null) {
                lastSkylandsLocation.put(playerName, skyLoc);
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

        writeLocation(base + ".last-normal", lastNormalWorldLocation.get(playerName));
        writeLocation(base + ".last-skylands", lastSkylandsLocation.get(playerName));

        dataConfig.save();
    }

    private void saveAllPlayerData() {
        if (dataConfig == null) {
            return;
        }
        for (String playerName : previousWorldName.keySet()) {
            savePlayerData(playerName);
        }
        for (String playerName : lastNormalWorldLocation.keySet()) {
            savePlayerData(playerName);
        }
        for (String playerName : lastSkylandsLocation.keySet()) {
            savePlayerData(playerName);
        }
    }

    // ------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("skylandsportalsreload")) {
            // Simple permission check: allow console, or in-game op
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (!p.isOp()) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
            }

            loadPluginConfig();
            // Keep existing player data; just re-read base config
            sender.sendMessage(ChatColor.GREEN + "[SkylandsPortals] Config reloaded. Skylands world = "
                    + skylandsWorldName);
            System.out.println("[SkylandsPortals] Config reloaded. Skylands world = " + skylandsWorldName);
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------

    /**
     * Handle water-bucket usage to "light" a glowstone portal.
     */
    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (event.getBucket() != Material.WATER_BUCKET) {
            return;
        }

        Block clicked = event.getBlockClicked();
        if (clicked == null) {
            return;
        }

        // This is where the water would go
        Block targetBlock = clicked.getRelative(event.getBlockFace());
        Location targetLocation = targetBlock.getLocation();

        if (tryCreateGlowstonePortal(targetLocation)) {
            // Stop the actual water from being placed
            event.setCancelled(true);

            // Consume the water in the player's hand and leave an empty bucket
            Player player = event.getPlayer();
            ItemStack hand = player.getItemInHand();
            if (hand != null && hand.getType() == Material.WATER_BUCKET) {
                hand.setType(Material.BUCKET);
                hand.setAmount(1);
                player.setItemInHand(hand);
            }
        }
    }

    /**
     * Right-clicking a portal block inside a glowstone frame with an empty bucket
     * "scoops" the portal up: gives the player a water bucket and clears the portal interior.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        if (clicked.getType() != PORTAL_MATERIAL) {
            return;
        }

        Location loc = clicked.getLocation();
        if (!isInGlowstonePortalFrame(loc)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack hand = player.getItemInHand();
        if (hand == null || hand.getType() != Material.BUCKET) {
            return;
        }

        // We are scooping up the portal: turn one empty bucket into a water bucket
        int amount = hand.getAmount();
        if (amount <= 1) {
            hand.setType(Material.WATER_BUCKET);
            hand.setAmount(1);
        } else {
            hand.setAmount(amount - 1);
            player.getInventory().addItem(new ItemStack(Material.WATER_BUCKET, 1));
        }

        // Clear portal interior for this specific frame
        clearPortalInFrame(loc);

        event.setCancelled(true);
    }

    /**
     * Physics on portal blocks:
     * - If they're in an intact glowstone frame (our custom portal), cancel physics so random
     *   updates don't eat the portal.
     * - Otherwise (vanilla obsidian portals, or glowstone frame already broken), let physics
     *   proceed so portals can disappear normally.
     */
    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != PORTAL_MATERIAL) {
            return;
        }

        Location loc = block.getLocation();

        if (isInGlowstonePortalFrame(loc)) {
            event.setCancelled(true);
        }
    }

    /**
     * If a glowstone block from a frame is broken, destroy nearby portal blocks.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.GLOWSTONE) {
            return;
        }

        Location loc = block.getLocation();
        World world = block.getWorld();

        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        int radiusX = 4;
        int radiusY = 5;
        int radiusZ = 4;

        for (int x = cx - radiusX; x <= cx + radiusX; x++) {
            for (int y = cy - radiusY; y <= cy + radiusY; y++) {
                for (int z = cz - radiusZ; z <= cz + radiusZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == PORTAL_MATERIAL) {
                        b.setType(Material.AIR);
                    }
                }
            }
        }
    }

    /**
     * Detect when a player steps into a nether portal block inside our glowstone
     * frame and immediately teleport them, bypassing vanilla dimension logic.
     */
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

        Block below = toBlock.getRelative(0, -1, 0);
        Block above = toBlock.getRelative(0, 1, 0);
        if (below.getType() != Material.GLOWSTONE && above.getType() != Material.GLOWSTONE) {
            return;
        }

        if (!isInGlowstonePortalFrame(to)) {
            return;
        }

        World currentWorld = player.getWorld();
        String currentName = currentWorld.getName();

        World targetWorld;
        Location targetBaseLocation;

        if (currentName.equalsIgnoreCase(skylandsWorldName)) {
            // Leaving skylands
            lastSkylandsLocation.put(playerName, from.clone());

            String storedWorldName = previousWorldName.get(playerName);
            if (storedWorldName == null ||
                storedWorldName.equalsIgnoreCase(skylandsWorldName) ||
                Bukkit.getWorld(storedWorldName) == null) {
                storedWorldName = primaryWorldName;
            }

            targetWorld = Bukkit.getWorld(storedWorldName);
            if (targetWorld == null) {
                player.sendMessage(ChatColor.RED + "Target world '" + storedWorldName + "' does not exist.");
                return;
            }

            Location storedNormal = lastNormalWorldLocation.get(playerName);
            if (storedNormal != null && storedNormal.getWorld() == targetWorld) {
                targetBaseLocation = storedNormal;
            } else {
                targetBaseLocation = targetWorld.getSpawnLocation();
            }

        } else {
            // Entering skylands
            previousWorldName.put(playerName, currentName);
            lastNormalWorldLocation.put(playerName, from.clone());

            targetWorld = Bukkit.getWorld(skylandsWorldName);
            if (targetWorld == null) {
                player.sendMessage(ChatColor.RED + "Target world '" + skylandsWorldName + "' does not exist.");
                return;
            }

            Location storedSky = lastSkylandsLocation.get(playerName);
            if (storedSky != null && storedSky.getWorld() == targetWorld) {
                targetBaseLocation = storedSky;
            } else {
                targetBaseLocation = targetWorld.getSpawnLocation();
            }
        }

        Location safeTarget = findSafeLocationNear(targetBaseLocation);
        safeTarget.setYaw(player.getLocation().getYaw());
        safeTarget.setPitch(player.getLocation().getPitch());

        player.teleport(safeTarget);
        ensureArrivalPortalAt(safeTarget);

        // Persist updated info
        savePlayerData(playerName);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Only clear truly transient data if we add any later.
        // We keep previousWorldName / locations to allow persistence across reconnects.
        String name = event.getPlayer().getName();
        // Nothing to remove for persistence.
        savePlayerData(name);
    }

    // ------------------------------------------------------------------------
    // Safe location + arrival portal handling
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
            type == PORTAL_MATERIAL ||
            type == Material.GLOWSTONE) {
            return false;
        }
        return true;
    }

    private void ensureArrivalPortalAt(Location loc) {
        if (hasNearbyPortal(loc, 8)) {
            return;
        }
        createArrivalPortalBehind(loc);
    }

    private boolean hasNearbyPortal(Location center, int radius) {
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
                    // Any portal nearby is good enough; don't recreate.
                    return true;
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
            createPortalX(world, baseX, baseY, baseZ);
        } else if (behind.equals("SOUTH")) {
            baseX = px - 1;
            baseZ = pz + 2;
            createPortalX(world, baseX, baseY, baseZ);
        } else if (behind.equals("EAST")) {
            baseX = px + 2;
            baseZ = pz - 1;
            createPortalZ(world, baseX, baseY, baseZ);
        } else {
            baseX = px - 2;
            baseZ = pz - 1;
            createPortalZ(world, baseX, baseY, baseZ);
        }
    }

    // ------------------------------------------------------------------------
    // Frame / portal detection + creation / clearing
    // ------------------------------------------------------------------------

    private boolean tryCreateGlowstonePortal(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }

        int lx = loc.getBlockX();
        int ly = loc.getBlockY();
        int lz = loc.getBlockZ();

        int baseZ = lz;
        for (int baseX = lx - 3; baseX <= lx; baseX++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lx >= baseX + 1 && lx <= baseX + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameX(world, baseX, baseY, baseZ)) {
                        createPortalX(world, baseX, baseY, baseZ);
                        return true;
                    }
                }
            }
        }

        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameZ(world, baseX, baseY, baseZCandidate)) {
                        createPortalZ(world, baseX, baseY, baseZCandidate);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean clearPortalInFrame(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }

        int lx = loc.getBlockX();
        int ly = loc.getBlockY();
        int lz = loc.getBlockZ();

        int baseZ = lz;
        for (int baseX = lx - 3; baseX <= lx; baseX++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lx >= baseX + 1 && lx <= baseX + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameX(world, baseX, baseY, baseZ)) {
                        clearPortalInteriorX(world, baseX, baseY, baseZ);
                        return true;
                    }
                }
            }
        }

        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameZ(world, baseX, baseY, baseZCandidate)) {
                        clearPortalInteriorZ(world, baseX, baseY, baseZCandidate);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isInGlowstonePortalFrame(Location loc) {
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

        int baseZ = lz;
        for (int baseX = lx - 3; baseX <= lx; baseX++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lx >= baseX + 1 && lx <= baseX + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameX(world, baseX, baseY, baseZ)) {
                        return true;
                    }
                }
            }
        }

        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2 &&
                    ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameZ(world, baseX, baseY, baseZCandidate)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isValidFrameX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                boolean isCorner = (dx == 0 || dx == 3) && (dy == 0 || dy == 4);
                boolean isFrameEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isFrameEdge) {
                    if (block.getType() != Material.GLOWSTONE) {
                        return false;
                    }
                } else {
                    Material type = block.getType();
                    if (type != Material.AIR &&
                        type != Material.WATER &&
                        type != Material.STATIONARY_WATER &&
                        type != PORTAL_MATERIAL) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isValidFrameZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                boolean isCorner = (dz == 0 || dz == 3) && (dy == 0 || dy == 4);
                boolean isFrameEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isFrameEdge) {
                    if (block.getType() != Material.GLOWSTONE) {
                        return false;
                    }
                } else {
                    Material type = block.getType();
                    if (type != Material.AIR &&
                        type != Material.WATER &&
                        type != Material.STATIONARY_WATER &&
                        type != PORTAL_MATERIAL) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void createPortalX(World world, int baseX, int baseY, int baseZ) {
        int portalId = PORTAL_MATERIAL.getId();
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                boolean isFrameEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);
                if (isFrameEdge) {
                    block.setType(Material.GLOWSTONE);
                } else {
                    block.setTypeIdAndData(portalId, (byte) 1, false);
                }
            }
        }
    }

    private void createPortalZ(World world, int baseX, int baseY, int baseZ) {
        int portalId = PORTAL_MATERIAL.getId();
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                boolean isFrameEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);
                if (isFrameEdge) {
                    block.setType(Material.GLOWSTONE);
                } else {
                    block.setTypeIdAndData(portalId, (byte) 2, false);
                }
            }
        }
    }

    private void clearPortalInteriorX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                if (block.getType() == PORTAL_MATERIAL) {
                    block.setType(Material.AIR);
                }
            }
        }
    }

    private void clearPortalInteriorZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 1; dz <= 2; dz++) {
            for (int dy = 1; dy <= 3; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                if (block.getType() == PORTAL_MATERIAL) {
                    block.setType(Material.AIR);
                }
            }
        }
    }
}
