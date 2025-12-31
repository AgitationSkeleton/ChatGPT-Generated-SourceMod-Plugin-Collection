// TronLightcycle.java
// Author: ChatGPT
//
// Spigot 1.21.10 single-file plugin.
// Lightcycle implemented as a fast black horse (no disguise) that emits a 2-block tall stained-glass-pane trail.
// Optional invisible LIGHT blocks are placed one block above the trail (y+2) and share the same lifetime.
//
// Notes:
// - Pane connectivity is forced by explicitly setting GlassPane faces.
// - Baton right-click is debounced and cancels the interaction to avoid double-firing / weirdness.
// - Trail cleanup for an owner uses that owner's FIFO list (no global scans) to avoid server stalls.

import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TronLightcycle extends JavaPlugin implements Listener, TabExecutor {

    // ---------- Keys ----------
    private NamespacedKey keyBaton;
    private NamespacedKey keyBatonColor;
    private NamespacedKey keyCycle;
    private NamespacedKey keyCycleOwner;

    // ---------- Player prefs ----------
    private File playersFile;
    private FileConfiguration playersConfig;

    // ---------- Config ----------
    private boolean allowAllWorlds;
    private Set<String> allowedWorldNames;

    private RenderMode renderMode;

    private boolean enableGlowingOutline;

    private double trailLifetimeSeconds;
    private double trailSelfImmunitySeconds;
    private double trailSampleBaseStep;
    private int trailUpdatePeriodTicks;

    private int clientViewDistanceBlocks;
    private int clientMaxChangesPerTickPerPlayer;

    private boolean lagCompEnabled;
    private boolean lagCompUseRealDeltaTime;
    private double lagCompMinStep;
    private double lagCompMaxStep;
    private int lagCompMaxSamplesPerUpdate;
    private String lagCompOnCap;

    private boolean positionAnomalyEnabled;
    private double positionAnomalyMaxStepDistance;
    private double positionAnomalyMaxStepVertical;
    private boolean positionAnomalyClearTrail;
    private boolean positionAnomalyPauseEmission;
    private double positionAnomalyCooldownSeconds;
    private boolean positionAnomalyDisableCooldown;

    private boolean debugEnabled;
    private boolean debugToOpsOnly;

    private double driverDamageOnCrash;
    private boolean driverInstantKillOnCrash;
    private double worldWallCrashSpeedThreshold;

    private int dismountGraceTicks;
    private int humIntervalTicks;

    private int spawnCooldownSeconds;

    private double horseMoveSpeed;   // attribute base
    private double horseJumpStrength; // attribute base

    private long batonDebounceMs;

    // Lighting
    private boolean lightingEnabled;
    private int lightingLevel; // 0-15

    // ---------- Runtime ----------
    private final Map<UUID, CycleInstance> activeCyclesByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spawnCooldownUntilMsByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> batonDebounceUntilMsByOwner = new ConcurrentHashMap<>();

    // SERVER render storage
    private final Map<BlockKey, TrailSegment> trailSegmentsByBaseKey = new ConcurrentHashMap<>();
    private final Map<BlockKey, TrailSegmentRef> trailIndexByBlockKey = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<BlockKey>> chunkToBaseKeys = new ConcurrentHashMap<>();

    private BukkitTask cycleTickTask;
    private int tickCounter = 0;

    // ---------- Scoreboard ----------
    private Scoreboard mainScoreboard;
    private final Map<String, Team> colorTeams = new HashMap<>();

    // ---------- Replaceable ----------
    private final Set<Material> replaceableMaterials = EnumSet.noneOf(Material.class);

    private enum RenderMode { SERVER, CLIENT }

    private enum CycleColor {
        RED, BLUE, ORANGE, WHITE, PURPLE, GREEN, YELLOW, PINK
    }

    @Override
    public void onEnable() {
        keyBaton = new NamespacedKey(this, "lightcycle_baton");
        keyBatonColor = new NamespacedKey(this, "lightcycle_baton_color");
        keyCycle = new NamespacedKey(this, "lightcycle_cycle");
        keyCycleOwner = new NamespacedKey(this, "lightcycle_owner");

        ensureConfigOnDisk();
        loadSettings();
        setupReplaceableMaterials();
        loadPlayersFile();

        mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        rebuildTeamsIfNeeded();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("lightcycle") != null) {
            getCommand("lightcycle").setExecutor(this);
            getCommand("lightcycle").setTabCompleter(this);
        }

        cycleTickTask = Bukkit.getScheduler().runTaskTimer(this, this::tickCycles, 1L, Math.max(1, trailUpdatePeriodTicks));
        getLogger().info("TronLightcycle enabled (horse cycle). RenderMode=" + renderMode);
    }

    @Override
    public void onDisable() {
        if (cycleTickTask != null) {
            cycleTickTask.cancel();
            cycleTickTask = null;
        }

        for (UUID ownerId : new ArrayList<>(activeCyclesByOwner.keySet())) {
            dismissCycle(ownerId, true, false);
        }
        activeCyclesByOwner.clear();

        restoreAllTrailSegments(true);
        getLogger().info("TronLightcycle disabled.");
    }

    // ---------- Config (create-on-disk) ----------
    private void ensureConfigOnDisk() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        if (!configFile.exists()) {
            FileConfiguration cfg = new YamlConfiguration();

            cfg.set("worlds.allowAllWorlds", false);
            cfg.set("worlds.allowedWorlds", Collections.singletonList("The_Grid"));

            cfg.set("trail.renderMode", "SERVER"); // SERVER or CLIENT
            cfg.set("trail.lifetimeSeconds", 10.0);
            cfg.set("trail.selfImmunitySeconds", 1.5);
            cfg.set("trail.sampleBaseStep", 0.32);
            cfg.set("trail.updatePeriodTicks", 2);

            cfg.set("trail.client.viewDistanceBlocks", 128);
            cfg.set("trail.client.maxChangesPerTickPerPlayer", 200);

            cfg.set("visual.enableGlowingOutline", true);

            cfg.set("lagCompensation.enabled", true);
            cfg.set("lagCompensation.useRealDeltaTime", true);
            cfg.set("lagCompensation.minStep", 0.22);
            cfg.set("lagCompensation.maxStep", 0.55);
            cfg.set("lagCompensation.maxSamplesPerUpdate", 18);
            cfg.set("lagCompensation.onCap", "TRUNCATE"); // TRUNCATE or ANOMALY

            cfg.set("positionAnomaly.enabled", true);
            cfg.set("positionAnomaly.maxStepDistance", 6.0);
            cfg.set("positionAnomaly.maxStepVertical", 3.0);
            cfg.set("positionAnomaly.clearTrailOnAnomaly", true);
            cfg.set("positionAnomaly.pauseEmissionOnAnomaly", true);
            cfg.set("positionAnomaly.cooldownSeconds", 3.0);
            cfg.set("positionAnomaly.disableCooldown", false);

            cfg.set("debug.enabled", true);
            cfg.set("debug.toOpsOnly", true);

            cfg.set("crash.driverDamage", 10.0);
            cfg.set("crash.instantKill", false);
            cfg.set("crash.worldWallSpeedThreshold", 0.75);

            cfg.set("cycle.dismountGraceTicks", 40);
            cfg.set("cycle.humIntervalTicks", 18);
            cfg.set("cycle.spawnCooldownSeconds", 5);

            cfg.set("cycle.horseMovementSpeed", 0.52);
            cfg.set("cycle.horseJumpStrength", 0.75);

            cfg.set("baton.debounceMs", 250);

            cfg.set("lighting.enabled", true);
            cfg.set("lighting.level", 12);

            try {
                cfg.save(configFile);
            } catch (IOException ioException) {
                getLogger().severe("Failed to create config.yml: " + ioException.getMessage());
            }
        }
        reloadConfig();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        allowAllWorlds = cfg.getBoolean("worlds.allowAllWorlds", false);
        allowedWorldNames = new HashSet<>();
        for (String worldName : cfg.getStringList("worlds.allowedWorlds")) {
            if (worldName != null && !worldName.trim().isEmpty()) allowedWorldNames.add(worldName.trim());
        }
        if (allowedWorldNames.isEmpty()) allowedWorldNames.add("The_Grid");

        String modeText = cfg.getString("trail.renderMode", "SERVER");
        renderMode = "CLIENT".equalsIgnoreCase(modeText) ? RenderMode.CLIENT : RenderMode.SERVER;

        enableGlowingOutline = cfg.getBoolean("visual.enableGlowingOutline", true);

        trailLifetimeSeconds = cfg.getDouble("trail.lifetimeSeconds", 10.0);
        trailSelfImmunitySeconds = cfg.getDouble("trail.selfImmunitySeconds", 1.5);
        trailSampleBaseStep = cfg.getDouble("trail.sampleBaseStep", 0.32);
        trailUpdatePeriodTicks = Math.max(1, cfg.getInt("trail.updatePeriodTicks", 2));

        clientViewDistanceBlocks = cfg.getInt("trail.client.viewDistanceBlocks", 128);
        clientMaxChangesPerTickPerPlayer = cfg.getInt("trail.client.maxChangesPerTickPerPlayer", 200);

        lagCompEnabled = cfg.getBoolean("lagCompensation.enabled", true);
        lagCompUseRealDeltaTime = cfg.getBoolean("lagCompensation.useRealDeltaTime", true);
        lagCompMinStep = cfg.getDouble("lagCompensation.minStep", 0.22);
        lagCompMaxStep = cfg.getDouble("lagCompensation.maxStep", 0.55);
        lagCompMaxSamplesPerUpdate = cfg.getInt("lagCompensation.maxSamplesPerUpdate", 18);
        lagCompOnCap = cfg.getString("lagCompensation.onCap", "TRUNCATE").toUpperCase(Locale.ROOT);

        positionAnomalyEnabled = cfg.getBoolean("positionAnomaly.enabled", true);
        positionAnomalyMaxStepDistance = cfg.getDouble("positionAnomaly.maxStepDistance", 6.0);
        positionAnomalyMaxStepVertical = cfg.getDouble("positionAnomaly.maxStepVertical", 3.0);
        positionAnomalyClearTrail = cfg.getBoolean("positionAnomaly.clearTrailOnAnomaly", true);
        positionAnomalyPauseEmission = cfg.getBoolean("positionAnomaly.pauseEmissionOnAnomaly", true);
        positionAnomalyCooldownSeconds = cfg.getDouble("positionAnomaly.cooldownSeconds", 3.0);
        positionAnomalyDisableCooldown = cfg.getBoolean("positionAnomaly.disableCooldown", false);

        debugEnabled = cfg.getBoolean("debug.enabled", true);
        debugToOpsOnly = cfg.getBoolean("debug.toOpsOnly", true);

        driverDamageOnCrash = cfg.getDouble("crash.driverDamage", 10.0);
        driverInstantKillOnCrash = cfg.getBoolean("crash.instantKill", false);
        worldWallCrashSpeedThreshold = cfg.getDouble("crash.worldWallSpeedThreshold", 0.75);

        dismountGraceTicks = cfg.getInt("cycle.dismountGraceTicks", 40);
        humIntervalTicks = cfg.getInt("cycle.humIntervalTicks", 18);
        spawnCooldownSeconds = cfg.getInt("cycle.spawnCooldownSeconds", 5);

        horseMoveSpeed = clamp(cfg.getDouble("cycle.horseMovementSpeed", 0.52), 0.15, 1.0);
        horseJumpStrength = clamp(cfg.getDouble("cycle.horseJumpStrength", 0.75), 0.1, 2.0);

        batonDebounceMs = Math.max(0L, cfg.getLong("baton.debounceMs", 250));

        lightingEnabled = cfg.getBoolean("lighting.enabled", true);
        lightingLevel = Math.max(0, Math.min(15, cfg.getInt("lighting.level", 12)));
    }

    private void setupReplaceableMaterials() {
        replaceableMaterials.clear();
        replaceableMaterials.add(Material.AIR);
        replaceableMaterials.add(Material.CAVE_AIR);
        replaceableMaterials.add(Material.VOID_AIR);

        // Grass/foliage
        tryAddMaterial(replaceableMaterials, "SHORT_GRASS");
        tryAddMaterial(replaceableMaterials, "GRASS");
        replaceableMaterials.add(Material.TALL_GRASS);

        // Snow layer
        replaceableMaterials.add(Material.SNOW);
    }

    private void tryAddMaterial(Set<Material> set, String materialName) {
        try {
            set.add(Material.valueOf(materialName));
        } catch (IllegalArgumentException ignored) { }
    }

    private void loadPlayersFile() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try { //noinspection ResultOfMethodCallIgnored
                playersFile.createNewFile();
            } catch (IOException ioException) {
                getLogger().severe("Failed to create players.yml: " + ioException.getMessage());
            }
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);
    }

    private void savePlayersFile() {
        try {
            playersConfig.save(playersFile);
        } catch (IOException ioException) {
            getLogger().severe("Failed to save players.yml: " + ioException.getMessage());
        }
    }

    // ---------- Teams ----------
    private void rebuildTeamsIfNeeded() {
        if (!enableGlowingOutline) return;
        ensureTeam("lc_red", ChatColor.RED);
        ensureTeam("lc_blue", ChatColor.BLUE);
        ensureTeam("lc_orange", ChatColor.GOLD);
        ensureTeam("lc_white", ChatColor.WHITE);
        ensureTeam("lc_purple", ChatColor.DARK_PURPLE);
        ensureTeam("lc_green", ChatColor.GREEN);
        ensureTeam("lc_yellow", ChatColor.YELLOW);
        ensureTeam("lc_pink", ChatColor.LIGHT_PURPLE);
    }

    private void ensureTeam(String teamName, ChatColor chatColor) {
        Team team = mainScoreboard.getTeam(teamName);
        if (team == null) team = mainScoreboard.registerNewTeam(teamName);
        team.setColor(chatColor);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.setOption(Team.Option.DEATH_MESSAGE_VISIBILITY, Team.OptionStatus.NEVER);
        colorTeams.put(teamName, team);
    }

    private void assignEntityToColorTeam(Entity entity, CycleColor cycleColor) {
        if (!enableGlowingOutline) return;
        String teamName = "lc_" + cycleColor.name().toLowerCase(Locale.ROOT);
        Team team = colorTeams.get(teamName);
        if (team == null) {
            rebuildTeamsIfNeeded();
            team = colorTeams.get(teamName);
        }
        if (team != null) {
            team.addEntry(entity.getUniqueId().toString());
            entity.setGlowing(true);
        }
    }

    private void removeEntityFromTeams(Entity entity) {
        if (entity == null) return;
        String entry = entity.getUniqueId().toString();
        for (Team team : colorTeams.values()) {
            if (team.hasEntry(entry)) team.removeEntry(entry);
        }
        entity.setGlowing(false);
    }

    // ---------- Colors ----------
    private CycleColor parseColor(String text) {
        if (text == null) return CycleColor.WHITE;
        try { return CycleColor.valueOf(text.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return CycleColor.WHITE; }
    }

    private Material paneMaterialForColor(CycleColor cycleColor) {
        switch (cycleColor) {
            case RED: return Material.RED_STAINED_GLASS_PANE;
            case BLUE: return Material.BLUE_STAINED_GLASS_PANE;
            case ORANGE: return Material.ORANGE_STAINED_GLASS_PANE;
            case PURPLE: return Material.PURPLE_STAINED_GLASS_PANE;
            case GREEN: return Material.GREEN_STAINED_GLASS_PANE;
            case YELLOW: return Material.YELLOW_STAINED_GLASS_PANE;
            case PINK: return Material.PINK_STAINED_GLASS_PANE;
            case WHITE:
            default: return Material.WHITE_STAINED_GLASS_PANE;
        }
    }

    private ChatColor chatColorForCycleColor(CycleColor cycleColor) {
        switch (cycleColor) {
            case RED: return ChatColor.RED;
            case BLUE: return ChatColor.BLUE;
            case ORANGE: return ChatColor.GOLD;
            case PURPLE: return ChatColor.DARK_PURPLE;
            case GREEN: return ChatColor.GREEN;
            case YELLOW: return ChatColor.YELLOW;
            case PINK: return ChatColor.LIGHT_PURPLE;
            case WHITE:
            default: return ChatColor.WHITE;
        }
    }

    private Color particleColorForCycleColor(CycleColor cycleColor) {
        switch (cycleColor) {
            case RED: return Color.fromRGB(255, 60, 60);
            case BLUE: return Color.fromRGB(80, 120, 255);
            case ORANGE: return Color.fromRGB(255, 160, 60);
            case PURPLE: return Color.fromRGB(180, 80, 255);
            case GREEN: return Color.fromRGB(80, 255, 120);
            case YELLOW: return Color.fromRGB(255, 255, 80);
            case PINK: return Color.fromRGB(255, 120, 200);
            case WHITE:
            default: return Color.fromRGB(245, 245, 245);
        }
    }

    private CycleColor getPlayerPreferredColor(UUID playerId) {
        String stored = playersConfig.getString(playerId.toString() + ".color", "WHITE");
        return parseColor(stored);
    }

    private void setPlayerPreferredColor(UUID playerId, CycleColor cycleColor) {
        playersConfig.set(playerId.toString() + ".color", cycleColor.name());
        savePlayersFile();
    }

    // ---------- Baton ----------
    private ItemStack createBatonItem(CycleColor cycleColor) {
        ItemStack itemStack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(chatColorForCycleColor(cycleColor) + cycleColor.name() + ChatColor.WHITE + " Light Cycle Baton");
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keyBaton, PersistentDataType.BYTE, (byte) 1);
            pdc.set(keyBatonColor, PersistentDataType.STRING, cycleColor.name());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private boolean isBatonItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.STICK) return false;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;
        Byte batonFlag = meta.getPersistentDataContainer().get(keyBaton, PersistentDataType.BYTE);
        return batonFlag != null && batonFlag == (byte) 1;
    }

    private CycleColor batonColorOrPlayerPref(ItemStack batonItem, Player player) {
        if (batonItem != null) {
            ItemMeta meta = batonItem.getItemMeta();
            if (meta != null) {
                String stored = meta.getPersistentDataContainer().get(keyBatonColor, PersistentDataType.STRING);
                if (stored != null && !stored.trim().isEmpty()) return parseColor(stored);
            }
        }
        return getPlayerPreferredColor(player.getUniqueId());
    }

    // ---------- Allowed worlds ----------
    private boolean isWorldAllowed(World world) {
        if (world == null) return false;
        if (allowAllWorlds) return true;
        return allowedWorldNames.contains(world.getName());
    }

    // ---------- Events ----------
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        dismissCycle(event.getPlayer().getUniqueId(), true, false);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        dismissCycle(event.getEntity().getUniqueId(), true, false);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID ownerId = findOwnerByCycleEntity(event.getEntity());
        if (ownerId != null) dismissCycle(ownerId, true, false);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!isWorldAllowed(player.getWorld())) dismissCycle(player.getUniqueId(), true, false);
        else if (renderMode == RenderMode.CLIENT) Bukkit.getScheduler().runTaskLater(this, () -> resyncClientTrailsForPlayer(player), 10L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (renderMode == RenderMode.CLIENT) Bukkit.getScheduler().runTaskLater(this, () -> resyncClientTrailsForPlayer(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (renderMode != RenderMode.SERVER) return;

        ChunkKey chunkKey = new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        Set<BlockKey> baseKeys = chunkToBaseKeys.remove(chunkKey);
        if (baseKeys == null || baseKeys.isEmpty()) return;

        for (BlockKey baseKey : baseKeys) {
            TrailSegment segment = trailSegmentsByBaseKey.remove(baseKey);
            if (segment != null) {
                removeSegmentFromIndices(segment);
                restoreSegmentServer(segment);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        UUID ownerId = findOwnerByCycleEntity(event.getEntity());
        if (ownerId != null) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity clicked = event.getRightClicked();
        UUID ownerId = findOwnerByCycleEntity(clicked);
        if (ownerId == null) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(ownerId)) {
            event.setCancelled(true);
            return;
        }

        CycleInstance cycleInstance = activeCyclesByOwner.get(ownerId);
        if (cycleInstance == null) return;

        cycleInstance.trailToggleEnabled = !cycleInstance.trailToggleEnabled;
        player.sendMessage(ChatColor.AQUA + "Light Trail: " + (cycleInstance.trailToggleEnabled ? "Enabled" : "Disabled"));
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isBatonItem(held)) return;

        // Debounce: prevent double-fires and some client weirdness
        UUID playerId = player.getUniqueId();
        long nowMs = System.currentTimeMillis();
        Long debounceUntil = batonDebounceUntilMsByOwner.get(playerId);
        if (debounceUntil != null && debounceUntil > nowMs) {
            event.setCancelled(true);
            return;
        }
        batonDebounceUntilMsByOwner.put(playerId, nowMs + batonDebounceMs);

        event.setCancelled(true);

        if (!isWorldAllowed(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "Lightcycles are not allowed in this world.");
            return;
        }

        CycleInstance existing = activeCyclesByOwner.get(playerId);
        if (existing != null) {
            player.sendMessage(ChatColor.GRAY + "[Recalling lightcycle...]");
            dismissCycle(playerId, true, true);
            spawnCooldownUntilMsByOwner.put(playerId, nowMs + (spawnCooldownSeconds * 1000L));
            return;
        }

        Long cooldownUntil = spawnCooldownUntilMsByOwner.get(playerId);
        if (cooldownUntil != null && cooldownUntil > nowMs) {
            double remaining = (cooldownUntil - nowMs) / 1000.0;
            player.sendMessage(ChatColor.RED + "[Your lightcycle is on " + String.format(Locale.US, "%.1f", remaining) + " second cooldown.]");
            return;
        }

        CycleColor cycleColor = batonColorOrPlayerPref(held, player);
        boolean ok = spawnCycleForPlayer(player, cycleColor);
        if (ok) player.sendMessage(ChatColor.GRAY + "[Lightcycle rezzed.]");
        else player.sendMessage(ChatColor.RED + "Failed to rez lightcycle (see console).");
    }

    // ---------- Commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"lightcycle".equalsIgnoreCase(command.getName())) return false;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Usage:");
            sender.sendMessage(ChatColor.AQUA + "/lightcycle color <red/blue/orange/white/purple/green/yellow/pink>");
            sender.sendMessage(ChatColor.AQUA + "/lightcycle give [player] (OP only)");
            sender.sendMessage(ChatColor.AQUA + "/lightcycle reload (OP only)");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("color".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can set a lightcycle color.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /lightcycle color <red/blue/orange/white/purple/green/yellow/pink>");
                return true;
            }

            Player player = (Player) sender;
            CycleColor cycleColor = parseColor(args[1]);
            setPlayerPreferredColor(player.getUniqueId(), cycleColor);
            player.sendMessage(ChatColor.AQUA + "Lightcycle color set to " + chatColorForCycleColor(cycleColor) + cycleColor.name() + ChatColor.AQUA + ".");
            return true;
        }

        if ("give".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use /lightcycle give.");
                return true;
            }
            Player giver = (Player) sender;
            if (!giver.isOp()) {
                giver.sendMessage(ChatColor.RED + "OP only.");
                return true;
            }

            Player target = giver;
            if (args.length >= 2) {
                Player found = Bukkit.getPlayerExact(args[1]);
                if (found == null) {
                    giver.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                    return true;
                }
                target = found;
            }

            CycleColor cycleColor = getPlayerPreferredColor(target.getUniqueId());
            target.getInventory().addItem(createBatonItem(cycleColor));

            giver.sendMessage(ChatColor.AQUA + "Gave a Light Cycle Baton to " + target.getName() + " (" + cycleColor.name() + ").");
            if (!target.equals(giver)) target.sendMessage(ChatColor.AQUA + "You received a " + chatColorForCycleColor(cycleColor) + cycleColor.name() + ChatColor.AQUA + " Light Cycle Baton.");
            return true;
        }

        if ("reload".equals(sub)) {
            if (!(sender instanceof Player) || !((Player) sender).isOp()) {
                sender.sendMessage(ChatColor.RED + "OP only.");
                return true;
            }
            loadSettings();
            setupReplaceableMaterials();
            rebuildTeamsIfNeeded();
            sender.sendMessage(ChatColor.AQUA + "TronLightcycle config reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!"lightcycle".equalsIgnoreCase(command.getName())) return Collections.emptyList();

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("color", "give", "reload"));
            subs.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return subs;
        }
        if (args.length == 2 && "color".equalsIgnoreCase(args[0])) {
            List<String> colors = Arrays.asList("red", "blue", "orange", "white", "purple", "green", "yellow", "pink");
            List<String> out = new ArrayList<>();
            for (String c : colors) if (c.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(c);
            return out;
        }
        return Collections.emptyList();
    }

    // ---------- Cycle spawn/dismiss ----------
    private boolean spawnCycleForPlayer(Player player, CycleColor cycleColor) {
        UUID ownerId = player.getUniqueId();

        try {
            dismissCycle(ownerId, true, false);

            Location spawnLocation = player.getLocation().clone();
            World world = spawnLocation.getWorld();
            if (world == null) return false;

            Horse horse = world.spawn(spawnLocation, Horse.class, spawnedHorse -> {
                spawnedHorse.setAdult();
                spawnedHorse.setTamed(true);
                spawnedHorse.setOwner(player);
                spawnedHorse.setAI(true);
                spawnedHorse.setInvulnerable(true);
                spawnedHorse.setSilent(true);

                spawnedHorse.setColor(Horse.Color.BLACK);
                spawnedHorse.setStyle(Horse.Style.WHITEFIELD);

                // saddle
                spawnedHorse.getInventory().setSaddle(new ItemStack(Material.SADDLE, 1));

                // speed/jump
                AttributeInstance speedAttr = spawnedHorse.getAttribute(Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.movement_speed")));
                if (speedAttr != null) speedAttr.setBaseValue(horseMoveSpeed);

                AttributeInstance jumpAttr = spawnedHorse.getAttribute(Registry.ATTRIBUTE.get(NamespacedKey.minecraft("horse.jump_strength")));
                if (jumpAttr != null) jumpAttr.setBaseValue(horseJumpStrength);
            });

            PersistentDataContainer pdc = horse.getPersistentDataContainer();
            pdc.set(keyCycle, PersistentDataType.BYTE, (byte) 1);
            pdc.set(keyCycleOwner, PersistentDataType.STRING, ownerId.toString());

            assignEntityToColorTeam(horse, cycleColor);

            horse.addPassenger(player);

            world.playSound(horse.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);

            CycleInstance cycleInstance = new CycleInstance(ownerId, horse.getUniqueId(), cycleColor);
            cycleInstance.trailToggleEnabled = true;
            cycleInstance.lastUpdateNanos = System.nanoTime();
            cycleInstance.lastEmittedPoint = horse.getLocation().toVector();
            cycleInstance.lastHumTick = tickCounter;

            activeCyclesByOwner.put(ownerId, cycleInstance);
            return true;
        } catch (Throwable t) {
            getLogger().severe("Failed to spawn lightcycle: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    private void dismissCycle(UUID ownerId, boolean clearTrail, boolean playStopSound) {
        CycleInstance cycleInstance = activeCyclesByOwner.remove(ownerId);

        Entity cycleEntity = null;
        if (cycleInstance != null) cycleEntity = Bukkit.getEntity(cycleInstance.cycleEntityId);

        if (cycleEntity != null) {
            removeEntityFromTeams(cycleEntity);

            if (playStopSound) cycleEntity.getWorld().playSound(cycleEntity.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f);

            for (Entity passenger : new ArrayList<>(cycleEntity.getPassengers())) cycleEntity.removePassenger(passenger);
            cycleEntity.remove();
        }

        if (clearTrail) {
            if (cycleInstance != null) {
                clearTrailUsingOwnerFIFO(cycleInstance, true);
            } else {
                // fallback: nothing to do
            }
        }
    }

    private UUID findOwnerByCycleEntity(Entity entity) {
        if (entity == null) return null;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = pdc.get(keyCycle, PersistentDataType.BYTE);
        if (flag == null || flag != (byte) 1) return null;
        String ownerText = pdc.get(keyCycleOwner, PersistentDataType.STRING);
        if (ownerText == null) return null;
        try { return UUID.fromString(ownerText); } catch (IllegalArgumentException ex) { return null; }
    }

    // ---------- Main tick loop ----------
    private void tickCycles() {
        tickCounter += trailUpdatePeriodTicks;

        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        for (CycleInstance cycleInstance : new ArrayList<>(activeCyclesByOwner.values())) {
            Player owner = Bukkit.getPlayer(cycleInstance.ownerId);
            Entity cycleEntity = Bukkit.getEntity(cycleInstance.cycleEntityId);

            if (owner == null || !owner.isOnline() || cycleEntity == null || cycleEntity.isDead() || !cycleEntity.isValid() || !(cycleEntity instanceof Horse)) {
                dismissCycle(cycleInstance.ownerId, true, false);
                continue;
            }

            Horse horse = (Horse) cycleEntity;

            if (!isWorldAllowed(horse.getWorld())) {
                dismissCycle(cycleInstance.ownerId, true, false);
                continue;
            }

            boolean ownerMounted = horse.getPassengers().contains(owner);
            if (!ownerMounted) {
                cycleInstance.dismountTicks++;
                if (cycleInstance.dismountTicks >= dismountGraceTicks) dismissCycle(cycleInstance.ownerId, true, true);
                continue;
            } else cycleInstance.dismountTicks = 0;

            // Hum: steady while mounted
            if ((tickCounter - cycleInstance.lastHumTick) >= humIntervalTicks) {
                horse.getWorld().playSound(horse.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.8f);
                cycleInstance.lastHumTick = tickCounter;
            }

            cleanupExpiredTrailFIFO(cycleInstance, nowMs);

            if (checkTrailCollision(cycleInstance, horse, nowMs) || checkWorldWallCollision(horse)) {
                crashCycle(cycleInstance, horse, owner);
                continue;
            }

            boolean emissionAllowedByToggle = cycleInstance.trailToggleEnabled;
            boolean emissionAllowedByCooldown = nowMs >= cycleInstance.emissionCooldownUntilMs;
            boolean emissionAllowed = emissionAllowedByToggle && emissionAllowedByCooldown;

            Vector currentPoint = horse.getLocation().toVector();

            if (!emissionAllowed) {
                cycleInstance.lastEmittedPoint = currentPoint;
                cycleInstance.lastUpdateNanos = nowNanos;
                continue;
            }

            double speed = horse.getVelocity().length();
            double stepDistance = trailSampleBaseStep;

            if (lagCompEnabled) {
                double deltaSeconds = 0.1;
                if (lagCompUseRealDeltaTime && cycleInstance.lastUpdateNanos > 0) {
                    deltaSeconds = Math.max(0.001, (nowNanos - cycleInstance.lastUpdateNanos) / 1_000_000_000.0);
                }

                double speedFactor = Math.max(0.20, Math.min(2.2, speed * 1.6));
                double dtFactor = Math.max(0.6, Math.min(1.8, deltaSeconds / 0.1));
                double scaled = (trailSampleBaseStep / speedFactor) / dtFactor;
                stepDistance = clamp(scaled, lagCompMinStep, lagCompMaxStep);
            }

            if (positionAnomalyEnabled) {
                Vector lastPoint = cycleInstance.lastEmittedPoint;
                Vector delta = currentPoint.clone().subtract(lastPoint);
                double dist = delta.length();
                double verticalAbs = Math.abs(delta.getY());

                if (dist > positionAnomalyMaxStepDistance || verticalAbs > positionAnomalyMaxStepVertical) {
                    handlePositionAnomaly(cycleInstance, dist, verticalAbs, nowMs, horse.getLocation());
                    cycleInstance.lastEmittedPoint = currentPoint;
                    cycleInstance.lastUpdateNanos = nowNanos;
                    continue;
                }
            }

            List<Vector> sampledPoints = samplePointsAlongSegment(cycleInstance.lastEmittedPoint, currentPoint, stepDistance, lagCompMaxSamplesPerUpdate);

            emitTrailFromSampledPoints(cycleInstance, horse.getWorld(), sampledPoints, nowMs);
            spawnRibbonParticles(cycleInstance, horse.getWorld(), cycleInstance.lastEmittedPoint, currentPoint);

            cycleInstance.lastEmittedPoint = currentPoint;
            cycleInstance.lastUpdateNanos = nowNanos;
        }
    }

    private void handlePositionAnomaly(CycleInstance cycleInstance, double deltaDistance, double deltaVertical, long nowMs, Location atLocation) {
        if (positionAnomalyClearTrail) clearTrailUsingOwnerFIFO(cycleInstance, true);

        if (debugEnabled) {
            String msg = ChatColor.GRAY + "LightCycle debug: anomaly detected (Δ=" +
                    String.format(Locale.US, "%.2f", deltaDistance) + " blocks, ΔY=" +
                    String.format(Locale.US, "%.2f", deltaVertical) + "). Trail cleared.";
            sendDebugMessage(cycleInstance.ownerId, msg);
        }

        if (positionAnomalyPauseEmission && !positionAnomalyDisableCooldown) {
            long cooldownMs = (long) Math.max(0, positionAnomalyCooldownSeconds * 1000.0);
            cycleInstance.emissionCooldownUntilMs = nowMs + cooldownMs;

            if (debugEnabled) {
                String msg2 = ChatColor.GRAY + "LightCycle debug: emission paused for " +
                        String.format(Locale.US, "%.1f", positionAnomalyCooldownSeconds) + "s.";
                sendDebugMessage(cycleInstance.ownerId, msg2);
            }
        } else {
            cycleInstance.emissionCooldownUntilMs = 0L;
        }

        if (atLocation != null && atLocation.getWorld() != null) atLocation.getWorld().playSound(atLocation, Sound.BLOCK_BEACON_DEACTIVATE, 0.4f, 1.9f);
    }

    private void sendDebugMessage(UUID ownerId, String message) {
        if (!debugEnabled) return;
        Player player = Bukkit.getPlayer(ownerId);
        if (player == null) return;
        if (debugToOpsOnly && !player.isOp()) return;
        player.sendMessage(message);
    }

    private void crashCycle(CycleInstance cycleInstance, Horse horse, Player driver) {
        Location loc = horse.getLocation();
        World world = horse.getWorld();

        spawnDerezzParticles(cycleInstance, world, loc);

        world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.2f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.0f);

        if (driver != null && driver.isOnline()) {
            if (driverInstantKillOnCrash) driver.setHealth(0.0);
            else driver.damage(driverDamageOnCrash, horse);
        }

        dismissCycle(cycleInstance.ownerId, true, false);
    }

    // ---------- Trail emission + lighting ----------
    private void emitTrailFromSampledPoints(CycleInstance cycleInstance, World world, List<Vector> sampledPoints, long nowMs) {
        if (world == null || sampledPoints == null || sampledPoints.isEmpty()) return;

        Material paneMaterial = paneMaterialForColor(cycleInstance.cycleColor);
        BlockData paneData = Bukkit.createBlockData(paneMaterial);

        int placedThisUpdate = 0;
        for (Vector point : sampledPoints) {
            // Hard cap per update to avoid spikes
            if (++placedThisUpdate > lagCompMaxSamplesPerUpdate) break;

            int blockX = (int) Math.floor(point.getX());
            int blockY = (int) Math.floor(point.getY());
            int blockZ = (int) Math.floor(point.getZ());

            BlockKey baseKey = new BlockKey(world.getUID(), blockX, blockY, blockZ);
            if (trailSegmentsByBaseKey.containsKey(baseKey)) continue;

            Block baseBlock = world.getBlockAt(blockX, blockY, blockZ);
            Block aboveBlock = world.getBlockAt(blockX, blockY + 1, blockZ);

            if (!isReplaceableForTrail(baseBlock) || !isReplaceableForTrail(aboveBlock)) continue;

            // Optional light at y+2, only if air
            Block lightBlock = world.getBlockAt(blockX, blockY + 2, blockZ);
            boolean lightPlaced = false;
            BlockData originalLightData = lightBlock.getBlockData().clone();

            if (lightingEnabled && isAirOnly(lightBlock)) {
                try {
                    BlockData lightData = Bukkit.createBlockData(Material.LIGHT);
                    if (lightData instanceof Light) ((Light) lightData).setLevel(lightingLevel);
                    lightBlock.setBlockData(lightData, false);
                    lightPlaced = true;
                } catch (Throwable ignored) {
                    lightPlaced = false;
                }
            }

            BlockData originalBaseData = baseBlock.getBlockData().clone();
            BlockData originalAboveData = aboveBlock.getBlockData().clone();

            TrailSegment segment = new TrailSegment(baseKey, nowMs, cycleInstance.ownerId, cycleInstance.cycleColor,
                    originalBaseData, originalAboveData, lightPlaced, originalLightData);

            trailSegmentsByBaseKey.put(baseKey, segment);

            BlockKey aboveKey = new BlockKey(world.getUID(), blockX, blockY + 1, blockZ);
            trailIndexByBlockKey.put(baseKey, new TrailSegmentRef(baseKey, nowMs, cycleInstance.ownerId));
            trailIndexByBlockKey.put(aboveKey, new TrailSegmentRef(baseKey, nowMs, cycleInstance.ownerId));

            ChunkKey chunkKey = new ChunkKey(world.getUID(), baseBlock.getChunk().getX(), baseBlock.getChunk().getZ());
            chunkToBaseKeys.computeIfAbsent(chunkKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(baseKey);

            cycleInstance.baseKeysFIFO.addLast(baseKey);

            if (renderMode == RenderMode.SERVER) {
                baseBlock.setBlockData(paneData, false);
                aboveBlock.setBlockData(paneData, false);

                // Force proper connectivity (base + above + neighbors)
                refreshPaneConnectivity(world, blockX, blockY, blockZ);
            } else {
                Location baseLoc = baseKey.toLocation(world);
                Location aboveLoc = baseLoc.clone().add(0, 1, 0);
                for (Player viewer : getClientViewers(world, baseLoc)) {
                    viewer.sendBlockChange(baseLoc, paneData);
                    viewer.sendBlockChange(aboveLoc, paneData);
                }
            }
        }
    }

    private void refreshPaneConnectivity(World world, int x, int y, int z) {
        // Update both y and y+1 panes and their four cardinal neighbors.
        updatePaneAt(world, x, y, z);
        updatePaneAt(world, x, y + 1, z);

        updatePaneAt(world, x + 1, y, z);
        updatePaneAt(world, x - 1, y, z);
        updatePaneAt(world, x, y, z + 1);
        updatePaneAt(world, x, y, z - 1);

        updatePaneAt(world, x + 1, y + 1, z);
        updatePaneAt(world, x - 1, y + 1, z);
        updatePaneAt(world, x, y + 1, z + 1);
        updatePaneAt(world, x, y + 1, z - 1);
    }

    private void updatePaneAt(World world, int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        if (!isAnyStainedGlassPane(b.getType())) return;

        BlockData data = b.getBlockData();
        if (!(data instanceof GlassPane)) return;

        GlassPane pane = (GlassPane) data;

        pane.setFace(org.bukkit.block.BlockFace.NORTH, isAnyStainedGlassPane(world.getBlockAt(x, y, z - 1).getType()));
        pane.setFace(org.bukkit.block.BlockFace.SOUTH, isAnyStainedGlassPane(world.getBlockAt(x, y, z + 1).getType()));
        pane.setFace(org.bukkit.block.BlockFace.EAST, isAnyStainedGlassPane(world.getBlockAt(x + 1, y, z).getType()));
        pane.setFace(org.bukkit.block.BlockFace.WEST, isAnyStainedGlassPane(world.getBlockAt(x - 1, y, z).getType()));

        b.setBlockData(pane, false);
    }

    private boolean isAnyStainedGlassPane(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_STAINED_GLASS_PANE");
    }

    private boolean isReplaceableForTrail(Block block) {
        return block != null && replaceableMaterials.contains(block.getType());
    }

    private boolean isAirOnly(Block block) {
        if (block == null) return false;
        Material t = block.getType();
        return t == Material.AIR || t == Material.CAVE_AIR || t == Material.VOID_AIR;
    }

    private void cleanupExpiredTrailFIFO(CycleInstance cycleInstance, long nowMs) {
        long lifetimeMs = (long) Math.max(0, trailLifetimeSeconds * 1000.0);

        // Remove at most 2 per tick so cleanup doesn't spike
        for (int i = 0; i < 2; i++) {
            BlockKey oldestBaseKey = cycleInstance.baseKeysFIFO.peekFirst();
            if (oldestBaseKey == null) return;

            TrailSegment segment = trailSegmentsByBaseKey.get(oldestBaseKey);
            if (segment == null) {
                cycleInstance.baseKeysFIFO.pollFirst();
                continue;
            }

            if (nowMs - segment.placedMs < lifetimeMs) return;

            cycleInstance.baseKeysFIFO.pollFirst();
            trailSegmentsByBaseKey.remove(oldestBaseKey);
            removeSegmentFromIndices(segment);

            if (renderMode == RenderMode.SERVER) restoreSegmentServer(segment);
            else restoreSegmentClient(segment);

            ChunkKey chunkKey = new ChunkKey(segment.baseKey.worldId, segment.baseKey.getChunkX(), segment.baseKey.getChunkZ());
            Set<BlockKey> set = chunkToBaseKeys.get(chunkKey);
            if (set != null) {
                set.remove(segment.baseKey);
                if (set.isEmpty()) chunkToBaseKeys.remove(chunkKey);
            }
        }
    }

    private void clearTrailUsingOwnerFIFO(CycleInstance cycleInstance, boolean doRenderRestore) {
        if (cycleInstance == null) return;
        while (!cycleInstance.baseKeysFIFO.isEmpty()) {
            BlockKey baseKey = cycleInstance.baseKeysFIFO.pollFirst();
            if (baseKey == null) continue;

            TrailSegment seg = trailSegmentsByBaseKey.remove(baseKey);
            if (seg == null) continue;

            removeSegmentFromIndices(seg);

            if (doRenderRestore) {
                if (renderMode == RenderMode.SERVER) restoreSegmentServer(seg);
                else restoreSegmentClient(seg);
            }

            ChunkKey chunkKey = new ChunkKey(seg.baseKey.worldId, seg.baseKey.getChunkX(), seg.baseKey.getChunkZ());
            Set<BlockKey> set = chunkToBaseKeys.get(chunkKey);
            if (set != null) {
                set.remove(seg.baseKey);
                if (set.isEmpty()) chunkToBaseKeys.remove(chunkKey);
            }
        }
    }

    private void removeSegmentFromIndices(TrailSegment segment) {
        trailIndexByBlockKey.remove(segment.baseKey);
        BlockKey aboveKey = new BlockKey(segment.baseKey.worldId, segment.baseKey.x, segment.baseKey.y + 1, segment.baseKey.z);
        trailIndexByBlockKey.remove(aboveKey);
    }

    private void restoreSegmentServer(TrailSegment segment) {
        World world = Bukkit.getWorld(segment.baseKey.worldId);
        if (world == null) return;

        int x = segment.baseKey.x;
        int y = segment.baseKey.y;
        int z = segment.baseKey.z;

        Block baseBlock = world.getBlockAt(x, y, z);
        Block aboveBlock = world.getBlockAt(x, y + 1, z);

        baseBlock.setBlockData(segment.originalBaseData, false);
        aboveBlock.setBlockData(segment.originalAboveData, false);

        if (segment.lightPlaced) {
            Block lightBlock = world.getBlockAt(x, y + 2, z);
            lightBlock.setBlockData(segment.originalLightData, false);
        }

        // Update connectivity around removal
        refreshPaneConnectivity(world, x, y, z);
    }

    private void restoreSegmentClient(TrailSegment segment) {
        World world = Bukkit.getWorld(segment.baseKey.worldId);
        if (world == null) return;

        Location baseLoc = segment.baseKey.toLocation(world);
        Location aboveLoc = baseLoc.clone().add(0, 1, 0);

        BlockData realBaseData = world.getBlockAt(segment.baseKey.x, segment.baseKey.y, segment.baseKey.z).getBlockData();
        BlockData realAboveData = world.getBlockAt(segment.baseKey.x, segment.baseKey.y + 1, segment.baseKey.z).getBlockData();

        for (Player viewer : getClientViewers(world, baseLoc)) {
            viewer.sendBlockChange(baseLoc, realBaseData);
            viewer.sendBlockChange(aboveLoc, realAboveData);
        }

        if (segment.lightPlaced) {
            Block lightBlock = world.getBlockAt(segment.baseKey.x, segment.baseKey.y + 2, segment.baseKey.z);
            lightBlock.setBlockData(segment.originalLightData, false);
        }
    }

    private void restoreAllTrailSegments(boolean serverRestoreOnly) {
        for (TrailSegment seg : new ArrayList<>(trailSegmentsByBaseKey.values())) {
            if (serverRestoreOnly && renderMode != RenderMode.SERVER) continue;
            if (renderMode == RenderMode.SERVER) restoreSegmentServer(seg);
            else restoreSegmentClient(seg);

            trailSegmentsByBaseKey.remove(seg.baseKey);
            removeSegmentFromIndices(seg);
        }
        chunkToBaseKeys.clear();
        trailIndexByBlockKey.clear();
    }

    // ---------- Sampling ----------
    private List<Vector> samplePointsAlongSegment(Vector start, Vector end, double step, int maxSamples) {
        List<Vector> points = new ArrayList<>();
        if (start == null || end == null) return points;

        Vector delta = end.clone().subtract(start);
        double distance = delta.length();
        if (distance < 0.001) return points;

        int samples = (int) Math.ceil(distance / Math.max(0.001, step));
        int clampedSamples = Math.min(samples, Math.max(1, maxSamples));

        Vector direction = delta.normalize();
        for (int i = 1; i <= clampedSamples; i++) {
            double d = Math.min(distance, i * step);
            points.add(start.clone().add(direction.clone().multiply(d)));
        }
        return points;
    }

    // ---------- Collision ----------
    private boolean checkTrailCollision(CycleInstance cycleInstance, Horse horse, long nowMs) {
        World world = horse.getWorld();
        Vector velocity = horse.getVelocity();
        double speed = velocity.length();
        if (speed < 0.05) return false;

        Vector forward = velocity.clone().normalize();
        Location baseLoc = horse.getLocation();

        double probeDistance = clamp(0.7 + speed * 1.2, 0.7, 2.5);

        List<Vector> probeOffsets = Arrays.asList(
                forward.clone().multiply(probeDistance),
                forward.clone().multiply(probeDistance).add(new Vector(0.3, 0, 0.3)),
                forward.clone().multiply(probeDistance).add(new Vector(-0.3, 0, -0.3))
        );

        long selfImmunityMs = (long) (trailSelfImmunitySeconds * 1000.0);

        for (Vector offset : probeOffsets) {
            Location probeLoc = baseLoc.clone().add(offset);
            int x = probeLoc.getBlockX();
            int y = probeLoc.getBlockY();
            int z = probeLoc.getBlockZ();

            for (int dy = 0; dy <= 1; dy++) {
                BlockKey key = new BlockKey(world.getUID(), x, y + dy, z);
                TrailSegmentRef ref = trailIndexByBlockKey.get(key);
                if (ref == null) continue;

                boolean sameOwner = cycleInstance.ownerId.equals(ref.ownerId);
                boolean immune = sameOwner && (nowMs - ref.placedMs) <= selfImmunityMs;
                if (!immune) return true;
            }
        }

        return false;
    }

    private boolean checkWorldWallCollision(Horse horse) {
        Vector velocity = horse.getVelocity();
        double speed = velocity.length();
        if (speed < worldWallCrashSpeedThreshold) return false;

        World world = horse.getWorld();
        Location loc = horse.getLocation();

        Vector forward = velocity.clone().normalize();
        double maxDistance = clamp(0.9 + speed * 1.1, 0.9, 3.0);

        for (double d = 0.35; d <= maxDistance; d += 0.25) {
            Location probe = loc.clone().add(forward.clone().multiply(d));

            int x = probe.getBlockX();
            int y = probe.getBlockY();
            int z = probe.getBlockZ();

            Block block0 = world.getBlockAt(x, y, z);
            Block block1 = world.getBlockAt(x, y + 1, z);

            if (isSolidWall(block0) || isSolidWall(block1)) return true;
        }
        return false;
    }

    private boolean isSolidWall(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        if (replaceableMaterials.contains(type)) return false;
        return type.isSolid();
    }

    // ---------- Particles ----------
    private void spawnRibbonParticles(CycleInstance cycleInstance, World world, Vector from, Vector to) {
        if (world == null || from == null || to == null) return;

        Color color = particleColorForCycleColor(cycleInstance.cycleColor);
        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 1.4f);

        Vector delta = to.clone().subtract(from);
        double distance = delta.length();
        if (distance < 0.15) return;

        Vector direction = delta.normalize();
        for (double d = 0.2; d <= Math.min(distance, 2.8); d += 0.2) {
            Vector p = from.clone().add(direction.clone().multiply(d));
            Location loc = new Location(world, p.getX(), p.getY() + 0.2, p.getZ());
            world.spawnParticle(Particle.DUST, loc, 1, 0.05, 0.05, 0.05, 0.0, dustOptions);
        }
    }

    private void spawnDerezzParticles(CycleInstance cycleInstance, World world, Location loc) {
        if (world == null || loc == null) return;

        Color color = particleColorForCycleColor(cycleInstance.cycleColor);
        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 2.2f);

        world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.5, 0), 80, 0.6, 0.5, 0.6, 0.0, dustOptions);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.2, 0), 60, 0.7, 0.6, 0.7, 0.0, dustOptions);
    }

    // ---------- Client viewers + resync ----------
    private List<Player> getClientViewers(World world, Location around) {
        if (world == null || around == null) return Collections.emptyList();

        int range = Math.max(16, clientViewDistanceBlocks);
        double rangeSq = range * (double) range;

        List<Player> viewers = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(around) <= rangeSq) viewers.add(player);
        }
        return viewers;
    }

    private void resyncClientTrailsForPlayer(Player player) {
        if (player == null || !player.isOnline() || renderMode != RenderMode.CLIENT) return;

        World world = player.getWorld();
        if (world == null) return;

        Location center = player.getLocation();
        int range = Math.max(16, clientViewDistanceBlocks);
        double rangeSq = range * (double) range;

        int sentChanges = 0;
        for (TrailSegment segment : trailSegmentsByBaseKey.values()) {
            if (!segment.baseKey.worldId.equals(world.getUID())) continue;

            Location segLoc = segment.baseKey.toLocation(world);
            if (segLoc.distanceSquared(center) > rangeSq) continue;

            Material paneMaterial = paneMaterialForColor(segment.cycleColor);
            BlockData paneData = Bukkit.createBlockData(paneMaterial);

            Location baseLoc = segLoc;
            Location aboveLoc = segLoc.clone().add(0, 1, 0);

            if (sentChanges >= clientMaxChangesPerTickPerPlayer) break;

            player.sendBlockChange(baseLoc, paneData);
            player.sendBlockChange(aboveLoc, paneData);
            sentChanges += 2;
        }
    }

    // ---------- Utils ----------
    private double clamp(double value, double minValue, double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    // ---------- Data ----------
    private static class CycleInstance {
        private final UUID ownerId;
        private final UUID cycleEntityId;
        private final CycleColor cycleColor;

        private boolean trailToggleEnabled = true;

        private Vector lastEmittedPoint;
        private long lastUpdateNanos;

        private final Deque<BlockKey> baseKeysFIFO = new ArrayDeque<>();

        private long emissionCooldownUntilMs = 0L;

        private int dismountTicks = 0;

        private int lastHumTick = 0;

        private CycleInstance(UUID ownerId, UUID cycleEntityId, CycleColor cycleColor) {
            this.ownerId = ownerId;
            this.cycleEntityId = cycleEntityId;
            this.cycleColor = cycleColor;
        }
    }

    private static class TrailSegment {
        private final BlockKey baseKey;
        private final long placedMs;
        private final UUID ownerId;
        private final CycleColor cycleColor;

        private final BlockData originalBaseData;
        private final BlockData originalAboveData;

        private final boolean lightPlaced;
        private final BlockData originalLightData;

        private TrailSegment(BlockKey baseKey, long placedMs, UUID ownerId, CycleColor cycleColor,
                             BlockData originalBaseData, BlockData originalAboveData,
                             boolean lightPlaced, BlockData originalLightData) {
            this.baseKey = baseKey;
            this.placedMs = placedMs;
            this.ownerId = ownerId;
            this.cycleColor = cycleColor;
            this.originalBaseData = originalBaseData;
            this.originalAboveData = originalAboveData;
            this.lightPlaced = lightPlaced;
            this.originalLightData = originalLightData;
        }
    }

    private static class TrailSegmentRef {
        private final BlockKey baseKey;
        private final long placedMs;
        private final UUID ownerId;

        private TrailSegmentRef(BlockKey baseKey, long placedMs, UUID ownerId) {
            this.baseKey = baseKey;
            this.placedMs = placedMs;
            this.ownerId = ownerId;
        }
    }

    private static class BlockKey {
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private int getChunkX() { return x >> 4; }
        private int getChunkZ() { return z >> 4; }

        private Location toLocation(World world) {
            return new Location(world, x, y, z);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof BlockKey)) return false;
            BlockKey other = (BlockKey) obj;
            return x == other.x && y == other.y && z == other.z && worldId.equals(other.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, x, y, z);
        }
    }

    private static class ChunkKey {
        private final UUID worldId;
        private final int chunkX;
        private final int chunkZ;

        private ChunkKey(UUID worldId, int chunkX, int chunkZ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChunkKey)) return false;
            ChunkKey other = (ChunkKey) obj;
            return chunkX == other.chunkX && chunkZ == other.chunkZ && worldId.equals(other.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, chunkX, chunkZ);
        }
    }
}