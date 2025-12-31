// BetaEndermen.java
// Minecraft Beta 1.7.3 (Project Poseidon / CraftBukkit 1060)
// Author: ChatGPT
//
// Enderman-like fake player NPCs named MHF_Enderman.
// - Neutral until stared at (~0.5s) or hit by a player
// - Teleports, wanders, water-averse, can pick up/place blocks (allowlist only)
// - Silent (no hurt/death sound). Optional teleport sound best-effort.
// - Drops snowballs (+ carried block item if any)
//
// No external dependencies. Uses NMS (net.minecraft.server) directly.

package com.redchanit.betaendermen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import net.minecraft.server.EntityPlayer;
import net.minecraft.server.ItemInWorldManager;
import net.minecraft.server.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

public final class BetaEndermen extends JavaPlugin implements Listener {

    // Settings (config.properties)
    private String npcName = "MHF_Enderman";
    private int maxEndermen = 10;

    private List<String> allowedWorlds = Arrays.asList("world", "IndevHell", "Skylands");

    private int spawnAttemptPeriodTicks = 200;
    private int spawnAttemptsPerCycle = 2;
    private int spawnRadiusMin = 24;
    private int spawnRadiusMax = 64;

    private int wanderRadius = 12;
    private int aggroRange = 32;
    private int meleeRange = 2;

    private int teleportRandomRange = 32;
    private int teleportEscapeRange = 12;

    private int stareTicksToAggro = 10;           // 0.5s at 20 tps
    private double stareAngleDegrees = 6.0;       // small cone

    private int waterContactTicksToEscape = 10;

    private int maxHealth = 40;
    private int meleeDamage = 7;

    private boolean portalParticles = true;       // best-effort
    private String teleportSoundName = "";        // best-effort; blank disables

    private boolean allowPickUpGrassAsGrassItem = false;

    // Allowlist: if block underfoot id not in this set, never pick up
    private final Set<Integer> pickupAllowedBlockIds = new HashSet<Integer>();

    // Runtime
    private final Map<Integer, EndermanNpc> npcsByEntityId = new HashMap<Integer, EndermanNpc>();

    private int tickTaskId = -1;
    private int spawnTaskId = -1;

    private static final Random RNG = new Random();

