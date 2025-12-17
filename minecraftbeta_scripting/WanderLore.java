/*
 * WanderLore - Beta 1.7.3 Bukkit/Poseidon
 * Author: ChatGPT
 *
 * Rare generated-chunk structures + very rare fake-player NPC encounters with simple dialogue.
 */

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

// CraftBukkit/NMS (Beta 1.7.3 style, no v1_ packages)
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import net.minecraft.server.EntityPlayer;
import net.minecraft.server.ItemInWorldManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Packet20NamedEntitySpawn;
import net.minecraft.server.Packet29DestroyEntity;
import net.minecraft.server.WorldServer;

public class WanderLore extends JavaPlugin implements Listener {

    private Logger log;
    private Properties pluginConfig;

    private String overworldName;
    private String netherName;

    private int minDistanceChunks;
    private int maxActiveNpcs;
    private int npcLookRange;
    private int npcInteractRange;

    private int structureChance;      // 1 in N per chunk
    private int majorStructureChance; // 1 in N per chunk
    private int npcAnchorChance;      // 1 in N per chunk (very rare)

    private boolean enableNether;
    private boolean enableOverworld;

    // Active NPCs in memory
    private final Map<Integer, LoreNpc> activeNpcsByEntityId = new HashMap<Integer, LoreNpc>();
    private final Map<String, ConversationState> conversationByPlayerName = new HashMap<String, ConversationState>();

    // Scheduler task id
    private int tickTaskId = -1;

    // Hidden marker constants
    private static final int ANCHOR_Y_OFFSET = -6; // below surface-ish
    private static final int ANCHOR_MAGIC_1 = 41;  // GOLD_BLOCK
    private static final int ANCHOR_MAGIC_2 = 49;  // OBSIDIAN
    private static final int ANCHOR_MAGIC_3 = 89;  // GLOWSTONE

