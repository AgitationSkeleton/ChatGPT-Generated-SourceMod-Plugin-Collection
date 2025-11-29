// SkylandsOverworldSpawns.java
//
// Makes Skylands use Overworld-like mob spawn rules in Beta 1.7.3.
// - Intercepts natural spawns in Skylands and reshapes them to Overworld-style
//   hostiles/passives based on light + ground.
// - Periodically spawns hostiles around players in Skylands wherever it is
//   dark enough (light level <= 7), ignoring world time.

import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.java.JavaPlugin;

public class SkylandsOverworldSpawns extends JavaPlugin implements Listener {

    private final Random random = new Random();
    private int hostileTaskId = -1;

    // Set this to true if you want console spam about hostile spawns.
    private static final boolean DEBUG_SPAWNS = true;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // Run hostile spawn logic every 10 seconds (200 ticks).
        hostileTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
            this,
            new Runnable() {
                public void run() {
                    spawnHostilesInSkylands();
                }
            },
            200L,
            200L
        );

        System.out.println("[SkylandsOverworldSpawns] Enabled.");
    }

    @Override
    public void onDisable() {
        if (hostileTaskId != -1) {
            getServer().getScheduler().cancelTask(hostileTaskId);
        }
        System.out.println("[SkylandsOverworldSpawns] Disabled.");
    }

    /**
     * Detect whether this world is a Skylands / Sky Dimension world.
     *
     * If your Skylands world has a different name, adjust this method.
     */
    private boolean isSkylandsWorld(World world) {
        if (world == null) {
            return false;
        }

        String name = world.getName();
        if (name == null) {
            return false;
        }

        String lower = name.toLowerCase();

        // Common names: "Skylands", "skylands", etc.
        // Adjust if your server uses a custom name.
        if (lower.contains("skylands") || lower.contains("sky")) {
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------------
    //  1) Event-based adjustments for natural spawns (passives + hostiles)
    // ------------------------------------------------------------------------

    @EventHandler(priority = Priority.Normal, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (!isSkylandsWorld(world)) {
            return;
        }

        SpawnReason reason = event.getSpawnReason();

        // Older Bukkit for Beta doesn't have CHUNK_GEN, so just check NATURAL.
        if (reason != SpawnReason.NATURAL) {
            return;
        }

        Location spawnLocation = event.getLocation();
        Block spawnBlock = spawnLocation.getBlock();
        Block blockBelow = spawnBlock.getRelative(0, -1, 0);

        int lightLevel = spawnBlock.getLightLevel();

        // Hostile conditions (approx Beta 1.7.3):
        // - Solid block below
        // - Light level <= 7
        //
        // Passive conditions:
        // - Grass block below
        // - Light level >= 9
        //
        Material belowType = blockBelow.getType();
        boolean belowIsSolid = isApproxSolid(belowType);
        boolean belowIsGrass = (belowType == Material.GRASS);

        boolean shouldBeHostile = belowIsSolid && (lightLevel <= 7);
        boolean shouldBePassive = belowIsGrass && (lightLevel >= 9);

        if (!shouldBeHostile && !shouldBePassive) {
            // Location isn't valid for any standard Overworld mob spawns
            // under typical rules, so cancel it.
            event.setCancelled(true);
            return;
        }

        CreatureType replacementType;

        if (shouldBeHostile) {
            replacementType = chooseRandomHostile();
        } else {
            replacementType = chooseRandomPassive();
        }

        // If the original mob already matches what we want, let it spawn.
        CreatureType originalType = event.getCreatureType();
        if (originalType == replacementType) {
            return;
        }

        // Otherwise, cancel the original spawn and create our own entity.
        event.setCancelled(true);

        try {
            world.spawnCreature(spawnLocation, replacementType);
        } catch (Throwable t) {
            // Fail-safe: don't crash the server if something goes wrong.
            System.out.println("[SkylandsOverworldSpawns] Failed to spawn "
                    + replacementType + " at " + formatLocation(spawnLocation)
                    + ": " + t.getMessage());
        }
    }

    /**
     * Approximate "solid" blocks for old Material API (no isSolid()).
     */
    private boolean isApproxSolid(Material material) {
        if (material == null) {
            return false;
        }

        // Treat obvious non-solids as not solid.
        if (material == Material.AIR ||
            material == Material.WATER ||
            material == Material.STATIONARY_WATER ||
            material == Material.LAVA ||
            material == Material.STATIONARY_LAVA) {
            return false;
        }

        // Everything else we treat as solid enough to stand on.
        return true;
    }

    /**
     * Approximate Overworld hostile mob selection for Beta 1.7.3.
     */
    private CreatureType chooseRandomHostile() {
        // Typical hostile pool: Zombie, Skeleton, Creeper, Spider.
        int roll = random.nextInt(4);

        switch (roll) {
            case 0:
                return CreatureType.ZOMBIE;
            case 1:
                return CreatureType.SKELETON;
            case 2:
                return CreatureType.CREEPER;
            case 3:
            default:
                return CreatureType.SPIDER;
        }
    }

    /**
     * Approximate Overworld passive mob selection for Beta 1.7.3.
     */
    private CreatureType chooseRandomPassive() {
        // Basic passive pool matching classic Overworld animals:
        // Pig, Sheep, Cow, Chicken, Wolf.
        int roll = random.nextInt(5);

        switch (roll) {
            case 0:
                return CreatureType.PIG;
            case 1:
                return CreatureType.SHEEP;
            case 2:
                return CreatureType.COW;
            case 3:
                return CreatureType.CHICKEN;
            case 4:
            default:
                return CreatureType.WOLF;
        }
    }

    // ------------------------------------------------------------------------
    //  2) Periodic hostile spawns in dark areas around players (time-independent)
    // ------------------------------------------------------------------------

    private void spawnHostilesInSkylands() {
        List<World> worlds = getServer().getWorlds();
        for (World world : worlds) {
            if (!isSkylandsWorld(world)) {
                continue;
            }

            List<Player> players = world.getPlayers();
            if (players == null || players.isEmpty()) {
                continue;
            }

            // Try more attempts per run per world to actually hit caves/overhangs
            for (int i = 0; i < 10; i++) {
                Player anchor = players.get(random.nextInt(players.size()));
                trySpawnHostileNearPlayer(world, anchor, players);
            }
        }
    }

    private void trySpawnHostileNearPlayer(World world, Player player, List<Player> allPlayers) {
        Location playerLoc = player.getLocation();
        int baseX = playerLoc.getBlockX();
        int baseZ = playerLoc.getBlockZ();

        // Choose a random position within ~32-64 blocks of the player,
        // but not too close.
        int dx = random.nextInt(49) - 24; // -24..24
        int dz = random.nextInt(49) - 24; // -24..24

        if (Math.abs(dx) < 8 && Math.abs(dz) < 8) {
            dx *= 2;
            dz *= 2;
        }

        int x = baseX + dx;
        int z = baseZ + dz;

        Location spawnLoc = findDarkSpawnLocation(world, x, z, allPlayers);
        if (spawnLoc == null) {
            return;
        }

        CreatureType type = chooseRandomHostile();

        try {
            Entity spawned = world.spawnCreature(spawnLoc, type);
            if (spawned != null && DEBUG_SPAWNS) {
                System.out.println("[SkylandsOverworldSpawns] Spawned " + type +
                        " at " + formatLocation(spawnLoc));
            }
        } catch (Throwable t) {
            System.out.println("[SkylandsOverworldSpawns] Failed to spawn hostile "
                    + type + " at " + formatLocation(spawnLoc) + ": " + t.getMessage());
        }
    }

    /**
     * Scan vertically at (x,z) for a dark, valid spawn position:
     * - Solid ground block.
     * - Air block above it to stand in.
     * - Light level at the air block <= 7.
     * - Distance from players between 24 and 128 blocks.
     */
    private Location findDarkSpawnLocation(World world, int x, int z, List<Player> players) {
        if (players == null || players.isEmpty()) {
            return null;
        }

        int maxY;
        try {
            maxY = world.getMaxHeight();
        } catch (Throwable t) {
            // Beta default height
            maxY = 128;
        }

        double minDistSq = 24.0 * 24.0;
        double maxDistSq = 128.0 * 128.0;

        // Scan downward from near the top of the world
        for (int y = maxY - 2; y >= 1; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);

            if (!isApproxSolid(ground.getType())) {
                continue;
            }

            if (above.getType() != Material.AIR) {
                continue;
            }

            int light = above.getLightLevel();
            if (light > 7) {
                continue;
            }

            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

            boolean okDistance = false;
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                Location pl = p.getLocation();
                if (pl.getWorld() != world) {
                    continue;
                }
                double distSq = pl.distanceSquared(loc);
                if (distSq < minDistSq) {
                    // Too close to some player
                    okDistance = false;
                    break;
                }
                if (distSq <= maxDistSq) {
                    okDistance = true;
                }
            }

            if (!okDistance) {
                continue;
            }

            return loc;
        }

        return null;
    }

    // ------------------------------------------------------------------------
    //  Utility
    // ------------------------------------------------------------------------

    private String formatLocation(Location loc) {
        if (loc == null) {
            return "(null)";
        }
        World world = loc.getWorld();
        String worldName = (world != null) ? world.getName() : "(nullWorld)";
        return worldName + "@" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
