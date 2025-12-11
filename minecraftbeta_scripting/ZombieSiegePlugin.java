// No package; compiled as a single file. Suitable for Beta 1.7.3 / CB 1060-style Bukkit.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Creature;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class ZombieSiegePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final int TICKS_PER_SECOND = 20;

    // Real-time timing (in ticks)
    private static final int PREP_MINUTES = 20;           // ~20 minutes of prep
    private static final int PREP_TICKS = PREP_MINUTES * 60 * TICKS_PER_SECOND;
    private static final int INTER_WAVE_SECONDS = 30;     // ~30 seconds between waves
    private static final int INTER_WAVE_TICKS = INTER_WAVE_SECONDS * TICKS_PER_SECOND;

    // Settings (hard-coded for old Bukkit)
    private int prepTicks;
    private boolean allowBlockBreaking;
    private boolean allowDoorBreaking;
    private int baseWaveSize;
    private int waveSizeIncrement;
    private boolean disableUndeadBurning;
    private boolean debug;

    private Set<String> enabledWorlds = new HashSet<String>();

    // worldName -> session
    private Map<String, SiegeSession> sessions = new HashMap<String, SiegeSession>();

    // Approximate block-breaking hardness (seconds)
    private Map<Material, Integer> hardnessSeconds = new HashMap<Material, Integer>();

    public void onEnable() {
        loadSettings();
        initHardnessTable();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zombiesiege").setExecutor(this);

        // Main tick: once per second
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                tickAllSessions();
            }
        }, TICKS_PER_SECOND, TICKS_PER_SECOND);
    }

    public void onDisable() {
        List<SiegeSession> copy = new ArrayList<SiegeSession>(sessions.values());
        for (int i = 0; i < copy.size(); i++) {
            stopSession(copy.get(i), "Zombie Siege plugin disabled.");
        }
        sessions.clear();
    }

    // -------------------------------------------------
    // Settings & hardness
    // -------------------------------------------------

    private void loadSettings() {
        // No config API for this older server, so everything is hard-coded
        prepTicks = PREP_TICKS;         // 20 minutes
        allowBlockBreaking = true;
        allowDoorBreaking = true;
        baseWaveSize = 10;
        waveSizeIncrement = 5;
        disableUndeadBurning = true;
        debug = false;

        enabledWorlds.clear();
        // Change this to your actual siege world name, then recompile
        enabledWorlds.add("ZombieSiegeWorld");
    }

    private void initHardnessTable() {
        hardnessSeconds.put(Material.WOOD, 4);
        hardnessSeconds.put(Material.LOG, 4);
        hardnessSeconds.put(Material.COBBLESTONE, 10);
        hardnessSeconds.put(Material.STONE, 12);
        hardnessSeconds.put(Material.GLASS, 1);
        hardnessSeconds.put(Material.SAND, 2);
        hardnessSeconds.put(Material.GRAVEL, 2);
        hardnessSeconds.put(Material.DIRT, 3);
        hardnessSeconds.put(Material.GRASS, 3);
        hardnessSeconds.put(Material.OBSIDIAN, 60);

        try {
            hardnessSeconds.put(Material.WOODEN_DOOR, 4);
        } catch (Throwable t) {
            try {
                hardnessSeconds.put(Material.WOOD_DOOR, 4);
            } catch (Throwable t2) {
                // ignore
            }
        }
    }

    // -------------------------------------------------
    // Utility
    // -------------------------------------------------

    private boolean isSiegeWorld(World world) {
        return world != null && enabledWorlds.contains(world.getName());
    }

    private void debug(SiegeSession session, String message) {
        if (!debug) return;
        String worldName = (session != null && session.world != null) ? session.world.getName() : "null";
        System.out.println("[ZombieSiege DEBUG " + worldName + "] " + message);
        if (session != null) {
            broadcastToSession(session, "[DEBUG] " + message);
        }
    }

    private void broadcastToSession(SiegeSession session, String message) {
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                p.sendMessage(message);
            }
        }
    }

    private void stopSession(SiegeSession session, String reason) {
        if (session == null) return;

        broadcastToSession(session, "Zombie Siege ended: " + reason);
        debug(session, "Session stopped: " + reason);

        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && !le.isDead()) {
                le.remove();
            }
        }
        session.activeMobs.clear();
        session.breakTasks.clear();

        sessions.remove(session.world.getName());
    }

    // -------------------------------------------------
    // Commands
    // -------------------------------------------------

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("zombiesiege")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        if (!isSiegeWorld(world)) {
            player.sendMessage("Zombie Siege is not enabled in this world.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("/zombiesiege start|stop|status|startwave");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("start")) {
            handleStart(player, world);
            return true;
        } else if (sub.equals("stop")) {
            handleStop(player, world);
            return true;
        } else if (sub.equals("status")) {
            handleStatus(player, world);
            return true;
        } else if (sub.equals("startwave")) {
            handleStartWave(player, world);
            return true;
        }

        player.sendMessage("/zombiesiege start|stop|status|startwave");
        return true;
    }

    private void handleStart(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            session = new SiegeSession(world);
            sessions.put(world.getName(), session);
        }

        // Already part of this siege? Don't rejoin spam.
        if (session.playersInSiege.contains(player.getName())) {
            player.sendMessage("You are already part of this Zombie Siege.");
            return;
        }

        // If something is already running/prepping, just join it
        if (session.prepTicksRemaining > 0 || session.siegeActive || session.awaitingNextWave) {
            session.addPlayer(player);
            player.sendMessage("You join the existing Zombie Siege in this world.");
            return;
        }

        // Fresh siege
        session.addPlayer(player);
        session.leader = player.getName();
        session.prepTicksRemaining = prepTicks;
        session.siegeActive = false;
        session.awaitingNextWave = false;
        session.currentWave = 0;
        session.interWaveTicksRemaining = 0;
        session.wavesSurvived = 0;
        session.deathThisWave = false;

        broadcastToSession(session, player.getName() + " has started a Zombie Siege in this world.");
        int minutes = prepTicks / (60 * TICKS_PER_SECOND);
        player.sendMessage("Zombie Siege preparation started. You have about " + minutes + " minutes to prepare.");
        debug(session, "Preparation started: " + session.prepTicksRemaining + " ticks.");
    }

    private void handleStop(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        if (session.leader != null && !session.leader.equals(player.getName()) && !player.isOp()) {
            player.sendMessage("Only the siege leader or an OP can stop this siege.");
            return;
        }

        stopSession(session, "Stopped by " + player.getName());
    }

    private void handleStatus(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        if (!session.playersInSiege.contains(player.getName()) && !player.isOp()) {
            player.sendMessage("You are not participating in this siege.");
            return;
        }

        String phase;
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            phase = "PREPARATION";
        } else if (session.siegeActive) {
            phase = "WAVE_ACTIVE";
        } else if (session.awaitingNextWave) {
            phase = "BETWEEN_WAVES";
        } else {
            phase = "IDLE";
        }

        player.sendMessage("Zombie Siege status:");
        player.sendMessage("  Phase: " + phase);
        if (session.prepTicksRemaining > 0) {
            int secondsLeft = session.prepTicksRemaining / TICKS_PER_SECOND;
            player.sendMessage("  Prep time remaining: " + secondsLeft + " seconds.");
        }
        if (session.siegeActive || session.awaitingNextWave) {
            player.sendMessage("  Current wave: " + session.currentWave);
            player.sendMessage("  Siege mobs alive: " + session.activeMobs.size());
            player.sendMessage("  Waves survived: " + session.wavesSurvived);
        }
        if (session.awaitingNextWave && session.interWaveTicksRemaining > 0) {
            int secs = session.interWaveTicksRemaining / TICKS_PER_SECOND;
            player.sendMessage("  Next wave in: " + secs + " seconds.");
        }
    }

    private void handleStartWave(Player player, World world) {
        SiegeSession session = sessions.get(world.getName());
        if (session == null) {
            player.sendMessage("No active Zombie Siege in this world.");
            return;
        }

        // Must be part of the siege or OP
        if (!session.playersInSiege.contains(player.getName()) && !player.isOp()) {
            player.sendMessage("You are not participating in this siege.");
            return;
        }

        // Only leader or OP can skip timers
        if (session.leader != null && !session.leader.equals(player.getName()) && !player.isOp()) {
            player.sendMessage("Only the siege leader or an OP can start waves early.");
            return;
        }

        // Case 1: still in prep, no waves have started
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            session.prepTicksRemaining = 0;
            broadcastToSession(session, "Preparation time skipped. Starting the first wave now.");
            debug(session, "Prep skipped by " + player.getName() + ", starting first wave.");
            startWave(session);
            return;
        }

        // Case 2: between waves, waiting for next wave timer
        if (session.awaitingNextWave && !session.siegeActive) {
            session.interWaveTicksRemaining = 0;
            broadcastToSession(session, "Next wave is starting early!");
            debug(session, "Inter-wave delay skipped by " + player.getName() + ".");
            startWave(session);
            return;
        }

        // Otherwise, nothing to fast-forward
        player.sendMessage("There is no pending wave to start early.");
    }

    // -------------------------------------------------
    // Event Handlers
    // -------------------------------------------------

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World from = event.getFrom();
        World to = player.getWorld();

        handlePlayerLeave(player, from);
        handlePlayerEnter(player, to);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        handlePlayerLeave(player, player.getWorld());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;
        if (!session.playersInSiege.contains(player.getName())) return;

        // Death ends the siege rather than resetting it
        broadcastToSession(session, "A siege player has fallen! The siege is over.");
        debug(session, "Player died during siege: " + player.getName());
        stopSession(session, "A siege player has fallen.");
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!disableUndeadBurning) return;

        Entity ent = event.getEntity();
        World world = ent.getWorld();
        if (!isSiegeWorld(world)) return;

        if (ent instanceof org.bukkit.entity.Zombie ||
            ent instanceof org.bukkit.entity.Skeleton ||
            ent instanceof org.bukkit.entity.PigZombie ||
            ent instanceof org.bukkit.entity.Giant) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!isSiegeWorld(world)) return;

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (session.prepTicksRemaining > 0 || session.siegeActive || session.awaitingNextWave) {
            event.setCancelled(true);
            player.sendMessage("You cannot sleep during a zombie siege.");
        }
    }

    // Cancel unrelated hostile spawns during active/between-waves siege
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (!isSiegeWorld(world)) return;

        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        if (!session.siegeActive && !session.awaitingNextWave) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL ||
            reason == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.setCancelled(true);
        }
    }

    private void handlePlayerEnter(Player player, World world) {
        if (!isSiegeWorld(world)) return;
        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;
        if (!session.playersInSiege.contains(player.getName())) return;

        player.sendMessage("You have returned to an active Zombie Siege world.");
    }

    private void handlePlayerLeave(Player player, World world) {
        if (world == null) return;
        SiegeSession session = sessions.get(world.getName());
        if (session == null) return;

        String name = player.getName();
        if (!session.playersInSiege.remove(name)) return;

        if (session.leader != null && session.leader.equals(name)) {
            session.pickNewLeader();
            if (session.leader != null) {
                Player newLeader = Bukkit.getPlayer(session.leader);
                if (newLeader != null) {
                    broadcastToSession(session, "New siege leader is " + newLeader.getName() + ".");
                }
            }
        }

        if (session.playersInSiege.isEmpty()) {
            stopSession(session, "All siege players left this world.");
        }
    }

    // -------------------------------------------------
    // Main ticking
    // -------------------------------------------------

    private void tickAllSessions() {
        List<SiegeSession> copy = new ArrayList<SiegeSession>(sessions.values());
        for (int i = 0; i < copy.size(); i++) {
            tickSession(copy.get(i));
        }
    }

    private void tickSession(SiegeSession session) {
        World world = session.world;
        if (world == null) {
            stopSession(session, "World unloaded.");
            return;
        }

        if (!isSiegeWorld(world)) {
            stopSession(session, "World no longer configured for sieges.");
            return;
        }

        // If no siege players online in this world, reset to idle
        if (!hasOnlineSiegePlayersInWorld(session)) {
            session.prepTicksRemaining = 0;
            session.siegeActive = false;
            session.awaitingNextWave = false;
            session.currentWave = 0;
            session.interWaveTicksRemaining = 0;
            return;
        }

        // Prep phase
        if (session.prepTicksRemaining > 0 && !session.siegeActive && !session.awaitingNextWave) {
            session.prepTicksRemaining -= TICKS_PER_SECOND;
            if (session.prepTicksRemaining <= 0) {
                session.prepTicksRemaining = 0;
                broadcastToSession(session, "Preparation time is over. The first wave is starting.");
                debug(session, "Prep complete, starting first wave.");
                startWave(session);
            }
            return;
        }

        // Between waves
        if (session.awaitingNextWave && !session.siegeActive) {
            if (session.interWaveTicksRemaining > 0) {
                session.interWaveTicksRemaining -= TICKS_PER_SECOND;
                if (session.interWaveTicksRemaining < 0) {
                    session.interWaveTicksRemaining = 0;
                }
            }

            if (session.interWaveTicksRemaining <= 0) {
                broadcastToSession(session, "Next wave is starting.");
                startWave(session);
            }
            return;
        }

        // Active wave
        if (session.siegeActive) {
            cleanMobList(session);
            aggroMobs(session);
            if (allowBlockBreaking || allowDoorBreaking) {
                tickBlockBreaking(session);
            }

            if (session.activeMobs.isEmpty()) {
                // Wave cleared
                session.siegeActive = false;
                session.awaitingNextWave = true;
                if (!session.deathThisWave) {
                    session.wavesSurvived++;
                }
                session.interWaveTicksRemaining = INTER_WAVE_TICKS;

                broadcastToSession(session, "Wave " + session.currentWave +
                        " cleared. Waves survived: " + session.wavesSurvived + ".");
                broadcastToSession(session, "Next wave will begin in about " +
                        INTER_WAVE_SECONDS + " seconds.");
                debug(session, "Wave " + session.currentWave + " cleared, waiting " +
                        INTER_WAVE_SECONDS + " seconds.");
            }
        }
    }

    private boolean hasOnlineSiegePlayersInWorld(SiegeSession session) {
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                return true;
            }
        }
        return false;
    }

    private void cleanMobList(SiegeSession session) {
        List<LivingEntity> alive = new ArrayList<LivingEntity>();
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && !le.isDead() && le.getWorld().equals(session.world)) {
                alive.add(le);
            }
        }
        session.activeMobs = alive;

        List<Integer> toRemove = new ArrayList<Integer>();
        for (Integer id : session.breakTasks.keySet()) {
            LivingEntity e = findMobById(session, id.intValue());
            if (e == null || e.isDead()) {
                toRemove.add(id);
            }
        }
        for (int i = 0; i < toRemove.size(); i++) {
            session.breakTasks.remove(toRemove.get(i));
        }
    }

    private LivingEntity findMobById(SiegeSession session, int id) {
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le != null && le.getEntityId() == id) {
                return le;
            }
        }
        return null;
    }

    private void startWave(SiegeSession session) {
        session.siegeActive = true;
        session.awaitingNextWave = false;
        session.deathThisWave = false;
        session.currentWave++;
        spawnWave(session);
    }

    private void spawnWave(SiegeSession session) {
        int playerCount = Math.max(1, session.playersInSiege.size());
        int wave = Math.max(1, session.currentWave);

        int zombiesToSpawn = baseWaveSize + (wave - 1) * waveSizeIncrement;
        zombiesToSpawn *= playerCount;

        debug(session, "Spawning wave " + wave + " with " +
                zombiesToSpawn + " zombies for " + playerCount + " players.");

        List<Player> targets = new ArrayList<Player>();
        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p != null && p.isOnline() && p.getWorld().equals(session.world)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) return;

        World world = session.world;
        for (int i = 0; i < zombiesToSpawn; i++) {
            Player target = targets.get(i % targets.size());
            Location base = target.getLocation();

            double angle = (2 * Math.PI) * (i / (double) zombiesToSpawn);
            double radius = 20 + (i % 12);
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;

            Location spawnLoc = findSafeSpawn(world, base.getX() + dx, base.getZ() + dz);
            if (spawnLoc == null) continue;

            LivingEntity mob = (LivingEntity) world.spawnCreature(spawnLoc, CreatureType.ZOMBIE);
            session.activeMobs.add(mob);
        }

        broadcastToSession(session, "Wave " + session.currentWave +
                " has begun! Horde size: " + zombiesToSpawn + ".");
        debug(session, "Wave spawn complete. Active mobs: " + session.activeMobs.size());
    }

    private Location findSafeSpawn(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        for (int y = 80; y > 5; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            Block above = world.getBlockAt(bx, y + 1, bz);
            Block below = world.getBlockAt(bx, y - 1, bz);

            if (!block.isEmpty() || !above.isEmpty()) continue;
            if (below.isEmpty()) continue;

            return new Location(world, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    // -------------------------------------------------
    // Aggro + teleport assistance
    // -------------------------------------------------

    private void aggroMobs(SiegeSession session) {
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le == null || le.isDead()) continue;
            if (!(le instanceof Creature)) continue;

            Player target = findNearestSiegePlayer(session, le.getLocation());
            if (target == null || !target.isOnline()) continue;

            Location mobLoc = le.getLocation();
            Location targetLoc = target.getLocation();
            double distSq = mobLoc.distanceSquared(targetLoc);

            double teleportRangeSq = 40 * 40;
            if (distSq > teleportRangeSq) {
                double offsetX = (Math.random() * 10.0) - 5.0;
                double offsetZ = (Math.random() * 10.0) - 5.0;
                Location newLoc = findSafeSpawn(session.world,
                        targetLoc.getX() + offsetX,
                        targetLoc.getZ() + offsetZ);
                if (newLoc != null) {
                    le.teleport(newLoc);
                }
            }

            ((Creature) le).setTarget(target);
        }
    }

    private Player findNearestSiegePlayer(SiegeSession session, Location from) {
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (String name : session.playersInSiege) {
            Player p = Bukkit.getPlayer(name);
            if (p == null || !p.isOnline()) continue;
            if (!p.getWorld().equals(session.world)) continue;

            double distSq = p.getLocation().distanceSquared(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best;
    }

    // -------------------------------------------------
    // Block / door breaking
    // -------------------------------------------------

    private void tickBlockBreaking(SiegeSession session) {
        // Advance existing tasks
        List<Integer> completed = new ArrayList<Integer>();

        for (Map.Entry<Integer, BreakTask> entry : session.breakTasks.entrySet()) {
            BreakTask task = entry.getValue();
            LivingEntity le = findMobById(session, task.entityId);

            if (le == null || le.isDead()) {
                completed.add(Integer.valueOf(task.entityId));
                continue;
            }

            if (!isNearBlock(le.getLocation(), task.targetBlock)) {
                completed.add(Integer.valueOf(task.entityId));
                continue;
            }

            task.ticksLeft -= TICKS_PER_SECOND;
            if (task.ticksLeft <= 0) {
                Block b = task.targetBlock;
                if (b != null && b.getType() != Material.AIR) {
                    Material type = b.getType();
                    b.setType(Material.AIR);
                    debug(session, "Block broken by mob " + task.entityId + " at " +
                            b.getX() + "," + b.getY() + "," + b.getZ() +
                            " (" + type.name() + ")");
                }
                completed.add(Integer.valueOf(task.entityId));
            }
        }

        for (int i = 0; i < completed.size(); i++) {
            session.breakTasks.remove(completed.get(i));
        }

        // Start new tasks
        for (int i = 0; i < session.activeMobs.size(); i++) {
            LivingEntity le = session.activeMobs.get(i);
            if (le == null || le.isDead()) continue;
            if (session.breakTasks.containsKey(Integer.valueOf(le.getEntityId()))) continue;

            Block target = findBreakableBlockNear(le.getLocation());
            if (target == null) continue;

            Material mat = target.getType();
            if (!allowDoorBreaking && isDoorMaterial(mat)) {
                continue;
            }

            int secs = getHardnessSeconds(mat);

            // Giants break blocks twice as fast
            if (le instanceof org.bukkit.entity.Giant) {
                secs = (secs + 1) / 2;
            }

            if (secs <= 0) continue;

            BreakTask task = new BreakTask(le.getEntityId(), target, secs * TICKS_PER_SECOND);
            session.breakTasks.put(Integer.valueOf(le.getEntityId()), task);
            debug(session, "Mob " + le.getEntityId() + " started breaking " + mat.name() +
                    " at " + target.getX() + "," + target.getY() + "," + target.getZ() +
                    " (" + secs + "s)");
        }
    }

    private boolean isNearBlock(Location loc, Block block) {
        if (block == null) return false;
        if (!block.getWorld().equals(loc.getWorld())) return false;
        Location bl = block.getLocation().add(0.5, 0.5, 0.5);
        double distSq = loc.distanceSquared(bl);
        return distSq <= 2.25; // within ~1.5 blocks
    }

    private Block findBreakableBlockNear(Location loc) {
        World world = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        for (int y = by; y <= by + 1; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = world.getBlockAt(bx + dx, y, bz + dz);
                    Material mat = b.getType();
                    if (mat == Material.AIR) continue;
                    if (!allowBlockBreaking && !isDoorMaterial(mat)) continue;

                    if (isBreakableMaterial(mat)) {
                        return b;
                    }
                }
            }
        }

        return null;
    }

    private boolean isBreakableMaterial(Material mat) {
        if (isDoorMaterial(mat)) return true;
        return hardnessSeconds.containsKey(mat);
    }

    private boolean isDoorMaterial(Material mat) {
        String name = mat.name().toUpperCase();
        return (name.indexOf("DOOR") != -1);
    }

    private int getHardnessSeconds(Material mat) {
        Integer secs = hardnessSeconds.get(mat);
        if (secs == null) return 5;
        return secs.intValue();
    }

    // -------------------------------------------------
    // Inner data classes
    // -------------------------------------------------

    private static class SiegeSession {
        final World world;
        final Set<String> playersInSiege = new HashSet<String>();
        String leader;

        int prepTicksRemaining = 0;
        boolean siegeActive = false;
        boolean awaitingNextWave = false;

        int currentWave = 0;
        int wavesSurvived = 0;
        boolean deathThisWave = false;

        int interWaveTicksRemaining = 0;

        List<LivingEntity> activeMobs = new ArrayList<LivingEntity>();
        Map<Integer, BreakTask> breakTasks = new HashMap<Integer, BreakTask>();

        SiegeSession(World world) {
            this.world = world;
        }

        void addPlayer(Player player) {
            if (player == null) return;
            String name = player.getName();
            playersInSiege.add(name);
            if (leader == null) {
                leader = name;
            }
        }

        void pickNewLeader() {
            for (String name : playersInSiege) {
                leader = name;
                return;
            }
            leader = null;
        }
    }

    private static class BreakTask {
        final int entityId;
        final Block targetBlock;
        int ticksLeft;

        BreakTask(int entityId, Block targetBlock, int ticksLeft) {
            this.entityId = entityId;
            this.targetBlock = targetBlock;
            this.ticksLeft = ticksLeft;
        }
    }
}
