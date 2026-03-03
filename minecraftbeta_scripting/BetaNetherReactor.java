import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

/**
 * BetaNetherReactor
 * Bukkit/Poseidon Beta 1.7.3-compatible single-file plugin.
 *
 * Author: ChatGPT
 */
public class BetaNetherReactor extends JavaPlugin implements Listener {

    // Config defaults
    private boolean spawnSpire = true;
    private boolean spawnDrops = true;
    private boolean spawnPigmen = true;
    private boolean pigmenHostile = false;

    private Material spireMaterial = Material.NETHERRACK; // netherrack/obsidian
    private boolean turnReactorBuildIntoObsidian = true;

    private boolean requireAllPlayersNearby = true;
    private double reactorDistanceRequirement = 16.0;

    private boolean allowNetherWorlds = false;
    private boolean allowSkylandsWorlds = true;

    private Set<String> worldWhitelistLower = new HashSet<String>();

    private int reactorEventDurationSeconds = 46;
    private int cooldownSeconds = 300;

    private boolean requireAmpleSpace = true;
    private Material reactorBuildMaterial = Material.COBBLESTONE; // cobblestone/iron block

    // Runtime state
    private final Map<String, Long> reactorCooldownByKey = new HashMap<String, Long>();
    private final Set<String> activeReactors = new HashSet<String>();

    private final Random rng = new Random();

    // Scheduler task id
    private int reactorTickTaskId = -1;

    // Reactor dome/spire bounds
    private static final int DOME_EXPAND = 8;
    private static final int DOME_MIN_Y_OFFSET = -3;
    private static final int DOME_MAX_Y_OFFSET = 12;

    // Per-reactor event state storage
    private final Map<String, ReactorRunState> runStates = new HashMap<String, ReactorRunState>();

    // Old Bukkit mob spawning uses CreatureType; use reflection so this compiles even if enum differs.
    private Object cachedPigZombieCreatureType = null;
    private boolean cachedCreatureTypeSearched = false;

    // Simple logger
    private java.util.logging.Logger log;

    @Override
    public void onEnable() {
        this.log = getServer().getLogger();

        loadOrCreateConfigProperties();

        getServer().getPluginManager().registerEvents(this, this);

        // 1Hz tick for all active reactors
        reactorTickTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
                this,
                new Runnable() {
                    public void run() {
                        tickAllReactorsOncePerSecond();
                    }
                },
                20L,
                20L
        );