    public void onEnable() {
        log = getServer().getLogger();
        loadOrCreateProperties();

        getServer().getPluginManager().registerEvents(this, this);

        // Tick task: prune NPCs when no players nearby, and do minor behaviors
        tickTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                tickNpcs();
            }
        }, 20L, 20L);

        log.info("[WanderLore] enabled for worlds: " + overworldName + " and " + netherName);
    }

    public void onDisable() {
        if (tickTaskId != -1) {
            getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }

        // Despawn all NPCs cleanly
        for (LoreNpc npc : new ArrayList<LoreNpc>(activeNpcsByEntityId.values())) {
            despawnNpc(npc);
        }
        activeNpcsByEntityId.clear();
        conversationByPlayerName.clear();
        log.info("[WanderLore] disabled.");
    }

    private void loadOrCreateProperties() {
        pluginConfig = new Properties();
        File dataDir = getDataFolder();
        if (!dataDir.exists()) dataDir.mkdirs();

        File configFile = new File(dataDir, "config.properties");
        if (!configFile.exists()) {
            try {
                FileOutputStream fos = new FileOutputStream(configFile);
                String defaultText =
                        "# WanderLore config\n" +
                        "overworldName=thebetawasbetter\n" +
                        "netherName=thebetawasbetter_nether\n" +
                        "\n" +
                        "# Only generate beyond this many chunks from spawn (prevents clutter near spawn)\n" +
                        "minDistanceChunks=10\n" +
                        "\n" +
                        "# Chances: 1 in N per newly generated chunk\n" +
                        "structureChance=900\n" +
                        "majorStructureChance=6000\n" +
                        "npcAnchorChance=20000\n" +
                        "\n" +
                        "# NPC tuning\n" +
                        "maxActiveNpcs=10\n" +
                        "npcLookRange=18\n" +
                        "npcInteractRange=5\n" +
                        "\n" +
                        "# World toggles\n" +
                        "enableOverworld=true\n" +
                        "enableNether=true\n";
                fos.write(defaultText.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed creating config.properties", e);
            }
        }

        try {
            FileInputStream fis = new FileInputStream(configFile);
            pluginConfig.load(fis);
            fis.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed reading config.properties", e);
        }

        overworldName = pluginConfig.getProperty("overworldName", "thebetawasbetter").trim();
        netherName = pluginConfig.getProperty("netherName", overworldName + "_nether").trim();

        minDistanceChunks = readInt("minDistanceChunks", 10);
        structureChance = readInt("structureChance", 900);
        majorStructureChance = readInt("majorStructureChance", 6000);
        npcAnchorChance = readInt("npcAnchorChance", 20000);

        maxActiveNpcs = readInt("maxActiveNpcs", 10);
        npcLookRange = readInt("npcLookRange", 18);
        npcInteractRange = readInt("npcInteractRange", 5);

        enableOverworld = readBool("enableOverworld", true);
        enableNether = readBool("enableNether", true);
    }

    private int readInt(String key, int def) {
        try { return Integer.parseInt(pluginConfig.getProperty(key, "" + def).trim()); }
        catch (Exception e) { return def; }
    }

    private boolean readBool(String key, boolean def) {
        String v = pluginConfig.getProperty(key, "" + def).trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes");
    }

    private boolean isTargetWorld(World w) {
        if (w == null) return false;
        String name = w.getName();
        if (name.equalsIgnoreCase(overworldName)) return enableOverworld;
        if (name.equalsIgnoreCase(netherName)) return enableNether;
        return false;
    }

    private boolean isNether(World w) {
        return w != null && w.getName().equalsIgnoreCase(netherName);
    }

    private boolean farEnoughFromSpawn(World w, int chunkX, int chunkZ) {
        Location spawn = w.getSpawnLocation();
        int spawnChunkX = spawn.getBlockX() >> 4;
        int spawnChunkZ = spawn.getBlockZ() >> 4;
        int dx = chunkX - spawnChunkX;
        int dz = chunkZ - spawnChunkZ;
        return (dx * dx + dz * dz) >= (minDistanceChunks * minDistanceChunks);
    }

    // Deterministic per-chunk RNG (no storage)
    private Random chunkRandom(World w, int chunkX, int chunkZ, long salt) {
        long seed = w.getSeed();
        long h = seed ^ (chunkX * 341873128712L) ^ (chunkZ * 132897987541L) ^ salt;
        return new Random(h);
    }

    @EventHandler
    public void onChunkPopulate(ChunkPopulateEvent event) {
        World w = event.getWorld();
        if (!isTargetWorld(w)) return;

        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        if (!farEnoughFromSpawn(w, cx, cz)) return;

        long saltBase = isNether(w) ? 0xBEEFCAFE : 0xC0FFEE11;
        Random rng = chunkRandom(w, cx, cz, saltBase);

        if (majorStructureChance > 0 && rng.nextInt(majorStructureChance) == 0) {
            generateMajorStructure(w, cx, cz, rng);
            return;
        }

        if (structureChance > 0 && rng.nextInt(structureChance) == 0) {
            generateMinorStructure(w, cx, cz, rng);
        }

        Random npcRng = chunkRandom(w, cx, cz, saltBase ^ 0x1234ABCD);
        if (npcAnchorChance > 0 && npcRng.nextInt(npcAnchorChance) == 0) {
            placeNpcAnchorMarker(w, cx, cz, npcRng);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World w = event.getWorld();
        if (!isTargetWorld(w)) return;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        if (conversationByPlayerName.containsKey(player.getName())) {
            return;
        }

        LoreNpc npc = findLookedAtNpc(player);
        if (npc == null) return;

        startNpcConversation(player, npc);
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        ConversationState state = conversationByPlayerName.get(player.getName());
        if (state == null) return;

        event.setCancelled(true);

        String msg = event.getMessage();
        if (msg == null) msg = "";
        msg = msg.trim().toLowerCase();

        boolean yes = msg.equals("y") || msg.equals("yes") || msg.equals("yeah") || msg.equals("yep");
        boolean no = msg.equals("n") || msg.equals("no") || msg.equals("nope") || msg.equals("nah");

        if (!yes && !no) {
            player.sendMessage(ChatColor.GRAY + "[...] (answer yes or no)");
            return;
        }

        continueConversation(player, state, yes);
    }

    private LoreNpc findLookedAtNpc(Player player) {
        if (player.getWorld() == null) return null;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        if (dir == null) return null;

        LoreNpc best = null;
        double bestScore = 0.92;

        for (LoreNpc npc : activeNpcsByEntityId.values()) {
            if (!npc.isValid()) continue;
            if (!npc.worldName.equalsIgnoreCase(player.getWorld().getName())) continue;

            Location npcLoc = npc.getBukkitLocation(getServer());
            if (npcLoc == null) continue;

            double dist = npcLoc.distance(player.getLocation());
            if (dist > npcInteractRange) continue;

            Vector to = npcLoc.toVector().subtract(eye.toVector()).normalize();
            double dot = dir.dot(to);
            if (dot > bestScore) {
                bestScore = dot;
                best = npc;
            }
        }
        return best;
    }

    private void startNpcConversation(Player player, LoreNpc npc) {
        ConversationState state = new ConversationState();
        state.npcEntityId = npc.entityId;
        state.step = 0;
        state.branch = npc.dialogBranch;

        conversationByPlayerName.put(player.getName(), state);

        player.sendMessage(ChatColor.DARK_GRAY + npc.displayName + ": " + ChatColor.GRAY + npc.getOpeningLine());
        player.sendMessage(ChatColor.GRAY + "(yes / no)");
    }

    private void continueConversation(Player player, ConversationState state, boolean yes) {
        LoreNpc npc = activeNpcsByEntityId.get(state.npcEntityId);
        if (npc == null || !npc.isValid()) {
            conversationByPlayerName.remove(player.getName());
            player.sendMessage(ChatColor.GRAY + "The presence is gone.");
            return;
        }

        state.step++;

        String line = npc.getNextLine(state.step, yes);
        if (line == null) {
            conversationByPlayerName.remove(player.getName());
            player.sendMessage(ChatColor.DARK_GRAY + npc.displayName + ": " + ChatColor.GRAY + npc.getFarewellLine());
            if (npc.rng.nextInt(4) == 0) {
                vanishNpc(npc);
            }
            return;
        }

        player.sendMessage(ChatColor.DARK_GRAY + npc.displayName + ": " + ChatColor.GRAY + line);
        player.sendMessage(ChatColor.GRAY + "(yes / no)");
    }

    // ---- Structure generation ----

    private void generateMinorStructure(World w, int cx, int cz, Random rng) {
        int x = (cx << 4) + 4 + rng.nextInt(8);
        int z = (cz << 4) + 4 + rng.nextInt(8);

        if (isNether(w)) {
            Location base = findNetherFloor(w, x, z);
            if (base == null) return;
            int pick = rng.nextInt(3);
            if (pick == 0) buildNetherAltar(base, rng);
            else if (pick == 1) buildBasaltSpine(base, rng);
            else buildObsidianEye(base, rng);
        } else {
            int y = w.getHighestBlockYAt(x, z);
            Location base = new Location(w, x, y, z);
            int pick = rng.nextInt(5);
            if (pick == 0) buildRuinedHut(base, rng);
            else if (pick == 1) buildStonehenge(base, rng);
            else if (pick == 2) buildSinkholeStairs(base, rng);
            else if (pick == 3) buildBrokenRoadMarker(base, rng);
            else buildWatcherCache(base, rng);
        }
    }

    private void generateMajorStructure(World w, int cx, int cz, Random rng) {
        int x = (cx << 4) + 2 + rng.nextInt(12);
        int z = (cz << 4) + 2 + rng.nextInt(12);

        if (isNether(w)) {
            Location base = findNetherFloor(w, x, z);
            if (base == null) return;
            int pick = rng.nextInt(2);
            if (pick == 0) buildNetherTrial(base, rng);
            else buildRitualGate(base, rng);
        } else {
            int y = w.getHighestBlockYAt(x, z);
            Location base = new Location(w, x, y, z);

            int pick = rng.nextInt(3);
            if (pick == 0) buildSandstonePyramid(base, rng);
            else if (pick == 1) buildCaveTemple(base, rng);
            else buildPlatformTrial(base, rng);
        }
    }

    private Location findNetherFloor(World w, int x, int z) {
        int startY = Math.min(118, w.getMaxHeight() - 2);
        for (int y = startY; y > 10; y--) {
            int type = w.getBlockAt(x, y, z).getTypeId();
            int above = w.getBlockAt(x, y + 1, z).getTypeId();
            if (type != 0 && above == 0) {
                return new Location(w, x, y + 1, z);
            }
        }
        return null;
    }

    // ---- Minor overworld structures ----

    private void buildRuinedHut(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int cobble = 4;
        int plank = 5;
        int fence = 85;
        int sign = 63;

        clearArea(w, bx - 3, by, bz - 3, bx + 3, by + 5, bz + 3);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                w.getBlockAt(bx + dx, by - 1, bz + dz).setTypeId(cobble);
            }
        }

        for (int y = 0; y <= 2; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                setIfChance(w, bx + dx, by + y, bz - 2, cobble, rng, 0.85);
                setIfChance(w, bx + dx, by + y, bz + 2, cobble, rng, 0.80);
            }
            for (int dz = -2; dz <= 2; dz++) {
                setIfChance(w, bx - 2, by + y, bz + dz, cobble, rng, 0.82);
                setIfChance(w, bx + 2, by + y, bz + dz, cobble, rng, 0.78);
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (rng.nextInt(3) == 0) {
                    w.getBlockAt(bx + dx, by + 3, bz + dz).setTypeId(plank);
                }
            }
        }

        w.getBlockAt(bx, by, bz).setTypeId(fence);
        w.getBlockAt(bx, by + 1, bz).setTypeId(fence);

        w.getBlockAt(bx, by + 1, bz + 1).setTypeId(sign);
        writeSign(w, bx, by + 1, bz + 1, new String[] {
                "We fled",
                "from the",
                "pale watcher",
                "to below."
        });
    }

    private void buildStonehenge(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int stone = 1;
        int moss = 48;
        int sign = 63;

        clearArea(w, bx - 6, by, bz - 6, bx + 6, by + 7, bz + 6);

        for (int i = 0; i < 12; i++) {
            double ang = (Math.PI * 2.0) * (i / 12.0);
            int px = bx + (int)Math.round(Math.cos(ang) * 5.0);
            int pz = bz + (int)Math.round(Math.sin(ang) * 5.0);
            int height = 3 + rng.nextInt(3);

            for (int y = 0; y < height; y++) {
                w.getBlockAt(px, by + y, pz).setTypeId(rng.nextInt(4) == 0 ? moss : stone);
            }

            if (rng.nextInt(2) == 0) {
                w.getBlockAt(px, by + height, pz).setTypeId(stone);
            }
        }

        w.getBlockAt(bx, by, bz).setTypeId(moss);

        w.getBlockAt(bx + 2, by, bz).setTypeId(sign);
        writeSign(w, bx + 2, by, bz, new String[] {
                "Twelve stand.",
                "One watches.",
                "Speak \"yes\"",
                "to lie."
        });
    }

    private void buildSinkholeStairs(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int cobble = 4;
        int torch = 50;
        int sign = 63;

        for (int y = 0; y < 10; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    w.getBlockAt(bx + dx, by - y, bz + dz).setTypeId(0);
                }
            }
            w.getBlockAt(bx + 1, by - y, bz).setTypeId(cobble);
            if (y % 3 == 0) {
                w.getBlockAt(bx, by - y, bz + 2).setTypeId(torch);
            }
        }

        int bottomY = by - 10;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                w.getBlockAt(bx + dx, bottomY, bz + dz).setTypeId(cobble);
                w.getBlockAt(bx + dx, bottomY + 1, bz + dz).setTypeId(0);
                w.getBlockAt(bx + dx, bottomY + 2, bz + dz).setTypeId(0);
            }
        }

        w.getBlockAt(bx, bottomY + 1, bz).setTypeId(sign);
        writeSign(w, bx, bottomY + 1, bz, new String[] {
                "Below the",
                "broken road,",
                "the cult",
                "counts steps."
        });
    }

    private void buildBrokenRoadMarker(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int cobble = 4;
        int gravel = 13;
        int sign = 63;

        for (int dz = -6; dz <= 6; dz++) {
            int type = (rng.nextInt(5) == 0) ? gravel : cobble;
            w.getBlockAt(bx, by - 1, bz + dz).setTypeId(type);
            if (rng.nextInt(3) == 0) w.getBlockAt(bx + 1, by - 1, bz + dz).setTypeId(type);
            if (rng.nextInt(3) == 0) w.getBlockAt(bx - 1, by - 1, bz + dz).setTypeId(type);
        }

        int gapDz = -1 + rng.nextInt(3);
        for (int dx = -1; dx <= 1; dx++) {
            w.getBlockAt(bx + dx, by - 1, bz + gapDz).setTypeId(0);
        }

        w.getBlockAt(bx + 2, by, bz + 2).setTypeId(sign);
        writeSign(w, bx + 2, by, bz + 2, new String[] {
                "The road",
                "does not",
                "lead there",
                "anymore."
        });
    }

    private void buildWatcherCache(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int obsidian = 49;
        int chest = 54;
        int sign = 63;

        int y = by - 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(bx + dx, y, bz + dz).setTypeId(obsidian);
                w.getBlockAt(bx + dx, y + 1, bz + dz).setTypeId(0);
                w.getBlockAt(bx + dx, y + 2, bz + dz).setTypeId(0);
            }
        }

        w.getBlockAt(bx, y + 1, bz).setTypeId(chest);

        Block b = w.getBlockAt(bx, y + 1, bz);
        if (b.getState() instanceof Chest) {
            Chest c = (Chest)b.getState();
            maybeAddItem(c, 339, 0, 1 + rng.nextInt(5), rng); // paper
            maybeAddItem(c, 263, 0, 1 + rng.nextInt(3), rng); // coal
            maybeAddItem(c, 331, 0, 1 + rng.nextInt(6), rng); // redstone
            maybeAddItem(c, 264, 0, rng.nextInt(2), rng);     // diamond
            c.update();
        }

        w.getBlockAt(bx, by, bz).setTypeId(sign);
        writeSign(w, bx, by, bz, new String[] {
                "He leaves",
                "small gifts.",
                "Do not",
                "accept."
        });
    }

    // ---- Major overworld structures ----

    private void buildSandstonePyramid(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int sandstone = 24;
        int cobble = 4;
        int torch = 50;
        int sign = 63;

        clearArea(w, bx - 10, by, bz - 10, bx + 10, by + 14, bz + 10);

        int size = 9;
        int height = 7;
        for (int h = 0; h < height; h++) {
            int half = size - h;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    w.getBlockAt(bx + dx, by + h, bz + dz).setTypeId(sandstone);
                }
            }
        }

        int chamberY = by + 2;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = 0; y <= 2; y++) {
                    w.getBlockAt(bx + dx, chamberY + y, bz + dz).setTypeId(0);
                }
            }
        }

        for (int y = 0; y <= 2; y++) {
            w.getBlockAt(bx, chamberY + y, bz - 3).setTypeId(0);
        }
        w.getBlockAt(bx, chamberY + 1, bz - 4).setTypeId(torch);

        w.getBlockAt(bx, chamberY, bz).setTypeId(cobble);
        w.getBlockAt(bx, chamberY + 1, bz).setTypeId(sign);
        writeSign(w, bx, chamberY + 1, bz, new String[] {
                "A name",
                "scratched",
                "away.",
                "Only eyes."
        });
    }

    private void buildCaveTemple(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int moss = 48;
        int cobble = 4;
        int obsidian = 49;
        int sign = 63;
        int chest = 54;

        int roomY = Math.max(12, by - (12 + rng.nextInt(10)));

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = 0; y <= 5; y++) {
                    int ax = bx + dx;
                    int ay = roomY + y;
                    int az = bz + dz;
                    boolean wall = (Math.abs(dx) == 6 || Math.abs(dz) == 6 || y == 0 || y == 5);
                    if (wall) {
                        int t = (rng.nextInt(5) == 0) ? moss : cobble;
                        w.getBlockAt(ax, ay, az).setTypeId(t);
                    } else {
                        w.getBlockAt(ax, ay, az).setTypeId(0);
                    }
                }
            }
        }

        w.getBlockAt(bx, roomY + 1, bz).setTypeId(obsidian);
        w.getBlockAt(bx, roomY + 2, bz).setTypeId(obsidian);
        w.getBlockAt(bx, roomY + 3, bz).setTypeId(sign);
        writeSign(w, bx, roomY + 3, bz, new String[] {
                "It saw you",
                "before you",
                "saw it.",
                "Turn back."
        });

        w.getBlockAt(bx + 4, roomY + 1, bz).setTypeId(chest);
        Block b = w.getBlockAt(bx + 4, roomY + 1, bz);
        if (b.getState() instanceof Chest) {
            Chest c = (Chest)b.getState();
            maybeAddItem(c, 265, 0, 1 + rng.nextInt(5), rng);
            maybeAddItem(c, 287, 0, 2 + rng.nextInt(6), rng);
            maybeAddItem(c, 289, 0, 1 + rng.nextInt(4), rng);
            maybeAddItem(c, 331, 0, 1 + rng.nextInt(8), rng);
            c.update();
        }

        int steps = Math.min(28, by - roomY);
        for (int i = 0; i < steps; i++) {
            int sx = bx - 6 + (i % 3);
            int sy = roomY + 1 + i;
            int sz = bz - 6;
            w.getBlockAt(sx, sy, sz).setTypeId(0);
            w.getBlockAt(sx, sy + 1, sz).setTypeId(0);
        }
    }

    private void buildPlatformTrial(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY() + 6;
        int bz = base.getBlockZ();

        int cobble = 4;
        int obsidian = 49;
        int sign = 63;
        int gold = 41;

        clearArea(w, bx - 12, by - 2, bz - 12, bx + 12, by + 12, bz + 12);

        int steps = 18 + rng.nextInt(10);
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double radius = 3.0;

        int px = bx;
        int py = by;
        int pz = bz;

        for (int i = 0; i < steps; i++) {
            radius += 0.35;
            angle += 0.7;

            px = bx + (int)Math.round(Math.cos(angle) * radius);
            pz = bz + (int)Math.round(Math.sin(angle) * radius);
            py = by + (i / 4);

            w.getBlockAt(px, py, pz).setTypeId((rng.nextInt(9) == 0) ? obsidian : cobble);
        }

        w.getBlockAt(px, py + 1, pz).setTypeId(gold);
        w.getBlockAt(px + 1, py + 1, pz).setTypeId(sign);
        writeSign(w, px + 1, py + 1, pz, new String[] {
                "You climbed",
                "for a lie.",
                "Now climb",
                "again."
        });
    }

    // ---- Nether structures ----

    private void buildNetherAltar(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int obsidian = 49;
        int glow = 89;
        int netherrack = 87;
        int fire = 51;
        int sign = 63;

        clearArea(w, bx - 5, by, bz - 5, bx + 5, by + 7, bz + 5);

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                w.getBlockAt(bx + dx, by - 1, bz + dz).setTypeId(obsidian);
            }
        }

        for (int i = 0; i < 6; i++) {
            int px = bx + (rng.nextBoolean() ? 3 : -3);
            int pz = bz + (-2 + rng.nextInt(5));
            int h = 2 + rng.nextInt(4);
            for (int y = 0; y < h; y++) {
                w.getBlockAt(px, by + y, pz).setTypeId(obsidian);
            }
        }

        w.getBlockAt(bx, by, bz).setTypeId(glow);
        w.getBlockAt(bx, by + 1, bz).setTypeId(fire);

        for (int dx = -4; dx <= 4; dx++) {
            w.getBlockAt(bx + dx, by - 2, bz - 4).setTypeId(netherrack);
            w.getBlockAt(bx + dx, by - 2, bz + 4).setTypeId(netherrack);
        }

        w.getBlockAt(bx + 1, by, bz + 2).setTypeId(sign);
        writeSign(w, bx + 1, by, bz + 2, new String[] {
                "The gate",
                "hungers.",
                "Bring a",
                "name."
        });
    }

    private void buildBasaltSpine(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int netherrack = 87;
        int obsidian = 49;

        int len = 10 + rng.nextInt(14);
        int dirX = rng.nextBoolean() ? 1 : 0;
        int dirZ = (dirX == 1) ? 0 : 1;
        if (rng.nextBoolean()) { dirX *= -1; dirZ *= -1; }

        int x = bx, y = by, z = bz;
        for (int i = 0; i < len; i++) {
            int h = 2 + rng.nextInt(4);
            for (int yy = 0; yy < h; yy++) {
                w.getBlockAt(x, y + yy, z).setTypeId((rng.nextInt(5) == 0) ? obsidian : netherrack);
            }
            x += dirX;
            z += dirZ;
            if (rng.nextInt(3) == 0) y += (rng.nextBoolean() ? 1 : -1);
            y = Math.max(8, Math.min(120, y));
        }
    }

    private void buildObsidianEye(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int obsidian = 49;
        int glow = 89;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = (dx * dx) / 9.0 + (dz * dz) / 4.0;
                if (d <= 1.0) {
                    w.getBlockAt(bx + dx, by, bz + dz).setTypeId(obsidian);
                    w.getBlockAt(bx + dx, by + 1, bz + dz).setTypeId(obsidian);
                }
            }
        }
        w.getBlockAt(bx, by + 1, bz).setTypeId(glow);
    }

    private void buildNetherTrial(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY() + 4;
        int bz = base.getBlockZ();

        int obsidian = 49;
        int glow = 89;
        int sign = 63;

        clearArea(w, bx - 14, by - 2, bz - 14, bx + 14, by + 12, bz + 14);

        int platforms = 14 + rng.nextInt(10);
        int x = bx, y = by, z = bz;

        for (int i = 0; i < platforms; i++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    w.getBlockAt(x + dx, y, z + dz).setTypeId(obsidian);
                }
            }
            if (rng.nextInt(4) == 0) w.getBlockAt(x, y + 1, z).setTypeId(glow);

            x += -2 + rng.nextInt(5);
            z += -2 + rng.nextInt(5);
            if (rng.nextInt(3) == 0) y += 1;
        }

        w.getBlockAt(x + 2, y + 1, z).setTypeId(sign);
        writeSign(w, x + 2, y + 1, z, new String[] {
                "The pale",
                "one doesn't",
                "burn here.",
                "Neither do you."
        });
    }

    private void buildRitualGate(Location base, Random rng) {
        World w = base.getWorld();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();

        int obsidian = 49;
        int glow = 89;
        int sign = 63;

        clearArea(w, bx - 8, by, bz - 8, bx + 8, by + 12, bz + 8);

        for (int y = 0; y <= 8; y++) {
            w.getBlockAt(bx - 3, by + y, bz).setTypeId(obsidian);
            w.getBlockAt(bx + 3, by + y, bz).setTypeId(obsidian);
        }
        for (int x = -3; x <= 3; x++) {
            w.getBlockAt(bx + x, by, bz).setTypeId(obsidian);
            w.getBlockAt(bx + x, by + 8, bz).setTypeId(obsidian);
        }

        w.getBlockAt(bx, by + 10, bz).setTypeId(glow);

        w.getBlockAt(bx - 1, by + 1, bz + 2).setTypeId(sign);
        writeSign(w, bx - 1, by + 1, bz + 2, new String[] {
                "Do you",
                "want to",
                "be seen?",
                "yes / no"
        });
    }

    // ---- NPC anchor marker ----

    private void placeNpcAnchorMarker(World w, int cx, int cz, Random rng) {
        int x = (cx << 4) + 7;
        int z = (cz << 4) + 7;

        int y;
        if (isNether(w)) {
            Location floor = findNetherFloor(w, x, z);
            if (floor == null) return;
            y = floor.getBlockY() + ANCHOR_Y_OFFSET;
        } else {
            y = w.getHighestBlockYAt(x, z) + ANCHOR_Y_OFFSET;
        }

        y = Math.max(6, Math.min(w.getMaxHeight() - 6, y));

        w.getBlockAt(x, y, z).setTypeId(ANCHOR_MAGIC_1);
        w.getBlockAt(x, y + 1, z).setTypeId(ANCHOR_MAGIC_2);
        w.getBlockAt(x, y + 2, z).setTypeId(ANCHOR_MAGIC_3);

        if (!isNether(w) && rng.nextInt(3) == 0) {
            int sy = w.getHighestBlockYAt(x, z);
            w.getBlockAt(x, sy, z).setTypeId(63);
            writeSign(w, x, sy, z, new String[] {
                    "Someone",
                    "stood here.",
                    "You missed",
                    "them."
            });
        }
    }

    private boolean hasNpcAnchorAt(World w, int cx, int cz) {
        int x = (cx << 4) + 7;
        int z = (cz << 4) + 7;
        int minY = 6;
        int maxY = Math.min(120, w.getMaxHeight() - 4);

        for (int y = minY; y <= maxY; y++) {
            if (w.getBlockAt(x, y, z).getTypeId() == ANCHOR_MAGIC_1 &&
                w.getBlockAt(x, y + 1, z).getTypeId() == ANCHOR_MAGIC_2 &&
                w.getBlockAt(x, y + 2, z).getTypeId() == ANCHOR_MAGIC_3) {
                return true;
            }
        }
        return false;
    }

    // ---- NPC tick / spawn / despawn ----

    private void tickNpcs() {
        for (World w : getServer().getWorlds()) {
            if (!isTargetWorld(w)) continue;

            for (Player p : w.getPlayers()) {
                if (p == null) continue;
                trySpawnNpcNearPlayer(p);
            }
        }

        for (LoreNpc npc : new ArrayList<LoreNpc>(activeNpcsByEntityId.values())) {
            if (!npc.isValid()) {
                activeNpcsByEntityId.remove(npc.entityId);
                continue;
            }

            Player nearest = findNearestPlayer(npc);
            if (nearest == null) {
                despawnNpc(npc);
                continue;
            }

            double dist = nearest.getLocation().distance(npc.getBukkitLocation(getServer()));
            if (dist > npcLookRange) {
                despawnNpc(npc);
                continue;
            }

            if (npc.rng.nextInt(240) == 0) {
                nearest.sendMessage(ChatColor.DARK_GRAY + "[" + npc.displayName + "] " + ChatColor.GRAY + npc.getAmbientLine());
            }
        }

        while (activeNpcsByEntityId.size() > maxActiveNpcs) {
            LoreNpc any = activeNpcsByEntityId.values().iterator().next();
            despawnNpc(any);
        }
    }

    private void trySpawnNpcNearPlayer(Player p) {
        if (activeNpcsByEntityId.size() >= maxActiveNpcs) return;

        World w = p.getWorld();
        if (w == null || !isTargetWorld(w)) return;

        Location pl = p.getLocation();
        int pcx = pl.getBlockX() >> 4;
        int pcz = pl.getBlockZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;

                if (!farEnoughFromSpawn(w, cx, cz)) continue;
                if (!w.isChunkLoaded(cx, cz)) continue;
                if (!hasNpcAnchorAt(w, cx, cz)) continue;
                if (isNpcAlreadyNearChunk(w.getName(), cx, cz)) continue;

                int x = (cx << 4) + 8;
                int z = (cz << 4) + 8;
                int y = isNether(w) ? findSafeNetherY(w, x, z) : w.getHighestBlockYAt(x, z);

                Location spawnLoc = new Location(w, x + 0.5, y + 1, z + 0.5);
                spawnLoreNpcAt(spawnLoc, w, cx, cz);
                return;
            }
        }
    }

    private boolean isNpcAlreadyNearChunk(String worldName, int cx, int cz) {
        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;

        for (LoreNpc npc : activeNpcsByEntityId.values()) {
            if (!npc.worldName.equalsIgnoreCase(worldName)) continue;
            Location loc = npc.getBukkitLocation(getServer());
            if (loc == null) continue;

            int ncx = loc.getBlockX() >> 4;
            int ncz = loc.getBlockZ() >> 4;
            if (Math.abs(ncx - cx) <= 0 && Math.abs(ncz - cz) <= 0) return true;

            if (Math.abs(loc.getBlockX() - centerX) < 24 && Math.abs(loc.getBlockZ() - centerZ) < 24) return true;
        }
        return false;
    }

    private int findSafeNetherY(World w, int x, int z) {
        Location floor = findNetherFloor(w, x, z);
        if (floor == null) return 64;
        return floor.getBlockY();
    }

    private Player findNearestPlayer(LoreNpc npc) {
        World w = getServer().getWorld(npc.worldName);
        if (w == null) return null;

        Location nloc = npc.getBukkitLocation(getServer());
        if (nloc == null) return null;

        Player best = null;
        double bestDist = 999999;

        for (Player p : w.getPlayers()) {
            double d = p.getLocation().distance(nloc);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }

        if (best == null) return null;
        if (bestDist > npcLookRange) return null;
        return best;
    }

    private void spawnLoreNpcAt(Location loc, World bukkitWorld, int chunkX, int chunkZ) {
        try {
            MinecraftServer ms = ((CraftServer)getServer()).getServer();
            WorldServer ws = ((CraftWorld)bukkitWorld).getHandle();

            Random rng = chunkRandom(bukkitWorld, chunkX, chunkZ, 0xDEADBEEF);
            NpcArchetype archetype = NpcArchetype.pick(rng, isNether(bukkitWorld));

            ItemInWorldManager manager = new ItemInWorldManager(ws);
            EntityPlayer ep = new EntityPlayer(ms, ws, archetype.name, manager);
            ep.setPositionRotation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());

            ws.addEntity(ep);

            LoreNpc npc = new LoreNpc();
            npc.entityId = ep.id;
            npc.nmsPlayer = ep;
            npc.worldName = bukkitWorld.getName();
            npc.displayName = archetype.displayName;
            npc.dialogBranch = archetype.dialogBranch;
            npc.rng = rng;

            activeNpcsByEntityId.put(npc.entityId, npc);

            Packet20NamedEntitySpawn spawnPacket = new Packet20NamedEntitySpawn(ep);
            for (Player p : bukkitWorld.getPlayers()) {
                if (p.getLocation().distance(loc) <= npcLookRange) {
                    ((org.bukkit.craftbukkit.entity.CraftPlayer)p).getHandle().netServerHandler.sendPacket(spawnPacket);
                }
            }

        } catch (Throwable t) {
            log.warning("[WanderLore] Failed to spawn NPC (NMS mismatch?): " + t.getMessage());
        }
    }

    private void vanishNpc(LoreNpc npc) {
        World w = getServer().getWorld(npc.worldName);
        if (w != null) {
            Location loc = npc.getBukkitLocation(getServer());
            for (Player p : w.getPlayers()) {
                if (loc != null && p.getLocation().distance(loc) <= npcLookRange) {
                    p.sendMessage(ChatColor.GRAY + "A chill passes. Something is gone.");
                }
            }
        }
        despawnNpc(npc);
    }

    private void despawnNpc(LoreNpc npc) {
        try {
            World w = getServer().getWorld(npc.worldName);
            if (w != null) {
                Packet29DestroyEntity destroy = new Packet29DestroyEntity(npc.entityId);
                for (Player p : w.getPlayers()) {
                    ((org.bukkit.craftbukkit.entity.CraftPlayer)p).getHandle().netServerHandler.sendPacket(destroy);
                }
            }

            if (npc.nmsPlayer != null) {
                WorldServer ws = ((CraftWorld)getServer().getWorld(npc.worldName)).getHandle();
                ws.removeEntity(npc.nmsPlayer);
            }
        } catch (Throwable t) {
        } finally {
            activeNpcsByEntityId.remove(npc.entityId);
        }
    }

    // ---- Utility helpers ----

    private void clearArea(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        minY = Math.max(1, minY);
        maxY = Math.min(w.getMaxHeight() - 1, maxY);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    int type = w.getBlockAt(x, y, z).getTypeId();
                    if (type == 0 || type == 18 || type == 6 || type == 31 || type == 32 || type == 37 || type == 38) {
                        w.getBlockAt(x, y, z).setTypeId(0);
                    }
                }
            }
        }
    }

    private void setIfChance(World w, int x, int y, int z, int typeId, Random rng, double chance) {
        if (rng.nextDouble() <= chance) {
            w.getBlockAt(x, y, z).setTypeId(typeId);
        }
    }

    private void writeSign(World w, int x, int y, int z, String[] lines) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof Sign) {
            Sign s = (Sign)b.getState();
            for (int i = 0; i < 4; i++) {
                s.setLine(i, (lines != null && i < lines.length) ? lines[i] : "");
            }
            s.update();
        }
    }

    private void maybeAddItem(Chest chest, int typeId, int data, int amount, Random rng) {
        if (amount <= 0) return;
        for (int i = 0; i < chest.getInventory().getSize(); i++) {
            if (chest.getInventory().getItem(i) == null) {
                chest.getInventory().setItem(i, new org.bukkit.inventory.ItemStack(typeId, amount, (short)data));
                return;
            }
        }
    }

    // ---- Data classes ----

    private static class ConversationState {
        int npcEntityId;
        int step;
        int branch;
    }

    private static class LoreNpc {
        int entityId;
        EntityPlayer nmsPlayer;
        String worldName;
        String displayName;
        int dialogBranch;
        Random rng;

        boolean isValid() { return nmsPlayer != null; }

        Location getBukkitLocation(org.bukkit.Server server) {
            World w = server.getWorld(worldName);
            if (w == null || nmsPlayer == null) return null;
            return new Location(w, nmsPlayer.locX, nmsPlayer.locY, nmsPlayer.locZ, nmsPlayer.yaw, nmsPlayer.pitch);
        }

        String getOpeningLine() {
            switch (dialogBranch) {
                case 0: return "You are far from roads. Do you know why you walked here?";
                case 1: return "I saw your torch from under the stone. Do you want to be seen?";
                case 2: return "The nether remembers names. Do you still have yours?";
                case 3: return "They built this with hands. Not yours. Will you claim it?";
                default: return "You found a seam in the world. Will you pull it?";
            }
        }

        String getNextLine(int step, boolean yes) {
            if (dialogBranch == 0) {
                if (step == 1) return yes ? "Good. Then you know the watcher doesn't chase—he waits." : "Then you're walking in someone else's story.";
                if (step == 2) return yes ? "If you find twelve stones, stand in the center and lie." : "Don't answer aloud down there. It listens.";
                if (step == 3) return yes ? "The road broke on purpose." : "The gifts in obsidian are bait.";
                return null;
            }

            if (dialogBranch == 1) {
                if (step == 1) return yes ? "Then look at me. Truly. Do you see my eyes?" : "Wise. Keep your back to walls you did not build.";
                if (step == 2) return yes ? "The pale one borrows faces. He borrowed mine." : "A 'no' can be a prayer.";
                if (step == 3) return yes ? "If you meet him, do not swing first. Ask 'why'." : "Walk until the ground becomes a question.";
                return null;
            }

            if (dialogBranch == 2) {
                if (step == 1) return yes ? "Names are doors. Doors can be opened from both sides." : "Then you are safer than most.";
                if (step == 2) return yes ? "Find the gate that is not a portal." : "Don't build where you found the mark.";
                if (step == 3) return yes ? "If the fire doesn't hurt, you're already late." : "He likes quiet answers.";
                return null;
            }

            if (dialogBranch == 3) {
                if (step == 1) return yes ? "Claim it, and it will claim you back." : "Then leave it unbroken. It hates that.";
                if (step == 2) return yes ? "There was a cave once. Then there was a hole. Then there was a city." : "Do you hear footsteps when you stop?";
                if (step == 3) return yes ? "Bring coal. Bring redstone. Leave the diamond." : "Good. Keep moving.";
                return null;
            }

            if (step == 1) return yes ? "Then pull gently." : "Then don't come back here.";
            if (step == 2) return yes ? "Fragments make a map if you stop pretending they're random." : "Random is how it hides.";
            return null;
        }

        String getFarewellLine() {
            String[] lines = new String[] {
                    "Walk carefully. The world is not empty.",
                    "If you see a house that shouldn't exist, do not sleep in it.",
                    "Keep your torches. Give up your certainty.",
                    "The watcher likes straight tunnels.",
                    "Don't dig two by two, forever."
            };
            return lines[rng.nextInt(lines.length)];
        }

        String getAmbientLine() {
            String[] lines = new String[] {
                    "…stop…",
                    "behind you",
                    "the road is wrong",
                    "twelve stones",
                    "a borrowed face",
                    "names are doors",
                    "the nether remembers"
            };
            return lines[rng.nextInt(lines.length)];
        }
    }

    private static class NpcArchetype {
        String name;
        String displayName;
        int dialogBranch;

        static NpcArchetype pick(Random rng, boolean nether) {
            List<NpcArchetype> pool = new ArrayList<NpcArchetype>();

            pool.add(make("PaleWatcher", "Pale Watcher", 1));
            pool.add(make("OldPeculier", "Old Peculier", 0));
            pool.add(make("RoadWarden", "Road Warden", 3));
            pool.add(make("UnderStone", "Under-Stone", 0));

            if (nether) {
                pool.add(make("AshSpeaker", "Ash Speaker", 2));
                pool.add(make("GateTender", "Gate Tender", 2));
            } else {
                pool.add(make("LostMiner", "Lost Miner", 3));
                pool.add(make("DwarfHermit", "Dwarf Hermit", 0));
            }

            return pool.get(rng.nextInt(pool.size()));
        }

        static NpcArchetype make(String name, String display, int branch) {
            NpcArchetype a = new NpcArchetype();
            a.name = name;
            a.displayName = display;
            a.dialogBranch = branch;
            return a;
        }
    }
}
