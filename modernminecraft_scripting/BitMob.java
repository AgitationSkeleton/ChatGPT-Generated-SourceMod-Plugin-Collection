// File: BitMob.java
// Author: ChatGPT
//
// Spigot 1.21.10 plugin: "BitMob"
//
// Updated look + pets:
// - Uses ItemDisplay (3D item/block look) instead of BlockDisplay (fixes weird 2D feel).
// - Glowing is OFF by default, so it won’t show through walls.
// - “Smaller” look is achieved via ItemDisplay transform (no JOML/Transformation needed).
//
// Wild Bits:
// - /bit spawn, /bit list, /bit dismiss <id|uuid>
// - Chat within 12 blocks: flashes yellow/red for 1s, broadcasts "<Bit> Yes!/No!"
// - Idle particles (white), yes/no particles (yellow/red)
// - Bob + spin (yaw)
//
// Pet Bit (per-player, persistent):
// - /bit spawnpet
// - /bit dismisspet
// - /bit togglepetspeech
// - Pet follows owner at short distance
// - If pet speech enabled: when owner chats, pet responds Yes/No (to owner only), with yellow/red flash + particles.

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BitMob extends JavaPlugin implements Listener, TabCompleter {

    private static final String BITS_FILE_NAME = "bits.yml";
    private static final String PETS_FILE_NAME = "pets.yml";

    private File bitsFile;
    private YamlConfiguration bitsYaml;

    private File petsFile;
    private YamlConfiguration petsYaml;

    private static final class BitInfo {
        final int id;
        final UUID uuid;
        final String worldName;
        final double x;
        final double y;
        final double z;

        BitInfo(int id, UUID uuid, String worldName, double x, double y, double z) {
            this.id = id;
            this.uuid = uuid;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private enum ReactionMode { IDLE, YES, NO }

    private static final class ReactionState {
        ReactionMode mode = ReactionMode.IDLE;
        long revertAtTick = 0L;
    }

    private static final class PetState {
        UUID petEntityUuid;
        boolean speechEnabled;
    }

    private final Map<UUID, BitInfo> bitsByUuid = new HashMap<>();
    private final Map<Integer, UUID> uuidById = new HashMap<>();

    // Wild bit runtime state
    private final Map<UUID, Long> lastReactMillisByBit = new HashMap<>();
    private final Map<UUID, Double> baseYByBit = new HashMap<>();
    private final Map<UUID, Double> phaseByBit = new HashMap<>();
    private final Map<UUID, Float> yawByBit = new HashMap<>();
    private final Map<UUID, ReactionState> reactionByBit = new HashMap<>();

    // Pet runtime state (per owner)
    private final Map<UUID, PetState> petByOwner = new HashMap<>();
    private final Map<UUID, Double> petPhaseByOwner = new HashMap<>();
    private final Map<UUID, Float> petYawByOwner = new HashMap<>();
    private final Map<UUID, ReactionState> petReactionByOwner = new HashMap<>();
    private final Map<UUID, Long> petLastReactMillisByOwner = new HashMap<>();

    // Config: restrictions
    private boolean restrictEnabled;
    private Set<String> allowedWorldsLower;

    // Config: chat reaction
    private int reactRadiusBlocks;
    private long flashTicks;
    private long perBitCooldownMillis;

    // Config: materials
    private Material baseMaterial;
    private Material yesMaterial;
    private Material noMaterial;

    // Config: display
    private boolean glowing;
    private String itemTransformName; // we keep it as string for compatibility; try set if enum exists

    // Config: animation
    private boolean animationEnabled;
    private int animationIntervalTicks;
    private double bobAmplitudeBlocks;
    private double bobSpeedRadiansPerTick;
    private float spinDegreesPerTick;

    // Config: particles
    private boolean particlesEnabled;
    private int particlesCount;
    private double particlesSpread;
    private float particlesSize;

    // Config: pet behavior
    private boolean petEnabled;
    private double petFollowDistance;
    private double petFollowHeight;
    private double petTeleportLerp; // 0..1
    private long petCooldownMillis;

    private long tickCounter = 0L;

    @Override
    public void onEnable() {
        ensureConfigOnDisk();
        loadConfigValues();

        ensureBitsFileOnDisk();
        loadBitsFromDisk();
        respawnAllBitsAsItemDisplays();

        ensurePetsFileOnDisk();
        loadPetsFromDisk();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("bit") != null) {
            getCommand("bit").setTabCompleter(this);
        }

        // Bring pets back on boot for online players
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ensurePetSpawned(p);
            }
        });

        startAnimationTask();
    }

    @Override
    public void onDisable() {
        saveBitsToDisk();
        savePetsToDisk();
    }

    // ----------------------------
    // Config
    // ----------------------------

    private void ensureConfigOnDisk() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) return;

        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("restrictions.enabled", false);
        cfg.set("restrictions.allowedWorlds", Collections.singletonList("The_Grid"));

        cfg.set("bit.baseMaterial", "WHITE_CONCRETE");
        cfg.set("bit.yesMaterial", "YELLOW_CONCRETE");
        cfg.set("bit.noMaterial", "RED_CONCRETE");

        // Default OFF so it doesn’t outline through walls
        cfg.set("bit.glowing", false);

        // ItemDisplay transform: these names exist on modern Spigot; if your jar differs, it’ll just fall back.
        // Common good options: "GROUND", "FIXED", "GUI", "THIRD_PERSON_RIGHT_HAND"
        cfg.set("bit.itemTransform", "GROUND");

        cfg.set("chatReaction.radiusBlocks", 12);
        cfg.set("chatReaction.flashTicks", 20); // 1 second
        cfg.set("chatReaction.perBitCooldownMillis", 1500);

        cfg.set("animation.enabled", true);
        cfg.set("animation.intervalTicks", 2);
        cfg.set("animation.bobAmplitudeBlocks", 0.20);
        cfg.set("animation.bobSpeedRadiansPerTick", 0.08);
        cfg.set("animation.spinDegreesPerTick", 6.0);

        cfg.set("particles.enabled", true);
        cfg.set("particles.count", 2);
        cfg.set("particles.spread", 0.08);
        cfg.set("particles.size", 1.0);

        cfg.set("pet.enabled", true);
        cfg.set("pet.followDistance", 1.8);
        cfg.set("pet.followHeight", 1.1);
        cfg.set("pet.teleportLerp", 0.35);
        cfg.set("pet.cooldownMillis", 900);

        try {
            cfg.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to create config.yml: " + e.getMessage());
        }
    }

    private void loadConfigValues() {
        reloadConfig();

        this.restrictEnabled = getConfig().getBoolean("restrictions.enabled", false);

        List<String> allowed = getConfig().getStringList("restrictions.allowedWorlds");
        if (allowed == null || allowed.isEmpty()) allowed = Collections.singletonList("The_Grid");

        Set<String> allowedLower = new HashSet<>();
        for (String worldName : allowed) {
            if (worldName != null) allowedLower.add(worldName.toLowerCase(Locale.ROOT));
        }
        this.allowedWorldsLower = allowedLower;

        this.reactRadiusBlocks = Math.max(1, getConfig().getInt("chatReaction.radiusBlocks", 12));
        this.flashTicks = Math.max(1, getConfig().getLong("chatReaction.flashTicks", 20L));
        this.perBitCooldownMillis = Math.max(0L, getConfig().getLong("chatReaction.perBitCooldownMillis", 1500L));

        this.baseMaterial = parseMaterial(getConfig().getString("bit.baseMaterial", "WHITE_CONCRETE"), Material.WHITE_CONCRETE);
        this.yesMaterial = parseMaterial(getConfig().getString("bit.yesMaterial", "YELLOW_CONCRETE"), Material.YELLOW_CONCRETE);
        this.noMaterial = parseMaterial(getConfig().getString("bit.noMaterial", "RED_CONCRETE"), Material.RED_CONCRETE);

        this.glowing = getConfig().getBoolean("bit.glowing", false);
        this.itemTransformName = getConfig().getString("bit.itemTransform", "GROUND");

        this.animationEnabled = getConfig().getBoolean("animation.enabled", true);
        this.animationIntervalTicks = Math.max(1, getConfig().getInt("animation.intervalTicks", 2));
        this.bobAmplitudeBlocks = clamp(getConfig().getDouble("animation.bobAmplitudeBlocks", 0.20), 0.0, 3.0);
        this.bobSpeedRadiansPerTick = clamp(getConfig().getDouble("animation.bobSpeedRadiansPerTick", 0.08), 0.0, 1.0);
        this.spinDegreesPerTick = (float) clamp(getConfig().getDouble("animation.spinDegreesPerTick", 6.0), 0.0, 45.0);

        this.particlesEnabled = getConfig().getBoolean("particles.enabled", true);
        this.particlesCount = Math.max(0, getConfig().getInt("particles.count", 2));
        this.particlesSpread = clamp(getConfig().getDouble("particles.spread", 0.08), 0.0, 2.0);
        this.particlesSize = (float) clamp(getConfig().getDouble("particles.size", 1.0), 0.1, 4.0);

        this.petEnabled = getConfig().getBoolean("pet.enabled", true);
        this.petFollowDistance = clamp(getConfig().getDouble("pet.followDistance", 1.8), 0.5, 10.0);
        this.petFollowHeight = clamp(getConfig().getDouble("pet.followHeight", 1.1), 0.0, 5.0);
        this.petTeleportLerp = clamp(getConfig().getDouble("pet.teleportLerp", 0.35), 0.05, 1.0);
        this.petCooldownMillis = Math.max(0L, getConfig().getLong("pet.cooldownMillis", 900L));
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        Material match = Material.matchMaterial(name.trim());
        return (match != null) ? match : fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ----------------------------
    // Restrictions
    // ----------------------------

    private boolean isWorldAllowed(World world) {
        if (!restrictEnabled) return true;
        if (world == null) return false;
        return allowedWorldsLower.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    // ----------------------------
    // Entity helpers (no World#getEntity(UUID) dependency)
    // ----------------------------

    private Entity getEntityByUuid(UUID uuid) {
        try {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) return e;
        } catch (Throwable ignored) {}

        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (uuid.equals(e.getUniqueId())) return e;
            }
        }
        return null;
    }

    // ----------------------------
    // Wild Bits persistence
    // ----------------------------

    private void ensureBitsFileOnDisk() {
        this.bitsFile = new File(getDataFolder(), BITS_FILE_NAME);
        if (!bitsFile.exists()) {
            this.bitsYaml = new YamlConfiguration();
            bitsYaml.set("nextId", 1);
            bitsYaml.set("bits", new ArrayList<>());
            try {
                bitsYaml.save(bitsFile);
            } catch (IOException e) {
                getLogger().severe("Failed to create " + BITS_FILE_NAME + ": " + e.getMessage());
            }
        } else {
            this.bitsYaml = YamlConfiguration.loadConfiguration(bitsFile);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadBitsFromDisk() {
        bitsByUuid.clear();
        uuidById.clear();
        baseYByBit.clear();
        phaseByBit.clear();
        yawByBit.clear();
        reactionByBit.clear();
        lastReactMillisByBit.clear();

        List<Map<?, ?>> list = (List<Map<?, ?>>) bitsYaml.getList("bits");
        if (list == null) return;

        for (Map<?, ?> entry : list) {
            try {
                int id = asInt(entry.get("id"));
                UUID uuid = UUID.fromString(Objects.toString(entry.get("uuid"), ""));
                String worldName = Objects.toString(entry.get("world"), "");
                double x = asDouble(entry.get("x"));
                double y = asDouble(entry.get("y"));
                double z = asDouble(entry.get("z"));

                BitInfo info = new BitInfo(id, uuid, worldName, x, y, z);
                bitsByUuid.put(uuid, info);
                uuidById.put(id, uuid);

                baseYByBit.put(uuid, y);
                phaseByBit.put(uuid, ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0));
                yawByBit.put(uuid, ThreadLocalRandom.current().nextFloat() * 360f);
                reactionByBit.put(uuid, new ReactionState());
            } catch (Exception ignored) {
                // skip malformed
            }
        }
    }

    private void saveBitsToDisk() {
        if (bitsYaml == null) return;

        List<Map<String, Object>> list = new ArrayList<>();
        for (BitInfo info : bitsByUuid.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", info.id);
            entry.put("uuid", info.uuid.toString());
            entry.put("world", info.worldName);
            entry.put("x", info.x);
            entry.put("y", info.y);
            entry.put("z", info.z);
            list.add(entry);
        }

        list.sort(Comparator.comparingInt(m -> (int) m.getOrDefault("id", 0)));
        bitsYaml.set("bits", list);

        try {
            bitsYaml.save(bitsFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save " + BITS_FILE_NAME + ": " + e.getMessage());
        }
    }

    private int nextId() {
        int next = bitsYaml.getInt("nextId", 1);
        bitsYaml.set("nextId", next + 1);
        try {
            bitsYaml.save(bitsFile);
        } catch (IOException ignored) {}
        return next;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(Objects.toString(value, "0"));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(Objects.toString(value, "0"));
    }

    // ----------------------------
    // Pets persistence
    // ----------------------------

    private void ensurePetsFileOnDisk() {
        this.petsFile = new File(getDataFolder(), PETS_FILE_NAME);
        if (!petsFile.exists()) {
            this.petsYaml = new YamlConfiguration();
            petsYaml.set("players", new LinkedHashMap<>());
            try {
                petsYaml.save(petsFile);
            } catch (IOException e) {
                getLogger().severe("Failed to create " + PETS_FILE_NAME + ": " + e.getMessage());
            }
        } else {
            this.petsYaml = YamlConfiguration.loadConfiguration(petsFile);
        }
    }

    @SuppressWarnings("unchecked")
	private void loadPetsFromDisk() {
		petByOwner.clear();
		petPhaseByOwner.clear();
		petYawByOwner.clear();
		petReactionByOwner.clear();
		petLastReactMillisByOwner.clear();
	
		Object playersObj = petsYaml.get("players");
		if (!(playersObj instanceof Map<?, ?> playersMap)) return;
	
		for (Map.Entry<?, ?> entry : playersMap.entrySet()) {
			try {
				UUID owner = UUID.fromString(Objects.toString(entry.getKey(), ""));
				Object val = entry.getValue();
				if (!(val instanceof Map<?, ?> data)) continue;
	
				PetState st = new PetState();
	
				String petUuidStr = Objects.toString(data.get("petUuid"), "");
				if (!petUuidStr.isEmpty()) {
					st.petEntityUuid = UUID.fromString(petUuidStr);
				}
	
				Object speechObj = data.get("speechEnabled");
				st.speechEnabled = Boolean.parseBoolean(Objects.toString(speechObj, "false"));
	
				petByOwner.put(owner, st);
				petPhaseByOwner.put(owner, ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0));
				petYawByOwner.put(owner, ThreadLocalRandom.current().nextFloat() * 360f);
				petReactionByOwner.put(owner, new ReactionState());
			} catch (Exception ignored) {
				// skip malformed entries
			}
		}
	}

    private void savePetsToDisk() {
        if (petsYaml == null) return;

        Map<String, Object> players = new LinkedHashMap<>();
        for (Map.Entry<UUID, PetState> entry : petByOwner.entrySet()) {
            UUID owner = entry.getKey();
            PetState st = entry.getValue();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("petUuid", (st.petEntityUuid != null) ? st.petEntityUuid.toString() : "");
            data.put("speechEnabled", st.speechEnabled);

            players.put(owner.toString(), data);
        }

        petsYaml.set("players", players);

        try {
            petsYaml.save(petsFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save " + PETS_FILE_NAME + ": " + e.getMessage());
        }
    }

    // ----------------------------
    // Spawning: ItemDisplay Bits
    // ----------------------------

    private ItemDisplay spawnBitItemDisplay(Location location, Material material) {
        if (location == null || location.getWorld() == null) return null;

        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(new ItemStack(material));
            spawned.setGravity(false);
            spawned.setInvulnerable(true);
            spawned.setPersistent(true);
            spawned.setGlowing(glowing);

            // Billboard center is usually fine, but can look “sprite-y”; leave OFF.
            // If you want to experiment later, you can enable it in config and set it here.

            // Brightness boost can look “through-wall-ish” in some packs; leave it alone by default.
            // If your API supports brightness and you want it, you can add it back.

            // Try to set a compact transform, if the enum exists in this API.
            trySetItemTransform(spawned, itemTransformName);

            // Reduce view range a bit so it doesn’t “pop” from too far away.
            // If method exists, use it; otherwise ignore.
            try {
                spawned.setViewRange(0.7f);
            } catch (Throwable ignored) {}
        });

        return display;
    }

    private void trySetItemTransform(ItemDisplay display, String transformName) {
        if (display == null || transformName == null) return;
        try {
            // ItemDisplay.ItemDisplayTransform exists on modern Bukkit/Spigot.
            Object[] constants = ItemDisplay.ItemDisplayTransform.class.getEnumConstants();
            if (constants == null) return;

            for (Object c : constants) {
                if (c.toString().equalsIgnoreCase(transformName)) {
                    display.setItemDisplayTransform((ItemDisplay.ItemDisplayTransform) c);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // If the API differs, ignore gracefully.
        }
    }

    private void setBitItem(ItemDisplay display, Material mat) {
        if (display == null || mat == null) return;
        display.setItemStack(new ItemStack(mat));
    }

    private void ensureWildRuntime(UUID uuid, double baseY) {
        baseYByBit.putIfAbsent(uuid, baseY);
        phaseByBit.putIfAbsent(uuid, ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0));
        yawByBit.putIfAbsent(uuid, ThreadLocalRandom.current().nextFloat() * 360f);
        reactionByBit.putIfAbsent(uuid, new ReactionState());
    }

    private void respawnAllBitsAsItemDisplays() {
        List<BitInfo> infos = new ArrayList<>(bitsByUuid.values());
        infos.sort(Comparator.comparingInt(i -> i.id));

        boolean changed = false;

        for (BitInfo info : infos) {
            World world = Bukkit.getWorld(info.worldName);
            if (world == null) continue;

            Entity existing = getEntityByUuid(info.uuid);
            if (existing instanceof ItemDisplay) {
                ensureWildRuntime(info.uuid, info.y);
                continue;
            }

            // If an old BlockDisplay exists, remove it
            if (existing != null) {
                existing.remove();
            }

            Location loc = new Location(world, info.x, info.y, info.z);
            ItemDisplay spawned = spawnBitItemDisplay(loc, baseMaterial);
            if (spawned == null) continue;

            UUID oldUuid = info.uuid;
            UUID newUuid = spawned.getUniqueId();

            BitInfo updated = new BitInfo(info.id, newUuid, info.worldName, info.x, info.y, info.z);

            bitsByUuid.remove(oldUuid);
            bitsByUuid.put(newUuid, updated);
            uuidById.put(info.id, newUuid);

            Double oldBase = baseYByBit.remove(oldUuid);
            Double oldPhase = phaseByBit.remove(oldUuid);
            Float oldYaw = yawByBit.remove(oldUuid);
            ReactionState oldReaction = reactionByBit.remove(oldUuid);
            lastReactMillisByBit.remove(oldUuid);

            baseYByBit.put(newUuid, (oldBase != null) ? oldBase : info.y);
            phaseByBit.put(newUuid, (oldPhase != null) ? oldPhase : ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0));
            yawByBit.put(newUuid, (oldYaw != null) ? oldYaw : ThreadLocalRandom.current().nextFloat() * 360f);
            reactionByBit.put(newUuid, (oldReaction != null) ? oldReaction : new ReactionState());

            changed = true;
        }

        if (changed) saveBitsToDisk();
    }

    // ----------------------------
    // Pet helpers
    // ----------------------------

    private void ensurePetState(UUID owner) {
        petByOwner.putIfAbsent(owner, new PetState());
        petPhaseByOwner.putIfAbsent(owner, ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0));
        petYawByOwner.putIfAbsent(owner, ThreadLocalRandom.current().nextFloat() * 360f);
        petReactionByOwner.putIfAbsent(owner, new ReactionState());
    }

    private void ensurePetSpawned(Player ownerPlayer) {
        if (!petEnabled) return;
        if (ownerPlayer == null || !ownerPlayer.isOnline()) return;

        ensurePetState(ownerPlayer.getUniqueId());
        PetState st = petByOwner.get(ownerPlayer.getUniqueId());
        if (st == null) return;

        // If no pet recorded, nothing to do
        if (st.petEntityUuid == null) return;

        Entity e = getEntityByUuid(st.petEntityUuid);
        if (e instanceof ItemDisplay) return;

        // Respawn the pet near owner
        if (!isWorldAllowed(ownerPlayer.getWorld())) return;

        Location spawn = ownerPlayer.getLocation().clone().add(0, petFollowHeight, 0);
        ItemDisplay spawned = spawnBitItemDisplay(spawn, baseMaterial);
        if (spawned == null) return;

        st.petEntityUuid = spawned.getUniqueId();
        savePetsToDisk();
    }

    private void removePet(Player ownerPlayer) {
        if (ownerPlayer == null) return;
        UUID owner = ownerPlayer.getUniqueId();

        ensurePetState(owner);
        PetState st = petByOwner.get(owner);
        if (st == null || st.petEntityUuid == null) return;

        Entity e = getEntityByUuid(st.petEntityUuid);
        if (e != null) e.remove();

        st.petEntityUuid = null;
        savePetsToDisk();
    }

    // ----------------------------
    // Nearest wild Bit lookup
    // ----------------------------

    private BitInfo getNearestWildBit(Location location, double maxDistanceSq) {
        if (location == null || location.getWorld() == null) return null;

        String worldName = location.getWorld().getName();
        BitInfo best = null;
        double bestSq = maxDistanceSq;

        for (BitInfo info : bitsByUuid.values()) {
            if (!worldName.equals(info.worldName)) continue;

            double dx = (info.x + 0.5) - location.getX();
            double dy = (info.y + 0.5) - location.getY();
            double dz = (info.z + 0.5) - location.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= bestSq) {
                bestSq = distSq;
                best = info;
            }
        }

        return best;
    }

    // ----------------------------
    // Animation loop: wild + pets
    // ----------------------------

    private void startAnimationTask() {
        if (!animationEnabled) return;

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCounter += animationIntervalTicks;

            // Wild bits
            for (BitInfo info : bitsByUuid.values()) {
                Entity entity = getEntityByUuid(info.uuid);
                if (!(entity instanceof ItemDisplay display)) continue;

                World world = display.getWorld();
                if (world == null) continue;

                ensureWildRuntime(info.uuid, info.y);

                ReactionState reaction = reactionByBit.get(info.uuid);
                if (reaction != null && reaction.mode != ReactionMode.IDLE && tickCounter >= reaction.revertAtTick) {
                    reaction.mode = ReactionMode.IDLE;
                    setBitItem(display, baseMaterial);
                }

                double baseY = baseYByBit.getOrDefault(info.uuid, info.y);
                double phase = phaseByBit.getOrDefault(info.uuid, 0.0);
                phase += bobSpeedRadiansPerTick * animationIntervalTicks;
                if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                phaseByBit.put(info.uuid, phase);

                double yOffset = Math.sin(phase) * bobAmplitudeBlocks;

                float yaw = yawByBit.getOrDefault(info.uuid, 0f);
                yaw += spinDegreesPerTick * animationIntervalTicks;
                if (yaw >= 360f) yaw -= 360f;
                yawByBit.put(info.uuid, yaw);

                Location target = new Location(world, info.x, baseY + yOffset, info.z, yaw, 0f);
                display.teleport(target);

                spawnBitParticles(world, target, reaction != null ? reaction.mode : ReactionMode.IDLE);
            }

            // Pet bits follow owners
            if (petEnabled) {
                for (Player owner : Bukkit.getOnlinePlayers()) {
                    UUID ownerId = owner.getUniqueId();
                    ensurePetState(ownerId);

                    PetState st = petByOwner.get(ownerId);
                    if (st == null || st.petEntityUuid == null) continue;

                    Entity ent = getEntityByUuid(st.petEntityUuid);
                    if (!(ent instanceof ItemDisplay petDisplay)) continue;

                    if (!isWorldAllowed(owner.getWorld())) continue;

                    // keep pet in owner's world
                    if (!petDisplay.getWorld().equals(owner.getWorld())) {
                        // respawn in correct world
                        petDisplay.remove();
                        Location spawn = owner.getLocation().clone().add(0, petFollowHeight, 0);
                        ItemDisplay spawned = spawnBitItemDisplay(spawn, baseMaterial);
                        if (spawned != null) {
                            st.petEntityUuid = spawned.getUniqueId();
                            savePetsToDisk();
                        }
                        continue;
                    }

                    ReactionState reaction = petReactionByOwner.get(ownerId);
                    if (reaction != null && reaction.mode != ReactionMode.IDLE && tickCounter >= reaction.revertAtTick) {
                        reaction.mode = ReactionMode.IDLE;
                        setBitItem(petDisplay, baseMaterial);
                    }

                    // bob phase for pet
                    double phase = petPhaseByOwner.getOrDefault(ownerId, 0.0);
                    phase += bobSpeedRadiansPerTick * animationIntervalTicks;
                    if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                    petPhaseByOwner.put(ownerId, phase);

                    double yOffset = Math.sin(phase) * (bobAmplitudeBlocks * 0.75);

                    // yaw spin for pet
                    float yaw = petYawByOwner.getOrDefault(ownerId, 0f);
                    yaw += (spinDegreesPerTick * 1.2f) * animationIntervalTicks;
                    if (yaw >= 360f) yaw -= 360f;
                    petYawByOwner.put(ownerId, yaw);

                    Location ownerLoc = owner.getLocation();
                    Vector back = ownerLoc.getDirection().clone().normalize().multiply(-petFollowDistance);
                    Location desired = ownerLoc.clone().add(back);
                    desired.setY(ownerLoc.getY() + petFollowHeight + yOffset);

                    // lerp movement so it doesn’t jitter
                    Location cur = petDisplay.getLocation();
                    Location lerp = new Location(
                            owner.getWorld(),
                            cur.getX() + (desired.getX() - cur.getX()) * petTeleportLerp,
                            cur.getY() + (desired.getY() - cur.getY()) * petTeleportLerp,
                            cur.getZ() + (desired.getZ() - cur.getZ()) * petTeleportLerp,
                            yaw,
                            0f
                    );

                    // If it's too far away (teleport catch-up)
                    if (cur.distanceSquared(ownerLoc) > 30 * 30) {
                        lerp = new Location(owner.getWorld(), desired.getX(), desired.getY(), desired.getZ(), yaw, 0f);
                    }

                    petDisplay.teleport(lerp);

                    spawnBitParticles(owner.getWorld(), lerp, reaction != null ? reaction.mode : ReactionMode.IDLE);
                }
            }

        }, 1L, animationIntervalTicks);
    }

    private void spawnBitParticles(World world, Location loc, ReactionMode mode) {
        if (!particlesEnabled || particlesCount <= 0 || world == null || loc == null) return;

        Color dustColor = Color.WHITE;
        if (mode == ReactionMode.YES) dustColor = Color.YELLOW;
        else if (mode == ReactionMode.NO) dustColor = Color.RED;

        Particle.DustOptions dust = new Particle.DustOptions(dustColor, particlesSize);

        Location pLoc = loc.clone().add(0.0, 0.15, 0.0);
        world.spawnParticle(
                Particle.DUST,
                pLoc,
                particlesCount,
                particlesSpread,
                particlesSpread,
                particlesSpread,
                0.0,
                dust
        );
    }

    // ----------------------------
    // Events: keep pets alive
    // ----------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> ensurePetSpawned(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Pet follows across worlds (respawned in new world)
        Bukkit.getScheduler().runTask(this, () -> ensurePetSpawned(event.getPlayer()));
    }

    // ----------------------------
    // Chat reaction: wild + pet
    // ----------------------------

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        final UUID playerUuid = event.getPlayer().getUniqueId();

        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) return;

            World world = player.getWorld();
            if (!isWorldAllowed(world)) return;

            // 1) Wild bit near player: broadcast reply
            BitInfo nearestWild = getNearestWildBit(player.getLocation(), reactRadiusBlocks * (double) reactRadiusBlocks);
            if (nearestWild != null) {
                handleWildReaction(nearestWild);
            }

            // 2) Pet bit: if enabled, respond to owner only (no distance requirement; it’s your pet)
            if (petEnabled) {
                handlePetReaction(player);
            }
        });
    }

    private void handleWildReaction(BitInfo bit) {
        long now = System.currentTimeMillis();
        Long last = lastReactMillisByBit.get(bit.uuid);
        if (last != null && (now - last) < perBitCooldownMillis) return;
        lastReactMillisByBit.put(bit.uuid, now);

        Entity entity = getEntityByUuid(bit.uuid);
        if (!(entity instanceof ItemDisplay display)) return;

        ensureWildRuntime(bit.uuid, bit.y);

        boolean yes = ThreadLocalRandom.current().nextBoolean();
        setBitItem(display, yes ? yesMaterial : noMaterial);
        Bukkit.broadcastMessage(yes ? "<Bit> Yes!" : "<Bit> No!");

        ReactionState st = reactionByBit.get(bit.uuid);
        if (st == null) {
            st = new ReactionState();
            reactionByBit.put(bit.uuid, st);
        }
        st.mode = yes ? ReactionMode.YES : ReactionMode.NO;
        st.revertAtTick = tickCounter + flashTicks;

        // safety revert
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Entity ent2 = getEntityByUuid(bit.uuid);
            if (ent2 instanceof ItemDisplay d2) setBitItem(d2, baseMaterial);
            ReactionState st2 = reactionByBit.get(bit.uuid);
            if (st2 != null) st2.mode = ReactionMode.IDLE;
        }, flashTicks);
    }

    private void handlePetReaction(Player owner) {
        UUID ownerId = owner.getUniqueId();
        ensurePetState(ownerId);

        PetState petState = petByOwner.get(ownerId);
        if (petState == null || petState.petEntityUuid == null) return;
        if (!petState.speechEnabled) return;

        long now = System.currentTimeMillis();
        Long last = petLastReactMillisByOwner.get(ownerId);
        if (last != null && (now - last) < petCooldownMillis) return;
        petLastReactMillisByOwner.put(ownerId, now);

        Entity entity = getEntityByUuid(petState.petEntityUuid);
        if (!(entity instanceof ItemDisplay display)) return;

        boolean yes = ThreadLocalRandom.current().nextBoolean();
        setBitItem(display, yes ? yesMaterial : noMaterial);

        // Owner-only chat
        owner.sendMessage(yes ? "<Bit> Yes!" : "<Bit> No!");

        ReactionState st = petReactionByOwner.get(ownerId);
        if (st == null) {
            st = new ReactionState();
            petReactionByOwner.put(ownerId, st);
        }
        st.mode = yes ? ReactionMode.YES : ReactionMode.NO;
        st.revertAtTick = tickCounter + flashTicks;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            Entity ent2 = getEntityByUuid(petState.petEntityUuid);
            if (ent2 instanceof ItemDisplay d2) setBitItem(d2, baseMaterial);
            ReactionState st2 = petReactionByOwner.get(ownerId);
            if (st2 != null) st2.mode = ReactionMode.IDLE;
        }, flashTicks);
    }

    // ----------------------------
    // Commands
    // ----------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bit")) return false;

        if (args.length == 0) {
            sender.sendMessage("Usage: /bit spawn | /bit list | /bit dismiss <id|uuid> | /bit spawnpet | /bit dismisspet | /bit togglepetspeech");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /bit spawn.");
                    return true;
                }
                if (!isWorldAllowed(player.getWorld())) {
                    sender.sendMessage("This world is not allowed by BitMob restrictions.");
                    return true;
                }

                Vector forward = player.getLocation().getDirection().normalize().multiply(2.0);
                Location spawnLoc = player.getLocation().clone().add(forward);
                spawnLoc.setY(player.getLocation().getY() + 1.0);

                ItemDisplay display = spawnBitItemDisplay(spawnLoc, baseMaterial);
                if (display == null) {
                    sender.sendMessage("Failed to spawn Bit.");
                    return true;
                }

                int id = nextId();
                UUID uuid = display.getUniqueId();

                BitInfo info = new BitInfo(id, uuid, player.getWorld().getName(), spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ());
                bitsByUuid.put(uuid, info);
                uuidById.put(id, uuid);

                ensureWildRuntime(uuid, spawnLoc.getY());
                baseYByBit.put(uuid, spawnLoc.getY());

                saveBitsToDisk();
                sender.sendMessage("Spawned Bit id=" + id + " uuid=" + uuid);
                return true;
            }
            case "list" -> {
                if (bitsByUuid.isEmpty()) {
                    sender.sendMessage("No Bits exist.");
                    return true;
                }

                List<BitInfo> list = new ArrayList<>(bitsByUuid.values());
                list.sort(Comparator.comparingInt(i -> i.id));

                sender.sendMessage("Bits (" + list.size() + "):");
                for (BitInfo info : list) {
                    sender.sendMessage(" - id=" + info.id
                            + " world=" + info.worldName
                            + " xyz=" + fmt(info.x) + "," + fmt(info.y) + "," + fmt(info.z)
                            + " uuid=" + info.uuid);
                }
                return true;
            }
            case "dismiss" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /bit dismiss <id|uuid>");
                    return true;
                }

                String target = args[1];
                UUID uuid = null;

                try {
                    int id = Integer.parseInt(target);
                    uuid = uuidById.get(id);
                } catch (NumberFormatException ignored) {}

                if (uuid == null) {
                    try {
                        uuid = UUID.fromString(target);
                    } catch (IllegalArgumentException ignored) {}
                }

                if (uuid == null || !bitsByUuid.containsKey(uuid)) {
                    sender.sendMessage("No Bit found for: " + target);
                    return true;
                }

                BitInfo info = bitsByUuid.get(uuid);
                World w = Bukkit.getWorld(info.worldName);
                if (w != null && !isWorldAllowed(w)) {
                    sender.sendMessage("This world is not allowed by BitMob restrictions.");
                    return true;
                }

                Entity e = getEntityByUuid(uuid);
                if (e != null) e.remove();

                bitsByUuid.remove(uuid);
                uuidById.remove(info.id);
                lastReactMillisByBit.remove(uuid);
                baseYByBit.remove(uuid);
                phaseByBit.remove(uuid);
                yawByBit.remove(uuid);
                reactionByBit.remove(uuid);

                saveBitsToDisk();
                sender.sendMessage("Dismissed Bit id=" + info.id + " uuid=" + uuid);
                return true;
            }
            case "spawnpet" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /bit spawnpet.");
                    return true;
                }
                if (!petEnabled) {
                    sender.sendMessage("Pets are disabled in config (pet.enabled=false).");
                    return true;
                }
                if (!isWorldAllowed(player.getWorld())) {
                    sender.sendMessage("This world is not allowed by BitMob restrictions.");
                    return true;
                }

                UUID owner = player.getUniqueId();
                ensurePetState(owner);
                PetState st = petByOwner.get(owner);
                if (st == null) return true;

                // Remove existing pet first
                if (st.petEntityUuid != null) {
                    Entity old = getEntityByUuid(st.petEntityUuid);
                    if (old != null) old.remove();
                }

                Location spawn = player.getLocation().clone().add(0, petFollowHeight, 0);
                ItemDisplay display = spawnBitItemDisplay(spawn, baseMaterial);
                if (display == null) {
                    sender.sendMessage("Failed to spawn pet Bit.");
                    return true;
                }

                st.petEntityUuid = display.getUniqueId();
                // default speech off if not set
                savePetsToDisk();

                sender.sendMessage("Spawned your pet Bit. (Use /bit togglepetspeech to enable speech)");
                return true;
            }
            case "dismisspet" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /bit dismisspet.");
                    return true;
                }
                removePet(player);
                sender.sendMessage("Dismissed your pet Bit.");
                return true;
            }
            case "togglepetspeech" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /bit togglepetspeech.");
                    return true;
                }

                UUID owner = player.getUniqueId();
                ensurePetState(owner);
                PetState st = petByOwner.get(owner);
                if (st == null) return true;

                st.speechEnabled = !st.speechEnabled;
                savePetsToDisk();

                player.sendMessage("Pet Bit speech is now " + (st.speechEnabled ? "ON" : "OFF") + ".");
                return true;
            }
            default -> {
                sender.sendMessage("Unknown subcommand. Usage: /bit spawn | /bit list | /bit dismiss <id|uuid> | /bit spawnpet | /bit dismisspet | /bit togglepetspeech");
                return true;
            }
        }
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    // ----------------------------
    // Tab completion
    // ----------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("bit")) return Collections.emptyList();

        if (args.length == 1) {
            return partial(args[0], Arrays.asList("spawn", "list", "dismiss", "spawnpet", "dismisspet", "togglepetspeech"));
        }

        if (args.length == 2 && "dismiss".equalsIgnoreCase(args[0])) {
            List<String> candidates = new ArrayList<>();
            List<BitInfo> list = new ArrayList<>(bitsByUuid.values());
            list.sort(Comparator.comparingInt(i -> i.id));
            for (BitInfo info : list) {
                candidates.add(String.valueOf(info.id));
                candidates.add(info.uuid.toString());
            }
            return partial(args[1], candidates);
        }

        return Collections.emptyList();
    }

    private List<String> partial(String token, List<String> options) {
        String t = (token == null) ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(t)) out.add(opt);
        }
        return out;
    }
}
