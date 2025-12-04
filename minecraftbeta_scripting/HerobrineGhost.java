import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockListener;

import org.bukkit.craftbukkit.entity.CraftPlayer;

import net.minecraft.server.MathHelper;
import net.minecraft.server.Packet20NamedEntitySpawn;
import net.minecraft.server.Packet29DestroyEntity;
import net.minecraft.server.Packet32EntityLook;

public class HerobrineGhost extends JavaPlugin {

    private final Random random = new Random();

    private final Map<String, Long> lastSighting = new HashMap<String, Long>();
    private final Set<String> markedPlayers = new HashSet<String>(); // players who summoned him
    private final Set<String> excludedWorlds = new HashSet<String>(); // lower-case world names

    // per-player totem cooldown
    private final Map<String, Long> lastTotemUse = new HashMap<String, Long>();

    private String herobrineUsername = "MHF_Herobrine"; // fake player (skin)
    private String herobrineChatName = "Herobrine";     // name in chat

    private long checkIntervalTicks = 300L;
    private double chancePerCheck = 0.02D;
    private double markedChanceMultiplier = 3.0D;
    private long cooldownMillis = 90L * 60L * 1000L; // random sighting cooldown
    private int minDistance = 25;
    private int maxDistance = 50;
    private long lifetimeTicks = 80L;

    private long totemCooldownMillis = 15L * 60L * 1000L; // 15 minutes
    private boolean totemEnabled = true;                  // new flag

    // Sound config (currently unused / stubbed)
    private double caveSoundChance = 0.35D;
    private String caveSoundName = "ambient.cave.cave";

    private final String[] summonLines = new String[] {
        "I see you.",
        "Do not summon me.",
        "You should not have done that.",
        "I am watching.",
        "You cannot escape."
    };

    private final TotemBlockListener blockListener = new TotemBlockListener(this);

    @Override
    public void onEnable() {
        loadConfigFromFile();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Event.Type.BLOCK_IGNITE, blockListener, Priority.Normal, this);