    @Override
    public void onEnable() {
        ensureConfigExists();
        loadConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        tickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                tickAll();
            }
        }, 1L, 1L);

        spawnTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                doSpawnCycle();
            }
        }, 40L, (long) spawnAttemptPeriodTicks);

        getServer().getLogger().info("[BetaEndermen] Enabled. Max=" + maxEndermen + " Name=" + npcName);
    }

    @Override
    public void onDisable() {
        if (tickTaskId != -1) Bukkit.getScheduler().cancelTask(tickTaskId);
        if (spawnTaskId != -1) Bukkit.getScheduler().cancelTask(spawnTaskId);

        for (EndermanNpc npc : new ArrayList<EndermanNpc>(npcsByEntityId.values())) {
            try { npc.despawn(true); } catch (Throwable ignored) {}
        }
        npcsByEntityId.clear();

        getServer().getLogger().info("[BetaEndermen] Disabled.");
    }

    // ------------------------------------------------------------------------
    // Commands
    // /betaendermen spawn [count]
    // /betaendermen reload
    // ------------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase(Locale.ROOT);
        if (!cmdName.equals("betaendermen")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage("Player-only command.");
            return true;
        }
        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage("You must be op to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("Usage: /betaendermen spawn [count]");
            player.sendMessage("       /betaendermen reload");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            loadConfig();
            player.sendMessage("BetaEndermen config reloaded.");
            return true;
        }

        if (sub.equals("spawn")) {
            int count = 1;
            if (args.length >= 2) {
                try { count = Math.max(1, Integer.parseInt(args[1])); } catch (Throwable ignored) {}
            }

            if (!isAllowedWorld(player.getWorld())) {
                player.sendMessage("This world is not in allowedWorlds.");
                return true;
            }

            int spawned = 0;
            for (int i = 0; i < count; i++) {
                if (npcsByEntityId.size() >= maxEndermen) break;

                Location spawnLoc = findSpawnFromPlayerView(player);
                if (spawnLoc == null) spawnLoc = findSpawnNearPlayer(player);

                if (spawnLoc == null) break;

                EndermanNpc npc = spawnNpcAt(spawnLoc);
                if (npc != null) {
                    npcsByEntityId.put(npc.getEntityId(), npc);
                    spawned++;
                }
            }

            player.sendMessage("Spawned " + spawned + " enderman NPC(s).");
            return true;
        }

        player.sendMessage("Unknown subcommand. Try: /betaendermen spawn [count] | reload");
        return true;
    }

    // ------------------------------------------------------------------------
    // Config (simple properties file)
    // ------------------------------------------------------------------------
    private File getConfigFile() {
        return new File(getDataFolder(), "config.properties");
    }

    private void ensureConfigExists() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        File f = getConfigFile();
        if (f.exists()) return;

        Properties p = new Properties();

        p.setProperty("npcName", "MHF_Enderman");
        p.setProperty("maxEndermen", "10");
        p.setProperty("allowedWorlds", "world,IndevHell,Skylands");

        p.setProperty("spawnAttemptPeriodTicks", "200");
        p.setProperty("spawnAttemptsPerCycle", "2");
        p.setProperty("spawnRadiusMin", "24");
        p.setProperty("spawnRadiusMax", "64");

        p.setProperty("wanderRadius", "12");
        p.setProperty("aggroRange", "32");
        p.setProperty("meleeRange", "2");

        p.setProperty("teleportRandomRange", "32");
        p.setProperty("teleportEscapeRange", "12");

        p.setProperty("stareTicksToAggro", "10");
        p.setProperty("stareAngleDegrees", "6.0");

        p.setProperty("waterContactTicksToEscape", "10");

        p.setProperty("maxHealth", "40");
        p.setProperty("meleeDamage", "7");

        p.setProperty("portalParticles", "true");
        p.setProperty("teleportSoundName", ""); // blank by default (silent)

        p.setProperty("allowPickUpGrassAsGrassItem", "false");

        // Allowlist only (Beta-safe names)
        // NOTE: "Material" names are used here; missing ones are simply ignored.
        p.setProperty("allowedPickupMaterials",
                "STONE,COBBLESTONE,DIRT,GRASS,SAND,GRAVEL,LOG,WOOD,LEAVES,CLAY," +
                "NETHERRACK,SOUL_SAND,SANDSTONE,BRICK,COAL_ORE,IRON_ORE,GOLD_ORE," +
                "DIAMOND_ORE,REDSTONE_ORE,LAPIS_ORE,MOSSY_COBBLESTONE,ICE,SNOW_BLOCK"
        );

        try (FileOutputStream fos = new FileOutputStream(f)) {
            p.store(fos, "BetaEndermen config (Poseidon/Beta 1.7.3)");
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config.properties", e);
        }
    }

    private void loadConfig() {
        Properties p = new Properties();
        File f = getConfigFile();

        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        } catch (IOException e) {
            getServer().getLogger().warning("[BetaEndermen] Failed to read config.properties, using defaults: " + e.getMessage());
        }

        npcName = p.getProperty("npcName", npcName);
        maxEndermen = parseInt(p.getProperty("maxEndermen"), maxEndermen);

        allowedWorlds = splitCsv(p.getProperty("allowedWorlds", "world,IndevHell,Skylands"));

        spawnAttemptPeriodTicks = parseInt(p.getProperty("spawnAttemptPeriodTicks"), spawnAttemptPeriodTicks);
        spawnAttemptsPerCycle = parseInt(p.getProperty("spawnAttemptsPerCycle"), spawnAttemptsPerCycle);
        spawnRadiusMin = parseInt(p.getProperty("spawnRadiusMin"), spawnRadiusMin);
        spawnRadiusMax = parseInt(p.getProperty("spawnRadiusMax"), spawnRadiusMax);

        wanderRadius = parseInt(p.getProperty("wanderRadius"), wanderRadius);
        aggroRange = parseInt(p.getProperty("aggroRange"), aggroRange);
        meleeRange = parseInt(p.getProperty("meleeRange"), meleeRange);

        teleportRandomRange = parseInt(p.getProperty("teleportRandomRange"), teleportRandomRange);
        teleportEscapeRange = parseInt(p.getProperty("teleportEscapeRange"), teleportEscapeRange);

        stareTicksToAggro = parseInt(p.getProperty("stareTicksToAggro"), stareTicksToAggro);
        stareAngleDegrees = parseDouble(p.getProperty("stareAngleDegrees"), stareAngleDegrees);

        waterContactTicksToEscape = parseInt(p.getProperty("waterContactTicksToEscape"), waterContactTicksToEscape);

        maxHealth = parseInt(p.getProperty("maxHealth"), maxHealth);
        meleeDamage = parseInt(p.getProperty("meleeDamage"), meleeDamage);

        portalParticles = parseBool(p.getProperty("portalParticles"), portalParticles);
        teleportSoundName = p.getProperty("teleportSoundName", teleportSoundName);
        if (teleportSoundName == null) teleportSoundName = "";

        allowPickUpGrassAsGrassItem = parseBool(p.getProperty("allowPickUpGrassAsGrassItem"), allowPickUpGrassAsGrassItem);

        pickupAllowedBlockIds.clear();
        String allowedPickupMaterials = p.getProperty("allowedPickupMaterials", "");
        for (String token : splitCsvRaw(allowedPickupMaterials)) {
            Material m = Material.matchMaterial(token);
            if (m != null) pickupAllowedBlockIds.add(m.getId());
        }

        getServer().getLogger().info("[BetaEndermen] Config loaded. Pickup allowlist size=" + pickupAllowedBlockIds.size());
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static boolean parseBool(String s, boolean def) {
        if (s == null) return def;
        return s.trim().equalsIgnoreCase("true") || s.trim().equalsIgnoreCase("yes") || s.trim().equals("1");
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String t : s.split(",")) {
            String v = t.trim();
            if (v.length() > 0) out.add(v);
        }
        return out;
    }

    private static List<String> splitCsvRaw(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String t : s.split(",")) {
            String v = t.trim();
            if (v.length() > 0) out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Spawning logic
    // ------------------------------------------------------------------------
    private void doSpawnCycle() {
        if (npcsByEntityId.size() >= maxEndermen) return;

        Player[] players = Bukkit.getOnlinePlayers(); // Beta API returns array :contentReference[oaicite:1]{index=1}
        if (players == null || players.length == 0) return;

        for (int attempt = 0; attempt < Math.max(1, spawnAttemptsPerCycle); attempt++) {
            if (npcsByEntityId.size() >= maxEndermen) return;

            Player anchor = players[RNG.nextInt(players.length)];
            World w = anchor.getWorld();
            if (!isAllowedWorld(w)) continue;

            Location spawnLoc = findSpawnNearPlayer(anchor);
            if (spawnLoc == null) continue;

            // Light level zero only
            if (spawnLoc.getBlock().getLightLevel() != 0) continue;

            EndermanNpc npc = spawnNpcAt(spawnLoc);
            if (npc != null) {
                npcsByEntityId.put(npc.getEntityId(), npc);
            }
        }
    }

    private EndermanNpc spawnNpcAt(Location spawnLoc) {
        try {
            CraftWorld cw = (CraftWorld) spawnLoc.getWorld();
            WorldServer ws = cw.getHandle();

            CraftServer cs = (CraftServer) Bukkit.getServer();
            MinecraftServer ms = cs.getServer();

            ItemInWorldManager mgr = new ItemInWorldManager(ws);
            EntityPlayer ep = new EntityPlayer(ms, ws, npcName, mgr);

            // Force position
            ep.setPositionRotation(spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(), spawnLoc.getYaw(), spawnLoc.getPitch());
            ws.addEntity(ep);

            EndermanNpc npc = new EndermanNpc(ep, spawnLoc.getWorld());
            npc.setMaxHealth(maxHealth);
            npc.setHealth(maxHealth);
            npc.clearHeldItem();
            return npc;
        } catch (Throwable t) {
            getServer().getLogger().warning("[BetaEndermen] Spawn failed: " + t.getMessage());
            return null;
        }
    }

    private boolean isAllowedWorld(World w) {
        if (w == null) return false;
        if (allowedWorlds == null || allowedWorlds.isEmpty()) return true;
        return allowedWorlds.contains(w.getName());
    }

    private Location findSpawnNearPlayer(Player player) {
        Location base = player.getLocation();
        World w = base.getWorld();

        int radius = spawnRadiusMin + RNG.nextInt(Math.max(1, spawnRadiusMax - spawnRadiusMin + 1));
        double angle = RNG.nextDouble() * Math.PI * 2.0;

        int dx = (int) Math.round(Math.cos(angle) * radius);
        int dz = (int) Math.round(Math.sin(angle) * radius);

        int x = base.getBlockX() + dx;
        int z = base.getBlockZ() + dz;

        int yStart = Math.min(127, base.getBlockY() + 16);
        int yMin = Math.max(2, base.getBlockY() - 24);

        for (int y = yStart; y >= yMin; y--) {
            if (isSafeStandingSpot(w, x, y, z)) {
                return new Location(w, x + 0.5, y, z + 0.5, RNG.nextFloat() * 360f, 0f);
            }
        }
        return null;
    }

    // Spawn from where player is looking (best-effort)
    private Location findSpawnFromPlayerView(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // step out 4..12 blocks
        World w = player.getWorld();
        for (int dist = 4; dist <= 12; dist++) {
            Vector pos = eye.toVector().add(dir.clone().multiply(dist));
            int x = pos.getBlockX();
            int z = pos.getBlockZ();

            // find ground near eye Y
            int yStart = Math.min(127, eye.getBlockY() + 4);
            int yMin = Math.max(2, eye.getBlockY() - 16);

            for (int y = yStart; y >= yMin; y--) {
                if (isSafeStandingSpot(w, x, y, z)) {
                    Location loc = new Location(w, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), 0f);
                    if (loc.getBlock().getLightLevel() == 0) return loc;
                    // command spawn ignores light restriction? you asked for command if not one already;
                    // keep it consistent with rules: require light 0 even for command spawn.
                }
            }
        }
        return null;
    }

    private boolean isSafeStandingSpot(World w, int x, int y, int z) {
        int belowId = w.getBlockAt(x, y - 1, z).getTypeId();
        int atId = w.getBlockAt(x, y, z).getTypeId();
        int aboveId = w.getBlockAt(x, y + 1, z).getTypeId();

        if (belowId == 0) return false;          // air
        if (isWaterId(belowId) || isLavaId(belowId)) return false;

        // Need two air blocks for player-sized entity
        if (atId != 0) return false;
        if (aboveId != 0) return false;

        return true;
    }

    private static boolean isWaterId(int id) { return id == 8 || id == 9; }
    private static boolean isLavaId(int id)  { return id == 10 || id == 11; }

    // allowlist-only pickup rule
    private boolean canPickUpBlockId(int typeId) {
        if (typeId == 0) return false;
        if (isWaterId(typeId) || isLavaId(typeId)) return false;
        return pickupAllowedBlockIds.contains(typeId);
    }

    // ------------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------------
    private void tickAll() {
        Iterator<Map.Entry<Integer, EndermanNpc>> it = npcsByEntityId.entrySet().iterator();
        while (it.hasNext()) {
            EndermanNpc npc = it.next().getValue();
            if (npc == null || npc.isDead()) it.remove();
        }

        for (EndermanNpc npc : new ArrayList<EndermanNpc>(npcsByEntityId.values())) {
            npc.tick();
        }
    }

    // ------------------------------------------------------------------------
    // Events (Beta event priority enum differs) :contentReference[oaicite:2]{index=2}
    // ------------------------------------------------------------------------
    @EventHandler(priority = Event.Priority.Highest)
    public void onEntityDamage(EntityDamageEvent event) {
        EndermanNpc npc = getNpcFromEventEntity(event);
        if (npc == null) return;

        // We manage health ourselves
        event.setCancelled(true);

        int dmg = event.getDamage();
        if (dmg <= 0) return;

        npc.applyDamage(dmg, null);
    }

    @EventHandler(priority = Event.Priority.Highest)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        EndermanNpc npc = getNpcFromEventEntity(event);
        if (npc == null) return;

        event.setCancelled(true);

        int dmg = event.getDamage();
        if (dmg <= 0) return;

        Player damagerPlayer = null;
        if (event.getDamager() instanceof Player) {
            damagerPlayer = (Player) event.getDamager();
        }

        npc.applyDamage(dmg, damagerPlayer);
        if (damagerPlayer != null) npc.setTarget(damagerPlayer);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        for (EndermanNpc npc : npcsByEntityId.values()) {
            if (npc != null && npc.isTarget(event.getPlayer())) npc.clearTarget();
        }
    }

    private EndermanNpc getNpcFromEventEntity(EntityDamageEvent event) {
        try {
            int id = event.getEntity().getEntityId();
            return npcsByEntityId.get(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // NPC brain
    // ------------------------------------------------------------------------
    private final class EndermanNpc {
        private final EntityPlayer handle;
        private final World world;

        private int health;
        private int maxHealthLocal;

        private Player target;
        private int attackCooldownTicks = 0;

        private int idleWanderTicks = 0;
        private Location idleWanderGoal = null;

        private int waterContactTicks = 0;

        // carried block info (block id + data)
        private Integer carriedBlockId = null;
        private byte carriedData = 0;

        // stare tracking
        private UUID staredBy = null;
        private int staredTicks = 0;

        private EndermanNpc(EntityPlayer ep, World w) {
            this.handle = ep;
            this.world = w;
            this.maxHealthLocal = maxHealth;
            this.health = maxHealth;
        }

        public int getEntityId() { return handle.id; }
        public boolean isDead() { return handle.dead; }

        public void setMaxHealth(int v) { maxHealthLocal = v; }
        public void setHealth(int v) { health = Math.max(0, Math.min(v, maxHealthLocal)); }

        public boolean isTarget(Player p) {
            return target != null && p != null && target.getUniqueId().equals(p.getUniqueId());
        }

        public void setTarget(Player p) {
            if (p == null || !p.isOnline()) return;
            target = p;
        }

        public void clearTarget() { target = null; }

        public void applyDamage(int damage, Player attacker) {
            health -= damage;
            if (health <= 0) {
                dieAndDrop();
                return;
            }

            if (attacker != null) setTarget(attacker);

            // sometimes teleport when hit
            if (RNG.nextDouble() < 0.25) teleportRandom(teleportRandomRange);
        }

        public void tick() {
            if (handle.dead) return;

            if (portalParticles && RNG.nextDouble() < 0.25) {
                tryPortalParticles(getLocation());
            }

            // water logic
            if (isInWater()) {
                waterContactTicks++;
                if (waterContactTicks % 10 == 0) applyDamage(1, null);
                if (waterContactTicks >= waterContactTicksToEscape) {
                    teleportEscapeFromWater();
                    waterContactTicks = 0;
                }
            } else {
                waterContactTicks = 0;
            }

            if (isInLava()) {
                applyDamage(4, null);
            }

            updateStareAggro();

            if (target != null && target.isOnline() && target.getWorld() == world) {
                chaseAndAttack();
            } else {
                target = null;
                idleWander();
            }

            pickUpOrPlaceTick();
        }

        private Location getLocation() {
            return new Location(world, handle.locX, handle.locY, handle.locZ, handle.yaw, handle.pitch);
        }

        private void updateStareAggro() {
            Location npcLoc = getLocation();

            Player best = null;
            double bestDistSq = Double.MAX_VALUE;

            Player[] players = Bukkit.getOnlinePlayers();
            for (Player p : players) {
                if (p.getWorld() != world) continue;

                double distSq = p.getLocation().distanceSquared(npcLoc);
                if (distSq > (aggroRange * aggroRange)) continue;

                if (isPlayerStaring(p, npcLoc)) {
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = p;
                    }
                }
            }

            if (best == null) {
                staredBy = null;
                staredTicks = 0;
                return;
            }

            UUID id = best.getUniqueId();
            if (staredBy != null && staredBy.equals(id)) staredTicks++;
            else { staredBy = id; staredTicks = 1; }

            if (staredTicks >= stareTicksToAggro) setTarget(best);
        }

        private boolean isPlayerStaring(Player player, Location npcLoc) {
            Location eye = player.getEyeLocation();
            Vector toNpc = npcLoc.toVector().subtract(eye.toVector());
            double dist = toNpc.length();
            if (dist < 0.001) return false;

            Vector look = eye.getDirection().normalize();
            Vector dirToNpc = toNpc.normalize();

            double dot = look.dot(dirToNpc);
            double minDot = Math.cos(Math.toRadians(stareAngleDegrees));
            if (dot < minDot) return false;

            return hasLineOfSight(eye, npcLoc);
        }

        private boolean hasLineOfSight(Location from, Location to) {
            Vector start = from.toVector();
            Vector end = to.toVector().add(new Vector(0, 1.2, 0));
            Vector delta = end.clone().subtract(start);

            double len = delta.length();
            if (len < 0.001) return true;

            double stepSize = 0.35;
            Vector step = delta.clone().multiply(1.0 / len).multiply(stepSize);
            Vector pos = start.clone();

            int steps = (int) Math.ceil(len / stepSize);
            for (int i = 0; i < steps; i++) {
                pos.add(step);
                int id = world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()).getTypeId();
                if (id != 0) {
                    // treat anything non-air as blocking (simple + fast)
                    return false;
                }
            }
            return true;
        }

        private void chaseAndAttack() {
            Location npcLoc = getLocation();
            Location tLoc = target.getLocation();

            double distSq = npcLoc.distanceSquared(tLoc);

            // teleport closer sometimes
            if (distSq > 16 * 16 && RNG.nextDouble() < 0.05) teleportNearTarget();

            moveTowards(tLoc, 0.22);

            if (attackCooldownTicks > 0) attackCooldownTicks--;
            if (distSq <= (meleeRange * meleeRange) && attackCooldownTicks <= 0) {
                attackCooldownTicks = 10;
                try { target.damage(meleeDamage); } catch (Throwable ignored) {}
            }
        }

        private void idleWander() {
            idleWanderTicks++;

            Location here = getLocation();
            if (idleWanderGoal == null || idleWanderTicks >= 60 || here.distanceSquared(idleWanderGoal) < 2.0) {
                idleWanderTicks = 0;
                idleWanderGoal = pickIdleGoal(here);
            }

            if (idleWanderGoal != null) moveTowards(idleWanderGoal, 0.12);

            if (RNG.nextDouble() < 0.005) teleportRandom(teleportRandomRange);
        }

        private Location pickIdleGoal(Location base) {
            int dx = RNG.nextInt(wanderRadius * 2 + 1) - wanderRadius;
            int dz = RNG.nextInt(wanderRadius * 2 + 1) - wanderRadius;

            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;

            int yStart = Math.min(127, base.getBlockY() + 4);
            int yMin = Math.max(2, base.getBlockY() - 8);

            for (int y = yStart; y >= yMin; y--) {
                if (isSafeStandingSpot(world, x, y, z)) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }

        private void moveTowards(Location dest, double speed) {
            Location npcLoc = getLocation();
            Vector dir = dest.toVector().subtract(npcLoc.toVector());
            dir.setY(0);

            double len = dir.length();
            if (len < 0.001) return;

            dir.normalize().multiply(speed);

            try {
                setMotion(handle, dir.getX(), 0.0, dir.getZ());
                float yaw = (float) (Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
                setYaw(handle, yaw);
            } catch (Throwable ignored) {}
        }

        private void pickUpOrPlaceTick() {
            if (carriedBlockId == null) {
                if (RNG.nextDouble() < 0.004) tryPickUpUnderfoot();
            } else {
                if (RNG.nextDouble() < 0.004) tryPlaceInFront();
            }
        }

        private void tryPickUpUnderfoot() {
            Location loc = getLocation();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            Block below = world.getBlockAt(x, y - 1, z);
            int typeId = below.getTypeId();
            if (!canPickUpBlockId(typeId)) return;

            carriedBlockId = typeId;
            carriedData = below.getData();

            below.setTypeId(0); // remove block (air)

            setHeldFromCarried();
        }

        private void tryPlaceInFront() {
            Location loc = getLocation();
            Vector forward = loc.getDirection().normalize();

            int fx = loc.getBlockX() + (int) Math.round(forward.getX());
            int fz = loc.getBlockZ() + (int) Math.round(forward.getZ());
            int fy = loc.getBlockY();

            int[][] candidates = new int[][]{
                    {fx, fy, fz},
                    {fx, fy + 1, fz},
                    {fx, fy - 1, fz}
            };

            for (int[] c : candidates) {
                Block target = world.getBlockAt(c[0], c[1], c[2]);
                Block belowTarget = world.getBlockAt(c[0], c[1] - 1, c[2]);

                if (target.getTypeId() != 0) continue;
                if (belowTarget.getTypeId() == 0) continue;
                if (isWaterId(belowTarget.getTypeId()) || isLavaId(belowTarget.getTypeId())) continue;

                target.setTypeIdAndData(carriedBlockId, carriedData, true);

                carriedBlockId = null;
                carriedData = 0;
                clearHeldItem();
                return;
            }
        }

        private void setHeldFromCarried() {
            if (carriedBlockId == null) return;

            int id = carriedBlockId.intValue();
            int data = carriedData & 0xFF;

            // grass item can be weird in Beta -> optionally show dirt instead
            if (id == Material.GRASS.getId() && !allowPickUpGrassAsGrassItem) {
                id = Material.DIRT.getId();
                data = 0;
            }

            setHeldItem(new ItemStack(id, 1, data));
        }

        public void clearHeldItem() { setHeldItem(null); }

        private void dieAndDrop() {
            Location loc = getLocation();

            // default drop: snowball
            world.dropItemNaturally(loc, new org.bukkit.inventory.ItemStack(Material.SNOW_BALL, 1));

            // drop carried block as item
            if (carriedBlockId != null) {
                int id = carriedBlockId.intValue();
                int data = carriedData & 0xFF;
                if (id == Material.GRASS.getId() && !allowPickUpGrassAsGrassItem) {
                    id = Material.DIRT.getId();
                    data = 0;
                }
                Material mat = Material.getMaterial(id);
                if (mat != null) {
                    org.bukkit.inventory.ItemStack drop = new org.bukkit.inventory.ItemStack(mat, 1);
                    drop.setDurability((short) data);
                    world.dropItemNaturally(loc, drop);
                }
            }

            despawn(true);
        }

        public void despawn(boolean sendDestroyPacket) {
            try { handle.dead = true; } catch (Throwable ignored) {}
            if (sendDestroyPacket) trySendDestroyPacket(handle.id);
        }

        private boolean isInWater() {
            Location loc = getLocation();
            int feet = world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).getTypeId();
            int body = world.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ()).getTypeId();
            return isWaterId(feet) || isWaterId(body);
        }

        private boolean isInLava() {
            Location loc = getLocation();
            int feet = world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).getTypeId();
            int body = world.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ()).getTypeId();
            return isLavaId(feet) || isLavaId(body);
        }

        private void teleportEscapeFromWater() {
            Location dest = findSafeTeleport(getLocation(), teleportEscapeRange);
            if (dest != null) doTeleport(dest);
            else teleportRandom(teleportEscapeRange);
        }

        private void teleportRandom(int range) {
            Location base = getLocation();
            Location dest = findSafeTeleport(base, range);
            if (dest != null) doTeleport(dest);
        }

        private void teleportNearTarget() {
            if (target == null) return;
            Location t = target.getLocation();
            Location dest = findSafeTeleport(t, 6);
            if (dest != null) doTeleport(dest);
            else teleportRandom(teleportRandomRange);
        }

        private Location findSafeTeleport(Location base, int range) {
            for (int tries = 0; tries < 16; tries++) {
                int dx = RNG.nextInt(range * 2 + 1) - range;
                int dz = RNG.nextInt(range * 2 + 1) - range;

                int x = base.getBlockX() + dx;
                int z = base.getBlockZ() + dz;

                int yStart = Math.min(127, base.getBlockY() + 8);
                int yMin = Math.max(2, base.getBlockY() - 16);

                for (int y = yStart; y >= yMin; y--) {
                    if (isSafeStandingSpot(world, x, y, z)) {
                        return new Location(world, x + 0.5, y, z + 0.5, RNG.nextFloat() * 360f, 0f);
                    }
                }
            }
            return null;
        }

        private void doTeleport(Location dest) {
            // Optional best-effort sound; many Beta builds won’t support string sounds -> safe to ignore failures
            if (teleportSoundName != null && teleportSoundName.length() > 0) {
                tryInvokePlaySound(world, dest, teleportSoundName, 1.0f, 1.0f);
            }

            try {
                handle.setPositionRotation(dest.getX(), dest.getY(), dest.getZ(), dest.getYaw(), dest.getPitch());
                trySendTeleportPacket(handle.id, dest);
            } catch (Throwable ignored) {}
        }

        private void tryPortalParticles(Location loc) {
            // Best-effort: some builds have Effect.PORTAL; if not, no-op.
            try {
                Class<?> effectClass = Class.forName("org.bukkit.Effect");
                Object portal = Enum.valueOf((Class<Enum>) effectClass, "PORTAL");
                Method playEffect = world.getClass().getMethod("playEffect", Location.class, effectClass, int.class);
                playEffect.invoke(world, loc, portal, 0);
            } catch (Throwable ignored) {}
        }

        private void setHeldItem(ItemStack nmsItem) {
            try {
                handle.inventory.items[handle.inventory.itemInHandIndex] = nmsItem;
            } catch (Throwable ignored) {}

            trySendEquipmentPacket(handle.id, 0, nmsItem);
        }

        // --- Packet helpers (reflection, so Poseidon minor diffs won't hard-crash) ---
        private void trySendEquipmentPacket(int entityId, int slot, ItemStack item) {
            try {
                Class<?> packetClazz = Class.forName("net.minecraft.server.Packet5EntityEquipment");
                Constructor<?> ctor = packetClazz.getConstructor(int.class, int.class, ItemStack.class);
                Object packet = ctor.newInstance(entityId, slot, item);
                sendPacketToAll(packet);
            } catch (Throwable ignored) {}
        }

        private void trySendTeleportPacket(int entityId, Location loc) {
            try {
                Class<?> packetClazz = Class.forName("net.minecraft.server.Packet34EntityTeleport");
                Constructor<?> ctor = packetClazz.getConstructor(int.class, int.class, int.class, int.class, byte.class, byte.class);
                int x = (int) Math.floor(loc.getX() * 32.0);
                int y = (int) Math.floor(loc.getY() * 32.0);
                int z = (int) Math.floor(loc.getZ() * 32.0);
                byte yaw = (byte) ((loc.getYaw() % 360) * 256 / 360);
                byte pitch = (byte) ((loc.getPitch() % 360) * 256 / 360);
                Object packet = ctor.newInstance(entityId, x, y, z, yaw, pitch);
                sendPacketToAll(packet);
            } catch (Throwable ignored) {}
        }

        private void trySendDestroyPacket(int entityId) {
            try {
                Class<?> packetClazz = Class.forName("net.minecraft.server.Packet29DestroyEntity");
                Constructor<?> ctor = packetClazz.getConstructor(int.class);
                Object packet = ctor.newInstance(entityId);
                sendPacketToAll(packet);
            } catch (Throwable ignored) {}
        }

        private void sendPacketToAll(Object packet) {
            try {
                Player[] players = Bukkit.getOnlinePlayers();
                for (Player p : players) {
                    Object craftPlayer = p;
                    Method getHandle = craftPlayer.getClass().getMethod("getHandle");
                    Object nmsPlayer = getHandle.invoke(craftPlayer);

                    Object netServerHandler = nmsPlayer.getClass().getField("netServerHandler").get(nmsPlayer);
                    Method sendPacket = netServerHandler.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server.Packet"));
                    sendPacket.invoke(netServerHandler, packet);
                }
            } catch (Throwable ignored) {}
        }

        private void setMotion(EntityPlayer ent, double x, double y, double z) throws Exception {
            ent.getClass().getField("motX").setDouble(ent, x);
            ent.getClass().getField("motY").setDouble(ent, y);
            ent.getClass().getField("motZ").setDouble(ent, z);
        }

        private void setYaw(EntityPlayer ent, float yaw) throws Exception {
            ent.getClass().getField("yaw").setFloat(ent, yaw);
        }
    }

    // Best-effort sound call for Beta variants:
    // Tries World.playSound(Location, String, float, float) if present.
    private void tryInvokePlaySound(World w, Location loc, String sound, float volume, float pitch) {
        try {
            Method m = w.getClass().getMethod("playSound", Location.class, String.class, float.class, float.class);
            m.invoke(w, loc, sound, volume, pitch);
        } catch (Throwable ignored) {}
    }
}
