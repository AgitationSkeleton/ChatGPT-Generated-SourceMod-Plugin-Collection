import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SecInMinecraft extends JavaPlugin implements Listener {

    private static final String FAKE_PLAYER_NAME = "funnyshid1";

    private NamespacedKey tagKey;

    // Chat config
    private double chatRangeBlocks = 12.0;
    private long chatTickInterval = 80L;
    private double chatChancePerInterval = 0.25;
    private List<String> chatPhrases = new ArrayList<>();
    private final Random random = new Random();

    // Persistence
    private File dataFile;
    private YamlConfiguration dataConfig;

    // One trio per world UUID
    private final Map<UUID, Trio> worldTrios = new HashMap<>();

    private static class Trio {
        UUID worldId;
        UUID boatId;
        UUID fakeId;      // ArmorStand disguised as player
        UUID endermanId;
        Location anchorLocation;

        Trio(UUID worldId, UUID boatId, UUID fakeId, UUID endermanId, Location anchorLocation) {
            this.worldId = worldId;
            this.boatId = boatId;
            this.fakeId = fakeId;
            this.endermanId = endermanId;
            this.anchorLocation = anchorLocation;
        }
    }

    @Override
    public void onEnable() {
        tagKey = new NamespacedKey(this, "secinminecraft");

        // Hard deps for this approach
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! SecInMinecraft requires ProtocolLib.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") == null) {
            getLogger().severe("LibsDisguises not found! SecInMinecraft requires LibsDisguises.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ensureConfigOnDisk();
        reloadLocalConfigValues();

        initDataFile();

        Bukkit.getPluginManager().registerEvents(this, this);

        loadPersistedTrios();
        restorePersistedTrios();

        startSeatEnforcerTask();
        startNpcChatTask();
    }

    @Override
    public void onDisable() {
        persistAllTrios();
    }

    // --------------------
    // Commands
    // --------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("secinminecraft")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("You must be an operator to use this command.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("dismiss")) {
            dismissInWorld(player.getWorld());
            player.sendMessage("SecInMinecraft: dismissed (if present) in this world.");
            return true;
        }

        spawnOrReplaceTrio(player);
        return true;
    }

    private void spawnOrReplaceTrio(Player player) {
        World world = player.getWorld();
        dismissInWorld(world);

        Location spawnLocation = player.getLocation().clone();

        Boat boat = (Boat) world.spawnEntity(spawnLocation, EntityType.OAK_BOAT);
        tagEntity(boat);
        boat.setInvulnerable(true);
        boat.setPersistent(true);

        // Fake "player" using a disguised ArmorStand
        ArmorStand fake = (ArmorStand) world.spawnEntity(spawnLocation, EntityType.ARMOR_STAND);
        setupFakePassenger(fake);
        applyFunnyshidDisguise(fake);

        Enderman enderman = (Enderman) world.spawnEntity(spawnLocation, EntityType.ENDERMAN);
        setupEnderman(enderman);

        // Mount after 1 tick
        new BukkitRunnable() {
            @Override
            public void run() {
                seatTrio(boat, fake, enderman);
            }
        }.runTask(this);

        Trio trio = new Trio(world.getUID(), boat.getUniqueId(), fake.getUniqueId(), enderman.getUniqueId(), spawnLocation);
        worldTrios.put(world.getUID(), trio);
        persistTrio(trio);

        player.sendMessage("SecInMinecraft: spawned boat + disguised funnyshid1 + enderman in this world.");
    }

    private void dismissInWorld(World world) {
        Trio trio = worldTrios.remove(world.getUID());

        if (trio != null) {
            Entity boat = getEntityByUuidInWorld(trio.boatId, world);
            if (boat != null) boat.remove();

            Entity fake = getEntityByUuidInWorld(trio.fakeId, world);
            if (fake != null) {
                try { DisguiseAPI.undisguiseToAll(fake); } catch (Throwable ignored) {}
                fake.remove();
            }

            Entity ender = getEntityByUuidInWorld(trio.endermanId, world);
            if (ender != null) ender.remove();

            removePersistedTrio(world.getUID());
            return;
        }

        // Fallback: remove any tagged entities in this world (loaded)
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!isTagged(entity)) continue;
            try { DisguiseAPI.undisguiseToAll(entity); } catch (Throwable ignored) {}
            entity.remove();
        }

        removePersistedTrio(world.getUID());
    }

    // --------------------
    // Entity setup
    // --------------------

    private void setupFakePassenger(ArmorStand fake) {
        tagEntity(fake);
        fake.setInvulnerable(true);
        fake.setPersistent(true);

        // Make the base entity unobtrusive; the disguise supplies visuals.
        fake.setInvisible(true);
        fake.setMarker(true);
        fake.setGravity(false);
        fake.setSilent(true);
        fake.setCollidable(false);
        fake.setRemoveWhenFarAway(false);

        // In case anything tries to move it
        fake.setAI(false);

        // Name not shown (chat will still show <funnyshid1> ...)
        fake.setCustomName(null);
        fake.setCustomNameVisible(false);
    }

    private void setupEnderman(Enderman enderman) {
        tagEntity(enderman);
        enderman.setInvulnerable(true);
        enderman.setPersistent(true);
        enderman.setCanPickupItems(false);
        enderman.setRemoveWhenFarAway(false);

        // Extra safety: no AI means no aggression, no wandering.
        // We still cancel targeting event for belt-and-suspenders.
        enderman.setAI(false);
        enderman.setSilent(true);
        enderman.setCollidable(false);
    }

    private void applyFunnyshidDisguise(Entity entity) {
        try {
            PlayerDisguise disguise = new PlayerDisguise(FAKE_PLAYER_NAME);
            // This is the important bit: player skin/profile comes from name.
            disguise.setName(FAKE_PLAYER_NAME);

            DisguiseAPI.disguiseToAll(entity, disguise);
        } catch (Throwable t) {
            getLogger().warning("Failed to apply LibsDisguises player disguise: " + t.getMessage());
        }
    }

    private void seatTrio(Boat boat, Entity fake, Enderman enderman) {
        if (boat == null || !boat.isValid()) {
            if (fake != null && fake.isValid()) fake.remove();
            if (enderman != null && enderman.isValid()) enderman.remove();
            return;
        }

        // Clear any passengers
        for (Entity passenger : new ArrayList<>(boat.getPassengers())) {
            boat.removePassenger(passenger);
        }

        // Driver seat first
        if (fake != null && fake.isValid()) {
            boat.addPassenger(fake);
        }
        // Second seat
        if (enderman != null && enderman.isValid()) {
            boat.addPassenger(enderman);
        }
    }

    // --------------------
    // Protection / Events
    // --------------------

    private void tagEntity(Entity entity) {
        if (entity == null) return;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(tagKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isTagged(Entity entity) {
        if (entity == null) return false;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte value = pdc.get(tagKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!isTagged(event.getEntity())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Entity vehicle = event.getVehicle();
        if (!isTagged(vehicle)) return;

        // Prevent destruction when possible
        event.setCancelled(true);

        // If it still gets removed by something else, cleanup the trio next tick
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!vehicle.isValid() || vehicle.isDead()) {
                    dismissInWorld(vehicle.getWorld());
                }
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler
    public void onEndermanTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;
        if (!isTagged(event.getEntity())) return;
        event.setCancelled(true);
    }

    // --------------------
    // Seat Enforcer (keeps things seated + re-applies disguise)
    // --------------------

    private void startSeatEnforcerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Trio trio : new ArrayList<>(worldTrios.values())) {
                    World world = Bukkit.getWorld(trio.worldId);
                    if (world == null) continue;

                    Entity boatEnt = getEntityByUuidInWorld(trio.boatId, world);
                    if (!(boatEnt instanceof Boat boat) || !boat.isValid()) continue;

                    Entity fakeEnt = getEntityByUuidInWorld(trio.fakeId, world);
                    Entity enderEnt = getEntityByUuidInWorld(trio.endermanId, world);

                    ArmorStand fake = (fakeEnt instanceof ArmorStand) ? (ArmorStand) fakeEnt : null;
                    Enderman enderman = (enderEnt instanceof Enderman) ? (Enderman) enderEnt : null;

                    if (fake == null || !fake.isValid() || enderman == null || !enderman.isValid()) continue;

                    // Re-assert protections
                    setupFakePassenger(fake);
                    setupEnderman(enderman);

                    // Re-apply disguise if needed (some servers/plugins can strip it on reload)
                    try {
                        if (!DisguiseAPI.isDisguised(fake)) {
                            applyFunnyshidDisguise(fake);
                        }
                    } catch (Throwable ignored) {}

                    // Enforce seat order
                    List<Entity> passengers = boat.getPassengers();
                    boolean fakeMounted = passengers.stream().anyMatch(e -> e.getUniqueId().equals(fake.getUniqueId()));
                    boolean enderMounted = passengers.stream().anyMatch(e -> e.getUniqueId().equals(enderman.getUniqueId()));

                    // Clear unexpected passengers / wrong state
                    if (passengers.size() > 2 || (!fakeMounted && !passengers.isEmpty())) {
                        for (Entity p : new ArrayList<>(passengers)) {
                            boat.removePassenger(p);
                        }
                        fakeMounted = false;
                        enderMounted = false;
                    }

                    if (!fakeMounted) {
                        if (fake.getVehicle() != null && !fake.getVehicle().getUniqueId().equals(boat.getUniqueId())) {
                            try { fake.leaveVehicle(); } catch (Throwable ignored) {}
                        }
                        boat.addPassenger(fake);
                    }

                    if (!enderMounted) {
                        if (enderman.getVehicle() != null && !enderman.getVehicle().getUniqueId().equals(boat.getUniqueId())) {
                            try { enderman.leaveVehicle(); } catch (Throwable ignored) {}
                        }
                        boat.addPassenger(enderman);
                    }
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    // --------------------
    // NPC-ish chat
    // --------------------

    private void startNpcChatTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (chatPhrases.isEmpty()) return;

                for (Trio trio : worldTrios.values()) {
                    World world = Bukkit.getWorld(trio.worldId);
                    if (world == null) continue;

                    Entity fakeEnt = getEntityByUuidInWorld(trio.fakeId, world);
                    if (fakeEnt == null || !fakeEnt.isValid()) continue;

                    Location npcLoc = fakeEnt.getLocation();
                    double rangeSq = chatRangeBlocks * chatRangeBlocks;

                    List<Player> nearby = new ArrayList<>();
                    for (Player p : world.getPlayers()) {
                        if (!p.isOnline()) continue;
                        if (p.getLocation().distanceSquared(npcLoc) <= rangeSq) nearby.add(p);
                    }
                    if (nearby.isEmpty()) continue;

                    if (random.nextDouble() > chatChancePerInterval) continue;

                    String phrase = chatPhrases.get(random.nextInt(chatPhrases.size()));
                    String message = "<" + FAKE_PLAYER_NAME + "> " + phrase;

                    for (Player p : nearby) {
                        p.sendMessage(message);
                    }
                }
            }
        }.runTaskTimer(this, chatTickInterval, chatTickInterval);
    }

    // --------------------
    // Config creation (for one-file build workflow)
    // --------------------

    private void ensureConfigOnDisk() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            YamlConfiguration defaultCfg = new YamlConfiguration();
            defaultCfg.set("npcChat.rangeBlocks", 12.0);
            defaultCfg.set("npcChat.tickInterval", 80);
            defaultCfg.set("npcChat.chancePerInterval", 0.25);
            defaultCfg.set("npcChat.phrases", Arrays.asList(
                    "the creeper so funnee!",
                    "minecraft is a perfectly secure system.",
                    "boat time."
            ));

            try {
                defaultCfg.save(configFile);
            } catch (IOException e) {
                getLogger().warning("Failed to write default config.yml: " + e.getMessage());
            }
        }

        reloadConfig();
    }

    private void reloadLocalConfigValues() {
        chatRangeBlocks = Math.max(1.0, getConfig().getDouble("npcChat.rangeBlocks", 12.0));
        chatTickInterval = Math.max(20L, getConfig().getLong("npcChat.tickInterval", 80L));
        chatChancePerInterval = clamp01(getConfig().getDouble("npcChat.chancePerInterval", 0.25));

        chatPhrases = getConfig().getStringList("npcChat.phrases");
        if (chatPhrases == null) chatPhrases = new ArrayList<>();
        if (chatPhrases.isEmpty()) {
            chatPhrases.add("the creeper so funnee!");
            chatPhrases.add("minecraft is a perfectly secure system.");
            chatPhrases.add("boat time.");
        }
    }

    private double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    // --------------------
    // Persistence (data.yml)
    // --------------------

    private void initDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Failed to create data.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadPersistedTrios() {
        worldTrios.clear();
        if (dataConfig == null) return;
        if (!dataConfig.isConfigurationSection("worlds")) return;

        for (String worldIdString : dataConfig.getConfigurationSection("worlds").getKeys(false)) {
            try {
                UUID worldId = UUID.fromString(worldIdString);

                String base = "worlds." + worldIdString + ".";
                String boatStr = dataConfig.getString(base + "boatUuid", "");
                String fakeStr = dataConfig.getString(base + "fakeUuid", "");
                String endStr = dataConfig.getString(base + "endermanUuid", "");

                if (boatStr.isEmpty() || fakeStr.isEmpty() || endStr.isEmpty()) continue;

                UUID boatId = UUID.fromString(boatStr);
                UUID fakeId = UUID.fromString(fakeStr);
                UUID endId = UUID.fromString(endStr);

                String worldName = dataConfig.getString(base + "worldName", null);
                double x = dataConfig.getDouble(base + "x", 0.0);
                double y = dataConfig.getDouble(base + "y", 0.0);
                double z = dataConfig.getDouble(base + "z", 0.0);

                World world = Bukkit.getWorld(worldId);
                if (world == null && worldName != null) world = Bukkit.getWorld(worldName);

                Location anchor = (world != null) ? new Location(world, x, y, z) : null;

                worldTrios.put(worldId, new Trio(worldId, boatId, fakeId, endId, anchor));
            } catch (Exception ignored) {}
        }
    }

    private void restorePersistedTrios() {
        // Let worlds load settle
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Trio trio : new ArrayList<>(worldTrios.values())) {
                    World world = Bukkit.getWorld(trio.worldId);
                    if (world == null) continue;

                    if (trio.anchorLocation != null) {
                        world.getChunkAt(trio.anchorLocation).load(true);
                    }

                    Entity boatEnt = getEntityByUuidInWorld(trio.boatId, world);
                    Entity fakeEnt = getEntityByUuidInWorld(trio.fakeId, world);
                    Entity endEnt = getEntityByUuidInWorld(trio.endermanId, world);

                    if (!(boatEnt instanceof Boat) || !(fakeEnt instanceof ArmorStand) || !(endEnt instanceof Enderman)) {
                        // If anything is missing, drop the record (we don't want respawn spam)
                        if (boatEnt != null) boatEnt.remove();
                        if (fakeEnt != null) {
                            try { DisguiseAPI.undisguiseToAll(fakeEnt); } catch (Throwable ignored) {}
                            fakeEnt.remove();
                        }
                        if (endEnt != null) endEnt.remove();

                        worldTrios.remove(trio.worldId);
                        removePersistedTrio(trio.worldId);
                        continue;
                    }

                    Boat boat = (Boat) boatEnt;
                    ArmorStand fake = (ArmorStand) fakeEnt;
                    Enderman enderman = (Enderman) endEnt;

                    setupFakePassenger(fake);
                    applyFunnyshidDisguise(fake);
                    setupEnderman(enderman);

                    seatTrio(boat, fake, enderman);

                    if (trio.anchorLocation == null) {
                        trio.anchorLocation = boat.getLocation();
                        persistTrio(trio);
                    }
                }
            }
        }.runTaskLater(this, 20L);
    }

    private void persistAllTrios() {
        if (dataConfig == null) return;
        dataConfig.set("worlds", null);

        for (Trio trio : worldTrios.values()) {
            persistTrio(trio);
        }
        saveDataFile();
    }

    private void persistTrio(Trio trio) {
        if (dataConfig == null || trio == null) return;

        World world = Bukkit.getWorld(trio.worldId);
        Location anchor = trio.anchorLocation;

        if (anchor == null && world != null) {
            Entity boat = getEntityByUuidInWorld(trio.boatId, world);
            if (boat != null) anchor = boat.getLocation();
        }

        String w = trio.worldId.toString();
        String base = "worlds." + w + ".";

        dataConfig.set(base + "boatUuid", trio.boatId.toString());
        dataConfig.set(base + "fakeUuid", trio.fakeId.toString());
        dataConfig.set(base + "endermanUuid", trio.endermanId.toString());
        if (world != null) dataConfig.set(base + "worldName", world.getName());

        if (anchor != null) {
            dataConfig.set(base + "x", anchor.getX());
            dataConfig.set(base + "y", anchor.getY());
            dataConfig.set(base + "z", anchor.getZ());
        }

        saveDataFile();
    }

    private void removePersistedTrio(UUID worldId) {
        if (dataConfig == null || worldId == null) return;
        dataConfig.set("worlds." + worldId.toString(), null);
        saveDataFile();
    }

    private void saveDataFile() {
        if (dataConfig == null || dataFile == null) return;
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }

    // --------------------
    // Utility
    // --------------------

    private Entity getEntityByUuidInWorld(UUID entityId, World expectedWorld) {
        if (entityId == null || expectedWorld == null) return null;
        Entity entity = Bukkit.getEntity(entityId);
        if (entity == null) return null;
        if (entity.getWorld() == null) return null;
        if (!entity.getWorld().getUID().equals(expectedWorld.getUID())) return null;
        return entity;
    }
}
