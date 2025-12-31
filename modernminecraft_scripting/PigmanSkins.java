package com.redchanit.pigmanskins;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PigmanSkins extends JavaPlugin implements Listener {

    private final Random rng = new Random();

    private NamespacedKey disguiseAppliedKey;
    private NamespacedKey disguiseCheckedKey;

    // --- LibsDisguises reflection (NO compile-time dependency) ---
    private boolean libsDisguisesAvailable = false;
    private Class<?> disguiseApiClass;
    private Class<?> playerDisguiseClass;
    private Method disguiseToAllMethod;
    private Method isDisguisedMethod;
    private Method getDisguiseMethod;
    private Method setSkinMethod;
    private Method setNameVisibleMethod;
    private Constructor<?> playerDisguiseCtor;

    // Watcher reflection for mirroring
    private Method disguiseGetWatcherMethod;
    private Method watcherSetMainHandMethod;
    private Method watcherSetItemInHandMethod;

    private int mirrorTaskId = -1;

    // --- Manual sound system ---
    private int ambientTaskId = -1;
    private final Map<UUID, Long> nextAmbientAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHurtAtMs = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        disguiseAppliedKey = new NamespacedKey(this, "disguiseApplied");
        disguiseCheckedKey = new NamespacedKey(this, "disguiseChecked");

        ensureConfigDefaults();
        Bukkit.getPluginManager().registerEvents(this, this);

        initLibsDisguisesReflection();
        if (!libsDisguisesAvailable) {
            getLogger().warning("LibsDisguises not found or incompatible. PigmanSkins will do nothing.");
            return;
        }

        startMirrorTaskIfEnabled();
        startManualSoundSystem();

        getLogger().info("PigmanSkins enabled.");
    }

    @Override
    public void onDisable() {
        if (mirrorTaskId != -1) {
            Bukkit.getScheduler().cancelTask(mirrorTaskId);
            mirrorTaskId = -1;
        }
        if (ambientTaskId != -1) {
            Bukkit.getScheduler().cancelTask(ambientTaskId);
            ambientTaskId = -1;
        }
        nextAmbientAtMs.clear();
        lastHurtAtMs.clear();
    }

    /* ------------------------------------------------------------ */
    /* Config defaults                                               */
    /* ------------------------------------------------------------ */

    private void ensureConfigDefaults() {
        FileConfiguration config = getConfig();

        setDefaultIfMissing(config, "general.onlyNether", true);
        setDefaultIfMissing(config, "general.hideNameTag", true);
        setDefaultIfMissing(config, "general.reapplyOnChunkLoad", true);

        setDefaultIfMissing(config, "mirror.enabled", true);
        setDefaultIfMissing(config, "mirror.intervalTicks", 10);

        setDefaultIfMissing(config, "piglin.enabled", true);
        setDefaultIfMissing(config, "piglin.chancePercent", 35);
        setDefaultIfMissing(config, "piglin.skinName", "MHF_Pig");

        setDefaultIfMissing(config, "zombifiedPiglin.enabled", true);
        setDefaultIfMissing(config, "zombifiedPiglin.chancePercent", 50);
        setDefaultIfMissing(config, "zombifiedPiglin.skinName", "MHF_PigZombie");
        setDefaultIfMissing(config, "zombifiedPiglin.forceGoldSword", true);

        setDefaultIfMissing(config, "piglinBrute.enabled", true);
        setDefaultIfMissing(config, "piglinBrute.chancePercent", 60);
        setDefaultIfMissing(config, "piglinBrute.skinName", "XaPhobia");
        setDefaultIfMissing(config, "piglinBrute.forceGoldSword", true);

        // Sounds (manual)
        setDefaultIfMissing(config, "sounds.enabled", true);

        setDefaultIfMissing(config, "sounds.ambient.enabled", true);
        setDefaultIfMissing(config, "sounds.ambient.minDelayTicks", 60);
        setDefaultIfMissing(config, "sounds.ambient.maxDelayTicks", 160);
        setDefaultIfMissing(config, "sounds.ambient.radius", 24.0);
        setDefaultIfMissing(config, "sounds.ambient.volume", 1.0);

        setDefaultIfMissing(config, "sounds.hurt.enabled", true);
        setDefaultIfMissing(config, "sounds.hurt.radius", 24.0);
        setDefaultIfMissing(config, "sounds.hurt.volume", 1.0);
        setDefaultIfMissing(config, "sounds.hurt.cooldownMs", 150);

        setDefaultIfMissing(config, "sounds.death.enabled", true);
        setDefaultIfMissing(config, "sounds.death.radius", 24.0);
        setDefaultIfMissing(config, "sounds.death.volume", 1.0);

        saveConfig();
    }

    private void setDefaultIfMissing(FileConfiguration config, String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }

    /* ------------------------------------------------------------ */
    /* LibsDisguises reflection                                      */
    /* ------------------------------------------------------------ */

    private void initLibsDisguisesReflection() {
        Plugin ld = Bukkit.getPluginManager().getPlugin("LibsDisguises");
        if (ld == null) {
            libsDisguisesAvailable = false;
            return;
        }

        try {
            disguiseApiClass = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            playerDisguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");

            // new PlayerDisguise(String disguiseName)
            playerDisguiseCtor = playerDisguiseClass.getConstructor(String.class);

            // DisguiseAPI.disguiseToAll(Entity, Disguise)
            disguiseToAllMethod = null;
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

            // DisguiseAPI.getDisguise(Entity) (optional; used for mirroring)
            getDisguiseMethod = null;
            for (Method method : disguiseApiClass.getMethods()) {
                if (method.getName().equals("getDisguise") && method.getParameterCount() == 1) {
                    Class<?> p0 = method.getParameterTypes()[0];
                    if (Entity.class.isAssignableFrom(p0)) {
                        getDisguiseMethod = method;
                        break;
                    }
                }
            }

            // PlayerDisguise.setSkin(String) or similar
            setSkinMethod = findFirstMethod(playerDisguiseClass,
                    new String[]{"setSkin", "setSkinName", "setSkinPlayer"},
                    String.class);

            // PlayerDisguise.setNameVisible(boolean) (optional)
            setNameVisibleMethod = null;
            try {
                setNameVisibleMethod = playerDisguiseClass.getMethod("setNameVisible", boolean.class);
            } catch (Throwable ignored) {}

            libsDisguisesAvailable = true;
        } catch (Throwable t) {
            libsDisguisesAvailable = false;
            getLogger().warning("LibsDisguises reflection init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private Method findFirstMethod(Class<?> targetClass, String[] methodNames, Class<?>... paramTypes) {
        for (String name : methodNames) {
            try {
                return targetClass.getMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private boolean isEntityDisguised(Entity entity) {
        try {
            Object result = isDisguisedMethod.invoke(null, entity);
            return (result instanceof Boolean b) && b;
        } catch (Throwable ignored) {}
        return false;
    }

    /* ------------------------------------------------------------ */
    /* Spawn + chunk-load disguise flow                              */
    /* ------------------------------------------------------------ */

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!libsDisguisesAvailable) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity)) return;

        if (getConfig().getBoolean("general.onlyNether", true)) {
            if (entity.getWorld().getEnvironment() != World.Environment.NETHER) return;
        }

        EntityType type = entity.getType();

        // skip baby piglins / baby zombified piglins (best-effort, reflection)
        if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBabyBestEffort(entity)) {
            return;
        }

        if (type == EntityType.PIGLIN) {
            maybeDisguiseAndMarkChecked(entity, "piglin");
        } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
            maybeDisguiseAndMarkChecked(entity, "zombifiedPiglin");
            maybeForceGoldSword(entity, "zombifiedPiglin.forceGoldSword");
        } else if (type == EntityType.PIGLIN_BRUTE) {
            maybeDisguiseAndMarkChecked(entity, "piglinBrute");
            maybeForceGoldSword(entity, "piglinBrute.forceGoldSword");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!libsDisguisesAvailable) return;
        if (!getConfig().getBoolean("general.reapplyOnChunkLoad", true)) return;

        if (getConfig().getBoolean("general.onlyNether", true)) {
            if (event.getWorld().getEnvironment() != World.Environment.NETHER) return;
        }

        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof LivingEntity)) continue;

            EntityType type = entity.getType();
            if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                continue;
            }

            if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBabyBestEffort(entity)) {
                continue;
            }

            // If already applied, ensure disguise exists after restart
            if (hasByteFlag(entity, disguiseAppliedKey, (byte) 1)) {
                if (!isEntityDisguised(entity)) {
                    if (type == EntityType.PIGLIN) {
                        forceApplyDisguise(entity, "piglin");
                    } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
                        forceApplyDisguise(entity, "zombifiedPiglin");
                        maybeForceGoldSword(entity, "zombifiedPiglin.forceGoldSword");
                    } else {
                        forceApplyDisguise(entity, "piglinBrute");
                        maybeForceGoldSword(entity, "piglinBrute.forceGoldSword");
                    }
                }
                continue;
            }

            // Not applied yet: if we've already checked, don't re-roll forever
            if (hasByteFlag(entity, disguiseCheckedKey, (byte) 1)) {
                continue;
            }

            // Convert existing mobs too (your requested feature)
            if (type == EntityType.PIGLIN) {
                maybeDisguiseAndMarkChecked(entity, "piglin");
            } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
                maybeDisguiseAndMarkChecked(entity, "zombifiedPiglin");
                maybeForceGoldSword(entity, "zombifiedPiglin.forceGoldSword");
            } else {
                maybeDisguiseAndMarkChecked(entity, "piglinBrute");
                maybeForceGoldSword(entity, "piglinBrute.forceGoldSword");
            }
        }
    }

    private void maybeDisguiseAndMarkChecked(Entity entity, String section) {
        setByteFlag(entity, disguiseCheckedKey, (byte) 1);

        if (!getConfig().getBoolean(section + ".enabled", true)) {
            return;
        }

        int chance = clampPercent(getConfig().getInt(section + ".chancePercent", 0));
        if (chance <= 0) return;

        if (chance < 100) {
            int roll = rng.nextInt(100) + 1;
            if (roll > chance) return;
        }

        forceApplyDisguise(entity, section);
    }

    private void forceApplyDisguise(Entity entity, String section) {
        String skinName = getConfig().getString(section + ".skinName", "MHF_PigZombie");
        if (skinName == null || skinName.trim().isEmpty()) skinName = "MHF_PigZombie";
        skinName = skinName.trim();

        try {
            Object disguise = playerDisguiseCtor.newInstance("Pigman");

            if (setSkinMethod != null) {
                try {
                    setSkinMethod.invoke(disguise, skinName);
                } catch (Throwable ignored) {}
            }

            if (getConfig().getBoolean("general.hideNameTag", true) && setNameVisibleMethod != null) {
                try {
                    setNameVisibleMethod.invoke(disguise, false);
                } catch (Throwable ignored) {}
            }

            // Also suppress Bukkit name tag
            try { entity.setCustomName(null); } catch (Throwable ignored) {}
            try { entity.setCustomNameVisible(false); } catch (Throwable ignored) {}

            disguiseToAllMethod.invoke(null, entity, disguise);

            setByteFlag(entity, disguiseAppliedKey, (byte) 1);
            setByteFlag(entity, disguiseCheckedKey, (byte) 1);

            // Reset sound scheduling so a newly disguised entity gets ambient soon-ish
            nextAmbientAtMs.remove(entity.getUniqueId());

        } catch (Throwable t) {
            getLogger().warning("Failed to apply disguise: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void maybeForceGoldSword(Entity entity, String configPath) {
        if (!getConfig().getBoolean(configPath, false)) return;
        if (!(entity instanceof LivingEntity living)) return;
        if (!hasByteFlag(entity, disguiseAppliedKey, (byte) 1)) return;

        EntityEquipment eq = living.getEquipment();
        if (eq == null) return;

        eq.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD, 1));
    }

    private int clampPercent(int value) {
        if (value < 0) return 0;
        return Math.min(value, 100);
    }

    private boolean isBabyBestEffort(Entity entity) {
        // Piglin and Zombified Piglin have isBaby on modern servers, but your API jar may not expose types.
        try {
            Method m = entity.getClass().getMethod("isBaby");
            Object result = m.invoke(entity);
            return (result instanceof Boolean b) && b;
        } catch (Throwable ignored) {}
        return false;
    }

    /* ------------------------------------------------------------ */
    /* Manual sounds (simple + reliable)                             */
    /* ------------------------------------------------------------ */

    private void startManualSoundSystem() {
        if (!getConfig().getBoolean("sounds.enabled", true)) return;

        // Ambient scheduler
        if (getConfig().getBoolean("sounds.ambient.enabled", true)) {
            ambientTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                boolean onlyNether = getConfig().getBoolean("general.onlyNether", true);

                int minDelayTicks = Math.max(10, getConfig().getInt("sounds.ambient.minDelayTicks", 60));
                int maxDelayTicks = Math.max(minDelayTicks, getConfig().getInt("sounds.ambient.maxDelayTicks", 160));

                double radius = Math.max(1.0, getConfig().getDouble("sounds.ambient.radius", 24.0));
                float volume = (float) Math.max(0.0, getConfig().getDouble("sounds.ambient.volume", 1.0));
                double radiusSq = radius * radius;

                long now = System.currentTimeMillis();

                for (World world : Bukkit.getWorlds()) {
                    if (onlyNether && world.getEnvironment() != World.Environment.NETHER) continue;

                    for (LivingEntity living : world.getLivingEntities()) {
                        EntityType type = living.getType();
                        if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                            continue;
                        }
                        if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) continue;

                        UUID id = living.getUniqueId();
                        long dueAt = nextAmbientAtMs.getOrDefault(id, 0L);
                        if (dueAt == 0L) {
                            nextAmbientAtMs.put(id, now + ticksToMs(randomInt(minDelayTicks, maxDelayTicks)));
                            continue;
                        }
                        if (now < dueAt) continue;

                        nextAmbientAtMs.put(id, now + ticksToMs(randomInt(minDelayTicks, maxDelayTicks)));

                        Sound ambient = switch (type) {
                            case ZOMBIFIED_PIGLIN -> Sound.ENTITY_ZOMBIFIED_PIGLIN_AMBIENT;
                            case PIGLIN -> Sound.ENTITY_PIGLIN_AMBIENT;
                            case PIGLIN_BRUTE -> Sound.ENTITY_PIGLIN_BRUTE_AMBIENT;
                            default -> null;
                        };
                        if (ambient == null) continue;

                        playSoundToNearbyPlayers(world, living.getLocation(), ambient, volume, 1.0f, radiusSq);
                    }
                }

                // Light pruning
                if (nextAmbientAtMs.size() > 5000) {
                    int pruned = 0;
                    Iterator<Map.Entry<UUID, Long>> it = nextAmbientAtMs.entrySet().iterator();
                    while (it.hasNext() && pruned < 500) {
                        Map.Entry<UUID, Long> entry = it.next();
                        Entity e = Bukkit.getEntity(entry.getKey());
                        if (e == null || !e.isValid()) it.remove();
                        pruned++;
                    }
                }
            }, 20L, 20L);
        }
    }

    @EventHandler
    public void onTaggedPigHurt(EntityDamageEvent event) {
        if (!getConfig().getBoolean("sounds.enabled", true) || !getConfig().getBoolean("sounds.hurt.enabled", true)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        EntityType type = living.getType();
        if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
            return;
        }
        if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) return;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, getConfig().getLong("sounds.hurt.cooldownMs", 150L));
        Long last = lastHurtAtMs.get(living.getUniqueId());
        if (last != null && (now - last) < cooldownMs) return;
        lastHurtAtMs.put(living.getUniqueId(), now);

        double radius = Math.max(1.0, getConfig().getDouble("sounds.hurt.radius", 24.0));
        float volume = (float) Math.max(0.0, getConfig().getDouble("sounds.hurt.volume", 1.0));
        double radiusSq = radius * radius;

        Sound hurt = switch (type) {
            case ZOMBIFIED_PIGLIN -> Sound.ENTITY_ZOMBIFIED_PIGLIN_HURT;
            case PIGLIN -> Sound.ENTITY_PIGLIN_HURT;
            case PIGLIN_BRUTE -> Sound.ENTITY_PIGLIN_BRUTE_HURT;
            default -> null;
        };
        if (hurt == null) return;

        playSoundToNearbyPlayers(living.getWorld(), living.getLocation(), hurt, volume, 1.0f, radiusSq);
    }

    @EventHandler
    public void onTaggedPigDeath(EntityDeathEvent event) {
        if (!getConfig().getBoolean("sounds.enabled", true) || !getConfig().getBoolean("sounds.death.enabled", true)) {
            return;
        }

        LivingEntity living = event.getEntity();

        EntityType type = living.getType();
        if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
            return;
        }
        if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) return;

        double radius = Math.max(1.0, getConfig().getDouble("sounds.death.radius", 24.0));
        float volume = (float) Math.max(0.0, getConfig().getDouble("sounds.death.volume", 1.0));
        double radiusSq = radius * radius;

        Sound death = switch (type) {
            case ZOMBIFIED_PIGLIN -> Sound.ENTITY_ZOMBIFIED_PIGLIN_DEATH;
            case PIGLIN -> Sound.ENTITY_PIGLIN_DEATH;
            case PIGLIN_BRUTE -> Sound.ENTITY_PIGLIN_BRUTE_DEATH;
            default -> null;
        };
        if (death == null) return;

        playSoundToNearbyPlayers(living.getWorld(), living.getLocation(), death, volume, 1.0f, radiusSq);
    }

    private void playSoundToNearbyPlayers(World world, org.bukkit.Location location, Sound sound, float volume, float pitch, double radiusSq) {
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSq) {
                player.playSound(location, sound, volume, pitch);
            }
        }
    }

    private int randomInt(int min, int max) {
        if (max <= min) return min;
        return min + rng.nextInt((max - min) + 1);
    }

    private long ticksToMs(int ticks) {
        return (long) ticks * 50L;
    }

    /* ------------------------------------------------------------ */
    /* Mirroring main-hand (reflection; lightweight)                  */
    /* ------------------------------------------------------------ */

    private void startMirrorTaskIfEnabled() {
        if (!getConfig().getBoolean("mirror.enabled", true)) return;
        if (getDisguiseMethod == null) {
            // Not fatal; mirroring just won't work
            return;
        }

        int intervalTicks = Math.max(1, getConfig().getInt("mirror.intervalTicks", 10));

        mirrorTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            // Scan Nether living entities; only touch ones we tagged
            boolean onlyNether = getConfig().getBoolean("general.onlyNether", true);

            for (World world : Bukkit.getWorlds()) {
                if (onlyNether && world.getEnvironment() != World.Environment.NETHER) continue;

                for (LivingEntity living : world.getLivingEntities()) {
                    EntityType type = living.getType();
                    if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                        continue;
                    }
                    if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) continue;

                    // Only if actually disguised
                    if (!isEntityDisguised(living)) continue;

                    EntityEquipment eq = living.getEquipment();
                    if (eq == null) continue;

                    ItemStack mainHand = eq.getItemInMainHand();
                    mirrorDisguiseMainHand(living, mainHand);
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private void mirrorDisguiseMainHand(Entity entity, ItemStack mainHand) {
        try {
            Object disguise = getDisguiseMethod.invoke(null, entity);
            if (disguise == null) return;

            if (disguiseGetWatcherMethod == null) {
                disguiseGetWatcherMethod = disguise.getClass().getMethod("getWatcher");
            }
            Object watcher = disguiseGetWatcherMethod.invoke(disguise);
            if (watcher == null) return;

            if (watcherSetMainHandMethod == null && watcherSetItemInHandMethod == null) {
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
            } else if (watcherSetItemInHandMethod != null) {
                watcherSetItemInHandMethod.invoke(watcher, safeCopy);
            }
        } catch (Throwable ignored) {}
    }

    /* ------------------------------------------------------------ */
    /* PDC helpers                                                   */
    /* ------------------------------------------------------------ */

    private boolean hasByteFlag(Entity entity, NamespacedKey key, byte expected) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        Byte val = data.get(key, PersistentDataType.BYTE);
        return val != null && val == expected;
    }

    private void setByteFlag(Entity entity, NamespacedKey key, byte value) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value);
    }
}
