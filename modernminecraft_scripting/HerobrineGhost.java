import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HerobrineGhost extends JavaPlugin {

    // -----------------------------
    // State
    // -----------------------------
    private final Random random = new Random();

    private final Map<UUID, Long> lastSightingMillisByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> markedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastTotemUseMillisByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastCombatMillisByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> playersWithOpenInventory = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Long> lastAmbientCueMillisByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> ambientCueCountByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastTestCommandMillisByPlayer = new ConcurrentHashMap<>();

    private Set<String> excludedWorldNamesLower = new HashSet<>();

    private String ghostDisplayName = "Herobrine";
    private String skinFromUsername = "MHF_Herobrine";

    private long checkIntervalTicks = 300L;
    private double chancePerCheck = 0.02D;
    private double markedChanceMultiplier = 3.0D;
    private long sightingCooldownMillis = 90L * 60L * 1000L;

    private int minDistanceBlocks = 25;
    private int maxDistanceBlocks = 50;
    private long lifetimeTicks = 80L;

    private long totemCooldownMillis = 15L * 60L * 1000L;

    // 0 = off, 1 = lightning+mark only (default), 2 = +brief ghost + message
    private int totemMode = 1;

    private List<String> summonLines = Arrays.asList(
            "I see you.",
            "Do not summon me.",
            "You should not have done that.",
            "I am watching.",
            "You cannot escape."
    );

    // FOV / sighting behavior
    private boolean fovBiasEnabled = true;
    private double minAngleFromViewDegrees = 70.0D;

    // Ambient system
    private boolean ambienceEnabled = true;
    private int ambienceMaxCuesPerSessionPerPlayer = 3;
    private long ambienceMinMillisBetweenCues = 180L * 1000L;
    private double ambienceChancePerCheckMarked = 0.02D;
    private double ambienceChancePerCheckUnmarked = 0.002D;
    private List<String> ambienceSoundNames = Arrays.asList(
            "AMBIENT_CAVE",
            "BLOCK_CHEST_OPEN",
            "BLOCK_STONE_BREAK",
            "BLOCK_WOOD_BREAK",
            "BLOCK_GRASS_STEP",
            "BLOCK_STONE_STEP"
    );

    private ProtocolManager protocolManager;
    private GhostManager ghostManager;
    private SkinCache skinCache;

    // -----------------------------
    // Plugin lifecycle
    // -----------------------------
    @Override
    public void onEnable() {
        ensureDefaultConfigFileExists();
        reloadLocalConfig();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found. This plugin requires ProtocolLib for fake-player packets.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        skinCache = new SkinCache(this);

        ghostManager = new GhostManager(this, protocolManager, skinCache);

        Bukkit.getPluginManager().registerEvents(new TotemListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatAndUiListener(this), this);

        startSightingTask();

        getLogger().info("Enabled. displayName=" + ghostDisplayName + ", skinFrom=" + skinFromUsername
                + ", totemMode=" + totemMode + ", ambience=" + ambienceEnabled);
    }

    @Override
    public void onDisable() {
        if (skinCache != null) {
            skinCache.saveQuietly();
        }
        getLogger().info("Disabled.");
    }

    // -----------------------------
    // Config (no resources needed)
    // -----------------------------
    private void ensureDefaultConfigFileExists() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                getLogger().warning("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                return;
            }

            File configFile = new File(dataFolder, "config.yml");
            if (configFile.exists()) return;

            YamlConfiguration defaults = new YamlConfiguration();

            defaults.set("ghost.displayName", "Herobrine");
            defaults.set("ghost.skinFromUsername", "MHF_Herobrine");

            defaults.set("timing.checkIntervalTicks", 300);
            defaults.set("timing.lifetimeTicks", 80);

            defaults.set("chance.chancePerCheck", 0.02);
            defaults.set("chance.markedChanceMultiplier", 3.0);

            defaults.set("cooldowns.sightingCooldownMinutes", 90);
            defaults.set("cooldowns.totemCooldownMinutes", 15);

            defaults.set("distance.minBlocks", 25);
            defaults.set("distance.maxBlocks", 50);

            defaults.set("worlds.excluded", new ArrayList<String>());

            defaults.set("fovBias.enabled", true);
            defaults.set("fovBias.minAngleFromViewDegrees", 70.0);

            // 0=off, 1=lightning+mark (default), 2=lightning+mark + brief ghost + message
            defaults.set("totem.mode", 1);

            defaults.set("ambience.enabled", true);
            defaults.set("ambience.maxCuesPerSessionPerPlayer", 3);
            defaults.set("ambience.minSecondsBetweenCues", 180);
            defaults.set("ambience.chancePerCheckMarked", 0.02);
            defaults.set("ambience.chancePerCheckUnmarked", 0.002);
            defaults.set("ambience.sounds", Arrays.asList(
                    "AMBIENT_CAVE",
                    "BLOCK_CHEST_OPEN",
                    "BLOCK_STONE_BREAK",
                    "BLOCK_WOOD_BREAK",
                    "BLOCK_GRASS_STEP",
                    "BLOCK_STONE_STEP"
            ));

            defaults.set("chat.summonLines", Arrays.asList(
                    "I see you.",
                    "Do not summon me.",
                    "You should not have done that.",
                    "I am watching.",
                    "You cannot escape."
            ));

            defaults.set("debug.logProtocolWarnings", true);

            defaults.save(configFile);
            getLogger().info("Wrote default config.yml");
        } catch (IOException e) {
            getLogger().warning("Failed to write default config.yml: " + e.getMessage());
        }
    }

    private boolean debugLogProtocolWarnings = true;

    private void reloadLocalConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        ghostDisplayName = config.getString("ghost.displayName", "Herobrine");
        skinFromUsername = config.getString("ghost.skinFromUsername", "MHF_Herobrine");

        checkIntervalTicks = clampLong(config.getLong("timing.checkIntervalTicks", 300L), 1L, 20L * 60L * 60L);
        lifetimeTicks = clampLong(config.getLong("timing.lifetimeTicks", 80L), 10L, 20L * 60L);

        chancePerCheck = clampDouble(config.getDouble("chance.chancePerCheck", 0.02D), 0.0D, 1.0D);
        markedChanceMultiplier = clampDouble(config.getDouble("chance.markedChanceMultiplier", 3.0D), 1.0D, 1000.0D);

        long sightingCooldownMinutes = config.getLong("cooldowns.sightingCooldownMinutes", 90L);
        sightingCooldownMillis = Math.max(0L, sightingCooldownMinutes) * 60L * 1000L;

        long totemCooldownMinutes = config.getLong("cooldowns.totemCooldownMinutes", 15L);
        totemCooldownMillis = Math.max(0L, totemCooldownMinutes) * 60L * 1000L;

        minDistanceBlocks = (int) clampLong(config.getLong("distance.minBlocks", 25L), 5L, 10000L);
        maxDistanceBlocks = (int) clampLong(config.getLong("distance.maxBlocks", 50L), (long) minDistanceBlocks + 1L, 10000L);

        fovBiasEnabled = config.getBoolean("fovBias.enabled", true);
        minAngleFromViewDegrees = clampDouble(config.getDouble("fovBias.minAngleFromViewDegrees", 70.0D), 0.0D, 179.0D);

        totemMode = (int) clampLong(config.getLong("totem.mode", 1L), 0L, 2L);

        ambienceEnabled = config.getBoolean("ambience.enabled", true);
        ambienceMaxCuesPerSessionPerPlayer = (int) clampLong(config.getLong("ambience.maxCuesPerSessionPerPlayer", 3L), 0L, 100L);
        long ambienceMinSecondsBetween = config.getLong("ambience.minSecondsBetweenCues", 180L);
        ambienceMinMillisBetweenCues = clampLong(ambienceMinSecondsBetween, 0L, 24L * 60L * 60L) * 1000L;
        ambienceChancePerCheckMarked = clampDouble(config.getDouble("ambience.chancePerCheckMarked", 0.02D), 0.0D, 1.0D);
        ambienceChancePerCheckUnmarked = clampDouble(config.getDouble("ambience.chancePerCheckUnmarked", 0.002D), 0.0D, 1.0D);

        List<String> sounds = config.getStringList("ambience.sounds");
        if (sounds != null && !sounds.isEmpty()) {
            List<String> cleaned = new ArrayList<>();
            for (String s : sounds) {
                if (s != null && !s.trim().isEmpty()) cleaned.add(s.trim());
            }
            if (!cleaned.isEmpty()) ambienceSoundNames = cleaned;
        }

        List<String> excluded = config.getStringList("worlds.excluded");
        excludedWorldNamesLower = new HashSet<>();
        for (String worldName : excluded) {
            if (worldName == null) continue;
            String trimmed = worldName.trim();
            if (!trimmed.isEmpty()) excludedWorldNamesLower.add(trimmed.toLowerCase(Locale.ROOT));
        }

        List<String> configuredSummonLines = config.getStringList("chat.summonLines");
        if (configuredSummonLines != null && !configuredSummonLines.isEmpty()) {
            List<String> cleaned = new ArrayList<>();
            for (String line : configuredSummonLines) {
                if (line != null && !line.trim().isEmpty()) cleaned.add(line.trim());
            }
            if (!cleaned.isEmpty()) summonLines = cleaned;
        }

        debugLogProtocolWarnings = config.getBoolean("debug.logProtocolWarnings", true);

        getLogger().info("Config loaded. excludedWorlds=" + excludedWorldNamesLower);
    }

    // -----------------------------
    // Command
    // -----------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("herobrineghost")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            ensureDefaultConfigFileExists();
            reloadLocalConfig();
            if (skinCache != null) skinCache.loadQuietly();
            sender.sendMessage("HerobrineGhost config reloaded.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use the test command.");
                return true;
            }

            // Per-player cooldown to avoid API hammering
            long now = System.currentTimeMillis();
            Long last = lastTestCommandMillisByPlayer.get(player.getUniqueId());
            if (last != null && (now - last) < 5000L) {
                player.sendMessage("Not yet.");
                return true;
            }
            lastTestCommandMillisByPlayer.put(player.getUniqueId(), now);

            GhostSpawnLogic.spawnTest(this, player, lifetimeTicks);
            sender.sendMessage("HerobrineGhost: test ghost spawn triggered.");
            return true;
        }

        sender.sendMessage("Usage: /" + label + " [reload|test]");
        return true;
    }

    // -----------------------------
    // Main scheduler
    // -----------------------------
    private void startSightingTask() {
        long period = checkIntervalTicks;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                runSightingAndAmbienceCheck();
            } catch (Throwable t) {
                getLogger().warning("Exception during check: " + t.getMessage());
            }
        }, period, period);
    }

    private void runSightingAndAmbienceCheck() {
        long nowMillis = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            if (isWorldExcluded(player.getWorld())) continue;

            maybePlayAmbientCue(player, nowMillis);

            if (!isPlayerEligibleForSighting(player, nowMillis)) continue;

            double localChance = chancePerCheck;
            boolean isMarked = markedPlayers.contains(player.getUniqueId());
            if (isMarked) localChance = Math.min(1.0D, localChance * markedChanceMultiplier);

            if (random.nextDouble() <= localChance) {
                GhostSpawnLogic.spawnRandomSighting(this, player, minDistanceBlocks, maxDistanceBlocks, lifetimeTicks);
                lastSightingMillisByPlayer.put(player.getUniqueId(), nowMillis);
            }
        }
    }

    private boolean isPlayerEligibleForSighting(Player player, long nowMillis) {
        World world = player.getWorld();
        if (world == null) return false;

        Long lastMillis = lastSightingMillisByPlayer.get(player.getUniqueId());
        if (lastMillis != null && nowMillis - lastMillis < sightingCooldownMillis) return false;

        if (!isPlayerAlone(player, 64.0D)) return false;

        if (isRecentlyInCombat(player, nowMillis, 10_000L)) return false;

        if (playersWithOpenInventory.contains(player.getUniqueId())) return false;

        return true;
    }

    private void maybePlayAmbientCue(Player player, long nowMillis) {
        if (!ambienceEnabled) return;
        if (ambienceMaxCuesPerSessionPerPlayer <= 0) return;

        if (!isPlayerAlone(player, 64.0D)) return;
        if (playersWithOpenInventory.contains(player.getUniqueId())) return;
        if (isRecentlyInCombat(player, nowMillis, 10_000L)) return;

        UUID playerId = player.getUniqueId();

        int usedCount = ambientCueCountByPlayer.getOrDefault(playerId, 0);
        if (usedCount >= ambienceMaxCuesPerSessionPerPlayer) return;

        Long lastCueMillis = lastAmbientCueMillisByPlayer.get(playerId);
        if (lastCueMillis != null && nowMillis - lastCueMillis < ambienceMinMillisBetweenCues) return;

        boolean isMarked = markedPlayers.contains(playerId);
        double chance = isMarked ? ambienceChancePerCheckMarked : ambienceChancePerCheckUnmarked;
        if (random.nextDouble() > chance) return;

        Sound picked = pickValidSound();
        if (picked == null) return;

        Location soundLocation = computeOffscreenSoundLocation(player, 8.0D, 16.0D);

        float volume = 0.25F + (random.nextFloat() * 0.20F);
        float pitch = 0.90F + (random.nextFloat() * 0.20F);

        try {
            player.playSound(soundLocation, picked, volume, pitch);
            lastAmbientCueMillisByPlayer.put(playerId, nowMillis);
            ambientCueCountByPlayer.put(playerId, usedCount + 1);
        } catch (Throwable ignored) {
        }
    }

    private Sound pickValidSound() {
        if (ambienceSoundNames == null || ambienceSoundNames.isEmpty()) return null;

        for (int attempt = 0; attempt < 6; attempt++) {
            String name = ambienceSoundNames.get(random.nextInt(ambienceSoundNames.size()));
            if (name == null) continue;
            name = name.trim();
            if (name.isEmpty()) continue;
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private Location computeOffscreenSoundLocation(Player player, double minDistance, double maxDistance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return base;

        Vector forward = base.getDirection();
        forward.setY(0);
        if (forward.lengthSquared() < 0.0001) forward = new Vector(0, 0, 1);
        forward.normalize();

        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double behindWeight = 0.6D + (random.nextDouble() * 0.4D);
        double sideWeight = (random.nextBoolean() ? 1 : -1) * (0.3D + random.nextDouble() * 0.7D);

        Vector direction = forward.clone().multiply(-behindWeight).add(right.multiply(sideWeight)).normalize();

        double distance = minDistance + (random.nextDouble() * (maxDistance - minDistance));
        Location loc = base.clone().add(direction.getX() * distance, 0.0D, direction.getZ() * distance);
        loc.setY(base.getY() + 1.0D);

        return loc;
    }

    private boolean isPlayerAlone(Player player, double radius) {
        World world = player.getWorld();
        if (world == null) return false;
        double radiusSq = radius * radius;
        Location loc = player.getLocation();
        for (Player other : world.getPlayers()) {
            if (!other.isOnline()) continue;
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            if (other.getLocation().distanceSquared(loc) < radiusSq) return false;
        }
        return true;
    }

    private boolean isRecentlyInCombat(Player player, long nowMillis, long windowMillis) {
        Long lastCombat = lastCombatMillisByPlayer.get(player.getUniqueId());
        return lastCombat != null && (nowMillis - lastCombat) < windowMillis;
    }

    private boolean isWorldExcluded(World world) {
        if (world == null) return false;
        String worldName = world.getName();
        if (worldName == null) return false;
        return excludedWorldNamesLower.contains(worldName.toLowerCase(Locale.ROOT));
    }

    // -----------------------------
    // Totem hook
    // -----------------------------
    private void handleTotemIgnite(Player player, Block fireTarget) {
        if (totemMode <= 0) return;
        if (player == null || fireTarget == null) return;
        TotemLogic.handleTotemIgnite(this, player, fireTarget);
    }

    // -----------------------------
    // Getters for helpers
    // -----------------------------
    private GhostManager getGhostManager() { return ghostManager; }
    private Random getRandom() { return random; }
    private String getGhostDisplayName() { return ghostDisplayName; }
    private String getSkinFromUsername() { return skinFromUsername; }
    private boolean isExcludedWorld(World world) { return isWorldExcluded(world); }
    private long getLifetimeTicks() { return lifetimeTicks; }
    private long getTotemCooldownMillis() { return totemCooldownMillis; }
    private int getTotemMode() { return totemMode; }
    private boolean shouldLogProtocolWarnings() { return debugLogProtocolWarnings; }

    private Long getLastTotemUseMillis(UUID playerId) { return lastTotemUseMillisByPlayer.get(playerId); }
    private void setLastTotemUseMillis(UUID playerId, long millis) { lastTotemUseMillisByPlayer.put(playerId, millis); }
    private void markPlayer(UUID playerId) { markedPlayers.add(playerId); }
    private void setLastSightingNow(UUID playerId) { lastSightingMillisByPlayer.put(playerId, System.currentTimeMillis()); }

    private String pickSummonLine() {
        if (summonLines == null || summonLines.isEmpty()) return "I see you.";
        return summonLines.get(random.nextInt(summonLines.size()));
    }

    private boolean isFovBiasEnabled() { return fovBiasEnabled; }
    private double getMinAngleFromViewDegrees() { return minAngleFromViewDegrees; }

    private static long clampLong(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double clampDouble(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    // =========================================================================
    // Listeners
    // =========================================================================
    private static final class TotemListener implements Listener {
        private final HerobrineGhost plugin;
        private TotemListener(HerobrineGhost plugin) { this.plugin = plugin; }

        @EventHandler
        public void onBlockIgnite(BlockIgniteEvent event) {
            if (event.isCancelled()) return;
            Block block = event.getBlock();
            Player player = event.getPlayer();
            if (block == null || player == null) return;
            plugin.handleTotemIgnite(player, block);
        }
    }

    private static final class CombatAndUiListener implements Listener {
        private final HerobrineGhost plugin;
        private CombatAndUiListener(HerobrineGhost plugin) { this.plugin = plugin; }

        @EventHandler
        public void onDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            plugin.lastCombatMillisByPlayer.put(player.getUniqueId(), System.currentTimeMillis());
        }

        @EventHandler
        public void onInventoryOpen(InventoryOpenEvent event) {
            if (!(event.getPlayer() instanceof Player player)) return;
            plugin.playersWithOpenInventory.add(player.getUniqueId());
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            if (!(event.getPlayer() instanceof Player player)) return;
            plugin.playersWithOpenInventory.remove(player.getUniqueId());
        }
    }

    // =========================================================================
    // Totem Logic
    // =========================================================================
    private static final class TotemLogic {
        private TotemLogic() { }

        public static void handleTotemIgnite(HerobrineGhost plugin, Player player, Block fireTarget) {
            Block netherrackBlock = fireTarget.getRelative(0, -1, 0);
            if (netherrackBlock.getType() != Material.NETHERRACK) return;

            World world = netherrackBlock.getWorld();
            if (plugin.isExcludedWorld(world)) return;

            if (!isValidHerobrineTotem(plugin, netherrackBlock)) return;

            long nowMillis = System.currentTimeMillis();
            Long lastUseMillis = plugin.getLastTotemUseMillis(player.getUniqueId());
            if (lastUseMillis != null && nowMillis - lastUseMillis < plugin.getTotemCooldownMillis()) {
                player.sendMessage("The air is still.");
                return;
            }

            Location strikeLocation = netherrackBlock.getLocation().clone().add(0.5D, 1.0D, 0.5D);
            world.strikeLightningEffect(strikeLocation);

            plugin.markPlayer(player.getUniqueId());
            plugin.setLastTotemUseMillis(player.getUniqueId(), nowMillis);
            plugin.setLastSightingNow(player.getUniqueId());

            int mode = plugin.getTotemMode();
            if (mode >= 2) {
                GhostSpawnLogic.spawnAtTotem(plugin, player, netherrackBlock.getLocation(), plugin.getLifetimeTicks());
                player.sendMessage("<" + plugin.getGhostDisplayName() + "> " + plugin.pickSummonLine());
            }
        }

        private static boolean isValidHerobrineTotem(HerobrineGhost plugin, Block netherrackBlock) {
            World world = netherrackBlock.getWorld();
            if (plugin.isExcludedWorld(world)) return false;

            int centerX = netherrackBlock.getX();
            int centerY = netherrackBlock.getY();
            int centerZ = netherrackBlock.getZ();

            int baseY = centerY - 1;

            Block centerBase = world.getBlockAt(centerX, baseY, centerZ);
            if (centerBase.getType() != Material.MOSSY_COBBLESTONE) return false;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Block ringBlock = world.getBlockAt(centerX + dx, baseY, centerZ + dz);
                    if (ringBlock.getType() != Material.GOLD_BLOCK) return false;
                }
            }

            Block northTorch = world.getBlockAt(centerX, centerY, centerZ - 1);
            Block southTorch = world.getBlockAt(centerX, centerY, centerZ + 1);
            Block westTorch  = world.getBlockAt(centerX - 1, centerY, centerZ);
            Block eastTorch  = world.getBlockAt(centerX + 1, centerY, centerZ);

            return isRedstoneTorch(northTorch) && isRedstoneTorch(southTorch)
                    && isRedstoneTorch(westTorch) && isRedstoneTorch(eastTorch);
        }

        private static boolean isRedstoneTorch(Block block) {
            return block != null && block.getType() == Material.REDSTONE_TORCH;
        }
    }

    // =========================================================================
    // Sighting Logic
    // =========================================================================
    private static final class GhostSpawnLogic {
        private GhostSpawnLogic() { }

        public static void spawnRandomSighting(HerobrineGhost plugin, Player viewer, int minDistance, int maxDistance, long lifetimeTicks) {
            Location playerLocation = viewer.getLocation();
            World world = playerLocation.getWorld();
            if (world == null || plugin.isExcludedWorld(world)) return;

            for (int attempt = 0; attempt < 10; attempt++) {
                Location ghostLocation = computeCandidateLocation(plugin, viewer, minDistance, maxDistance);
                if (ghostLocation == null) continue;

                if (plugin.isFovBiasEnabled()) {
                    double angle = computeAngleFromViewDegrees(viewer, ghostLocation);
                    if (angle < plugin.getMinAngleFromViewDegrees()) continue;
                }

                LookUtil.orientLocationTowards(ghostLocation, playerLocation);

                if (ghostLocation.distanceSquared(playerLocation) < 20.0D * 20.0D) continue;

                plugin.getGhostManager().spawnGhostForViewer(viewer, ghostLocation, lifetimeTicks);
                return;
            }
        }

        private static Location computeCandidateLocation(HerobrineGhost plugin, Player viewer, int minDistance, int maxDistance) {
            Location playerLocation = viewer.getLocation();
            World world = playerLocation.getWorld();
            if (world == null) return null;

            double distance = minDistance + (maxDistance - minDistance) * plugin.getRandom().nextDouble();
            double angle = plugin.getRandom().nextDouble() * Math.PI * 2.0;

            double deltaX = Math.cos(angle) * distance;
            double deltaZ = Math.sin(angle) * distance;

            int targetX = playerLocation.getBlockX() + (int) Math.round(deltaX);
            int targetZ = playerLocation.getBlockZ() + (int) Math.round(deltaZ);

            int targetY = world.getHighestBlockYAt(targetX, targetZ);
            if (targetY <= world.getMinHeight()) return null;

            Location ghostLocation = new Location(world, targetX + 0.5D, targetY, targetZ + 0.5D, 0.0F, 0.0F);

            Block occupyingBlock = world.getBlockAt(ghostLocation);
            if (!occupyingBlock.isEmpty()) ghostLocation.setY(ghostLocation.getY() + 1.0D);

            return ghostLocation;
        }

        private static double computeAngleFromViewDegrees(Player viewer, Location target) {
            Location viewLoc = viewer.getLocation();
            Vector forward = viewLoc.getDirection().clone();
            forward.setY(0);
            if (forward.lengthSquared() < 0.0001D) forward = new Vector(0, 0, 1);
            forward.normalize();

            Vector toTarget = target.toVector().subtract(viewLoc.toVector());
            toTarget.setY(0);
            if (toTarget.lengthSquared() < 0.0001D) return 0.0D;
            toTarget.normalize();

            double dot = clampDot(forward.dot(toTarget));
            double radians = Math.acos(dot);
            return Math.toDegrees(radians);
        }

        private static double clampDot(double dot) {
            if (dot > 1.0D) return 1.0D;
            if (dot < -1.0D) return -1.0D;
            return dot;
        }

        public static void spawnAtTotem(HerobrineGhost plugin, Player viewer, Location netherrackLocation, long lifetimeTicks) {
            World world = netherrackLocation.getWorld();
            if (world == null || plugin.isExcludedWorld(world)) return;

            Location viewerLocation = viewer.getLocation();

            Location ghostLocation = new Location(
                    world,
                    netherrackLocation.getBlockX() + 0.5D,
                    netherrackLocation.getBlockY() + 1.0D,
                    netherrackLocation.getBlockZ() + 0.5D,
                    0.0F,
                    0.0F
            );

            LookUtil.orientLocationTowards(ghostLocation, viewerLocation);

            Block occupyingBlock = world.getBlockAt(ghostLocation);
            if (!occupyingBlock.isEmpty()) ghostLocation.setY(ghostLocation.getY() + 1.0D);

            plugin.getGhostManager().spawnGhostForViewer(viewer, ghostLocation, lifetimeTicks);
        }

        public static void spawnTest(HerobrineGhost plugin, Player viewer, long lifetimeTicks) {
            Location baseLocation = viewer.getLocation();
            World world = baseLocation.getWorld();
            if (world == null || plugin.isExcludedWorld(world)) return;

            float yaw = baseLocation.getYaw();
            double radians = Math.toRadians(yaw);

            double forwardX = -Math.sin(radians);
            double forwardZ =  Math.cos(radians);

            double distance = 6.0D;
            double ghostX = baseLocation.getX() + forwardX * distance;
            double ghostZ = baseLocation.getZ() + forwardZ * distance;

            int blockX = (int) Math.floor(ghostX);
            int blockZ = (int) Math.floor(ghostZ);

            int blockY = world.getHighestBlockYAt(blockX, blockZ);
            if (blockY <= world.getMinHeight()) blockY = baseLocation.getBlockY();

            Location ghostLocation = new Location(world, blockX + 0.5D, blockY, blockZ + 0.5D, 0.0F, 0.0F);
            LookUtil.orientLocationTowards(ghostLocation, baseLocation);

            Block occupyingBlock = world.getBlockAt(ghostLocation);
            if (!occupyingBlock.isEmpty()) ghostLocation.setY(ghostLocation.getY() + 1.0D);

            plugin.getGhostManager().spawnGhostForViewer(viewer, ghostLocation, lifetimeTicks);
        }
    }

    private static final class LookUtil {
        private LookUtil() { }

        public static void orientLocationTowards(Location fromLocation, Location targetLocation) {
            double fromX = fromLocation.getX();
            double fromY = fromLocation.getY() + 1.62D;
            double fromZ = fromLocation.getZ();

            double toX = targetLocation.getX();
            double toY = targetLocation.getY() + 1.62D;
            double toZ = targetLocation.getZ();

            double deltaX = toX - fromX;
            double deltaY = toY - fromY;
            double deltaZ = toZ - fromZ;

            double distanceXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (distanceXZ < 0.0001D) distanceXZ = 0.0001D;

            float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
            float pitch = (float) -Math.toDegrees(Math.atan2(deltaY, distanceXZ));

            fromLocation.setYaw(yaw);
            fromLocation.setPitch(pitch);
        }
    }

    // =========================================================================
    // Skin Cache + Fetcher (429-safe)
    // =========================================================================
    private static final class SkinCache {
        private final HerobrineGhost plugin;

        private final File cacheFile;
        private String cachedValue;
        private String cachedSignature;
        private long cachedAtMillis;

        private long nextAllowedFetchMillis = 0L;
        private long backoffMillis = 60_000L; // start at 1 minute

        private SkinCache(HerobrineGhost plugin) {
            this.plugin = plugin;
            this.cacheFile = new File(plugin.getDataFolder(), "skin-cache.yml");
            loadQuietly();
        }

        public synchronized void loadQuietly() {
            try {
                if (!cacheFile.exists()) return;
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cacheFile);
                cachedValue = yaml.getString("textures.value", null);
                cachedSignature = yaml.getString("textures.signature", null);
                cachedAtMillis = yaml.getLong("textures.cachedAtMillis", 0L);
                nextAllowedFetchMillis = yaml.getLong("fetch.nextAllowedFetchMillis", 0L);
                backoffMillis = Math.max(60_000L, yaml.getLong("fetch.backoffMillis", 60_000L));
            } catch (Throwable ignored) {
            }
        }

        public synchronized void saveQuietly() {
            try {
                if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("textures.value", cachedValue);
                yaml.set("textures.signature", cachedSignature);
                yaml.set("textures.cachedAtMillis", cachedAtMillis);
                yaml.set("fetch.nextAllowedFetchMillis", nextAllowedFetchMillis);
                yaml.set("fetch.backoffMillis", backoffMillis);
                yaml.save(cacheFile);
            } catch (Throwable ignored) {
            }
        }

        public synchronized Optional<SkinProperty> getCachedSkin() {
            if (cachedValue == null || cachedSignature == null) return Optional.empty();
            return Optional.of(new SkinProperty(cachedValue, cachedSignature));
        }

        public synchronized boolean canAttemptFetchNow(long nowMillis) {
            return nowMillis >= nextAllowedFetchMillis;
        }

        public synchronized void recordFetchSuccess(String value, String signature, long nowMillis) {
            cachedValue = value;
            cachedSignature = signature;
            cachedAtMillis = nowMillis;

            // reset backoff
            backoffMillis = 60_000L;
            nextAllowedFetchMillis = nowMillis; // allowed immediately

            saveQuietly();
        }

        public synchronized void recordFetchFailure(long nowMillis) {
            nextAllowedFetchMillis = nowMillis + backoffMillis;
            backoffMillis = Math.min(backoffMillis * 2L, 6L * 60L * 60L * 1000L); // cap at 6 hours
            saveQuietly();
        }

        private static final class SkinProperty {
            private final String value;
            private final String signature;

            private SkinProperty(String value, String signature) {
                this.value = value;
                this.signature = signature;
            }
        }
    }

    private static final class SkinFetcher {
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
        private static final Pattern VALUE_PATTERN = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern SIGNATURE_PATTERN = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");

        private SkinFetcher() { }

        public static Optional<SkinCache.SkinProperty> fetchSkinPropertyBlocking(String skinFromUsername) throws Exception {
            UUID skinUuid = fetchUuidForUsername(skinFromUsername);
            return Optional.of(fetchTexturesProperty(skinUuid));
        }

        private static UUID fetchUuidForUsername(String username) throws Exception {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new RateLimitedException("Rate limited fetching UUID (HTTP 429)");
            }
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                throw new IllegalStateException("Failed to fetch UUID for " + username + " (HTTP " + response.statusCode() + ")");
            }

            Matcher matcher = ID_PATTERN.matcher(response.body());
            if (!matcher.find()) throw new IllegalStateException("Could not parse UUID for " + username);

            String raw = matcher.group(1);
            return uuidFromMojangId(raw);
        }

        private static SkinCache.SkinProperty fetchTexturesProperty(UUID uuid) throws Exception {
            String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "") + "?unsigned=false";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new RateLimitedException("Rate limited fetching textures (HTTP 429)");
            }
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                throw new IllegalStateException("Failed to fetch textures for " + uuid + " (HTTP " + response.statusCode() + ")");
            }

            String body = response.body();

            Matcher valueMatcher = VALUE_PATTERN.matcher(body);
            Matcher signatureMatcher = SIGNATURE_PATTERN.matcher(body);

            if (!valueMatcher.find()) throw new IllegalStateException("Could not parse textures value");
            if (!signatureMatcher.find()) throw new IllegalStateException("Could not parse textures signature");

            return new SkinCache.SkinProperty(valueMatcher.group(1), signatureMatcher.group(1));
        }

        private static UUID uuidFromMojangId(String raw32) {
            String withDashes = raw32.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5"
            );
            return UUID.fromString(withDashes);
        }

        private static final class RateLimitedException extends Exception {
            private RateLimitedException(String message) { super(message); }
        }
    }

    // =========================================================================
    // Ghost Manager (ProtocolLib compatibility probe + graceful degradation)
    // =========================================================================
    private static final class GhostManager {
        private final HerobrineGhost plugin;
        private final ProtocolManager protocolManager;
        private final SkinCache skinCache;

        private boolean protocolCompatible = true;
        private boolean loggedProtocolWarning = false;

        private GhostManager(HerobrineGhost plugin, ProtocolManager protocolManager, SkinCache skinCache) {
            this.plugin = plugin;
            this.protocolManager = protocolManager;
            this.skinCache = skinCache;

            probeProtocolCompatibility();
        }

        private void probeProtocolCompatibility() {
            try {
                PacketContainer test = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);

                // Some incompatible ProtocolLib builds will give empty modifiers here.
                int actionCount = safeModifierSize(() -> test.getPlayerInfoAction());
                int dataCount = safeModifierSize(() -> test.getPlayerInfoDataLists());

                if (actionCount <= 0 || dataCount <= 0) {
                    protocolCompatible = false;
                }
            } catch (Throwable t) {
                protocolCompatible = false;
            }

            if (!protocolCompatible) {
                logProtocolWarningOnce("ProtocolLib appears incompatible with this server version. Ghost spawning will be disabled until ProtocolLib is updated for your MC version.");
            }
        }

        private interface ModifierProvider { Object get() throws Exception; }

        private int safeModifierSize(ModifierProvider provider) {
            try {
                Object modifier = provider.get();
                // Many ProtocolLib modifiers have a size() method.
                Method sizeMethod = modifier.getClass().getMethod("size");
                Object size = sizeMethod.invoke(modifier);
                if (size instanceof Integer) return (Integer) size;
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private void logProtocolWarningOnce(String message) {
            if (loggedProtocolWarning) return;
            loggedProtocolWarning = true;
            if (plugin.shouldLogProtocolWarnings()) {
                plugin.getLogger().warning(message);
                plugin.getLogger().warning("You likely need a ProtocolLib build that supports 1.21.10 (dev build).");
            }
        }

        public void spawnGhostForViewer(Player viewer, Location ghostLocation, long lifetimeTicks) {
            if (viewer == null || !viewer.isOnline()) return;

            if (!protocolCompatible) {
                // No spam. Just do nothing; ambience/totem still works.
                logProtocolWarningOnce("Ghost spawn blocked due to ProtocolLib incompatibility.");
                return;
            }

            UUID ghostUuid = UUID.randomUUID();
            String ghostName = plugin.getGhostDisplayName();
            WrappedGameProfile ghostProfile = new WrappedGameProfile(ghostUuid, ghostName);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                applySkinWithCache(ghostProfile);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    if (plugin.isExcludedWorld(viewer.getWorld())) return;

                    int entityId = plugin.getRandom().nextInt(Integer.MAX_VALUE);

                    boolean infoOk = sendPlayerInfoAdd(viewer, ghostProfile);
                    if (!infoOk) {
                        protocolCompatible = false;
                        logProtocolWarningOnce("PLAYER_INFO packet write failed. Ghost spawning disabled until ProtocolLib is updated.");
                        return;
                    }

                    if (!sendNamedEntitySpawn(viewer, entityId, ghostProfile.getUUID(), ghostLocation)) {
                        return;
                    }

                    long stepTicks = 5L;
                    for (long tickOffset = stepTicks; tickOffset < lifetimeTicks; tickOffset += stepTicks) {
                        long scheduledDelay = tickOffset;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!viewer.isOnline()) return;
                            Location lookLocation = ghostLocation.clone();
                            LookUtil.orientLocationTowards(lookLocation, viewer.getLocation());
                            sendLookPackets(viewer, entityId, lookLocation);
                        }, scheduledDelay);
                    }

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!viewer.isOnline()) return;
                        sendDestroyEntity(viewer, entityId);
                        sendPlayerInfoRemove(viewer, ghostUuid, ghostName);
                    }, lifetimeTicks);

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!viewer.isOnline()) return;
                        sendPlayerInfoRemove(viewer, ghostUuid, ghostName);
                    }, 20L);
                });
            });
        }

        private void applySkinWithCache(WrappedGameProfile profile) {
            long now = System.currentTimeMillis();

            // Use cached skin if present.
            Optional<SkinCache.SkinProperty> cached = skinCache.getCachedSkin();
            if (cached.isPresent()) {
                tryApplyTexturesProperty(profile, cached.get().value, cached.get().signature);
                return;
            }

            // No cache; respect backoff.
            if (!skinCache.canAttemptFetchNow(now)) {
                return;
            }

            try {
                Optional<SkinCache.SkinProperty> fetched = SkinFetcher.fetchSkinPropertyBlocking(plugin.getSkinFromUsername());
                if (fetched.isPresent()) {
                    SkinCache.SkinProperty prop = fetched.get();
                    skinCache.recordFetchSuccess(prop.value, prop.signature, now);
                    tryApplyTexturesProperty(profile, prop.value, prop.signature);
                }
            } catch (SkinFetcher.RateLimitedException rate) {
                skinCache.recordFetchFailure(now);
                // Do not log loudly; rate limiting is expected during testing.
                plugin.getLogger().warning("Skin fetch rate-limited (HTTP 429). Backing off.");
            } catch (Exception e) {
                skinCache.recordFetchFailure(now);
                plugin.getLogger().warning("Failed to fetch skin for " + plugin.getSkinFromUsername() + ": " + e.getMessage());
            }
        }

        private void tryApplyTexturesProperty(WrappedGameProfile targetProfile, String value, String signature) {
            // First try ProtocolLib wrapper properties if available.
            try {
                Object props = targetProfile.getProperties();
                if (props != null) {
                    // removeAll("textures"), put("textures", WrappedSignedProperty)
                    targetProfile.getProperties().removeAll("textures");
                    targetProfile.getProperties().put("textures", new WrappedSignedProperty("textures", value, signature));
                    return;
                }
            } catch (Throwable ignored) {
            }

            // Fallback: reflect into the underlying GameProfile handle without compile-time authlib dependency.
            try {
                Object handle = targetProfile.getHandle();
                if (handle == null) return;

                Method getPropertiesMethod = handle.getClass().getMethod("getProperties");
                Object propertyMap = getPropertiesMethod.invoke(handle);
                if (propertyMap == null) return;

                Method removeAll = propertyMap.getClass().getMethod("removeAll", String.class);
                removeAll.invoke(propertyMap, "textures");

                // com.mojang.authlib.properties.Property("textures", value, signature)
                Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
                Constructor<?> ctor = propertyClass.getConstructor(String.class, String.class, String.class);
                Object propObj = ctor.newInstance("textures", value, signature);

                Method put = propertyMap.getClass().getMethod("put", Object.class, Object.class);
                put.invoke(propertyMap, "textures", propObj);
            } catch (Throwable ignored) {
            }
        }

        private boolean sendPlayerInfoAdd(Player viewer, WrappedGameProfile profile) {
            try {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);

                // This is where incompatible ProtocolLib builds blow up.
                packet.getPlayerInfoAction().write(0, EnumWrappers.PlayerInfoAction.ADD_PLAYER);

                PlayerInfoData infoData = new PlayerInfoData(
                        profile,
                        0,
                        EnumWrappers.NativeGameMode.SURVIVAL,
                        WrappedChatComponent.fromText(profile.getName())
                );

                packet.getPlayerInfoDataLists().write(0, Collections.singletonList(infoData));
                protocolManager.sendServerPacket(viewer, packet);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private void sendPlayerInfoRemove(Player viewer, UUID ghostUuid, String ghostName) {
            try {
                PacketContainer remove = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                remove.getUUIDLists().write(0, Collections.singletonList(ghostUuid));
                protocolManager.sendServerPacket(viewer, remove);
                return;
            } catch (Throwable ignored) {
            }

            try {
                WrappedGameProfile dummyProfile = new WrappedGameProfile(ghostUuid, ghostName);

                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
                packet.getPlayerInfoAction().write(0, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);

                PlayerInfoData infoData = new PlayerInfoData(
                        dummyProfile,
                        0,
                        EnumWrappers.NativeGameMode.SURVIVAL,
                        WrappedChatComponent.fromText(dummyProfile.getName())
                );

                packet.getPlayerInfoDataLists().write(0, Collections.singletonList(infoData));
                protocolManager.sendServerPacket(viewer, packet);
            } catch (Throwable ignored) {
            }
        }

        private boolean sendNamedEntitySpawn(Player viewer, int entityId, UUID ghostUuid, Location location) {
            try {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);

                packet.getIntegers().write(0, entityId);
                packet.getUUIDs().write(0, ghostUuid);

                packet.getDoubles().write(0, location.getX());
                packet.getDoubles().write(1, location.getY());
                packet.getDoubles().write(2, location.getZ());

                byte yaw = (byte) (location.getYaw() * 256.0F / 360.0F);
                byte pitch = (byte) (location.getPitch() * 256.0F / 360.0F);

                packet.getBytes().write(0, yaw);
                packet.getBytes().write(1, pitch);

                protocolManager.sendServerPacket(viewer, packet);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void sendLookPackets(Player viewer, int entityId, Location lookLocation) {
            try {
                byte yaw = (byte) (lookLocation.getYaw() * 256.0F / 360.0F);
                byte pitch = (byte) (lookLocation.getPitch() * 256.0F / 360.0F);

                PacketContainer lookPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_LOOK);
                lookPacket.getIntegers().write(0, entityId);
                lookPacket.getBytes().write(0, yaw);
                lookPacket.getBytes().write(1, pitch);
                lookPacket.getBooleans().write(0, true);
                protocolManager.sendServerPacket(viewer, lookPacket);

                PacketContainer headPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
                headPacket.getIntegers().write(0, entityId);
                headPacket.getBytes().write(0, yaw);
                protocolManager.sendServerPacket(viewer, headPacket);
            } catch (Throwable ignored) {
            }
        }

        private void sendDestroyEntity(Player viewer, int entityId) {
            try {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                packet.getIntLists().write(0, Collections.singletonList(entityId));
                protocolManager.sendServerPacket(viewer, packet);
            } catch (Throwable ignored) {
            }
        }
    }
}
