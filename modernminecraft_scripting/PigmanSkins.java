package com.redchanit.pigmanskins;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PigmanSkins extends JavaPlugin implements Listener {

    private final Random rng = new Random();

    private NamespacedKey disguiseAppliedKey;
    private NamespacedKey disguiseCheckedKey;

    private File configFile;
    private FileConfiguration config;

    // LibsDisguises reflection
    private boolean libsDisguisesAvailable = false;
    private Class<?> disguiseApiClass;
    private Class<?> playerDisguiseClass;
    private Method disguiseToAllMethod;
    private Method isDisguisedMethod;
    private Method setSkinMethod;
    private Method setNameVisibleMethod;
    private Constructor<?> playerDisguiseCtor;

    // Mirroring (LibsDisguises watcher reflection)
    private Method getDisguiseMethod;
    private Method disguiseGetWatcherMethod;
    private Method watcherSetMainHandMethod;
    private Method watcherSetItemInHandMethod;
    private int mirrorTaskId = -1;

    // Ambient sound emitter
    private int ambientSoundTaskId = -1;

    // ProtocolLib sound fix
    private boolean protocolLibAvailable = false;
    private ProtocolManager protocolManager;
    private final Map<UUID, Long> lastHurtSoundMs = new ConcurrentHashMap<>();

    // Track only entities we actually disguised
    private final Set<UUID> trackedEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ItemSignature> lastMainHandSignature = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        disguiseAppliedKey = new NamespacedKey(this, "disguiseApplied");
        disguiseCheckedKey = new NamespacedKey(this, "disguiseChecked");

        loadOrCreateConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        initLibsDisguisesReflection();

        if (!libsDisguisesAvailable) {
            getLogger().warning("LibsDisguises not found or not compatible. This plugin will do nothing until LibsDisguises is installed.");
            return;
        }

        initProtocolLibSoundFix();
        startMirrorTaskIfEnabled();
        startAmbientSoundTaskIfEnabled();

        getLogger().info("Enabled. LibsDisguises=" + libsDisguisesAvailable + ", ProtocolLib=" + protocolLibAvailable);
    }

    @Override
    public void onDisable() {
        if (mirrorTaskId != -1) {
            Bukkit.getScheduler().cancelTask(mirrorTaskId);
            mirrorTaskId = -1;
        }
        if (ambientSoundTaskId != -1) {
            Bukkit.getScheduler().cancelTask(ambientSoundTaskId);
            ambientSoundTaskId = -1;
        }
        trackedEntities.clear();
        lastMainHandSignature.clear();
        lastHurtSoundMs.clear();
    }

    /* ------------------------------------------------------------ */
    /* Config                                                       */
    /* ------------------------------------------------------------ */

    private void loadOrCreateConfig() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        configFile = new File(getDataFolder(), "config.yml");
        config = YamlConfiguration.loadConfiguration(configFile);

        // General
        setDefaultIfMissing("general.onlyNether", true);
        setDefaultIfMissing("general.hideNameTag", true);
        setDefaultIfMissing("general.reapplyOnChunkLoad", true);

        // Mirroring
        setDefaultIfMissing("mirror.enabled", true);
        setDefaultIfMissing("mirror.intervalTicks", 10);

        // Sounds
        setDefaultIfMissing("sounds.enabled", true);

        // Ambient (semi-frequent, but noticeable)
        setDefaultIfMissing("sounds.ambient.enabled", true);
        setDefaultIfMissing("sounds.ambient.intervalTicks", 40);    // every 2s check
        setDefaultIfMissing("sounds.ambient.chancePercent", 18);    // a bit higher so you actually hear them
        setDefaultIfMissing("sounds.ambient.radius", 24);
        setDefaultIfMissing("sounds.ambient.volume", 1.0);
        setDefaultIfMissing("sounds.ambient.pitchMin", 0.9);
        setDefaultIfMissing("sounds.ambient.pitchMax", 1.1);

        // Hurt sound fix (swap player hurt -> mob hurt)
        setDefaultIfMissing("sounds.hurtFix.enabled", true);
        setDefaultIfMissing("sounds.hurtFix.radius", 24);
        setDefaultIfMissing("sounds.hurtFix.volume", 1.0);
        setDefaultIfMissing("sounds.hurtFix.pitchMin", 0.9);
        setDefaultIfMissing("sounds.hurtFix.pitchMax", 1.1);
        setDefaultIfMissing("sounds.hurtFix.cooldownMs", 180);

        // Piglin disguise config
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

    /* ------------------------------------------------------------ */
    /* LibsDisguises reflection                                     */
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

            playerDisguiseCtor = playerDisguiseClass.getConstructor(String.class);

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

            try {
                setSkinMethod = playerDisguiseClass.getMethod("setSkin", String.class);
            } catch (NoSuchMethodException ignored) {
                setSkinMethod = findFirstMethod(playerDisguiseClass, new String[]{"setSkinName", "setSkinPlayer"}, String.class);
            }

            try {
                setNameVisibleMethod = playerDisguiseClass.getMethod("setNameVisible", boolean.class);
            } catch (NoSuchMethodException ignored) {
                setNameVisibleMethod = null;
            }

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
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private boolean isEntityDisguised(Entity entity) {
        try {
            Object result = isDisguisedMethod.invoke(null, entity);
            if (result instanceof Boolean boolVal) {
                return boolVal;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /* ------------------------------------------------------------ */
    /* ProtocolLib hurt-sound fix                                   */
    /* ------------------------------------------------------------ */

    private void initProtocolLibSoundFix() {
        if (!config.getBoolean("sounds.enabled", true) || !config.getBoolean("sounds.hurtFix.enabled", true)) {
            protocolLibAvailable = false;
            return;
        }

        Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null) {
            protocolLibAvailable = false;
            getLogger().warning("ProtocolLib not found; cannot swap player hurt sounds to mob hurt sounds. (Ambient emitter will still work.)");
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolLibAvailable = true;

        List<PacketType> packetTypes = new ArrayList<>();
        // This one exists on essentially all ProtocolLib builds
        packetTypes.add(PacketType.Play.Server.NAMED_SOUND_EFFECT);

        // Optionally include ENTITY_SOUND / SOUND_EFFECT if present (avoids compile-time missing symbols)
        PacketType maybeEntitySound = getServerPacketTypeByFieldName("ENTITY_SOUND");
        if (maybeEntitySound != null) {
            packetTypes.add(maybeEntitySound);
        }
        PacketType maybeSoundEffect = getServerPacketTypeByFieldName("SOUND_EFFECT");
        if (maybeSoundEffect != null) {
            packetTypes.add(maybeSoundEffect);
        }

        PacketType[] listenTypes = packetTypes.toArray(new PacketType[0]);

        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, listenTypes) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!config.getBoolean("sounds.enabled", true) || !config.getBoolean("sounds.hurtFix.enabled", true)) {
                    return;
                }

                String soundKey = readSoundKeyLower(event);
                if (soundKey == null) {
                    return;
                }

                if (!isPlayerHurtSound(soundKey)) {
                    return;
                }

                LivingEntity source = resolveSoundSourceEntity(event);
                if (source == null) {
                    return;
                }

                EntityType type = source.getType();
                if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                    return;
                }
                if (!hasByteFlag(source, disguiseAppliedKey, (byte) 1)) {
                    return;
                }

                // Cooldown to prevent spam/doubles
                long now = System.currentTimeMillis();
                long cooldownMs = Math.max(0, config.getLong("sounds.hurtFix.cooldownMs", 180));
                Long last = lastHurtSoundMs.get(source.getUniqueId());
                if (last != null && (now - last) < cooldownMs) {
                    event.setCancelled(true);
                    return;
                }
                lastHurtSoundMs.put(source.getUniqueId(), now);

                // Cancel the player hurt sound, play mob hurt instead
                event.setCancelled(true);
                playMobHurtSound(source);
            }
        });

        getLogger().info("ProtocolLib hurt-sound fix enabled (player hurt -> mob hurt). Listening packets=" + packetTypes.size());
    }

    private PacketType getServerPacketTypeByFieldName(String fieldName) {
        try {
            Class<?> serverClass = PacketType.Play.Server.class;
            Field field = serverClass.getField(fieldName);
            Object val = field.get(null);
            if (val instanceof PacketType pt) {
                return pt;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String readSoundKeyLower(PacketEvent event) {
        try {
            // Named sound usually exposes string key
            if (event.getPacket().getStrings().size() > 0) {
                String key = event.getPacket().getStrings().read(0);
                if (key != null) {
                    return key.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: toString of sound object if available on this ProtocolLib build
        try {
            Object soundObj = event.getPacket().getModifier().readSafely(0);
            if (soundObj != null) {
                return soundObj.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean isPlayerHurtSound(String soundKeyLower) {
        return soundKeyLower.contains("entity.player.hurt")
                || soundKeyLower.contains("player.hurt")
                || soundKeyLower.contains("entity.player.hurt_")
                || soundKeyLower.contains("player_hurt");
    }

    /**
     * Resolve the sound source entity as best as possible.
     * - If it's an entity-sound packet and includes entityId, match it against tracked entities.
     * - Else use the sound packet location and find nearest tracked entity.
     */
    private LivingEntity resolveSoundSourceEntity(PacketEvent event) {
        // Try entityId route first (works for ENTITY_SOUND on many builds)
        Integer entityId = tryReadEntityId(event);
        if (entityId != null) {
            LivingEntity match = findTrackedByEntityId(event.getPlayer().getWorld(), entityId);
            if (match != null) {
                return match;
            }
        }

        // Fallback: location route (works for NAMED_SOUND_EFFECT)
        SoundLocation loc = tryReadSoundLocation(event);
        if (loc == null || loc.world == null) {
            return null;
        }
        return findTrackedLivingEntityNear(loc.world, loc.x, loc.y, loc.z, 2.5);
    }

    private Integer tryReadEntityId(PacketEvent event) {
        try {
            // Many entity sound packets store entity id as first integer
            if (event.getPacket().getIntegers().size() > 0) {
                return event.getPacket().getIntegers().read(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private LivingEntity findTrackedByEntityId(World world, int entityId) {
        for (UUID id : trackedEntities) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (living.getWorld() != world) {
                continue;
            }
            if (living.getEntityId() == entityId) {
                return living;
            }
        }
        return null;
    }

    private SoundLocation tryReadSoundLocation(PacketEvent event) {
        try {
            // Named sound packets typically have x/y/z ints scaled by 8
            if (event.getPacket().getIntegers().size() >= 3) {
                int x = event.getPacket().getIntegers().read(0);
                int y = event.getPacket().getIntegers().read(1);
                int z = event.getPacket().getIntegers().read(2);
                return new SoundLocation(event.getPlayer().getWorld(), x / 8.0, y / 8.0, z / 8.0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private LivingEntity findTrackedLivingEntityNear(World world, double x, double y, double z, double radius) {
        double radiusSq = radius * radius;

        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (UUID id : trackedEntities) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (living.getWorld() != world) {
                continue;
            }
            double dx = living.getLocation().getX() - x;
            double dy = living.getLocation().getY() - y;
            double dz = living.getLocation().getZ() - z;
            double distSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distSq <= radiusSq && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = living;
            }
        }

        return best;
    }

    private void playMobHurtSound(LivingEntity entity) {
        double radius = Math.max(1.0, config.getDouble("sounds.hurtFix.radius", 24.0));
        float volume = (float) Math.max(0.0, config.getDouble("sounds.hurtFix.volume", 1.0));
        double pitchMin = config.getDouble("sounds.hurtFix.pitchMin", 0.9);
        double pitchMax = config.getDouble("sounds.hurtFix.pitchMax", 1.1);
        if (pitchMax < pitchMin) {
            double temp = pitchMin;
            pitchMin = pitchMax;
            pitchMax = temp;
        }
        float pitch = (float) (pitchMin + rng.nextDouble() * (pitchMax - pitchMin));

        Sound sound = switch (entity.getType()) {
            case ZOMBIFIED_PIGLIN -> Sound.ENTITY_ZOMBIFIED_PIGLIN_HURT;
            case PIGLIN -> Sound.ENTITY_PIGLIN_HURT;
            case PIGLIN_BRUTE -> Sound.ENTITY_PIGLIN_BRUTE_HURT;
            default -> null;
        };
        if (sound == null) {
            return;
        }

        double radiusSq = radius * radius;
        for (var player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entity.getLocation()) <= radiusSq) {
                player.playSound(entity.getLocation(), sound, volume, pitch);
            }
        }
    }

    private static final class SoundLocation {
        final World world;
        final double x;
        final double y;
        final double z;

        SoundLocation(World world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /* ------------------------------------------------------------ */
    /* Ambient sound emitter                                         */
    /* ------------------------------------------------------------ */

    private void startAmbientSoundTaskIfEnabled() {
        if (!config.getBoolean("sounds.enabled", true) || !config.getBoolean("sounds.ambient.enabled", true)) {
            return;
        }

        int intervalTicks = Math.max(1, config.getInt("sounds.ambient.intervalTicks", 40));
        int chancePercent = clampPercent(config.getInt("sounds.ambient.chancePercent", 18));
        double radius = Math.max(1.0, config.getDouble("sounds.ambient.radius", 24.0));
        float volume = (float) Math.max(0.0, config.getDouble("sounds.ambient.volume", 1.0));
        double pitchMin = config.getDouble("sounds.ambient.pitchMin", 0.9);
        double pitchMax = config.getDouble("sounds.ambient.pitchMax", 1.1);
        if (pitchMax < pitchMin) {
            double temp = pitchMin;
            pitchMin = pitchMax;
            pitchMax = temp;
        }

        final double radiusSquared = radius * radius;
        final int chanceFinal = chancePercent;
        final double pitchMinFinal = pitchMin;
        final double pitchMaxFinal = pitchMax;

        ambientSoundTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (trackedEntities.isEmpty()) {
                return;
            }

            UUID[] ids = trackedEntities.toArray(new UUID[0]);

            for (UUID id : ids) {
                Entity e = Bukkit.getEntity(id);
                if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                    continue;
                }

                if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) {
                    continue;
                }

                EntityType type = living.getType();
                if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                    continue;
                }

                if (config.getBoolean("general.onlyNether", true)) {
                    if (living.getWorld().getEnvironment() != World.Environment.NETHER) {
                        continue;
                    }
                }

                if (chanceFinal < 100) {
                    int roll = rng.nextInt(100) + 1;
                    if (roll > chanceFinal) {
                        continue;
                    }
                }

                Sound ambientSound = switch (type) {
                    case ZOMBIFIED_PIGLIN -> Sound.ENTITY_ZOMBIFIED_PIGLIN_AMBIENT;
                    case PIGLIN -> Sound.ENTITY_PIGLIN_AMBIENT;
                    case PIGLIN_BRUTE -> Sound.ENTITY_PIGLIN_BRUTE_AMBIENT;
                    default -> null;
                };

                if (ambientSound == null) {
                    continue;
                }

                float pitch = (float) (pitchMinFinal + rng.nextDouble() * (pitchMaxFinal - pitchMinFinal));

                for (var player : living.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(living.getLocation()) <= radiusSquared) {
                        player.playSound(living.getLocation(), ambientSound, volume, pitch);
                    }
                }
            }
        }, intervalTicks, intervalTicks);
    }

    /* ------------------------------------------------------------ */
    /* Mirroring main-hand                                           */
    /* ------------------------------------------------------------ */

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

            UUID[] entityIds = trackedEntities.toArray(new UUID[0]);

            for (UUID entityId : entityIds) {
                Entity entity = Bukkit.getEntity(entityId);
                if (!(entity instanceof LivingEntity living) || entity.isDead() || !entity.isValid()) {
                    trackedEntities.remove(entityId);
                    lastMainHandSignature.remove(entityId);
                    continue;
                }

                if (config.getBoolean("general.onlyNether", true)) {
                    if (living.getWorld().getEnvironment() != World.Environment.NETHER) {
                        trackedEntities.remove(entityId);
                        lastMainHandSignature.remove(entityId);
                        continue;
                    }
                }

                if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) {
                    trackedEntities.remove(entityId);
                    lastMainHandSignature.remove(entityId);
                    continue;
                }

                if (!isEntityDisguised(living)) {
                    continue;
                }

                EntityEquipment equipment = living.getEquipment();
                if (equipment == null) {
                    continue;
                }

                ItemStack mainHand = equipment.getItemInMainHand();
                ItemSignature signatureNow = ItemSignature.fromItem(mainHand);

                ItemSignature signaturePrev = lastMainHandSignature.get(entityId);
                if (signatureNow.equals(signaturePrev)) {
                    continue;
                }

                if (mirrorDisguiseMainHand(living, mainHand)) {
                    lastMainHandSignature.put(entityId, signatureNow);
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private boolean mirrorDisguiseMainHand(Entity entity, ItemStack mainHand) {
        try {
            Object disguise = getDisguiseMethod.invoke(null, entity);
            if (disguise == null) {
                return false;
            }

            if (disguiseGetWatcherMethod == null) {
                disguiseGetWatcherMethod = disguise.getClass().getMethod("getWatcher");
            }
            Object watcher = disguiseGetWatcherMethod.invoke(disguise);
            if (watcher == null) {
                return false;
            }

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
                return true;
            }
            if (watcherSetItemInHandMethod != null) {
                watcherSetItemInHandMethod.invoke(watcher, safeCopy);
                return true;
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------------------------------------ */
    /* Spawn + chunk-load logic (catch existing + persist)           */
    /* ------------------------------------------------------------ */

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

        if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBaby(entity)) {
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

            EntityType type = entity.getType();
            if (type != EntityType.PIGLIN && type != EntityType.ZOMBIFIED_PIGLIN && type != EntityType.PIGLIN_BRUTE) {
                continue;
            }

            if ((type == EntityType.PIGLIN || type == EntityType.ZOMBIFIED_PIGLIN) && isBaby(entity)) {
                continue;
            }

            if (hasByteFlag(entity, disguiseAppliedKey, (byte) 1)) {
                trackedEntities.add(entity.getUniqueId());

                if (!isEntityDisguised(entity)) {
                    if (type == EntityType.PIGLIN) {
                        forceDisguise(entity, "piglin");
                    } else if (type == EntityType.ZOMBIFIED_PIGLIN) {
                        forceDisguise(entity, "zombifiedPiglin");
                        maybeForceGoldSword(entity, "zombifiedPiglin.forceGoldSword");
                    } else if (type == EntityType.PIGLIN_BRUTE) {
                        forceDisguise(entity, "piglinBrute");
                        maybeForceGoldSword(entity, "piglinBrute.forceGoldSword");
                    }
                }
                continue;
            }

            if (hasByteFlag(entity, disguiseCheckedKey, (byte) 1)) {
                continue;
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
    }

    private boolean isBaby(Entity entity) {
        if (entity instanceof Ageable ageable) {
            return !ageable.isAdult();
        }
        return false;
    }

    private void maybeDisguiseAndMarkChecked(Entity entity, String section) {
        setByteFlag(entity, disguiseCheckedKey, (byte) 1);

        if (!config.getBoolean(section + ".enabled", true)) {
            return;
        }

        int chance = clampPercent(config.getInt(section + ".chancePercent", 0));
        if (chance <= 0) {
            return;
        }

        if (chance < 100) {
            int roll = rng.nextInt(100) + 1;
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
            }

            if (config.getBoolean("general.hideNameTag", true) && setNameVisibleMethod != null) {
                try {
                    setNameVisibleMethod.invoke(disguise, false);
                } catch (Throwable ignored) {}
            }

            try { entity.setCustomName(null); } catch (Throwable ignored) {}
            try { entity.setCustomNameVisible(false); } catch (Throwable ignored) {}

            disguiseToAllMethod.invoke(null, entity, disguise);

            setByteFlag(entity, disguiseAppliedKey, (byte) 1);
            setByteFlag(entity, disguiseCheckedKey, (byte) 1);

            trackedEntities.add(entity.getUniqueId());
            lastMainHandSignature.remove(entity.getUniqueId());

        } catch (Throwable t) {
            getLogger().warning("Failed to apply disguise: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void maybeForceGoldSword(Entity entity, String configPath) {
        if (!config.getBoolean(configPath, true)) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (!hasByteFlag(living, disguiseAppliedKey, (byte) 1)) {
            return;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD, 1));
        lastMainHandSignature.remove(entity.getUniqueId());
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

    private int clampPercent(int value) {
        if (value < 0) return 0;
        return Math.min(value, 100);
    }

    /* ------------------------------------------------------------ */
    /* Item signature (mirroring)                                    */
    /* ------------------------------------------------------------ */

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
