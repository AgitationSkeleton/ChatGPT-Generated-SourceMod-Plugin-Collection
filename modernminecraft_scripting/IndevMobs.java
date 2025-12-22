// IndevMobs.java
// Author: ChatGPT
package com.redchanit.indevmobs;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class IndevMobs extends JavaPlugin implements Listener {

    private NamespacedKey indevMobKey;
    private NamespacedKey indevMobTypeKey;

    private Plugin citizensPlugin;
    private boolean citizensAvailable = false;

    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> spawnedEntities = new ConcurrentHashMap<>();

    private int spawnTaskId = -1;

    @Override
    public void onEnable() {
        this.indevMobKey = new NamespacedKey(this, "indevmob");
        this.indevMobTypeKey = new NamespacedKey(this, "indevmob_type");

        // IMPORTANT: In your build workflow, config.yml is NOT embedded in the jar.
        // So we generate defaults programmatically instead of saveDefaultConfig().
        ensureConfigDefaults();

        loadSkinCacheFromConfig();

        citizensPlugin = Bukkit.getPluginManager().getPlugin("Citizens");
        citizensAvailable = (citizensPlugin != null && citizensPlugin.isEnabled());

        if (!citizensAvailable) {
            getLogger().warning("Citizens was not found/enabled. IndevMobs requires Citizens to spawn humanoid NPC mobs.");
            getLogger().warning("Install Citizens, restart, then this plugin will begin spawning Indev mobs.");
        } else {
            getLogger().info("Citizens detected. Humanoid Indev mobs will be enabled.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("skins.preloadOnEnable", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                for (String mobType : getMobTypes()) {
                    ensureSkinLoaded(mobType);
                }
            });
        }

        startSpawner();
    }

    @Override
    public void onDisable() {
        if (spawnTaskId != -1) {
            Bukkit.getScheduler().cancelTask(spawnTaskId);
            spawnTaskId = -1;
        }
        spawnedEntities.clear();
    }

    private void ensureConfigDefaults() {
        FileConfiguration cfg = getConfig();

        cfg.addDefault("enabled", true);

        cfg.addDefault("spawning.enabledWorlds", new ArrayList<String>());
        cfg.addDefault("spawning.tickInterval", 200);
        cfg.addDefault("spawning.attemptsPerIntervalPerPlayer", 1);
        cfg.addDefault("spawning.spawnChancePerAttempt", 0.10);
        cfg.addDefault("spawning.spawnRadiusBlocks", 96);
        cfg.addDefault("spawning.minDistanceFromPlayer", 24.0);
        cfg.addDefault("spawning.locationTries", 12);
        cfg.addDefault("spawning.maxPerWorld", 20);
        cfg.addDefault("spawning.despawnIfNoPlayersWithin", 128.0);

        cfg.addDefault("spawning.weights.rana", 1.0);
        cfg.addDefault("spawning.weights.steve", 1.0);
        cfg.addDefault("spawning.weights.blacksteve", 1.0);
        cfg.addDefault("spawning.weights.beastboy", 1.0);

        cfg.addDefault("sounds.hurt.volume", 1.0);
        cfg.addDefault("sounds.hurt.pitch", 1.0);

        cfg.addDefault("skins.preloadOnEnable", true);
        cfg.addDefault("skins.allowMineskinRequests", true);
        cfg.addDefault("skins.mineskinApiKey", "");
        cfg.addDefault("skins.userAgent", "IndevMobs/1.0");
        cfg.addDefault("skins.variant", "classic");
        cfg.addDefault("skins.visibility", "unlisted");

        cfg.addDefault("skins.urls.rana", "https://files.catbox.moe/yo0n7z.png");
        cfg.addDefault("skins.urls.steve", "https://files.catbox.moe/lj5tkg.png");
        cfg.addDefault("skins.urls.blacksteve", "https://files.catbox.moe/ue85z2.png");
        cfg.addDefault("skins.urls.beastboy", "https://files.catbox.moe/p9lszo.png");

        cfg.addDefault("mobs.rana.displayName", "Rana");
        cfg.addDefault("mobs.rana.showNameplate", false);
        cfg.addDefault("mobs.rana.maxHealth", 20.0);
        cfg.addDefault("mobs.rana.wander.tickPeriod", 80);
        cfg.addDefault("mobs.rana.wander.radiusBlocks", 16);
        cfg.addDefault("mobs.rana.wander.speed", 1.0);

        cfg.addDefault("mobs.steve.displayName", "Steve");
        cfg.addDefault("mobs.steve.showNameplate", false);
        cfg.addDefault("mobs.steve.maxHealth", 20.0);
        cfg.addDefault("mobs.steve.wander.tickPeriod", 80);
        cfg.addDefault("mobs.steve.wander.radiusBlocks", 16);
        cfg.addDefault("mobs.steve.wander.speed", 1.0);

        cfg.addDefault("mobs.blacksteve.displayName", "Black Steve");
        cfg.addDefault("mobs.blacksteve.showNameplate", false);
        cfg.addDefault("mobs.blacksteve.maxHealth", 20.0);
        cfg.addDefault("mobs.blacksteve.wander.tickPeriod", 80);
        cfg.addDefault("mobs.blacksteve.wander.radiusBlocks", 16);
        cfg.addDefault("mobs.blacksteve.wander.speed", 1.0);

        cfg.addDefault("mobs.beastboy.displayName", "Beast Boy");
        cfg.addDefault("mobs.beastboy.showNameplate", false);
        cfg.addDefault("mobs.beastboy.maxHealth", 20.0);
        cfg.addDefault("mobs.beastboy.wander.tickPeriod", 80);
        cfg.addDefault("mobs.beastboy.wander.radiusBlocks", 16);
        cfg.addDefault("mobs.beastboy.wander.speed", 1.0);

        // Drops
        cfg.addDefault("drops.steve.common.FEATHER.min", 0);
        cfg.addDefault("drops.steve.common.FEATHER.max", 2);
        cfg.addDefault("drops.steve.common.GUNPOWDER.min", 0);
        cfg.addDefault("drops.steve.common.GUNPOWDER.max", 2);
        cfg.addDefault("drops.steve.common.STRING.min", 0);
        cfg.addDefault("drops.steve.common.STRING.max", 2);
        cfg.addDefault("drops.steve.rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault("drops.steve.rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault("drops.steve.rare.FLINT_AND_STEEL.max", 1);

        cfg.addDefault("drops.blacksteve.common.FEATHER.min", 0);
        cfg.addDefault("drops.blacksteve.common.FEATHER.max", 2);
        cfg.addDefault("drops.blacksteve.common.GUNPOWDER.min", 0);
        cfg.addDefault("drops.blacksteve.common.GUNPOWDER.max", 2);
        cfg.addDefault("drops.blacksteve.common.STRING.min", 0);
        cfg.addDefault("drops.blacksteve.common.STRING.max", 2);
        cfg.addDefault("drops.blacksteve.rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault("drops.blacksteve.rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault("drops.blacksteve.rare.FLINT_AND_STEEL.max", 1);

        cfg.addDefault("drops.beastboy.common.FEATHER.min", 0);
        cfg.addDefault("drops.beastboy.common.FEATHER.max", 2);
        cfg.addDefault("drops.beastboy.common.GUNPOWDER.min", 0);
        cfg.addDefault("drops.beastboy.common.GUNPOWDER.max", 2);
        cfg.addDefault("drops.beastboy.common.STRING.min", 0);
        cfg.addDefault("drops.beastboy.common.STRING.max", 2);
        cfg.addDefault("drops.beastboy.rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault("drops.beastboy.rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault("drops.beastboy.rare.FLINT_AND_STEEL.max", 1);

        cfg.addDefault("drops.rana.common.APPLE.min", 0);
        cfg.addDefault("drops.rana.common.APPLE.max", 2);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.chance", 0.05);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.min", 0);
        cfg.addDefault("drops.rana.rare.GOLDEN_APPLE.max", 1);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.chance", 0.05);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.min", 0);
        cfg.addDefault("drops.rana.rare.FLINT_AND_STEEL.max", 1);

        cfg.options().copyDefaults(true);
        saveConfig();
    }

    // ------------------------------------------------------------------------
    // Spawner loop
    // ------------------------------------------------------------------------
    private void startSpawner() {
        int intervalTicks = Math.max(20, getConfig().getInt("spawning.tickInterval", 200));
        spawnTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!citizensAvailable) return;
            if (!getConfig().getBoolean("enabled", true)) return;

            for (World world : Bukkit.getWorlds()) {
                List<String> enabledWorlds = getConfig().getStringList("spawning.enabledWorlds");
                if (enabledWorlds != null && !enabledWorlds.isEmpty() && !enabledWorlds.contains(world.getName())) {
                    continue;
                }

                int maxPerWorld = Math.max(0, getConfig().getInt("spawning.maxPerWorld", 20));
                int currentCount = countAliveInWorld(world);
                if (currentCount >= maxPerWorld) continue;

                List<Player> players = world.getPlayers();
                if (players.isEmpty()) continue;

                int attemptsPerPlayer = Math.max(0, getConfig().getInt("spawning.attemptsPerIntervalPerPlayer", 1));
                int radius = Math.max(16, getConfig().getInt("spawning.spawnRadiusBlocks", 96));
                double spawnChance = clamp01(getConfig().getDouble("spawning.spawnChancePerAttempt", 0.10));

                for (Player player : players) {
                    for (int i = 0; i < attemptsPerPlayer; i++) {
                        if (currentCount >= maxPerWorld) break;
                        if (Math.random() > spawnChance) continue;

                        Location spawnLoc = pickSpawnLocationNear(player.getLocation(), radius);
                        if (spawnLoc == null) continue;

                        double minDistance = Math.max(8.0, getConfig().getDouble("spawning.minDistanceFromPlayer", 24.0));
                        if (spawnLoc.distanceSquared(player.getLocation()) < (minDistance * minDistance)) continue;

                        String mobType = pickMobTypeByWeight();
                        if (mobType == null) continue;

                        boolean spawned = spawnIndevNpc(mobType, spawnLoc);
                        if (spawned) currentCount++;
                    }
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private int countAliveInWorld(World world) {
        int count = 0;
        Iterator<Map.Entry<UUID, String>> it = spawnedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> entry = it.next();
            UUID uuid = entry.getKey();
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                it.remove();
                continue;
            }
            if (entity.getWorld().equals(world)) count++;
        }
        return count;
    }

    private Location pickSpawnLocationNear(Location origin, int radius) {
        World world = origin.getWorld();
        if (world == null) return null;

        int tries = Math.max(5, getConfig().getInt("spawning.locationTries", 12));

        for (int t = 0; t < tries; t++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);

            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;

            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            boolean generated = isChunkGeneratedSafe(world, chunkX, chunkZ);
            if (!generated) continue;

            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (!chunk.isLoaded()) chunk.load();

            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight()) continue;
            if (y >= world.getMaxHeight() - 3) continue;

            Location base = new Location(world, x + 0.5, y, z + 0.5);

            if (!base.getBlock().getType().isAir()) continue;
            if (!base.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
            if (base.clone().add(0, -1, 0).getBlock().isLiquid()) continue;

            return base;
        }

        return null;
    }

    private boolean isChunkGeneratedSafe(World world, int chunkX, int chunkZ) {
        try {
            return world.isChunkGenerated(chunkX, chunkZ);
        } catch (Throwable ignored) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            return chunk.isLoaded();
        }
    }

    private List<String> getMobTypes() {
        return Arrays.asList("rana", "steve", "blacksteve", "beastboy");
    }

    private String pickMobTypeByWeight() {
        ConfigurationSection weights = getConfig().getConfigurationSection("spawning.weights");
        if (weights == null) return "rana";

        double total = 0.0;
        Map<String, Double> w = new HashMap<>();
        for (String type : getMobTypes()) {
            double value = Math.max(0.0, weights.getDouble(type, 1.0));
            w.put(type, value);
            total += value;
        }
        if (total <= 0.0) return null;

        double roll = Math.random() * total;
        double acc = 0.0;
        for (String type : getMobTypes()) {
            acc += w.getOrDefault(type, 0.0);
            if (roll <= acc) return type;
        }
        return "rana";
    }

    // ------------------------------------------------------------------------
    // Citizens reflection spawning
    // ------------------------------------------------------------------------
    private boolean spawnIndevNpc(String mobType, Location spawnLoc) {
        if (!citizensAvailable) return false;

        SkinData skin = ensureSkinLoaded(mobType);
        if (skin == null || skin.value.isEmpty() || skin.signature.isEmpty()) {
            return false;
        }

        String displayName = getConfig().getString("mobs." + mobType + ".displayName", mobType);
        boolean showNameplate = getConfig().getBoolean("mobs." + mobType + ".showNameplate", false);

        try {
            Object citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getClass().getMethod("getNPCRegistry").invoke(null);

            Class<?> entityTypeClass = Class.forName("org.bukkit.entity.EntityType");
            Object playerEntityType = Enum.valueOf((Class<Enum>) entityTypeClass, "PLAYER");

            Object npc = registry.getClass().getMethod("createNPC", entityTypeClass, String.class)
                    .invoke(registry, playerEntityType, displayName);

            boolean spawned = (boolean) npc.getClass().getMethod("spawn", Location.class).invoke(npc, spawnLoc);
            if (!spawned) return false;

            Entity entity = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity == null) return false;

            tagEntity(entity, mobType);

            try {
                double maxHealth = Math.max(1.0, getConfig().getDouble("mobs." + mobType + ".maxHealth", 20.0));
                entity.getClass().getMethod("setMaxHealth", double.class).invoke(entity, maxHealth);
                entity.getClass().getMethod("setHealth", double.class).invoke(entity, maxHealth);
            } catch (Throwable ignored) {
            }

            applyCitizensSkinTrait(npc, displayName, skin.signature, skin.value);

            startWanderTask(npc, entity, mobType);

            if (!showNameplate && entity instanceof Player) {
                try {
                    entity.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(entity, false);
                } catch (Throwable ignored) {
                }
            }

            spawnedEntities.put(entity.getUniqueId(), mobType);
            return true;

        } catch (Throwable ex) {
            getLogger().warning("Failed to spawn NPC via Citizens: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void applyCitizensSkinTrait(Object npc, String skinName, String signature, String value) {
        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skinTrait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTraitClass);

            skinTrait.getClass().getMethod("setSkinPersistent", String.class, String.class, String.class)
                    .invoke(skinTrait, skinName, signature, value);

        } catch (Throwable ex) {
            getLogger().warning("Could not apply Citizens SkinTrait. NPC may appear as default skin. " +
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void startWanderTask(Object npc, Entity entity, String mobType) {
        int periodTicks = Math.max(40, getConfig().getInt("mobs." + mobType + ".wander.tickPeriod", 80));
        int wanderRadius = Math.max(4, getConfig().getInt("mobs." + mobType + ".wander.radiusBlocks", 16));
        double speed = clamp(getConfig().getDouble("mobs." + mobType + ".wander.speed", 1.0), 0.05, 2.0);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (entity == null || !entity.isValid() || entity.isDead()) return;

            double despawnDist = getConfig().getDouble("spawning.despawnIfNoPlayersWithin", 128.0);
            if (despawnDist > 0 && entity.getWorld().getPlayers().stream()
                    .noneMatch(p -> p.getLocation().distanceSquared(entity.getLocation()) <= despawnDist * despawnDist)) {
                tryDespawnCitizensNpc(npc);
                return;
            }

            Location origin = entity.getLocation();
            Location target = pickGroundTarget(origin, wanderRadius);
            if (target == null) return;

            try {
                Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);

                try {
                    navigator.getClass().getMethod("setDefaultParameters").invoke(navigator);
                } catch (Throwable ignored) {
                }

                try {
                    Object params = navigator.getClass().getMethod("getDefaultParameters").invoke(navigator);
                    params.getClass().getMethod("speedModifier", float.class).invoke(params, (float) speed);
                } catch (Throwable ignored) {
                }

                navigator.getClass().getMethod("setTarget", Location.class).invoke(navigator, target);

            } catch (Throwable ignored) {
            }

        }, periodTicks, periodTicks);
    }

    private void tryDespawnCitizensNpc(Object npc) {
        try {
            npc.getClass().getMethod("despawn").invoke(npc);
        } catch (Throwable ignored) {
        }
    }

    private Location pickGroundTarget(Location origin, int radius) {
        World world = origin.getWorld();
        if (world == null) return null;

        for (int tries = 0; tries < 8; tries++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;

            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight()) continue;

            Location base = new Location(world, x + 0.5, y, z + 0.5);
            if (!base.getBlock().getType().isAir()) continue;
            if (!base.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
            if (base.clone().add(0, -1, 0).getBlock().isLiquid()) continue;

            return base;
        }
        return null;
    }

    private void tagEntity(Entity entity, String mobType) {
        try {
            entity.getPersistentDataContainer().set(indevMobKey, PersistentDataType.BYTE, (byte) 1);
            entity.getPersistentDataContainer().set(indevMobTypeKey, PersistentDataType.STRING, mobType);
        } catch (Throwable ignored) {
        }
    }

    private String getTaggedMobType(Entity entity) {
        try {
            Byte present = entity.getPersistentDataContainer().get(indevMobKey, PersistentDataType.BYTE);
            if (present == null || present != (byte) 1) return null;
            return entity.getPersistentDataContainer().get(indevMobTypeKey, PersistentDataType.STRING);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Sounds & drops
    // ------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        String mobType = getTaggedMobType(entity);
        if (mobType == null) return;

        float volume = (float) getConfig().getDouble("sounds.hurt.volume", 1.0);
        float pitch = (float) getConfig().getDouble("sounds.hurt.pitch", 1.0);

        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PLAYER_HURT, volume, pitch);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        String mobType = getTaggedMobType(entity);
        if (mobType == null) return;

        event.getDrops().clear();
        event.getDrops().addAll(buildDropsFor(mobType));
    }

    private List<ItemStack> buildDropsFor(String mobType) {
        List<ItemStack> drops = new ArrayList<>();
        ConfigurationSection dropSection = getConfig().getConfigurationSection("drops." + mobType);
        if (dropSection == null) return drops;

        ConfigurationSection common = dropSection.getConfigurationSection("common");
        if (common != null) {
            for (String matName : common.getKeys(false)) {
                int min = Math.max(0, common.getInt(matName + ".min", 0));
                int max = Math.max(min, common.getInt(matName + ".max", min));
                int amount = randomInt(min, max);
                if (amount <= 0) continue;

                ItemStack stack = makeItemSafe(matName, amount);
                if (stack != null) drops.add(stack);
            }
        }

        ConfigurationSection rare = dropSection.getConfigurationSection("rare");
        if (rare != null) {
            for (String matName : rare.getKeys(false)) {
                double chance = clamp01(rare.getDouble(matName + ".chance", 0.05));
                int min = Math.max(0, rare.getInt(matName + ".min", 0));
                int max = Math.max(min, rare.getInt(matName + ".max", min));
                if (Math.random() > chance) continue;

                int amount = randomInt(min, max);
                if (amount <= 0) continue;

                ItemStack stack = makeItemSafe(matName, amount);
                if (stack != null) drops.add(stack);
            }
        }

        return drops;
    }

    private ItemStack makeItemSafe(String materialName, int amount) {
        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            return new ItemStack(mat, amount);
        } catch (Throwable ex) {
            getLogger().warning("Unknown material in config: " + materialName);
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // MineSkin
    // ------------------------------------------------------------------------
    private void loadSkinCacheFromConfig() {
        ConfigurationSection cached = getConfig().getConfigurationSection("skins.cached");
        if (cached == null) return;

        for (String key : cached.getKeys(false)) {
            String value = cached.getString(key + ".value", "");
            String signature = cached.getString(key + ".signature", "");
            if (!value.isEmpty() && !signature.isEmpty()) {
                skinCache.put(key.toLowerCase(Locale.ROOT), new SkinData(value, signature));
            }
        }
    }

    private SkinData ensureSkinLoaded(String mobType) {
        mobType = mobType.toLowerCase(Locale.ROOT);

        SkinData existing = skinCache.get(mobType);
        if (existing != null) return existing;

        boolean allow = getConfig().getBoolean("skins.allowMineskinRequests", true);
        if (!allow) return null;

        String url = getConfig().getString("skins.urls." + mobType, "");
        if (url == null || url.isEmpty()) return null;

        String apiKey = getConfig().getString("skins.mineskinApiKey", "");
        String userAgent = getConfig().getString("skins.userAgent", "IndevMobs/1.0");
        String variant = getConfig().getString("skins.variant", "classic");
        String visibility = getConfig().getString("skins.visibility", "unlisted");

        SkinData generated = requestMineSkin(url, mobType, variant, visibility, apiKey, userAgent);
        if (generated != null) {
            skinCache.put(mobType, generated);

            getConfig().set("skins.cached." + mobType + ".value", generated.value);
            getConfig().set("skins.cached." + mobType + ".signature", generated.signature);
            saveConfig();
        }

        return generated;
    }

    private SkinData requestMineSkin(String pngUrl, String name, String variant, String visibility,
                                    String apiKey, String userAgent) {

        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.mineskin.org/v2/generate");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                String trimmed = apiKey.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                    trimmed = "Bearer " + trimmed;
                }
                conn.setRequestProperty("Authorization", trimmed);
            }

            String safeName = sanitizeName(name);
            String json = "{"
                    + "\"url\":\"" + escapeJson(pngUrl) + "\""
                    + ",\"name\":\"" + escapeJson(safeName) + "\""
                    + ",\"variant\":\"" + escapeJson(variant) + "\""
                    + ",\"visibility\":\"" + escapeJson(visibility) + "\""
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            BufferedReader reader;
            if (code >= 200 && code < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            String body = sb.toString();

            if (code < 200 || code >= 300) {
                getLogger().warning("MineSkin request failed (" + code + "): " + body);
                return null;
            }

            String value = extractJsonString(body, "\"value\":\"");
            String signature = extractJsonString(body, "\"signature\":\"");
            if (value == null || signature == null || value.isEmpty() || signature.isEmpty()) {
                getLogger().warning("MineSkin response missing value/signature: " + body);
                return null;
            }

            return new SkinData(value, signature);

        } catch (Throwable ex) {
            getLogger().warning("MineSkin request error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String extractJsonString(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        idx += marker.length();
        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                out.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return null;
    }

    private String sanitizeName(String input) {
        if (input == null) return "IndevMob";
        String trimmed = input.trim();
        if (trimmed.length() > 20) trimmed = trimmed.substring(0, 20);
        if (trimmed.isEmpty()) trimmed = "IndevMob";
        return trimmed;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------
    private int randomInt(int min, int max) {
        if (max < min) max = min;
        if (min == max) return min;
        return min + (int) Math.floor(Math.random() * (max - min + 1));
    }

    private double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static final class SkinData {
        final String value;
        final String signature;

        SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }
}
