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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HerobrineGhost extends JavaPlugin implements Listener {

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

    // 0 = off, 1 = lightning+mark only (default), 2 = + brief ghost + message
    private int totemMode = 1;

    private List<String> summonLines = Arrays.asList(
            "I see you.",
            "Do not summon me.",
            "You should not have done that.",
            "I am watching.",
            "You cannot escape."
    );

    // View bias
    private boolean fovBiasEnabled = true;
    private double minAngleFromViewDegrees = 70.0D;

    // Ambient
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

    // Citizens reflection handles
    private Plugin citizensPlugin;
    private CitizensBridge citizensBridge;

    // -----------------------------
    // Enable/Disable
    // -----------------------------
    @Override
    public void onEnable() {
        ensureDefaultConfigFileExists();
        reloadLocalConfig();

        citizensPlugin = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizensPlugin == null || !citizensPlugin.isEnabled()) {
            getLogger().severe("Citizens is not installed/enabled. This build of HerobrineGhost uses Citizens for NPCs.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        citizensBridge = new CitizensBridge(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        startSightingTask();

        getLogger().info("Enabled (Citizens). displayName=" + ghostDisplayName + ", skinFrom=" + skinFromUsername
                + ", totemMode=" + totemMode + ", ambience=" + ambienceEnabled);
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }

    // -----------------------------
    // Config file creation
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

            defaults.save(configFile);
            getLogger().info("Wrote default config.yml");
        } catch (IOException e) {
            getLogger().warning("Failed to write default config.yml: " + e.getMessage());
        }
    }

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

        getLogger().info("Config loaded. excludedWorlds=" + excludedWorldNamesLower);
    }

    // -----------------------------
    // Commands
    // -----------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("herobrineghost")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            ensureDefaultConfigFileExists();
            reloadLocalConfig();
            sender.sendMessage("HerobrineGhost config reloaded.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use the test command.");
                return true;
            }

            long now = System.currentTimeMillis();
            Long last = lastTestCommandMillisByPlayer.get(player.getUniqueId());
            if (last != null && (now - last) < 3000L) {
                player.sendMessage("Not yet.");
                return true;
            }
            lastTestCommandMillisByPlayer.put(player.getUniqueId(), now);

            spawnGhostAtTestLocation(player);
            sender.sendMessage("HerobrineGhost: test sighting triggered.");
            return true;
        }

        sender.sendMessage("Usage: /" + label + " [reload|test]");
        return true;
    }

    // -----------------------------
    // Events (totem + combat/ui)
    // -----------------------------
    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.isCancelled()) return;
        if (totemMode <= 0) return;

        Player player = event.getPlayer();
        Block fireBlock = event.getBlock();
        if (player == null || fireBlock == null) return;

        Block netherrack = fireBlock.getRelative(0, -1, 0);
        if (netherrack.getType() != Material.NETHERRACK) return;
        if (isWorldExcluded(player.getWorld())) return;

        if (!isValidHerobrineTotem(netherrack)) return;

        long now = System.currentTimeMillis();
        Long lastUse = lastTotemUseMillisByPlayer.get(player.getUniqueId());
        if (lastUse != null && now - lastUse < totemCooldownMillis) {
            player.sendMessage("The air is still.");
            return;
        }

        Location strikeLocation = netherrack.getLocation().add(0.5, 1.0, 0.5);
        player.getWorld().strikeLightningEffect(strikeLocation);

        markedPlayers.add(player.getUniqueId());
        lastTotemUseMillisByPlayer.put(player.getUniqueId(), now);
        lastSightingMillisByPlayer.put(player.getUniqueId(), now);

        if (totemMode >= 2) {
            // Totem spawns should be on/near surface, not cave pockets.
            Location raw = netherrack.getLocation().add(0.5, 1.0, 0.5);
            Location adjusted = adjustSpawnToSurfaceStandable(raw);
            spawnGhostNearLocation(player, adjusted, lifetimeTicks);
            player.sendMessage("<" + ghostDisplayName + "> " + pickSummonLine());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        lastCombatMillisByPlayer.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        playersWithOpenInventory.add(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        playersWithOpenInventory.remove(player.getUniqueId());
    }

    // -----------------------------
    // Scheduler
    // -----------------------------
    private void startSightingTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                runSightingAndAmbienceCheck();
            } catch (Throwable t) {
                getLogger().warning("Exception during check: " + t.getMessage());
            }
        }, checkIntervalTicks, checkIntervalTicks);
    }

    private void runSightingAndAmbienceCheck() {
        long nowMillis = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            if (isWorldExcluded(player.getWorld())) continue;

            maybePlayAmbientCue(player, nowMillis);

            if (!isPlayerEligibleForSighting(player, nowMillis)) continue;

            double localChance = chancePerCheck;
            if (markedPlayers.contains(player.getUniqueId())) {
                localChance = Math.min(1.0D, localChance * markedChanceMultiplier);
            }

            if (random.nextDouble() <= localChance) {
                Location ghostLocation = computeRandomSightingLocation(player);
                if (ghostLocation != null) {
                    // Ensure surface standable (but do not scan underground).
                    Location adjusted = adjustSpawnToSurfaceStandable(ghostLocation);
                    spawnGhostNearLocation(player, adjusted, lifetimeTicks);
                    lastSightingMillisByPlayer.put(player.getUniqueId(), nowMillis);
                }
            }
        }
    }

    private boolean isPlayerEligibleForSighting(Player player, long nowMillis) {
        Long last = lastSightingMillisByPlayer.get(player.getUniqueId());
        if (last != null && nowMillis - last < sightingCooldownMillis) return false;

        if (!isPlayerAlone(player, 64.0D)) return false;

        Long lastCombat = lastCombatMillisByPlayer.get(player.getUniqueId());
        if (lastCombat != null && nowMillis - lastCombat < 10_000L) return false;

        if (playersWithOpenInventory.contains(player.getUniqueId())) return false;

        return true;
    }

    private void maybePlayAmbientCue(Player player, long nowMillis) {
        if (!ambienceEnabled) return;
        if (ambienceMaxCuesPerSessionPerPlayer <= 0) return;

        if (!isPlayerAlone(player, 64.0D)) return;
        if (playersWithOpenInventory.contains(player.getUniqueId())) return;

        Long lastCombat = lastCombatMillisByPlayer.get(player.getUniqueId());
        if (lastCombat != null && nowMillis - lastCombat < 10_000L) return;

        UUID playerId = player.getUniqueId();
        int usedCount = ambientCueCountByPlayer.getOrDefault(playerId, 0);
        if (usedCount >= ambienceMaxCuesPerSessionPerPlayer) return;

        Long lastCue = lastAmbientCueMillisByPlayer.get(playerId);
        if (lastCue != null && nowMillis - lastCue < ambienceMinMillisBetweenCues) return;

        boolean marked = markedPlayers.contains(playerId);
        double chance = marked ? ambienceChancePerCheckMarked : ambienceChancePerCheckUnmarked;
        if (random.nextDouble() > chance) return;

        Sound sound = pickValidSound();
        if (sound == null) return;

        Location soundLocation = computeOffscreenSoundLocation(player, 8.0D, 16.0D);

        try {
            player.playSound(soundLocation, sound, 0.30F, 0.95F + (random.nextFloat() * 0.15F));
            lastAmbientCueMillisByPlayer.put(playerId, nowMillis);
            ambientCueCountByPlayer.put(playerId, usedCount + 1);
        } catch (Throwable ignored) { }
    }

    private Sound pickValidSound() {
        for (int i = 0; i < 6; i++) {
            String name = ambienceSoundNames.get(random.nextInt(ambienceSoundNames.size()));
            if (name == null) continue;
            name = name.trim();
            if (name.isEmpty()) continue;
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private Location computeOffscreenSoundLocation(Player player, double minDistance, double maxDistance) {
        Location base = player.getLocation();
        Vector forward = base.getDirection();
        forward.setY(0);
        if (forward.lengthSquared() < 0.0001D) forward = new Vector(0, 0, 1);
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

    private Location computeRandomSightingLocation(Player viewer) {
        Location playerLocation = viewer.getLocation();
        World world = playerLocation.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 10; attempt++) {
            double distance = minDistanceBlocks + (maxDistanceBlocks - minDistanceBlocks) * random.nextDouble();
            double angle = random.nextDouble() * Math.PI * 2.0;

            int x = playerLocation.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = playerLocation.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

            int y = world.getHighestBlockYAt(x, z) + 1; // air above surface
            if (y <= world.getMinHeight()) continue;

            Location candidate = new Location(world, x + 0.5D, y, z + 0.5D);

            if (fovBiasEnabled) {
                double degrees = angleFromViewDegrees(viewer, candidate);
                if (degrees < minAngleFromViewDegrees) continue;
            }

            lookAt(candidate, playerLocation);

            if (candidate.distanceSquared(playerLocation) < (20.0D * 20.0D)) continue;

            return candidate;
        }

        return null;
    }

    private void spawnGhostAtTestLocation(Player viewer) {
        Location base = viewer.getLocation();
        World world = base.getWorld();
        if (world == null) return;

        float yaw = base.getYaw();
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ =  Math.cos(radians);

        double distance = 6.0D;
        double rawX = base.getX() + forwardX * distance;
        double rawZ = base.getZ() + forwardZ * distance;

        Location rough = new Location(world, rawX, base.getY(), rawZ);
        rough.setYaw(0.0F);
        rough.setPitch(0.0F);

        // For test: prefer "near my Y", then fall back to surface.
        Location adjusted = adjustSpawnNearPreferredY(rough, base.getBlockY());
        lookAt(adjusted, base);

        spawnGhostNearLocation(viewer, adjusted, lifetimeTicks);
    }

    private void spawnGhostNearLocation(Player viewer, Location ghostLocation, long lifeTicks) {
        if (citizensBridge == null) return;

        citizensBridge.spawnTemporaryPlayerNpc(
                ghostDisplayName,
                skinFromUsername,
                ghostLocation,
                lifeTicks
        );
    }

    // -----------------------------
    // Totem structure validation
    // -----------------------------
    private boolean isValidHerobrineTotem(Block netherrack) {
        World world = netherrack.getWorld();

        int cx = netherrack.getX();
        int cy = netherrack.getY();
        int cz = netherrack.getZ();

        int baseY = cy - 1;

        Block centerBase = world.getBlockAt(cx, baseY, cz);
        if (centerBase.getType() != Material.MOSSY_COBBLESTONE) return false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (world.getBlockAt(cx + dx, baseY, cz + dz).getType() != Material.GOLD_BLOCK) return false;
            }
        }

        Block n = world.getBlockAt(cx, cy, cz - 1);
        Block s = world.getBlockAt(cx, cy, cz + 1);
        Block w = world.getBlockAt(cx - 1, cy, cz);
        Block e = world.getBlockAt(cx + 1, cy, cz);

        return n.getType() == Material.REDSTONE_TORCH
                && s.getType() == Material.REDSTONE_TORCH
                && w.getType() == Material.REDSTONE_TORCH
                && e.getType() == Material.REDSTONE_TORCH;
    }

    // -----------------------------
    // Spawn placement helpers (fixed)
    // -----------------------------

    private Location adjustSpawnNearPreferredY(Location rough, int preferredY) {
        World world = rough.getWorld();
        if (world == null) return rough;

        int x = rough.getBlockX();
        int z = rough.getBlockZ();

        // Clamp preferredY to sane range
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        preferredY = Math.max(minY, Math.min(maxY, preferredY));

        // Search around preferredY without ever diving deep.
        // Order: a few above first, then same, then down a bit.
        int[] offsets = new int[] { 3, 2, 1, 0, -1, -2, -3, -4, -5, -6, -7, -8 };
        for (int off : offsets) {
            int y = preferredY + off;
            if (y < minY || y > maxY) continue;
            if (isStandableAt(world, x, y, z)) {
                return new Location(world, x + 0.5D, y + 0.05D, z + 0.5D, rough.getYaw(), rough.getPitch());
            }
        }

        // Fallback: surface standable.
        return adjustSpawnToSurfaceStandable(rough);
    }

    private Location adjustSpawnToSurfaceStandable(Location rough) {
        World world = rough.getWorld();
        if (world == null) return rough;

        int x = rough.getBlockX();
        int z = rough.getBlockZ();

        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;

        // Start at the air block above the highest solid block.
        int surfaceAirY = world.getHighestBlockYAt(x, z) + 1;
        surfaceAirY = Math.max(minY, Math.min(maxY, surfaceAirY));

        // Try that exact spot first
        if (isStandableAt(world, x, surfaceAirY, z)) {
            return new Location(world, x + 0.5D, surfaceAirY + 0.05D, z + 0.5D, rough.getYaw(), rough.getPitch());
        }

        // If a tree canopy etc blocks it, search locally (small range only).
        // IMPORTANT: This is intentionally shallow to avoid underground cave pockets.
        for (int delta = 1; delta <= 6; delta++) {
            int downY = surfaceAirY - delta;
            if (downY >= minY && isStandableAt(world, x, downY, z)) {
                return new Location(world, x + 0.5D, downY + 0.05D, z + 0.5D, rough.getYaw(), rough.getPitch());
            }

            int upY = surfaceAirY + delta;
            if (upY <= maxY && isStandableAt(world, x, upY, z)) {
                return new Location(world, x + 0.5D, upY + 0.05D, z + 0.5D, rough.getYaw(), rough.getPitch());
            }
        }

        // Last resort: return original, centered.
        return new Location(world, x + 0.5D, rough.getY(), z + 0.5D, rough.getYaw(), rough.getPitch());
    }

    private boolean isStandableAt(World world, int x, int feetY, int z) {
        // feetY is the air block where the NPC's feet would be.
        Block below = world.getBlockAt(x, feetY - 1, z);
        Block body = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);

        boolean belowSolid = below.getType().isSolid();
        boolean bodyClear = body.isEmpty() || !body.getType().isSolid();
        boolean headClear = head.isEmpty() || !head.getType().isSolid();

        return belowSolid && bodyClear && headClear;
    }

    // -----------------------------
    // Utility
    // -----------------------------
    private boolean isWorldExcluded(World world) {
        if (world == null) return false;
        String name = world.getName();
        return name != null && excludedWorldNamesLower.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isPlayerAlone(Player player, double radius) {
        World world = player.getWorld();
        double radiusSq = radius * radius;
        Location loc = player.getLocation();
        for (Player other : world.getPlayers()) {
            if (!other.isOnline()) continue;
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            if (other.getLocation().distanceSquared(loc) < radiusSq) return false;
        }
        return true;
    }

    private double angleFromViewDegrees(Player viewer, Location target) {
        Location viewLoc = viewer.getLocation();
        Vector forward = viewLoc.getDirection().clone();
        forward.setY(0);
        if (forward.lengthSquared() < 0.0001D) forward = new Vector(0, 0, 1);
        forward.normalize();

        Vector toTarget = target.toVector().subtract(viewLoc.toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 0.0001D) return 0.0D;
        toTarget.normalize();

        double dot = Math.max(-1.0D, Math.min(1.0D, forward.dot(toTarget)));
        return Math.toDegrees(Math.acos(dot));
    }

    private void lookAt(Location from, Location target) {
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        from.setYaw(yaw);
        from.setPitch(0.0F);
    }

    private String pickSummonLine() {
        if (summonLines == null || summonLines.isEmpty()) return "I see you.";
        return summonLines.get(random.nextInt(summonLines.size()));
    }

    private static long clampLong(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double clampDouble(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    // =========================================================================
    // Citizens Bridge (reflection, so no compile dependency)
    // =========================================================================
    private static final class CitizensBridge {
        private final HerobrineGhost plugin;

        private Method citizensApiGetNpcRegistry;
        private Method npcRegistryCreateNpc;

        private Method npcSpawn;
        private Method npcDespawn;
        private Method npcDestroy;
        private Method npcIsSpawned;
        private Method npcGetEntity;
        private Method npcGetOrAddTrait;

        private Class<?> skinTraitClass;
        private Method skinTraitSetSkinName;

        private CitizensBridge(HerobrineGhost plugin) {
            this.plugin = plugin;
            hook();
        }

        private void hook() {
            try {
                Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
                citizensApiGetNpcRegistry = citizensApi.getMethod("getNPCRegistry");

                Object registry = citizensApiGetNpcRegistry.invoke(null);

                npcRegistryCreateNpc = registry.getClass().getMethod("createNPC", EntityType.class, String.class);

                skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
                skinTraitSetSkinName = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);

                plugin.getLogger().info("Citizens bridge hooked.");
            } catch (Throwable t) {
                plugin.getLogger().severe("Failed to hook Citizens API: " + t.getMessage());
                Bukkit.getPluginManager().disablePlugin(plugin);
            }
        }

        public void spawnTemporaryPlayerNpc(String npcName, String skinFromName, Location location, long lifeTicks) {
            try {
                Object registry = citizensApiGetNpcRegistry.invoke(null);
                Object npc = npcRegistryCreateNpc.invoke(registry, EntityType.PLAYER, npcName);

                cacheNpcMethods(npc);

                Object trait = npcGetOrAddTrait.invoke(npc, skinTraitClass);
                skinTraitSetSkinName.invoke(trait, skinFromName, true);

                npcSpawn.invoke(npc, location);

                try {
                    Object ent = npcGetEntity.invoke(npc);
                    if (ent instanceof org.bukkit.entity.Entity bukkitEntity) {
                        bukkitEntity.setRotation(location.getYaw(), location.getPitch());
                    }
                } catch (Throwable ignored) { }

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        boolean spawned = (boolean) npcIsSpawned.invoke(npc);
                        if (spawned) npcDespawn.invoke(npc);
                    } catch (Throwable ignored) { }
                    try {
                        npcDestroy.invoke(npc);
                    } catch (Throwable ignored) { }
                }, lifeTicks);

            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to spawn Citizens NPC: " + t.getMessage());
            }
        }

        private void cacheNpcMethods(Object npc) throws Exception {
            if (npcSpawn != null) return;

            Class<?> npcClass = npc.getClass();
            npcSpawn = findMethod(npcClass, "spawn", Location.class);
            npcDespawn = findMethod(npcClass, "despawn");
            npcDestroy = findMethod(npcClass, "destroy");
            npcIsSpawned = findMethod(npcClass, "isSpawned");
            npcGetEntity = findMethod(npcClass, "getEntity");
            npcGetOrAddTrait = findMethod(npcClass, "getOrAddTrait", Class.class);

            if (npcSpawn == null || npcDespawn == null || npcDestroy == null || npcIsSpawned == null || npcGetOrAddTrait == null) {
                throw new IllegalStateException("NPC method set incomplete (Citizens API mismatch).");
            }
        }

        private Method findMethod(Class<?> type, String name, Class<?>... params) {
            try {
                return type.getMethod(name, params);
            } catch (Throwable ignored) { }
            try {
                return type.getDeclaredMethod(name, params);
            } catch (Throwable ignored) { }
            return null;
        }
    }
}
