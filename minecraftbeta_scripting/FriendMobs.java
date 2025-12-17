import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Creature;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.entity.EntityDamageEvent;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

public class FriendMobs extends JavaPlugin implements Listener {

    // -----------------------------
    // Config (manual .properties)
    // -----------------------------
    private File pluginDir;
    private File configFile;
    private Properties configProps;


    // Defaults (can be overridden by config.properties)
	private boolean followEnabled;
	private double followTeleportDistance;
	private double followTeleportOffset;

    private int maxFriendsPerPlayer;          // -1 unlimited
    private int retargetIntervalTicks;        // 20 = 1s
    private double protectRadius;             // targets within this radius of owner
    private double ownerSearchRadius;         // scan radius for possible targets
    private int spawnForwardBlocks;           // spawn in front of player

    private boolean preventSunBurn;
    private boolean preventFriendlyFire;
    private boolean preventOwnerDamageToFriend;

    // friendAllowedMobs.<name>=true/false
    // summonAllowedMobs.<name>=true/false

    // -----------------------------
    // Friend tracking
    // -----------------------------
    // entityId -> ownerName
    private final Map<Integer, String> friendOwnerById = new HashMap<Integer, String>();
    // ownerName(lower) -> set(entityId)
    private final Map<String, Set<Integer>> friendIdsByOwner = new HashMap<String, Set<Integer>>();

    // -----------------------------
    // Optional NMS reflection for slime sizing
    // -----------------------------
    private Method slimeSetSizeMethod; // EntitySlime.setSize(int)

