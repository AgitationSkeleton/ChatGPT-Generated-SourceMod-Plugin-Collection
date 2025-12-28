package com.redchanit.pigmanskins;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class PigmanSkins extends JavaPlugin implements Listener {

    private final Random rng = new Random();

    private NamespacedKey disguiseAppliedKey;

    private File configFile;
    private FileConfiguration config;

    // Cached reflective handles (LibsDisguises)
    private boolean libsDisguisesAvailable = false;
    private Class<?> disguiseApiClass;
    private Class<?> playerDisguiseClass;
    private Method disguiseToAllMethod;
    private Method isDisguisedMethod;
    private Method setSkinMethod;
    private Method setNameVisibleMethod;
    private Constructor<?> playerDisguiseCtor;

    // For mirroring held items
    private Method getDisguiseMethod;           // DisguiseAPI.getDisguise(Entity)
    private Method disguiseGetWatcherMethod;    // Disguise.getWatcher()
    private Method watcherSetMainHandMethod;    // PlayerWatcher.setItemInMainHand(ItemStack) OR similar
    private Method watcherSetItemInHandMethod;  // fallback name
    private int mirrorTaskId = -1;

    private final Set<UUID> trackedEntities = new HashSet<>();
    private final Map<UUID, ItemSignature> lastMainHandSignature = new HashMap<>();

    @Override
    public void onEnable() {
        disguiseAppliedKey = new NamespacedKey(this, "disguiseApplied");

        loadOrCreateConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        initLibsDisguisesReflection();

        if (!libsDisguisesAvailable) {
            getLogger().warning("LibsDisguises not found or not compatible. This plugin will do nothing until LibsDisguises is installed.");
            return;
        }

        startMirrorTaskIfEnabled();

        getLogger().info("Enabled with LibsDisguises support. Watching Nether piglins for disguise chances.");
    }

    @Override
    public void onDisable() {
        if (mirrorTaskId != -1) {
            Bukkit.getScheduler().cancelTask(mirrorTaskId);
            mirrorTaskId = -1;
        }
        trackedEntities.clear();
        lastMainHandSignature.clear();
    }

    private void loadOrCreateConfig() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        configFile = new File(getDataFolder(), "config.yml");
        config = YamlConfiguration.loadConfiguration(configFile);

        // Defaults
        setDefaultIfMissing("general.onlyNether", true);
        setDefaultIfMissing("general.hideNameTag", true);
        setDefaultIfMissing("general.reapplyOnChunkLoad", true);

        setDefaultIfMissing("mirror.enabled", true);
        setDefaultIfMissing("mirror.intervalTicks", 10);

        setDefaultIfMissing("piglin.enabled", true);
        setDefaultIfMissing("piglin.chancePercent", 35);
        setDefaultIfMissing("piglin.skinName", "MHF_Pig");

        setDefaultIfMissing("zombifiedPiglin.enabled", true);
        setDefaultIfMissing("zombifiedPiglin.chancePercent", 50);
        setDefaultIfMissing("zombifiedPiglin.skinName", "MHF_PigZombie");
        setDefaultIfMissing("zombifiedPiglin.forceGoldSword", true);

        setDefaultIfMissing("piglinBrute.enabled", true);
        setDefaultIfMissing("piglinBrute.chancePercent", 60);
        setDefaultIfMissing("piglinBrute.skinName", "XaPhobia");
        setDefaultIfMissing("piglinBrute.forceGoldSword", true);

        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save config.yml: " + e.getMessage());
        }
    }

    private void setDefaultIfMissing(String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }

    private void initLibsDisguisesReflection() {
        Plugin ld = Bukkit.getPluginManager().getPlugin("LibsDisguises");
        if (ld == null) {
            libsDisguisesAvailable = false;
            return;
        }

        try {
            disguiseApiClass = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            playerDisguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");

            playerDisguiseCtor = playerDisguiseClass.getConstructor(String.class);

            // DisguiseAPI.disguiseToAll(Entity, Disguise)
            for (Method method : disguiseApiClass.getMethods()) {
                if (method.getName().equals("disguiseToAll") && method.getParameterCount() == 2) {
                    disguiseToAllMethod = method;
                    break;
                }
            }
            if (disguiseToAllMethod == null) {
                throw new NoSuchMethodException("DisguiseAPI.disguiseToAll(Entity, Disguise) not found");
            }

            isDisguisedMethod = disguiseApiClass.getMethod("isDisguised", Entity.class);

            // DisguiseAPI.getDisguise(Entity)
            getDisguiseMethod = null;
            for (Method method : disguiseApiClass.getMethods()) {
                if (method.getName().equals("getDisguise") && method.getParameterCount() == 1) {
                    Class<?> paramType = method.getParameterTypes()[0];
                    if (Entity.class.isAssignableFrom(paramType)) {
                        getDisguiseMethod = method;
                        break;
                    }
                }
            }
            // Not fatal if missing; mirroring will simply not run.

            // PlayerDisguise#setSkin(String)
            try {
                setSkinMethod = playerDisguiseClass.getMethod("setSkin", String.class);
            } catch (NoSuchMethodException ignored) {
                setSkinMethod = findFirstMethod(playerDisguiseClass, new String[]{"setSkinName", "setSkinPlayer"}, String.class);
            }

            // PlayerDisguise#setNameVisible(boolean)
            try {
                setNameVisibleMethod = playerDisguiseClass.getMethod("setNameVisible", boolean.class);
            } catch (NoSuchMethodException ignored) {
                setNameVisibleMethod = null;
            }

            // Prepare watcher reflection (Disguise.getWatcher().setItemInMainHand(ItemStack))
            disguiseGetWatcherMethod = null;
            watcherSetMainHandMethod = null;
            watcherSetItemInHandMethod = null;

            libsDisguisesAvailable = true;
        } catch (Throwable t) {
            libsDisguisesAvailable = false;
            getLogger().warning("LibsDisguises detected but reflection init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private Method findFirstMethod(Class<?> targetClass, String[] methodNames, Class<?>... paramTypes) {
        for (String methodName : methodNames) {
            try {
                return targetClass.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {
                // try next
            }
        }
        return null;
    }

    private void startMirrorTaskIfEnabled() {
        if (!config.getBoolean("mirror.enabled", true)) {
            return;
        }
        if (getDisguiseMethod == null) {
            getLogger().warning("LibsDisguises getDisguise(Entity) not found; cannot mirror main-hand items.");
            return;
        }

        int intervalTicks = Math.max(1, config.getInt("mirror.intervalTicks", 10));

        mirrorTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (trackedEntities.isEmpty()) {
                return;
            }

            // Iterate a snapshot to avoid concurrent modification if entities get added during loop
            UUID[] entityIds = trackedEntities.toArray(new UUID[0]);

            for (UUID uuid : entityIds) {
                Entity entity = Bukkit.getEntity(uuid);
                if (!(entity instanceof LivingEntity living) || entity.isDead() || !entity.isValid()) {
                    trackedEntities.remove(uuid);
                    lastMainHandSignature.remove(uuid);
                    continue;
                }

                if (config.getBoolean("general.onlyNether", true)) {
                    if (living.getWorld().getEnvironment() != World.Environment.NETHER) {
                        // If entity moved worlds (unlikely), stop tracking
                        trackedEntities.remove(uuid);
                        lastMainHandSignature.remove(uuid);
                        continue;
                    }
                }

                if (!isEntityDisguised(living)) {
                    // Still track it (chunk-load handler will reapply), but no watcher updates now
                    continue;
                }

                EntityEquipment equipment = living.getEquipment();
                if (equipment == null) {
                    continue;
                }

                ItemStack mainHand = equipment.getItemInMainHand();
                ItemSignature signatureNow = ItemSignature.fromItem(mainHand);

                ItemSignature signaturePrev = lastMainHandSignature.get(uuid);
                if (signatureNow.equals(signaturePrev)) {
                    continue; // no change; do nothing
                }

                if (mirrorDisguiseMainHand(living, mainHand)) {
                    lastMainHandSignature.put(uuid, signatureNow);
                }
            }

        }, intervalTicks, intervalTicks);
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!libsDisguisesAvailable) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob)) {
            return;
        }

        if (config.getBoolean("general.onlyNether", true)) {
            if (entity.getWorld().getEnvironment() != World.Environment.NETHER) {
                return;
            }
        }

        EntityType type = entity.getType();

        // Skip babies for piglin/zombified piglin (brutes have no baby variant)
        if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBaby(entity)) {
            return;
        }

        if (type == EntityType.PIGLIN) {
            maybeDisguise(entity, "piglin");
        } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
            maybeDisguise(entity, "zombifiedPiglin");
            maybeForceGoldSword(entity, "zombifiedPiglin.forceGoldSword");
        } else if (type == EntityType.PIGLIN_BRUTE) {
            maybeDisguise(entity, "piglinBrute");
            maybeForceGoldSword(entity, "piglinBrute.forceGoldSword");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!libsDisguisesAvailable) {
            return;
        }
        if (!config.getBoolean("general.reapplyOnChunkLoad", true)) {
            return;
        }
        if (config.getBoolean("general.onlyNether", true)) {
            if (event.getWorld().getEnvironment() != World.Environment.NETHER) {
                return;
            }
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Mob)) {
                continue;
            }

            PersistentDataContainer data = entity.getPersistentDataContainer();
            boolean wasApplied = data.has(disguiseAppliedKey, PersistentDataType.BYTE)
                    && data.get(disguiseAppliedKey, PersistentDataType.BYTE) != null
                    && data.get(disguiseAppliedKey, PersistentDataType.BYTE) == (byte) 1;

            if (!wasApplied) {
                continue;
            }

            // Track it for mirroring (safe even if mirroring disabled; loop is cheap)
            trackedEntities.add(entity.getUniqueId());

            if (!isEntityDisguised(entity)) {
                EntityType type = entity.getType();

                if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBaby(entity)) {
                    continue;
                }

                if (type == EntityType.PIGLIN) {
                    forceDisguise(entity, "piglin");
                } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
                    forceDisguise(entity, "zombifiedPiglin");
                } else if (type == EntityType.PIGLIN_BRUTE) {
                    forceDisguise(entity, "piglinBrute");
                }
            }
        }
    }

    private boolean isBaby(Entity entity) {
        if (entity instanceof Ageable ageable) {
            return !ageable.isAdult();
        }
        return false;
    }

    private void maybeDisguise(Entity entity, String section) {
        if (!config.getBoolean(section + ".enabled", true)) {
            return;
        }

        int chance = clampPercent(config.getInt(section + ".chancePercent", 0));
        if (chance <= 0) {
            return;
        }

        if (chance < 100) {
            int roll = rng.nextInt(100) + 1; // 1..100
            if (roll > chance) {
                return;
            }
        }

        forceDisguise(entity, section);
    }

    private void forceDisguise(Entity entity, String section) {
        String skinName = config.getString(section + ".skinName", "MHF_PigZombie");
        if (skinName == null || skinName.trim().isEmpty()) {
            skinName = "MHF_PigZombie";
        }
        skinName = skinName.trim();

        try {
            Object disguise = playerDisguiseCtor.newInstance("Pigman");

            if (setSkinMethod != null) {
                setSkinMethod.invoke(disguise, skinName);
            } else {
                getLogger().warning("Could not find a compatible setSkin method in LibsDisguises. Skin may not apply.");
            }

            if (config.getBoolean("general.hideNameTag", true) && setNameVisibleMethod != null) {
                try {
                    setNameVisibleMethod.invoke(disguise, false);
                } catch (Throwable ignored) {
                    // ignore
                }
            }

            try {
                entity.setCustomName(null);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                entity.setCustomNameVisible(false);
            } catch (Throwable ignored) {
                // ignore
            }

            disguiseToAllMethod.invoke(null, entity, disguise);

            entity.getPersistentDataContainer().set(disguiseAppliedKey, PersistentDataType.BYTE, (byte) 1);

            // Track for mirroring
            trackedEntities.add(entity.getUniqueId());
            lastMainHandSignature.remove(entity.getUniqueId()); // force initial sync

        } catch (Throwable t) {
            getLogger().warning("Failed to apply disguise: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private boolean isEntityDisguised(Entity entity) {
        try {
            Object result = isDisguisedMethod.invoke(null, entity);
            if (result instanceof Boolean boolVal) {
                return boolVal;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    private void maybeForceGoldSword(Entity entity, String configPath) {
        if (!config.getBoolean(configPath, true)) {
            return;
        }

        PersistentDataContainer data = entity.getPersistentDataContainer();
        boolean wasApplied = data.has(disguiseAppliedKey, PersistentDataType.BYTE)
                && data.get(disguiseAppliedKey, PersistentDataType.BYTE) != null
                && data.get(disguiseAppliedKey, PersistentDataType.BYTE) == (byte) 1;

        if (!wasApplied) {
            return;
        }

        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }

        equipment.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD, 1));
        lastMainHandSignature.remove(entity.getUniqueId()); // force sync after we change it
    }

    private boolean mirrorDisguiseMainHand(Entity entity, ItemStack mainHand) {
        try {
            Object disguise = getDisguiseMethod.invoke(null, entity);
            if (disguise == null) {
                return false;
            }

            // Resolve Disguise#getWatcher and watcher methods lazily (once)
            if (disguiseGetWatcherMethod == null) {
                disguiseGetWatcherMethod = disguise.getClass().getMethod("getWatcher");
            }
            Object watcher = disguiseGetWatcherMethod.invoke(disguise);
            if (watcher == null) {
                return false;
            }

            if (watcherSetMainHandMethod == null && watcherSetItemInHandMethod == null) {
                // Try common watcher method names
                watcherSetMainHandMethod = findFirstMethod(watcher.getClass(),
                        new String[]{"setItemInMainHand", "setMainHand"},
                        ItemStack.class);

                watcherSetItemInHandMethod = findFirstMethod(watcher.getClass(),
                        new String[]{"setItemInHand", "setItemInHandMain"},
                        ItemStack.class);
            }

            ItemStack safeCopy = (mainHand == null) ? null : mainHand.clone();

            if (watcherSetMainHandMethod != null) {
                watcherSetMainHandMethod.invoke(watcher, safeCopy);
                return true;
            }
            if (watcherSetItemInHandMethod != null) {
                watcherSetItemInHandMethod.invoke(watcher, safeCopy);
                return true;
            }

            // If neither exists, we can't mirror on this LD build
            return false;

        } catch (Throwable ignored) {
            return false;
        }
    }

    private int clampPercent(int value) {
        if (value < 0) return 0;
        return Math.min(value, 100);
    }

    private static final class ItemSignature {
        private final Material material;
        private final int damage;
        private final int customModelData;
        private final boolean hasMeta;

        private ItemSignature(Material material, int damage, int customModelData, boolean hasMeta) {
            this.material = material;
            this.damage = damage;
            this.customModelData = customModelData;
            this.hasMeta = hasMeta;
        }

        static ItemSignature fromItem(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) {
                return new ItemSignature(Material.AIR, 0, 0, false);
            }

            int dmg = 0;
            int cmd = 0;
            boolean metaFlag = false;

            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                metaFlag = true;
                if (meta instanceof Damageable damageable) {
                    dmg = damageable.getDamage();
                }
                if (meta.hasCustomModelData()) {
                    cmd = meta.getCustomModelData();
                }
            }

            return new ItemSignature(item.getType(), dmg, cmd, metaFlag);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ItemSignature sig)) {
                return false;
            }
            return sig.material == material
                    && sig.damage == damage
                    && sig.customModelData == customModelData
                    && sig.hasMeta == hasMeta;
        }

        @Override
        public int hashCode() {
            int result = material != null ? material.hashCode() : 0;
            result = 31 * result + damage;
            result = 31 * result + customModelData;
            result = 31 * result + (hasMeta ? 1 : 0);
            return result;
        }
    }
}