        log.info("[BetaNetherReactor] enabled.");
    }

    @Override
    public void onDisable() {
        if (reactorTickTaskId != -1) {
            try {
                getServer().getScheduler().cancelTask(reactorTickTaskId);
            } catch (Throwable ignored) {
            }
        }
        activeReactors.clear();
        runStates.clear();
        log.info("[BetaNetherReactor] disabled.");
    }

    // -------------------------------------------------------------------------
    // Config (Properties file)
    // -------------------------------------------------------------------------

    private void loadOrCreateConfigProperties() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }

        File cfgFile = new File(dataFolder, "config.properties");
        Properties props = new Properties();

        // Defaults
        putDefault(props, "spawnSpire", "1");
        putDefault(props, "spawnDrops", "1");
        putDefault(props, "spawnPigmen", "1");
        putDefault(props, "pigmenHostile", "0");
        putDefault(props, "spireMaterial", "netherrack"); // netherrack/obsidian
        putDefault(props, "turnReactorBuildIntoObsidian", "1");
        putDefault(props, "requireAllPlayersInWorldNearReactor", "1");
        putDefault(props, "reactorDistanceRequirement", "16.0");
        putDefault(props, "allowNetherWorlds", "0");
        putDefault(props, "allowSkylandsWorlds", "1");
        putDefault(props, "worldWhitelist", ""); // comma-separated list; blank = allow all (subject to env toggles)
        putDefault(props, "reactorEventDurationSeconds", "46");
        putDefault(props, "cooldownSeconds", "300");
        putDefault(props, "requireAmpleSpace", "1");
        putDefault(props, "reactorBuildMaterial", "cobblestone"); // cobblestone/iron_block

        boolean wroteDefaults = false;

        if (cfgFile.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(cfgFile);
                props.load(fis);
            } catch (Exception ex) {
                log.warning("[BetaNetherReactor] Failed to read config.properties, using defaults: " + ex.getMessage());
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (IOException ignored) {}
                }
            }
        } else {
            wroteDefaults = true;
        }

        // If file didn't exist, write it now.
        if (wroteDefaults) {
            saveProperties(cfgFile, props);
        } else {
            // Ensure any missing keys are added (and rewrite if needed)
            boolean changed = ensureAllDefaultsPresent(props);
            if (changed) saveProperties(cfgFile, props);
        }

        // Read into fields
        spawnSpire = readBoolFlexible(props.getProperty("spawnSpire", "1"), true);
        spawnDrops = readBoolFlexible(props.getProperty("spawnDrops", "1"), true);
        spawnPigmen = readBoolFlexible(props.getProperty("spawnPigmen", "1"), true);
        pigmenHostile = readBoolFlexible(props.getProperty("pigmenHostile", "0"), false);

        String spireMatStr = props.getProperty("spireMaterial", "netherrack").trim();
        spireMaterial = "obsidian".equalsIgnoreCase(spireMatStr) ? Material.OBSIDIAN : Material.NETHERRACK;

        turnReactorBuildIntoObsidian = readBoolFlexible(props.getProperty("turnReactorBuildIntoObsidian", "1"), true);

        requireAllPlayersNearby = readBoolFlexible(props.getProperty("requireAllPlayersInWorldNearReactor", "1"), true);
        reactorDistanceRequirement = readDouble(props.getProperty("reactorDistanceRequirement", "16.0"), 16.0);

        allowNetherWorlds = readBoolFlexible(props.getProperty("allowNetherWorlds", "0"), false);
        allowSkylandsWorlds = readBoolFlexible(props.getProperty("allowSkylandsWorlds", "1"), true);

        reactorEventDurationSeconds = clampInt(readInt(props.getProperty("reactorEventDurationSeconds", "46"), 46), 5, 600);
        cooldownSeconds = clampInt(readInt(props.getProperty("cooldownSeconds", "300"), 300), 0, 86400);

        requireAmpleSpace = readBoolFlexible(props.getProperty("requireAmpleSpace", "1"), true);

        String buildMatStr = props.getProperty("reactorBuildMaterial", "cobblestone").trim();
        reactorBuildMaterial = "iron_block".equalsIgnoreCase(buildMatStr) ? Material.IRON_BLOCK : Material.COBBLESTONE;

        // World whitelist parsing (comma-separated)
        worldWhitelistLower.clear();
        String wl = props.getProperty("worldWhitelist", "").trim();
        if (wl.length() > 0) {
            String[] parts = wl.split(",");
            for (int i = 0; i < parts.length; i++) {
                String name = parts[i] == null ? "" : parts[i].trim();
                if (name.length() == 0) continue;
                worldWhitelistLower.add(name.toLowerCase(Locale.ROOT));
            }
        }

        log.info("[BetaNetherReactor] Config loaded: spawnSpire=" + spawnSpire
                + ", spawnDrops=" + spawnDrops
                + ", spawnPigmen=" + spawnPigmen
                + ", pigmenHostile=" + pigmenHostile
                + ", spireMaterial=" + spireMaterial
                + ", buildMaterial=" + reactorBuildMaterial
                + ", durationSeconds=" + reactorEventDurationSeconds
                + ", cooldownSeconds=" + cooldownSeconds);
    }

    private void putDefault(Properties props, String key, String value) {
        if (!props.containsKey(key)) {
            props.setProperty(key, value);
        }
    }

    private boolean ensureAllDefaultsPresent(Properties props) {
        Properties defaults = new Properties();
        putDefault(defaults, "spawnSpire", "1");
        putDefault(defaults, "spawnDrops", "1");
        putDefault(defaults, "spawnPigmen", "1");
        putDefault(defaults, "pigmenHostile", "0");
        putDefault(defaults, "spireMaterial", "netherrack");
        putDefault(defaults, "turnReactorBuildIntoObsidian", "1");
        putDefault(defaults, "requireAllPlayersInWorldNearReactor", "1");
        putDefault(defaults, "reactorDistanceRequirement", "16.0");
        putDefault(defaults, "allowNetherWorlds", "0");
        putDefault(defaults, "allowSkylandsWorlds", "1");
        putDefault(defaults, "worldWhitelist", "");
        putDefault(defaults, "reactorEventDurationSeconds", "46");
        putDefault(defaults, "cooldownSeconds", "300");
        putDefault(defaults, "requireAmpleSpace", "1");
        putDefault(defaults, "reactorBuildMaterial", "cobblestone");

        boolean changed = false;
        for (Object kObj : defaults.keySet()) {
            String k = (String) kObj;
            if (!props.containsKey(k)) {
                props.setProperty(k, defaults.getProperty(k));
                changed = true;
            }
        }
        return changed;
    }

    private void saveProperties(File cfgFile, Properties props) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cfgFile);
            props.store(fos, "BetaNetherReactor config (comma-separated worldWhitelist; 1/0 booleans)");
        } catch (Exception ex) {
            log.warning("[BetaNetherReactor] Failed to write config.properties: " + ex.getMessage());
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    private boolean readBoolFlexible(String raw, boolean def) {
        if (raw == null) return def;
        String s = raw.trim();
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return def;
    }

    private int readInt(String raw, int def) {
        if (raw == null) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private double readDouble(String raw, double def) {
        if (raw == null) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    // -------------------------------------------------------------------------
    // Activation
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        if (clicked.getType() != Material.DIAMOND_BLOCK) return;

        Player player = e.getPlayer();
        World world = clicked.getWorld();

        if (!isWorldAllowed(world)) {
            player.sendMessage(ChatColor.RED + "[Reactor] Reactors are disabled in this world.");
            return;
        }

        if (!isValidReactorAtCore(clicked)) {
            player.sendMessage(ChatColor.RED + "[Reactor] This diamond block is not built into a valid Nether Reactor.");
            return;
        }

        final String reactorKey = makeReactorKey(clicked);

        if (activeReactors.contains(reactorKey)) {
            player.sendMessage(ChatColor.YELLOW + "[Reactor] This reactor is already active.");
            return;
        }

        if (cooldownSeconds > 0) {
            long now = System.currentTimeMillis();
            Long last = reactorCooldownByKey.get(reactorKey);
            if (last != null) {
                long elapsedSec = (now - last.longValue()) / 1000L;
                long remaining = (long) cooldownSeconds - elapsedSec;
                if (remaining > 0) {
                    player.sendMessage(ChatColor.YELLOW + "[Reactor] Reactor cooling down: " + remaining + "s remaining.");
                    return;
                }
            }
        }

        if (requireAllPlayersNearby && !allPlayersInWorldNear(world, clicked.getLocation(), reactorDistanceRequirement)) {
            player.sendMessage(ChatColor.RED + "[Reactor] All players in this world must be within "
                    + (int) reactorDistanceRequirement + " blocks to activate.");
            return;
        }

        if (requireAmpleSpace && !hasAmpleSpace(clicked.getLocation())) {
            player.sendMessage(ChatColor.RED + "[Reactor] Not enough clear space to form the spire (disable requireAmpleSpace to bypass).");
            return;
        }

        // Activate reactor
        activeReactors.add(reactorKey);
        reactorCooldownByKey.put(reactorKey, System.currentTimeMillis());

        Location coreLoc = clicked.getLocation();

        player.sendMessage(ChatColor.DARK_RED + "[Reactor] The reactor awakens...");

        if (turnReactorBuildIntoObsidian) {
            transformReactorFrame(clicked, Material.OBSIDIAN);
        }

        if (spawnSpire) {
            buildSpire(coreLoc, spireMaterial);
        }

        // Create state for ticking
        ReactorRunState st = new ReactorRunState(coreLoc);
        runStates.put(reactorKey, st);
    }

    private boolean isWorldAllowed(World world) {
        if (!worldWhitelistLower.isEmpty()) {
            if (!worldWhitelistLower.contains(world.getName().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        // Old Beta servers don't have World.Environment in some forks; use name heuristics.
        String wn = world.getName().toLowerCase(Locale.ROOT);

        boolean looksNether = wn.contains("nether");
        if (looksNether) return allowNetherWorlds;

        boolean looksSky = wn.contains("skylands") || wn.contains("sky");
        if (looksSky) return allowSkylandsWorlds;

        return true;
    }

    private String makeReactorKey(Block core) {
        Location l = core.getLocation();
        return core.getWorld().getName() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private boolean allPlayersInWorldNear(World world, Location center, double dist) {
        double distSq = dist * dist;
        List players = world.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            Player p = (Player) players.get(i);
            if (p == null) continue;
            if (p.getLocation().distanceSquared(center) > distSq) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Reactor layout validation / transforms
    // -------------------------------------------------------------------------

    /**
     * Reactor layout with DIAMOND_BLOCK as core:
     * Layer y-1: 3x3 gold corners; edges + center = buildMaterial
     * Layer y: center = diamond (core); 4 corners = buildMaterial
     * Layer y+1: plus of buildMaterial
     */
    private boolean isValidReactorAtCore(Block core) {
        Location c = core.getLocation();
        World w = core.getWorld();
        int x = c.getBlockX();
        int y = c.getBlockY();
        int z = c.getBlockZ();

        if (w.getBlockAt(x, y, z).getType() != Material.DIAMOND_BLOCK) return false;

        // Bottom corners gold
        if (w.getBlockAt(x - 1, y - 1, z - 1).getType() != Material.GOLD_BLOCK) return false;
        if (w.getBlockAt(x + 1, y - 1, z - 1).getType() != Material.GOLD_BLOCK) return false;
        if (w.getBlockAt(x - 1, y - 1, z + 1).getType() != Material.GOLD_BLOCK) return false;
        if (w.getBlockAt(x + 1, y - 1, z + 1).getType() != Material.GOLD_BLOCK) return false;

        // Bottom edges+center build
        if (w.getBlockAt(x,     y - 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x - 1, y - 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x + 1, y - 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x,     y - 1, z - 1).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x,     y - 1, z + 1).getType() != reactorBuildMaterial) return false;

        // Core layer corners build
        if (w.getBlockAt(x - 1, y, z - 1).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x + 1, y, z - 1).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x - 1, y, z + 1).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x + 1, y, z + 1).getType() != reactorBuildMaterial) return false;

        // Top plus build
        if (w.getBlockAt(x, y + 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x - 1, y + 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x + 1, y + 1, z).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x, y + 1, z - 1).getType() != reactorBuildMaterial) return false;
        if (w.getBlockAt(x, y + 1, z + 1).getType() != reactorBuildMaterial) return false;

        return true;
    }

    private void transformReactorFrame(Block core, Material to) {
        Location c = core.getLocation();
        World w = core.getWorld();
        int x = c.getBlockX();
        int y = c.getBlockY();
        int z = c.getBlockZ();

        // Bottom layer y-1: gold + build -> to
        setTypeSafe(w.getBlockAt(x - 1, y - 1, z - 1), to);
        setTypeSafe(w.getBlockAt(x + 1, y - 1, z - 1), to);
        setTypeSafe(w.getBlockAt(x - 1, y - 1, z + 1), to);
        setTypeSafe(w.getBlockAt(x + 1, y - 1, z + 1), to);

        setTypeSafe(w.getBlockAt(x,     y - 1, z), to);
        setTypeSafe(w.getBlockAt(x - 1, y - 1, z), to);
        setTypeSafe(w.getBlockAt(x + 1, y - 1, z), to);
        setTypeSafe(w.getBlockAt(x,     y - 1, z - 1), to);
        setTypeSafe(w.getBlockAt(x,     y - 1, z + 1), to);

        // Core layer corners y
        setTypeSafe(w.getBlockAt(x - 1, y, z - 1), to);
        setTypeSafe(w.getBlockAt(x + 1, y, z - 1), to);
        setTypeSafe(w.getBlockAt(x - 1, y, z + 1), to);
        setTypeSafe(w.getBlockAt(x + 1, y, z + 1), to);

        // Top plus y+1
        setTypeSafe(w.getBlockAt(x, y + 1, z), to);
        setTypeSafe(w.getBlockAt(x - 1, y + 1, z), to);
        setTypeSafe(w.getBlockAt(x + 1, y + 1, z), to);
        setTypeSafe(w.getBlockAt(x, y + 1, z - 1), to);
        setTypeSafe(w.getBlockAt(x, y + 1, z + 1), to);
    }

    private void setTypeSafe(Block b, Material m) {
        if (b == null) return;
        if (b.getType() == Material.DIAMOND_BLOCK) return;
        b.setType(m);
    }

    private boolean hasAmpleSpace(Location coreLoc) {
        World w = coreLoc.getWorld();
        int cx = coreLoc.getBlockX();
        int cy = coreLoc.getBlockY();
        int cz = coreLoc.getBlockZ();

        for (int y = cy + DOME_MIN_Y_OFFSET; y <= cy + DOME_MAX_Y_OFFSET; y++) {
            for (int x = cx - DOME_EXPAND; x <= cx + DOME_EXPAND; x++) {
                for (int z = cz - DOME_EXPAND; z <= cz + DOME_EXPAND; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t == Material.AIR) continue;

                    // Allow fluids/snow to be overwritten
                    if (t == Material.WATER || t == Material.STATIONARY_WATER
                            || t == Material.LAVA || t == Material.STATIONARY_LAVA
                            || t == Material.SNOW) {
                        continue;
                    }

                    // Allow reactor area overwrite
                    if (isPartOfReactorFrame(coreLoc, b.getLocation())) continue;

                    return false;
                }
            }
        }
        return true;
    }

    private boolean isPartOfReactorFrame(Location coreLoc, Location blockLoc) {
        if (!coreLoc.getWorld().equals(blockLoc.getWorld())) return false;
        int dx = blockLoc.getBlockX() - coreLoc.getBlockX();
        int dy = blockLoc.getBlockY() - coreLoc.getBlockY();
        int dz = blockLoc.getBlockZ() - coreLoc.getBlockZ();
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -1 && dy <= 1;
    }

    // -------------------------------------------------------------------------
    // Spire generation
    // -------------------------------------------------------------------------

    private void buildSpire(Location coreLoc, Material mat) {
        World w = coreLoc.getWorld();
        int x = coreLoc.getBlockX();
        int y = coreLoc.getBlockY();
        int z = coreLoc.getBlockZ();

        // Floor volume (y-3 .. y-2), expand 8
        fillSolidSquare(w, x, y - 3, z, DOME_EXPAND, 2, mat);

        // Hollow walls from y-1 .. y+2 (height 4)
        buildHollowWalls(w, x, y - 1, z, DOME_EXPAND, 4, mat);

        // Ceiling at y+3
        fillSolidSquare(w, x, y + 3, z, DOME_EXPAND, 1, mat);

        // Taper-ish layers
        fillSolidSquare(w, x, y + 4, z, DOME_EXPAND, 1, mat);
        fillSolidSquare(w, x, y + 5, z, 5, 7, mat);
        fillSolidSquare(w, x, y + 12, z, 3, 3, mat);
    }

    private void fillSolidSquare(World w, int cx, int startY, int cz, int expand, int height, Material mat) {
        for (int yy = 0; yy < height; yy++) {
            int y = startY + yy;
            for (int x = cx - expand; x <= cx + expand; x++) {
                for (int z = cz - expand; z <= cz + expand; z++) {
                    w.getBlockAt(x, y, z).setType(mat);
                }
            }
        }
    }

    private void buildHollowWalls(World w, int cx, int startY, int cz, int expand, int height, Material mat) {
        for (int yy = 0; yy < height; yy++) {
            int y = startY + yy;
            for (int x = cx - expand; x <= cx + expand; x++) {
                for (int z = cz - expand; z <= cz + expand; z++) {
                    boolean isWall = (x == cx - expand || x == cx + expand || z == cz - expand || z == cz + expand);
                    if (!isWall) {
                        boolean nearCoreColumn = (Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1 && yy <= 2);
                        if (!nearCoreColumn) {
                            w.getBlockAt(x, y, z).setType(Material.AIR);
                        }
                        continue;
                    }
                    w.getBlockAt(x, y, z).setType(mat);
                }
            }
        }
    }

    private void punchRandomHoles(Location coreLoc, int count) {
        World w = coreLoc.getWorld();
        int cx = coreLoc.getBlockX();
        int cy = coreLoc.getBlockY();
        int cz = coreLoc.getBlockZ();

        for (int i = 0; i < count; i++) {
            int x = cx - DOME_EXPAND + rng.nextInt(DOME_EXPAND * 2 + 1);
            int y = cy + rng.nextInt(DOME_MAX_Y_OFFSET + 1);
            int z = cz - DOME_EXPAND + rng.nextInt(DOME_EXPAND * 2 + 1);

            Block b = w.getBlockAt(x, y, z);
            if (b.getType() == spireMaterial) {
                b.setType(Material.AIR);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reactor ticking (1Hz, handles all active reactors)
    // -------------------------------------------------------------------------

    private static class ReactorRunState {
        final Location coreLoc;
        int elapsedSeconds = 0;
        int curLevel = 0;

        ReactorRunState(Location coreLoc) {
            this.coreLoc = coreLoc;
        }
    }

    private void tickAllReactorsOncePerSecond() {
        if (activeReactors.isEmpty()) return;

        // Snapshot keys to avoid concurrent modification issues
        List<String> keys = new ArrayList<String>(activeReactors);

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            ReactorRunState st = runStates.get(key);
            if (st == null) {
                // Shouldn't happen, but recover
                activeReactors.remove(key);
                continue;
            }
            tickSingleReactor(key, st);
        }
    }

    private void tickSingleReactor(String reactorKey, ReactorRunState st) {
        // Level change times copied from snapshot logic
        int[] levelChangeTimes = new int[]{10, 13, 20, 22, 25, 30, 34, 36, 38, 40};

        if (st.elapsedSeconds >= reactorEventDurationSeconds) {
            activeReactors.remove(reactorKey);
            runStates.remove(reactorKey);

            if (spawnSpire) {
                punchRandomHoles(st.coreLoc, 90);
            }

            World w = st.coreLoc.getWorld();
            List players = w.getPlayers();
            double distSq = reactorDistanceRequirement * reactorDistanceRequirement;
            for (int i = 0; i < players.size(); i++) {
                Player p = (Player) players.get(i);
                if (p.getLocation().distanceSquared(st.coreLoc) <= distSq) {
                    p.sendMessage(ChatColor.GRAY + "[Reactor] The reactor cools and falls silent.");
                }
            }
            return;
        }

        // Handle level changes
        if (contains(levelChangeTimes, st.elapsedSeconds)) {
            st.curLevel++;

            if (spawnDrops) {
                int numItems = getNumItemsPerLevel(st.curLevel);
                for (int i = 0; i < numItems; i++) {
                    spawnReactorItem(st.coreLoc);
                }
            }

            if (spawnPigmen) {
                int numPigmen = getNumEnemiesPerLevel(st.curLevel);
                for (int i = 0; i < numPigmen; i++) {
                    trySpawnPigman(st.coreLoc);
                }
            }
        }

        st.elapsedSeconds++;
    }

    private boolean contains(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Drops and pigmen (Beta-safe APIs)
    // -------------------------------------------------------------------------

    private int getNumItemsPerLevel(int curLevel) {
        if (curLevel == 0) return 9;
        if (curLevel < 4) return 15;
        if (curLevel < 8) return Math.max(0, rng.nextInt(42) - 4);
        return Math.max(0, rng.nextInt(27) - 2);
    }

    private int getNumEnemiesPerLevel(int curLevel) {
        if (curLevel == 0) return 3;
        if (curLevel < 4) return 2;
        if (curLevel < 6) return Math.max(0, rng.nextInt(2));
        return Math.max(0, rng.nextInt(1));
    }

    private void spawnReactorItem(Location coreLoc) {
        ItemStack stack = getSpawnItem();
        Location dropLoc = getRandomDropLocation(coreLoc);

        coreLoc.getWorld().dropItemNaturally(dropLoc, stack);
    }

    /**
     * Items based on the reactor snapshot pool, excluding melon/pumpkin seeds.
     * Common: glowstone dust, mushrooms, reeds, cactus.
     * Low odds: arrow, bed, bone, book, bow, bowl, feather, painting, wood door, bookshelf.
     */
    private ItemStack getSpawnItem() {
        int itemType = rng.nextInt(7);
        switch (itemType) {
            case 0:
                return new ItemStack(Material.GLOWSTONE_DUST, 3);
            case 1:
                return new ItemStack(Material.BROWN_MUSHROOM, 1);
            case 2:
                return new ItemStack(Material.RED_MUSHROOM, 1);
            case 3:
                return new ItemStack(Material.SUGAR_CANE, 1);
            case 4:
                return new ItemStack(Material.CACTUS, 1);
            case 5:
                return new ItemStack(Material.GLOWSTONE_DUST, 1 + rng.nextInt(3));
            default:
                return getLowOddsSpawnItem();
        }
    }

    private ItemStack getLowOddsSpawnItem() {
        if (rng.nextInt(10) <= 8) {
            Material[] mats = new Material[]{
                    Material.ARROW,
                    Material.BED,
                    Material.BONE,
                    Material.BOOK,
                    Material.BOW,
                    Material.BOWL,
                    Material.FEATHER,
                    Material.PAINTING,
                    Material.WOOD_DOOR
            };
            Material m = mats[rng.nextInt(mats.length)];
            return new ItemStack(m, 1);
        } else {
            return new ItemStack(Material.BOOKSHELF, 1);
        }
    }

    private Location getRandomDropLocation(Location coreLoc) {
        int cx = coreLoc.getBlockX();
        int cy = coreLoc.getBlockY();
        int cz = coreLoc.getBlockZ();

        int dx = -6 + rng.nextInt(13);
        int dz = -6 + rng.nextInt(13);
        int dy = rng.nextInt(6);

        return new Location(coreLoc.getWorld(), cx + dx + 0.5, cy + dy + 0.8, cz + dz + 0.5);
    }

    private void trySpawnPigman(Location coreLoc) {
        World w = coreLoc.getWorld();
        Location spawnLoc = getRandomMobLocation(coreLoc);

        // Use old API: world.spawnCreature(Location, CreatureType)
        Object creatureType = getPigZombieCreatureType();
        if (creatureType == null) return;

        Entity ent;
        try {
            ent = spawnCreatureReflect(w, spawnLoc, creatureType);
        } catch (Throwable t) {
            return;
        }

        if (pigmenHostile && ent instanceof Creature) {
            Player target = getNearestPlayer(coreLoc, reactorDistanceRequirement);
            if (target != null) {
                try {
                    ((Creature) ent).setTarget(target);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Location getRandomMobLocation(Location coreLoc) {
        int cx = coreLoc.getBlockX();
        int cy = coreLoc.getBlockY();
        int cz = coreLoc.getBlockZ();

        int dx = -7 + rng.nextInt(15);
        int dz = -7 + rng.nextInt(15);
        int dy = rng.nextInt(5);

        Location loc = new Location(coreLoc.getWorld(), cx + dx + 0.5, cy + dy + 1.0, cz + dz + 0.5);

        for (int i = 0; i < 4; i++) {
            Block b = loc.getBlock();
            if (b.getType() == Material.AIR) break;
            loc = loc.add(0, 1, 0);
        }
        return loc;
    }

    private Player getNearestPlayer(Location center, double maxDist) {
        World w = center.getWorld();
        double best = maxDist * maxDist;
        Player bestP = null;

        List players = w.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            Player p = (Player) players.get(i);
            double d = p.getLocation().distanceSquared(center);
            if (d <= best) {
                best = d;
                bestP = p;
            }
        }
        return bestP;
    }

    // -------------------------------------------------------------------------
    // Old Bukkit CreatureType reflection helpers
    // -------------------------------------------------------------------------

    private Object getPigZombieCreatureType() {
        if (cachedCreatureTypeSearched) return cachedPigZombieCreatureType;

        cachedCreatureTypeSearched = true;

        try {
            Class ctClass = Class.forName("org.bukkit.entity.CreatureType");
            // Try common enum names across forks
            String[] names = new String[]{"PIG_ZOMBIE", "PIG_ZOMBIE_MAN", "PIGZOMBIE", "PIG_ZOMBIEMAN", "ZOMBIE_PIGMAN"};
            for (int i = 0; i < names.length; i++) {
                try {
                    Object enumVal = Enum.valueOf(ctClass, names[i]);
                    cachedPigZombieCreatureType = enumVal;
                    log.info("[BetaNetherReactor] Using CreatureType." + names[i] + " for pigmen spawning.");
                    return cachedPigZombieCreatureType;
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable t) {
            // ignore
        }

        log.warning("[BetaNetherReactor] Could not resolve CreatureType for pigmen; spawning disabled.");
        cachedPigZombieCreatureType = null;
        return null;
    }

    private Entity spawnCreatureReflect(World w, Location loc, Object creatureTypeEnum) throws Exception {
        // Method: World.spawnCreature(Location, CreatureType)
        Class worldClass = w.getClass();
        Class locClass = Class.forName("org.bukkit.Location");
        Class ctClass = Class.forName("org.bukkit.entity.CreatureType");

        java.lang.reflect.Method m = worldClass.getMethod("spawnCreature", locClass, ctClass);
        Object entObj = m.invoke(w, loc, creatureTypeEnum);

        return (Entity) entObj;
    }
}