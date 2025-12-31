import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.BlockFace;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SkylandsPortals extends JavaPlugin implements Listener {

    private static final Material PORTAL_MATERIAL = Material.NETHER_PORTAL;

    private static final String CONFIG_SKYLANDS_WORLD_NAME = "skylands-world-name";
    private static final String DEFAULT_SKYLANDS_WORLD_NAME = "betaworld_skylands";

    private static final String DEFAULT_FALLBACK_EXIT_WORLD = "world";

    private String skylandsWorldName = DEFAULT_SKYLANDS_WORLD_NAME;

    private File dataFile;
    private FileConfiguration dataConfig;

    private final Map<String, String> previousWorldName = new HashMap<>();
    private final Map<String, Location> lastNormalWorldLocation = new HashMap<>();
    private final Map<String, Location> lastSkylandsLocation = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        ensureDefaultConfigOnDisk();
        loadPluginConfig();

        loadPlayerData();

        getLogger().info("SkylandsPortals enabled. Skylands world = " + skylandsWorldName);
    }

    @Override
    public void onDisable() {
        saveAllPlayerData();
        getLogger().info("SkylandsPortals disabled.");
    }

    private void ensureDefaultConfigOnDisk() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            FileConfiguration created = YamlConfiguration.loadConfiguration(configFile);
            created.set(CONFIG_SKYLANDS_WORLD_NAME, DEFAULT_SKYLANDS_WORLD_NAME);
            try {
                created.save(configFile);
            } catch (IOException e) {
                getLogger().warning("Failed to create default config.yml: " + e.getMessage());
            }
        }
    }

    private void loadPluginConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        skylandsWorldName = config.getString(CONFIG_SKYLANDS_WORLD_NAME, DEFAULT_SKYLANDS_WORLD_NAME);
        if (skylandsWorldName == null || skylandsWorldName.trim().isEmpty()) {
            skylandsWorldName = DEFAULT_SKYLANDS_WORLD_NAME;
        }
    }

    // ------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skylandsportalsreload")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!player.isOp()) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
            }

            loadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[SkylandsPortals] Config reloaded. Skylands world = " + skylandsWorldName);
            getLogger().info("Config reloaded. Skylands world = " + skylandsWorldName);
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------------
    // Portal creation: placing water inside a glowstone frame
    // ------------------------------------------------------------------------

    @EventHandler
    public void onWaterPlaceInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Player player = event.getPlayer();

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() != Material.WATER_BUCKET) {
            return;
        }

        BlockFace face = event.getBlockFace();
        if (face == null) {
            return;
        }

        Block targetBlock = clicked.getRelative(face);
        Location targetLocation = targetBlock.getLocation();

        if (tryCreateGlowstonePortal(targetLocation)) {
            // Stop the water being placed
            event.setCancelled(true);

            // Consume water bucket (unless creative)
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET, 1));
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
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() != Material.BUCKET) {
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.WATER_BUCKET, 1));
        }

        clearPortalInFrame(loc);

        event.setCancelled(true);
    }

    // ------------------------------------------------------------------------
    // Prevent portal physics from popping our custom portals
    // ------------------------------------------------------------------------

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != PORTAL_MATERIAL) {
            return;
        }

        if (isInGlowstonePortalFrame(block.getLocation())) {
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
        int radiusY = 6;
        int radiusZ = 4;

        int minY = Math.max(world.getMinHeight(), cy - radiusY);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radiusY);

        for (int x = cx - radiusX; x <= cx + radiusX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - radiusZ; z <= cz + radiusZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == PORTAL_MATERIAL) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Teleport on entering the portal frame
    // ------------------------------------------------------------------------

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        String playerName = player.getName();

        Block toBlock = to.getBlock();
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

        boolean currentlyInSkylands = currentName.equalsIgnoreCase(skylandsWorldName);

        World targetWorld;
        Location targetBaseLocation;

        if (currentlyInSkylands) {
            // Leaving skylands -> go back to previous world; if none recorded, go to "world"
            lastSkylandsLocation.put(playerName, from.clone());

            String prevName = previousWorldName.get(playerName);
            if (prevName == null || prevName.trim().isEmpty()) {
                prevName = DEFAULT_FALLBACK_EXIT_WORLD;
            }

            targetWorld = Bukkit.getWorld(prevName);
            if (targetWorld == null) {
                // last-ditch: server's first loaded world
                if (!Bukkit.getWorlds().isEmpty()) {
                    targetWorld = Bukkit.getWorlds().get(0);
                    prevName = targetWorld.getName();
                } else {
                    player.sendMessage(ChatColor.RED + "No worlds are loaded to return to.");
                    return;
                }
            }

            Location storedNormal = lastNormalWorldLocation.get(playerName);
            if (storedNormal != null && storedNormal.getWorld() != null
                    && storedNormal.getWorld().getName().equalsIgnoreCase(targetWorld.getName())) {
                targetBaseLocation = storedNormal;
            } else {
                targetBaseLocation = targetWorld.getSpawnLocation();
            }

        } else {
            // Entering skylands -> record the world we came from
            previousWorldName.put(playerName, currentName);
            lastNormalWorldLocation.put(playerName, from.clone());

            targetWorld = Bukkit.getWorld(skylandsWorldName);
            if (targetWorld == null) {
                player.sendMessage(ChatColor.RED + "Target world '" + skylandsWorldName + "' does not exist.");
                return;
            }

            Location storedSky = lastSkylandsLocation.get(playerName);
            if (storedSky != null && storedSky.getWorld() != null
                    && storedSky.getWorld().getName().equalsIgnoreCase(targetWorld.getName())) {
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

        savePlayerData(playerName);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayerData(player.getName());
    }

    // ------------------------------------------------------------------------
    // Safe-ish placement + arrival portal creation
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

        int maxY = world.getMaxHeight() - 2;
        int minY = world.getMinHeight() + 1;

        int searchTop = Math.min(maxY, baseY + 16);
        int searchBottom = Math.max(minY, baseY - 16);

        for (int y = searchTop; y >= searchBottom; y--) {
            if (isSafeStandLocation(world, bx, y, bz)) {
                return new Location(world, bx + 0.5, y, bz + 0.5, base.getYaw(), base.getPitch());
            }
        }

        return world.getSpawnLocation();
    }

    private boolean isSafeStandLocation(World world, int x, int y, int z) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        if (y < minY || y > maxY) {
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

        return isSolidGround(below);
    }

    private boolean isPassable(Block block) {
        if (block.getType() == PORTAL_MATERIAL) {
            return false;
        }
        return block.isPassable();
    }

    private boolean isSolidGround(Block block) {
        Material type = block.getType();
        if (type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR
                || type == Material.WATER
                || type == Material.LAVA
                || type == PORTAL_MATERIAL
                || type == Material.GLOWSTONE) {
            return false;
        }
        return type.isSolid();
    }

    private void ensureArrivalPortalAt(Location loc) {
        // Important change: only count *our* glowstone-framed portals as "nearby"
        if (hasNearbyGlowstonePortal(loc, 10)) {
            return;
        }
        createArrivalPortalBehind(loc);
    }

    private boolean hasNearbyGlowstonePortal(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return false;
        }

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int minY = Math.max(world.getMinHeight(), cy - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radius);

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == PORTAL_MATERIAL) {
                        if (isInGlowstonePortalFrame(b.getLocation())) {
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
        while (yaw < 0) yaw += 360.0F;
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

        if (facing.equals("NORTH")) {
            int baseX = px - 1;
            int baseY = py - 1;
            int baseZ = pz + 2;
            if (isAreaClearForPortalX(world, baseX, baseY, baseZ)) {
                createPortalX(world, baseX, baseY, baseZ);
            }
        } else if (facing.equals("SOUTH")) {
            int baseX = px - 1;
            int baseY = py - 1;
            int baseZ = pz - 2;
            if (isAreaClearForPortalX(world, baseX, baseY, baseZ)) {
                createPortalX(world, baseX, baseY, baseZ);
            }
        } else if (facing.equals("EAST")) {
            int baseX = px - 2;
            int baseY = py - 1;
            int baseZ = pz - 1;
            if (isAreaClearForPortalZ(world, baseX, baseY, baseZ)) {
                createPortalZ(world, baseX, baseY, baseZ);
            }
        } else {
            int baseX = px + 2;
            int baseY = py - 1;
            int baseZ = pz - 1;
            if (isAreaClearForPortalZ(world, baseX, baseY, baseZ)) {
                createPortalZ(world, baseX, baseY, baseZ);
            }
        }
    }

    private boolean isAreaClearForPortalX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                if (!block.isPassable() && block.getType() != Material.GLOWSTONE && block.getType() != PORTAL_MATERIAL) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isAreaClearForPortalZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                if (!block.isPassable() && block.getType() != Material.GLOWSTONE && block.getType() != PORTAL_MATERIAL) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Frame detection + creation / clearing
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
                if (lx >= baseX + 1 && lx <= baseX + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {

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
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {

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
                if (lx >= baseX + 1 && lx <= baseX + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameX(world, baseX, baseY, baseZ)) {
                        clearPortalInteriorX(world, baseX, baseY, baseZ);
                        refreshPortalArea(world, baseX, baseY, baseZ, true);
                        return true;
                    }
                }
            }
        }

        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {

                    if (isValidFrameZ(world, baseX, baseY, baseZCandidate)) {
                        clearPortalInteriorZ(world, baseX, baseY, baseZCandidate);
                        refreshPortalArea(world, baseX, baseY, baseZCandidate, false);
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
                    if (type != Material.AIR
                            && type != Material.CAVE_AIR
                            && type != Material.VOID_AIR
                            && type != Material.WATER
                            && type != PORTAL_MATERIAL) {
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
                    if (type != Material.AIR
                            && type != Material.CAVE_AIR
                            && type != Material.VOID_AIR
                            && type != Material.WATER
                            && type != PORTAL_MATERIAL) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isInGlowstonePortalFrame(Location loc) {
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
                if (lx >= baseX + 1 && lx <= baseX + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {
                    if (isValidFrameX(world, baseX, baseY, baseZ)) {
                        return true;
                    }
                }
            }
        }

        int baseX = lx;
        for (int baseZCandidate = lz - 3; baseZCandidate <= lz; baseZCandidate++) {
            for (int baseY = ly - 4; baseY <= ly; baseY++) {
                if (lz >= baseZCandidate + 1 && lz <= baseZCandidate + 2
                        && ly >= baseY + 1 && ly <= baseY + 3) {
                    if (isValidFrameZ(world, baseX, baseY, baseZCandidate)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void createPortalX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                boolean isCorner = (dx == 0 || dx == 3) && (dy == 0 || dy == 4);
                boolean isFrameEdge = (dx == 0 || dx == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isFrameEdge) {
                    block.setType(Material.GLOWSTONE, false);
                } else {
                    block.setType(PORTAL_MATERIAL, false);
                    setPortalAxis(block, org.bukkit.Axis.X);
                }
            }
        }

        // Force clients to render portal blocks reliably (immediate + 1 tick later)
        refreshPortalArea(world, baseX, baseY, baseZ, true);
    }

    private void createPortalZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 0; dz < 4; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                boolean isCorner = (dz == 0 || dz == 3) && (dy == 0 || dy == 4);
                boolean isFrameEdge = (dz == 0 || dz == 3 || dy == 0 || dy == 4);

                if (isCorner) {
                    continue;
                }

                if (isFrameEdge) {
                    block.setType(Material.GLOWSTONE, false);
                } else {
                    block.setType(PORTAL_MATERIAL, false);
                    setPortalAxis(block, org.bukkit.Axis.Z);
                }
            }
        }

        refreshPortalArea(world, baseX, baseY, baseZ, false);
    }

    private void setPortalAxis(Block block, org.bukkit.Axis axis) {
        try {
            BlockData data = block.getBlockData();
            if (data instanceof Orientable) {
                Orientable orientable = (Orientable) data;
                orientable.setAxis(axis);
                block.setBlockData(orientable, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Forces client-side visuals to update for our portal area.
     * This fixes occasional "portal blocks exist but are invisible" rendering.
     */
    private void refreshPortalArea(World world, int baseX, int baseY, int baseZ, boolean xOriented) {
        // Immediate poke
        pokePortalArea(world, baseX, baseY, baseZ, xOriented);

        // Delayed poke (covers client timing around cancelled water placement)
        Bukkit.getScheduler().runTask(this, () -> pokePortalArea(world, baseX, baseY, baseZ, xOriented));
    }

    private void pokePortalArea(World world, int baseX, int baseY, int baseZ, boolean xOriented) {
        if (world == null) {
            return;
        }

        // Re-apply blockdata to portal blocks in the interior, and update edges too
        for (int w = 0; w < 4; w++) {
            for (int h = 0; h < 5; h++) {
                Block block;
                if (xOriented) {
                    block = world.getBlockAt(baseX + w, baseY + h, baseZ);
                } else {
                    block = world.getBlockAt(baseX, baseY + h, baseZ + w);
                }

                Material type = block.getType();
                if (type == PORTAL_MATERIAL) {
                    BlockData data = block.getBlockData();
                    block.setBlockData(data, false);
                } else {
                    // Touch the frame/interior blocks so the client refreshes neighboring portal faces too
                    BlockData data = block.getBlockData();
                    block.setBlockData(data, false);
                }
            }
        }
    }

    private void clearPortalInteriorX(World world, int baseX, int baseY, int baseZ) {
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ);
                if (block.getType() == PORTAL_MATERIAL) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void clearPortalInteriorZ(World world, int baseX, int baseY, int baseZ) {
        for (int dz = 1; dz <= 2; dz++) {
            for (int dy = 1; dy <= 3; dy++) {
                Block block = world.getBlockAt(baseX, baseY + dy, baseZ + dz);
                if (block.getType() == PORTAL_MATERIAL) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Player data persistence (data.yml)
    // ------------------------------------------------------------------------

    private void loadPlayerData() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        dataFile = new File(getDataFolder(), "data.yml");
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        if (!dataConfig.contains("players")) {
            return;
        }

        for (String playerName : dataConfig.getConfigurationSection("players").getKeys(false)) {
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

    private void saveAllPlayerData() {
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

    private void savePlayerData(String playerName) {
        if (dataConfig == null || dataFile == null) {
            loadPlayerData();
            if (dataConfig == null || dataFile == null) {
                return;
            }
        }

        String base = "players." + playerName;

        String prevWorld = previousWorldName.get(playerName);
        if (prevWorld != null) {
            dataConfig.set(base + ".previous-world", prevWorld);
        }

        Location normalLoc = lastNormalWorldLocation.get(playerName);
        writeLocation(base + ".last-normal", normalLoc);

        Location skyLoc = lastSkylandsLocation.get(playerName);
        writeLocation(base + ".last-skylands", skyLoc);

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }

    private Location readLocation(String path) {
        if (!dataConfig.contains(path + ".world")) {
            return null;
        }

        String worldName = dataConfig.getString(path + ".world", null);
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        double x = dataConfig.getDouble(path + ".x");
        double y = dataConfig.getDouble(path + ".y");
        double z = dataConfig.getDouble(path + ".z");
        float yaw = (float) dataConfig.getDouble(path + ".yaw");
        float pitch = (float) dataConfig.getDouble(path + ".pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    private void writeLocation(String path, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            dataConfig.set(path, null);
            return;
        }

        dataConfig.set(path + ".world", loc.getWorld().getName());
        dataConfig.set(path + ".x", loc.getX());
        dataConfig.set(path + ".y", loc.getY());
        dataConfig.set(path + ".z", loc.getZ());
        dataConfig.set(path + ".yaw", loc.getYaw());
        dataConfig.set(path + ".pitch", loc.getPitch());
    }
}