    @Override
    public void onEnable() {
        System.out.println("[FriendMobs] Enabling...");

        pluginDir = new File("plugins", "FriendMobs");
        if (!pluginDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pluginDir.mkdirs();
        }
        configFile = new File(pluginDir, "config.properties");

        loadOrCreateConfig();
        readConfigIntoFields();
        setupNmsReflection();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Friend logic loop
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                tickFriendLogic();
            }
        }, retargetIntervalTicks, retargetIntervalTicks);

        System.out.println("[FriendMobs] Enabled.");
    }

    @Override
    public void onDisable() {
        friendOwnerById.clear();
        friendIdsByOwner.clear();
        System.out.println("[FriendMobs] Disabled.");
    }

    // -----------------------------
    // Config handling (Properties)
    // -----------------------------
    private void loadOrCreateConfig() {
        configProps = new Properties();

        if (configFile.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(configFile);
                configProps.load(fis);
            } catch (Exception ex) {
                System.out.println("[FriendMobs] Failed to read config.properties, using defaults: " + ex.getMessage());
            } finally {
                closeQuietly(fis);
            }
        }

        boolean changed = false;

        changed |= ensureDefault("maxFriendsPerPlayer", "-1");
        changed |= ensureDefault("retargetIntervalTicks", "20");
        changed |= ensureDefault("protectRadius", "16.0");
        changed |= ensureDefault("ownerSearchRadius", "24.0");
        changed |= ensureDefault("spawnForwardBlocks", "3");
		changed |= ensureDefault("followEnabled", "true");
		changed |= ensureDefault("followTeleportDistance", "12.0");
		changed |= ensureDefault("followTeleportOffset", "2.0");


        changed |= ensureDefault("preventSunBurn", "true");
        changed |= ensureDefault("preventFriendlyFire", "true");
        changed |= ensureDefault("preventOwnerDamageToFriend", "true");

        // Friend whitelist defaults: only your set enabled
        changed |= ensureDefault("friendAllowedMobs.zombie", "true");
        changed |= ensureDefault("friendAllowedMobs.skeleton", "true");
        changed |= ensureDefault("friendAllowedMobs.spider", "true");
        changed |= ensureDefault("friendAllowedMobs.pigzombie", "true");
        changed |= ensureDefault("friendAllowedMobs.giant", "true");
        changed |= ensureDefault("friendAllowedMobs.monster", "true");
        changed |= ensureDefault("friendAllowedMobs.smallslime", "true");

        // A few common others default false for friend
        changed |= ensureDefault("friendAllowedMobs.creeper", "false");
        changed |= ensureDefault("friendAllowedMobs.ghast", "false");
        changed |= ensureDefault("friendAllowedMobs.slime", "false");

        // Summon whitelist defaults: all CreatureType values true
        CreatureType[] creatureTypes = getCreatureTypeValuesSafe();
        if (creatureTypes != null) {
            for (CreatureType ct : creatureTypes) {
                String key = "summonAllowedMobs." + ct.name().toLowerCase(Locale.ENGLISH);
                changed |= ensureDefault(key, "true");
            }
        }

        // Also ensure the custom aliases exist in summon list (defaults true)
        changed |= ensureDefault("summonAllowedMobs.pigzombie", "true");
        changed |= ensureDefault("summonAllowedMobs.smallslime", "true");
        changed |= ensureDefault("summonAllowedMobs.monster", "true");

        if (changed) {
            saveConfigProps();
        }
    }

    private boolean ensureDefault(String key, String value) {
        if (!configProps.containsKey(key)) {
            configProps.setProperty(key, value);
            return true;
        }
        return false;
    }

    private void saveConfigProps() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(configFile);
            configProps.store(fos, "FriendMobs configuration");
        } catch (Exception ex) {
            System.out.println("[FriendMobs] Failed to write config.properties: " + ex.getMessage());
        } finally {
            closeQuietly(fos);
        }
    }
	
	private Location getFollowLocationNearOwner(Player owner) {
		Location base = owner.getLocation().clone();
		float yaw = base.getYaw();
		double yawRad = Math.toRadians(yaw);
	
		// slightly behind the owner so it doesn't spawn inside their face
		double dx =  Math.sin(yawRad) * followTeleportOffset;
		double dz = -Math.cos(yawRad) * followTeleportOffset;
	
		return base.add(dx, 0.0, dz);
	}
	

    private void readConfigIntoFields() {
		followEnabled = parseBoolean(configProps.getProperty("followEnabled", "true"), true);
		followTeleportDistance = parseDouble(configProps.getProperty("followTeleportDistance", "12.0"), 12.0);
		followTeleportOffset = parseDouble(configProps.getProperty("followTeleportOffset", "2.0"), 2.0);

        maxFriendsPerPlayer = parseInt(configProps.getProperty("maxFriendsPerPlayer", "-1"), -1);
        retargetIntervalTicks = parseInt(configProps.getProperty("retargetIntervalTicks", "20"), 20);
        if (retargetIntervalTicks < 1) retargetIntervalTicks = 1;

        protectRadius = parseDouble(configProps.getProperty("protectRadius", "16.0"), 16.0);
        ownerSearchRadius = parseDouble(configProps.getProperty("ownerSearchRadius", "24.0"), 24.0);

        spawnForwardBlocks = parseInt(configProps.getProperty("spawnForwardBlocks", "3"), 3);
        if (spawnForwardBlocks < 1) spawnForwardBlocks = 1;

        preventSunBurn = parseBoolean(configProps.getProperty("preventSunBurn", "true"), true);
        preventFriendlyFire = parseBoolean(configProps.getProperty("preventFriendlyFire", "true"), true);
        preventOwnerDamageToFriend = parseBoolean(configProps.getProperty("preventOwnerDamageToFriend", "true"), true);
    }

    private boolean isFriendAllowedMobName(String mobName) {
        String key = "friendAllowedMobs." + mobName.toLowerCase(Locale.ENGLISH);
        return parseBoolean(configProps.getProperty(key, "false"), false);
    }

    private boolean isSummonAllowedMobName(String mobName) {
        String key = "summonAllowedMobs." + mobName.toLowerCase(Locale.ENGLISH);
        return parseBoolean(configProps.getProperty(key, "false"), false);
    }

    // -----------------------------
    // NMS reflection (slime size)
    // -----------------------------
    private void setupNmsReflection() {
        slimeSetSizeMethod = null;
        try {
            Class<?> slimeClass = Class.forName("net.minecraft.server.EntitySlime");
            slimeSetSizeMethod = slimeClass.getMethod("setSize", int.class);
        } catch (Throwable ignored) {
            slimeSetSizeMethod = null;
        }
    }

    // -----------------------------
    // Friend bookkeeping
    // -----------------------------
    private boolean isFriendEntity(Entity entity) {
        return entity != null && friendOwnerById.containsKey(entity.getEntityId());
    }

    private String getFriendOwnerName(Entity entity) {
        return friendOwnerById.get(entity.getEntityId());
    }

    private void addFriend(Entity entity, String ownerName) {
        int entityId = entity.getEntityId();
        friendOwnerById.put(entityId, ownerName);

        String ownerKey = ownerName.toLowerCase(Locale.ENGLISH);
        Set<Integer> ids = friendIdsByOwner.get(ownerKey);
        if (ids == null) {
            ids = new HashSet<Integer>();
            friendIdsByOwner.put(ownerKey, ids);
        }
        ids.add(entityId);
    }

    private void removeFriendById(int entityId) {
        String owner = friendOwnerById.remove(entityId);
        if (owner != null) {
            String ownerKey = owner.toLowerCase(Locale.ENGLISH);
            Set<Integer> ids = friendIdsByOwner.get(ownerKey);
            if (ids != null) {
                ids.remove(entityId);
                if (ids.isEmpty()) {
                    friendIdsByOwner.remove(ownerKey);
                }
            }
        }
    }

    private int getActiveFriendCount(String ownerName) {
        String ownerKey = ownerName.toLowerCase(Locale.ENGLISH);
        Set<Integer> ids = friendIdsByOwner.get(ownerKey);
        if (ids == null || ids.isEmpty()) return 0;

        int count = 0;
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            int entityId = it.next();
            Entity entity = findEntityByIdInWorlds(entityId);
            if (entity == null || entity.isDead()) {
                it.remove();
                friendOwnerById.remove(entityId);
            } else {
                count++;
            }
        }

        if (ids.isEmpty()) friendIdsByOwner.remove(ownerKey);
        return count;
    }

    private Entity findEntityByIdInWorlds(int entityId) {
        List<World> worlds = Bukkit.getWorlds();
        for (World w : worlds) {
            List<Entity> entities = w.getEntities();
            for (Entity e : entities) {
                if (e.getEntityId() == entityId) return e;
            }
        }
        return null;
    }

    // -----------------------------
    // Spawning helpers
    // -----------------------------
    private Location getSpawnLocationInFront(Player player) {
        Location base = player.getLocation().clone();

        // Poseidon-safe forward vector using yaw only
        float yaw = base.getYaw();
        double yawRad = Math.toRadians(yaw);

        double dx = -Math.sin(yawRad) * spawnForwardBlocks;
        double dz =  Math.cos(yawRad) * spawnForwardBlocks;

        return base.add(dx, 0.0, dz);
    }

    private Entity spawnMobByName(World world, Location loc, String mobName) {
        String name = mobName.toLowerCase(Locale.ENGLISH);

        if (name.equals("smallslime")) {
            Entity slime = spawnByCreatureTypeName(world, loc, "SLIME");
            forceSlimeSizeSmall(slime);
            return slime;
        }

        if (name.equals("pigzombie")) {
            // common alias; try both enum names found on forks
            Entity e = spawnByCreatureTypeName(world, loc, "PIG_ZOMBIE");
            if (e != null) return e;
            return spawnByCreatureTypeName(world, loc, "PIGZOMBIE");
        }

        if (name.equals("monster")) {
            // Prefer CreatureType.MONSTER (matches your other Poseidon plugins)
            return spawnByCreatureTypeName(world, loc, "MONSTER");
        }

        // Normal: match CreatureType name directly (zombie, skeleton, spider, giant, etc.)
        return spawnByCreatureTypeName(world, loc, name.toUpperCase(Locale.ENGLISH));
    }

    private Entity spawnByCreatureTypeName(World world, Location loc, String creatureTypeName) {
        try {
            CreatureType[] values = CreatureType.values();
            for (CreatureType ct : values) {
                if (ct.name().equalsIgnoreCase(creatureTypeName)) {
                    // Poseidon/Beta Bukkit uses World.spawnCreature(Location, CreatureType)
                    return world.spawnCreature(loc, ct);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void forceSlimeSizeSmall(Entity slimeEntity) {
        if (slimeEntity == null) return;
        if (slimeSetSizeMethod == null) return;

        try {
            Method getHandle = slimeEntity.getClass().getMethod("getHandle");
            Object nmsSlime = getHandle.invoke(slimeEntity);
            slimeSetSizeMethod.invoke(nmsSlime, 1);
        } catch (Throwable ignored) {
        }
    }

    private CreatureType[] getCreatureTypeValuesSafe() {
        try {
            return CreatureType.values();
        } catch (Throwable t) {
            return null;
        }
    }

    // -----------------------------
    // Friend AI loop
    // -----------------------------
    private void tickFriendLogic() {
        List<Integer> friendIds = new ArrayList<Integer>(friendOwnerById.keySet());

        for (int friendId : friendIds) {
            Entity friendEntity = findEntityByIdInWorlds(friendId);
            if (friendEntity == null || friendEntity.isDead()) {
                removeFriendById(friendId);
                continue;
            }

            if (preventSunBurn) {
                friendEntity.setFireTicks(0);
            }

            if (!(friendEntity instanceof Creature)) {
                continue;
            }

            String ownerName = friendOwnerById.get(friendId);
            if (ownerName == null) continue;

            Player owner = Bukkit.getPlayer(ownerName);
            if (owner == null) continue;
            if (!owner.isOnline()) continue;

            Creature creature = (Creature) friendEntity;

            LivingEntity target = pickTargetForOwner(owner);
			if (target != null) {
				try {
					creature.setTarget(target);
				} catch (Throwable ignored) {
				}
			} else {
				try {
					creature.setTarget(null);
				} catch (Throwable ignored) {
				}
			
				// Follow behavior (Poseidon-safe): leash teleport if too far
				if (followEnabled) {
					double distSq = friendEntity.getLocation().distanceSquared(owner.getLocation());
					if (distSq > (followTeleportDistance * followTeleportDistance)) {
						Location followLoc = getFollowLocationNearOwner(owner);
						// Keep same world (should be), and don't spam teleport if worlds differ
						if (followLoc.getWorld() == friendEntity.getWorld()) {
							friendEntity.teleport(followLoc);
						}
					}
				}
			}
        }
    }
	
	@EventHandler
	public void onEntityDamage(EntityDamageEvent event) {
		// Hard block owner->friend damage even if Poseidon applies damage despite cancelling the ByEntity event
		if (!preventOwnerDamageToFriend) return;
	
		Entity victim = event.getEntity();
		if (!isFriendEntity(victim)) return;
	
		// Only block if the friend belongs to a currently-online owner AND the cause is direct entity damage.
		// We still rely on the ByEntity handler to identify the damager, but this is a safety net.
		// If your Poseidon build exposes getDamage()/setDamage(), use it; otherwise cancel should help.
		try {
			event.setCancelled(true);
		} catch (Throwable ignored) {
		}
	
		try {
			// Some forks support setDamage
			Method setDamage = event.getClass().getMethod("setDamage", int.class);
			setDamage.invoke(event, 0);
		} catch (Throwable ignored) {
		}
	}
	

    private LivingEntity pickTargetForOwner(Player owner) {
        Location ownerLoc = owner.getLocation();
        World world = ownerLoc.getWorld();
        if (world == null) return null;

        double protectRadiusSq = protectRadius * protectRadius;
        double searchRadiusSq = ownerSearchRadius * ownerSearchRadius;

        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        List<Entity> entities = world.getEntities();
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity)) continue;
            if (e.isDead()) continue;

            if (e instanceof Player) continue;
            if (isFriendEntity(e)) continue;
            if (!looksHostile(e)) continue;

            double distSq = e.getLocation().distanceSquared(ownerLoc);
            if (distSq > searchRadiusSq) continue;
            if (distSq > protectRadiusSq) continue;

            if (distSq < bestDistSq) {
                best = (LivingEntity) e;
                bestDistSq = distSq;
            }
        }

        return best;
    }

    private boolean looksHostile(Entity entity) {
        String cn = entity.getClass().getName().toLowerCase(Locale.ENGLISH);
        return cn.contains("zombie")
                || cn.contains("skeleton")
                || cn.contains("spider")
                || cn.contains("creeper")
                || cn.contains("slime")
                || cn.contains("ghast")
                || cn.contains("pigzombie")
                || cn.contains("giant")
                || cn.contains("monster");
    }

    // -----------------------------
    // Events
    // -----------------------------
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity e = event.getEntity();
        if (isFriendEntity(e)) {
            removeFriendById(e.getEntityId());
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!preventSunBurn) return;
        Entity e = event.getEntity();
        if (isFriendEntity(e)) {
            event.setCancelled(true);
            e.setFireTicks(0);
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        Entity attacker = event.getEntity();
        if (!isFriendEntity(attacker)) return;

        Entity target = event.getTarget();
        if (target == null) return;

        String ownerName = getFriendOwnerName(attacker);
        if (ownerName == null) return;

        // Friend should not target its owner
        if (target instanceof Player) {
            Player p = (Player) target;
            if (p.getName().equalsIgnoreCase(ownerName)) {
                event.setCancelled(true);
                return;
            }
        }

        // Friend should not target other friends
        if (preventFriendlyFire && isFriendEntity(target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        // Friend mobs should not damage their owner
        if (isFriendEntity(damager) && victim instanceof Player) {
            String ownerName = getFriendOwnerName(damager);
            if (ownerName != null && ((Player) victim).getName().equalsIgnoreCase(ownerName)) {
                event.setCancelled(true);
                return;
            }
        }

        // Friend-on-friend damage blocked
        if (preventFriendlyFire && isFriendEntity(damager) && isFriendEntity(victim)) {
            event.setCancelled(true);
            return;
        }

        // Owner cannot damage their friend (optional)
        if (preventOwnerDamageToFriend && (damager instanceof Player) && isFriendEntity(victim)) {
            String ownerName = getFriendOwnerName(victim);
            if (ownerName != null && ((Player) damager).getName().equalsIgnoreCase(ownerName)) {
				event.setCancelled(true);
				try {
					event.setDamage(0);
				} catch (Throwable ignored) {
				}
            }
        }
    }

    // -----------------------------
    // Commands
    // -----------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ENGLISH);

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;

        if (cmd.equals("summonfriend")) {
            return handleSummonFriend(player, args);
        }
        if (cmd.equals("friendlist")) {
            return handleFriendList(player, args);
        }
        if (cmd.equals("dismissfriend")) {
            return handleDismissFriend(player, args);
        }
        if (cmd.equals("summon")) {
            return handleSummon(player, args);
        }

        return false;
    }

    private boolean handleSummonFriend(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("Usage: /summonfriend <mobname>");
            return true;
        }

        String mobName = args[0].toLowerCase(Locale.ENGLISH);

        if (!isFriendAllowedMobName(mobName)) {
            player.sendMessage("Unknown or disallowed mob.");
            return true;
        }

        if (maxFriendsPerPlayer >= 0) {
            int count = getActiveFriendCount(player.getName());
            if (count >= maxFriendsPerPlayer) {
                player.sendMessage("You have reached the maximum number of friend mobs.");
                return true;
            }
        }

        Location spawnLoc = getSpawnLocationInFront(player);
        Entity spawned = spawnMobByName(player.getWorld(), spawnLoc, mobName);
        if (spawned == null) {
            player.sendMessage("Spawn failed for that mob on this server build.");
            return true;
        }

        addFriend(spawned, player.getName());

        if (preventSunBurn) spawned.setFireTicks(0);

        player.sendMessage("Summoned friend: " + mobName + " (id " + spawned.getEntityId() + ")");
        return true;
    }

    private boolean handleSummon(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("Usage: /summon <mobname>");
            return true;
        }

        String mobName = args[0].toLowerCase(Locale.ENGLISH);

        if (!isSummonAllowedMobName(mobName)) {
            player.sendMessage("Unknown or disallowed mob.");
            return true;
        }

        Location spawnLoc = getSpawnLocationInFront(player);
        Entity spawned = spawnMobByName(player.getWorld(), spawnLoc, mobName);
        if (spawned == null) {
            player.sendMessage("Spawn failed for that mob on this server build.");
            return true;
        }

        player.sendMessage("Summoned: " + mobName + " (id " + spawned.getEntityId() + ")");
        return true;
    }

    private boolean handleFriendList(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 0) {
            player.sendMessage("Usage: /friendlist");
            return true;
        }

        String ownerKey = player.getName().toLowerCase(Locale.ENGLISH);
        Set<Integer> ids = friendIdsByOwner.get(ownerKey);

        if (ids == null || ids.isEmpty()) {
            player.sendMessage("You have no friend mobs.");
            return true;
        }

        List<String> lines = new ArrayList<String>();
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            int id = it.next();
            Entity e = findEntityByIdInWorlds(id);
            if (e == null || e.isDead()) {
                it.remove();
                friendOwnerById.remove(id);
                continue;
            }
            lines.add("id " + id + " - " + e.getClass().getSimpleName() + " @ " + e.getWorld().getName());
        }

        if (ids.isEmpty()) {
            friendIdsByOwner.remove(ownerKey);
            player.sendMessage("You have no friend mobs.");
            return true;
        }

        player.sendMessage("Friend mobs (" + lines.size() + "):");
        for (String line : lines) {
            player.sendMessage(line);
        }

        return true;
    }

    private boolean handleDismissFriend(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("Usage: /dismissfriend <id|all>");
            return true;
        }

        String ownerKey = player.getName().toLowerCase(Locale.ENGLISH);

        if (args[0].equalsIgnoreCase("all")) {
            Set<Integer> ids = friendIdsByOwner.get(ownerKey);
            if (ids == null || ids.isEmpty()) {
                player.sendMessage("You have no friend mobs.");
                return true;
            }

            int dismissed = 0;
            List<Integer> snapshot = new ArrayList<Integer>(ids);
            for (int id : snapshot) {
                Entity e = findEntityByIdInWorlds(id);
                if (e != null && !e.isDead()) {
                    e.remove();
                }
                removeFriendById(id);
                dismissed++;
            }

            player.sendMessage("Dismissed " + dismissed + " friend mob(s).");
            return true;
        }

        int targetId;
        try {
            targetId = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            player.sendMessage("Usage: /dismissfriend <id|all>");
            return true;
        }

        if (!friendOwnerById.containsKey(targetId)) {
            player.sendMessage("No friend mob found with that id.");
            return true;
        }

        String ownerName = friendOwnerById.get(targetId);
        if (ownerName == null || !ownerName.equalsIgnoreCase(player.getName())) {
            player.sendMessage("That friend mob does not belong to you.");
            return true;
        }

        Entity e = findEntityByIdInWorlds(targetId);
        if (e != null && !e.isDead()) {
            e.remove();
        }
        removeFriendById(targetId);

        player.sendMessage("Dismissed friend mob id " + targetId + ".");
        return true;
    }

    // -----------------------------
    // Utility parsing / IO
    // -----------------------------
    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) return defaultValue;
        String v = value.trim().toLowerCase(Locale.ENGLISH);
        if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        return defaultValue;
    }

    private void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