        startSightingTask();
        System.out.println("[HerobrineGhost] Enabled. Username = " + herobrineUsername +
                           ", ChatName = " + herobrineChatName);
    }

    @Override
    public void onDisable() {
        System.out.println("[HerobrineGhost] Disabled.");
    }

    // ---------------------------------------------------------
    // Config
    // ---------------------------------------------------------

    private void loadConfigFromFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File cfgFile = new File(getDataFolder(), "config.properties");
        Properties props = new Properties();

        try {
            if (cfgFile.exists()) {
                FileReader reader = new FileReader(cfgFile);
                props.load(reader);
                reader.close();
            } else {
                props.setProperty("herobrineUsername", "MHF_Herobrine");
                props.setProperty("herobrineChatName", "Herobrine");
                props.setProperty("checkIntervalTicks", "300");
                props.setProperty("chancePerCheck", "0.02");
                props.setProperty("markedChanceMultiplier", "3.0");
                props.setProperty("cooldownMinutes", "90");
                props.setProperty("minDistance", "25");
                props.setProperty("maxDistance", "50");
                props.setProperty("lifetimeTicks", "80");
                props.setProperty("excludedWorlds", "");
                props.setProperty("caveSoundChance", "0.35");
                props.setProperty("caveSoundName", "ambient.cave.cave");
                props.setProperty("totemCooldownMinutes", "15");
                props.setProperty("totemEnabled", "true"); // new

                FileWriter writer = new FileWriter(cfgFile);
                props.store(writer, "HerobrineGhost configuration");
                writer.close();
            }
        } catch (IOException e) {
            System.out.println("[HerobrineGhost] Could not load config.properties: " + e.getMessage());
        }

        herobrineUsername = props.getProperty("herobrineUsername", "MHF_Herobrine");
        herobrineChatName = props.getProperty("herobrineChatName", "Herobrine");

        checkIntervalTicks = parseLong(props.getProperty("checkIntervalTicks", "300"), 300L);
        chancePerCheck = parseDouble(props.getProperty("chancePerCheck", "0.02"), 0.02D);
        markedChanceMultiplier = parseDouble(props.getProperty("markedChanceMultiplier", "3.0"), 3.0D);

        long cooldownMinutes = parseLong(props.getProperty("cooldownMinutes", "90"), 90L);
        cooldownMillis = cooldownMinutes * 60L * 1000L;

        long totemCooldownMinutes = parseLong(props.getProperty("totemCooldownMinutes", "15"), 15L);
        totemCooldownMillis = totemCooldownMinutes * 60L * 1000L;

        totemEnabled = parseBoolean(props.getProperty("totemEnabled", "true"), true);

        minDistance = (int) parseLong(props.getProperty("minDistance", "25"), 25L);
        maxDistance = (int) parseLong(props.getProperty("maxDistance", "50"), 50L);
        lifetimeTicks = parseLong(props.getProperty("lifetimeTicks", "80"), 80L);

        if (minDistance < 5) minDistance = 5;
        if (maxDistance <= minDistance) maxDistance = minDistance + 5;
        if (chancePerCheck < 0.0D) chancePerCheck = 0.0D;
        if (chancePerCheck > 1.0D) chancePerCheck = 1.0D;
        if (markedChanceMultiplier < 1.0D) markedChanceMultiplier = 1.0D;

        excludedWorlds.clear();
        String excluded = props.getProperty("excludedWorlds", "");
        if (excluded != null && excluded.length() > 0) {
            String[] parts = excluded.split(",");
            for (int i = 0; i < parts.length; i++) {
                String name = parts[i].trim();
                if (name.length() == 0) continue;
                excludedWorlds.add(name.toLowerCase());
            }
        }

        caveSoundChance = parseDouble(props.getProperty("caveSoundChance", "0.35"), 0.35D);
        if (caveSoundChance < 0.0D) caveSoundChance = 0.0D;
        if (caveSoundChance > 1.0D) caveSoundChance = 1.0D;
        String soundName = props.getProperty("caveSoundName", "ambient.cave.cave");
        if (soundName != null && soundName.trim().length() > 0) {
            caveSoundName = soundName.trim();
        }

        System.out.println("[HerobrineGhost] Config loaded. Username=" + herobrineUsername +
                           ", ChatName=" + herobrineChatName);
        if (!excludedWorlds.isEmpty()) {
            System.out.println("[HerobrineGhost] Excluded worlds: " + excludedWorlds.toString());
        }
        System.out.println("[HerobrineGhost] Totem cooldown: " + (totemCooldownMillis / 60000L) + " minutes.");
        System.out.println("[HerobrineGhost] Totem enabled: " + totemEnabled);
    }

    private long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean parseBoolean(String s, boolean def) {
        if (s == null) return def;
        String t = s.trim().toLowerCase();
        if (t.equals("true") || t.equals("yes") || t.equals("on")) return true;
        if (t.equals("false") || t.equals("no") || t.equals("off")) return false;
        return def;
    }

    private boolean isWorldExcluded(World world) {
        if (world == null) return false;
        String name = world.getName();
        if (name == null) return false;
        return excludedWorlds.contains(name.toLowerCase());
    }

    // ---------------------------------------------------------
    // Sightings
    // ---------------------------------------------------------

    private void startSightingTask() {
        long period = checkIntervalTicks;
        if (period <= 0L) period = 300L;

        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                try {
                    runSightingCheck();
                } catch (Throwable t) {
                    System.out.println("[HerobrineGhost] Exception during sighting check: " + t.getMessage());
                }
            }
        }, period, period);
    }

    private void runSightingCheck() {
        long now = System.currentTimeMillis();

        Player[] players = getServer().getOnlinePlayers();
        for (int i = 0; i < players.length; i++) {
            Player player = players[i];

            if (!isPlayerEligible(player, now)) continue;

            double localChance = chancePerCheck;
            if (markedPlayers.contains(player.getName())) {
                localChance *= markedChanceMultiplier;
                if (localChance > 1.0D) localChance = 1.0D;
            }

            if (random.nextDouble() <= localChance) {
                spawnRandomNpcSighting(player);
                lastSighting.put(player.getName(), Long.valueOf(now));
            }
        }
    }

    private boolean isPlayerEligible(Player player, long now) {
        World world = player.getWorld();
        if (world == null) return false;
        if (isWorldExcluded(world)) return false;

        Long last = lastSighting.get(player.getName());
        if (last != null && now - last.longValue() < cooldownMillis) return false;

        Player[] players = getServer().getOnlinePlayers();
        Location pLoc = player.getLocation();
        int nearbyCount = 0;

        for (int i = 0; i < players.length; i++) {
            Player other = players[i];
            if (other == player) continue;
            if (other.getWorld() != world) continue;
            if (other.getLocation().distanceSquared(pLoc) < (64 * 64)) {
                nearbyCount++;
            }
        }
        return nearbyCount == 0;
    }

    private void spawnRandomNpcSighting(Player viewer) {
        try {
            Location playerLoc = viewer.getLocation();
            World world = playerLoc.getWorld();
            if (isWorldExcluded(world)) return;

            double distance = minDistance + (maxDistance - minDistance) * random.nextDouble();
            double angle = random.nextDouble() * Math.PI * 2.0;

            double dx = Math.cos(angle) * distance;
            double dz = Math.sin(angle) * distance;

            int targetX = playerLoc.getBlockX() + (int) Math.round(dx);
            int targetZ = playerLoc.getBlockZ() + (int) Math.round(dz);

            int targetY = world.getHighestBlockYAt(targetX, targetZ);
            if (targetY <= 0) return;

            Location npcLoc = new Location(
                world,
                targetX + 0.5D,
                targetY,
                targetZ + 0.5D,
                0.0F,
                0.0F
            );

            orientLocationTowards(npcLoc, playerLoc);

            Block standingOn = world.getBlockAt(npcLoc);
            if (!standingOn.isEmpty()) {
                npcLoc.setY(npcLoc.getY() + 1.0D);
            }

            spawnClientGhost(viewer, npcLoc);
            System.out.println("[HerobrineGhost] Random sighting for " + viewer.getName() +
                    " at " + npcLoc.getX() + "," + npcLoc.getY() + "," + npcLoc.getZ());
        } catch (Throwable t) {
            System.out.println("[HerobrineGhost] Random NPC sighting failed: " + t.toString());
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------
    // Totem summon
    // ---------------------------------------------------------

    protected void handleTotemIgnite(Player player, Block fireTarget) {
        if (!totemEnabled) return;          // respect config
        if (player == null || fireTarget == null) return;

        Block nethBlock = fireTarget.getRelative(0, -1, 0);
        if (nethBlock.getType() != Material.NETHERRACK) {
            return;
        }

        World world = nethBlock.getWorld();
        if (isWorldExcluded(world)) return;

        if (!isValidHerobrineTotem(nethBlock)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastUse = lastTotemUse.get(player.getName());
        if (lastUse != null && now - lastUse.longValue() < totemCooldownMillis) {
            player.sendMessage("The air is still. Herobrine does not answer your call yet.");
            return;
        }

        Location nethLoc = nethBlock.getLocation();
        int x = nethLoc.getBlockX();
        int y = nethLoc.getBlockY();
        int z = nethLoc.getBlockZ();

        System.out.println("[HerobrineGhost] Valid totem triggered at " + x + "," + y + "," + z);

        Location strikeLoc = nethLoc.clone().add(0.5D, 1.0D, 0.5D);
        world.strikeLightningEffect(strikeLoc);

        spawnTotemNpc(player, nethLoc);

        markedPlayers.add(player.getName());
        lastTotemUse.put(player.getName(), Long.valueOf(now));

        String line = summonLines[random.nextInt(summonLines.length)];
        player.sendMessage("<" + herobrineChatName + "> " + line);

        lastSighting.put(player.getName(), Long.valueOf(System.currentTimeMillis()));
    }

    private boolean isValidHerobrineTotem(Block nethBlock) {
        World world = nethBlock.getWorld();
        if (isWorldExcluded(world)) return false;

        int cx = nethBlock.getX();
        int cy = nethBlock.getY();
        int cz = nethBlock.getZ();

        if (nethBlock.getType() != Material.NETHERRACK) return false;

        int baseY = cy - 1;

        Block centerBase = world.getBlockAt(cx, baseY, cz);
        if (centerBase.getType() != Material.MOSSY_COBBLESTONE) {
            return false;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block b = world.getBlockAt(cx + dx, baseY, cz + dz);
                if (b.getType() != Material.GOLD_BLOCK) {
                    return false;
                }
            }
        }

        Block northTorch = world.getBlockAt(cx,     cy, cz - 1);
        Block southTorch = world.getBlockAt(cx,     cy, cz + 1);
        Block westTorch  = world.getBlockAt(cx - 1, cy, cz);
        Block eastTorch  = world.getBlockAt(cx + 1, cy, cz);

        if (northTorch.getType() != Material.REDSTONE_TORCH_ON) return false;
        if (southTorch.getType() != Material.REDSTONE_TORCH_ON) return false;
        if (westTorch.getType()  != Material.REDSTONE_TORCH_ON) return false;
        if (eastTorch.getType()  != Material.REDSTONE_TORCH_ON) return false;

        return true;
    }

    private void spawnTotemNpc(Player viewer, Location nethLoc) {
        try {
            World world = nethLoc.getWorld();
            if (isWorldExcluded(world)) return;

            Location viewerLoc = viewer.getLocation();

            Location npcLoc = new Location(
                world,
                nethLoc.getBlockX() + 0.5D,
                nethLoc.getBlockY() + 1.0D,
                nethLoc.getBlockZ() + 0.5D,
                0.0F,
                0.0F
            );

            orientLocationTowards(npcLoc, viewerLoc);

            Block standingOn = world.getBlockAt(npcLoc);
            if (!standingOn.isEmpty()) {
                npcLoc.setY(npcLoc.getY() + 1.0D);
            }

            spawnClientGhost(viewer, npcLoc);
            System.out.println("[HerobrineGhost] Totem NPC spawned for " + viewer.getName());
        } catch (Throwable t) {
            System.out.println("[HerobrineGhost] Totem NPC spawn failed: " + t.toString());
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------
    // Packet-based ghost with head tracking
    // ---------------------------------------------------------

    private void spawnClientGhost(final Player viewer, final Location npcLoc) {
        try {
            final CraftPlayer cp = (CraftPlayer) viewer;
            final net.minecraft.server.EntityPlayer handle = cp.getHandle();

            final int entityId = random.nextInt(Integer.MAX_VALUE);

            Location spawnLoc = npcLoc.clone();
            Packet20NamedEntitySpawn spawn = new Packet20NamedEntitySpawn();
            spawn.a = entityId;
            spawn.b = herobrineUsername;
            spawn.c = MathHelper.floor(spawnLoc.getX() * 32.0D);
            spawn.d = MathHelper.floor(spawnLoc.getY() * 32.0D);
            spawn.e = MathHelper.floor(spawnLoc.getZ() * 32.0D);

            orientLocationTowards(spawnLoc, viewer.getLocation());
            spawn.f = (byte) (spawnLoc.getYaw() * 256.0F / 360.0F);
            spawn.g = (byte) (spawnLoc.getPitch() * 256.0F / 360.0F);
            spawn.h = 0;

            handle.netServerHandler.sendPacket(spawn);

            // Maybe play cave sound (currently stubbed / no-op)
            maybePlayCaveSound(viewer, npcLoc);

            // Head tracking: send periodic look packets so he follows the player
            long step = 5L;
            for (long t = step; t < lifetimeTicks; t += step) {
                final long delay = t;
                getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
                    public void run() {
                        try {
                            Location ghostLoc = npcLoc.clone();
                            Location viewerLoc = viewer.getLocation();
                            orientLocationTowards(ghostLoc, viewerLoc);
                            byte yaw = (byte) (ghostLoc.getYaw() * 256.0F / 360.0F);
                            byte pitch = (byte) (ghostLoc.getPitch() * 256.0F / 360.0F);
                            Packet32EntityLook look = new Packet32EntityLook(entityId, yaw, pitch);
                            handle.netServerHandler.sendPacket(look);
                        } catch (Throwable t2) {
                            System.out.println("[HerobrineGhost] Failed look update: " + t2.toString());
                        }
                    }
                }, delay);
            }

            // Despawn after lifetime
            getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
                public void run() {
                    try {
                        Packet29DestroyEntity destroy = new Packet29DestroyEntity(entityId);
                        handle.netServerHandler.sendPacket(destroy);
                    } catch (Throwable t) {
                        System.out.println("[HerobrineGhost] Failed to destroy ghost: " + t.toString());
                    }
                }
            }, lifetimeTicks);

        } catch (Throwable t) {
            System.out.println("[HerobrineGhost] spawnClientGhost failed: " + t.toString());
            t.printStackTrace();
        }
    }

    private void maybePlayCaveSound(Player viewer, Location loc) {
        // Stubbed out for Beta 1.7.3 / Poseidon, since we don't have a clean
        // string-based sound API exposed here. Leaving the config keys in
        // place so this can be wired up later if desired.
        if (caveSoundChance <= 0.0D) return;
        // no-op on this server version
    }

    // Stub: does nothing on this version, but kept so config / call sites remain intact.
    private void playRawSound(Player viewer, Location loc, String soundName, float volume, float pitch) {
        // Intentionally empty – Beta 1.7.3 server.jar we're using does not expose
        // the sound packet class we tried earlier.
    }

    private void orientLocationTowards(Location npcLoc, Location targetLoc) {
        double fromX = npcLoc.getX();
        double fromY = npcLoc.getY() + 1.6D;
        double fromZ = npcLoc.getZ();

        double toX = targetLoc.getX();
        double toY = targetLoc.getY() + 1.6D;
        double toZ = targetLoc.getZ();

        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);
        if (distanceXZ < 0.0001D) distanceXZ = 0.0001D;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        npcLoc.setYaw(yaw);
        npcLoc.setPitch(pitch);
    }

    // ---------------------------------------------------------
    // Commands
    // ---------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("herobrineghost")) return false;

        if (args.length == 1) {
            String sub = args[0];

            if (sub.equalsIgnoreCase("reload")) {
                loadConfigFromFile();
                sender.sendMessage("HerobrineGhost config reloaded. Username = " + herobrineUsername);
                return true;
            }

            if (sub.equalsIgnoreCase("test")) {
                if (sender instanceof Player) {
                    Player p = (Player) sender;
                    spawnTestNpc(p);
                } else {
                    sender.sendMessage("Only players can use the test command.");
                }
                return true;
            }
        }

        sender.sendMessage("Usage: /" + label + " [reload|test]");
        return true;
    }

    // ---------------------------------------------------------
    // Test helper
    // ---------------------------------------------------------

    private void spawnTestNpc(Player viewer) {
        try {
            Location base = viewer.getLocation();
            World world = base.getWorld();
            if (isWorldExcluded(world)) {
                viewer.sendMessage("HerobrineGhost: world is excluded.");
                return;
            }

            float yaw = base.getYaw();
            double rad = Math.toRadians(yaw);

            double dx = -Math.sin(rad);
            double dz =  Math.cos(rad);
            double distance = 6.0D;

            double gx = base.getX() + dx * distance;
            double gz = base.getZ() + dz * distance;

            int blockX = (int) Math.floor(gx);
            int blockZ = (int) Math.floor(gz);
            int blockY = world.getHighestBlockYAt(blockX, blockZ);
            if (blockY <= 0) blockY = base.getBlockY();

            Location npcLoc = new Location(
                world,
                blockX + 0.5D,
                blockY,
                blockZ + 0.5D,
                0.0F,
                0.0F
            );

            orientLocationTowards(npcLoc, base);

            Block standingOn = world.getBlockAt(npcLoc);
            if (!standingOn.isEmpty()) {
                npcLoc.setY(npcLoc.getY() + 1.0D);
            }

            spawnClientGhost(viewer, npcLoc);
            viewer.sendMessage("HerobrineGhost: NPC test spawn triggered.");
            System.out.println("[HerobrineGhost] Test NPC spawn for " + viewer.getName() +
                    " at " + npcLoc.getX() + "," + npcLoc.getY() + "," + npcLoc.getZ());
        } catch (Throwable t) {
            System.out.println("[HerobrineGhost] Test NPC spawn failed: " + t.toString());
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------
    // Ignite listener
    // ---------------------------------------------------------

    private static class TotemBlockListener extends BlockListener {
        private final HerobrineGhost plugin;

        public TotemBlockListener(HerobrineGhost plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onBlockIgnite(BlockIgniteEvent event) {
            if (event.isCancelled()) return;

            Block target = event.getBlock();
            if (target == null) return;

            Player player = event.getPlayer();
            if (player == null) return;

            plugin.handleTotemIgnite(player, target);
        }
    }
}
