package com.redchanit.stonepurifier;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public final class StonePurifier extends JavaPlugin implements Listener {

    private static final Set<Material> TARGET_MATERIALS = Set.of(
            Material.DIORITE,
            Material.ANDESITE,
            Material.GRANITE
    );

    private File configFile;
    private YamlConfiguration config;

    private int defaultRadiusChunks;
    private int chunksPerTick;
    private boolean processNewChunks;
    private boolean logNewChunkEdits;

    // Avoid multiple concurrent clean tasks
    private boolean cleanTaskRunning = false;

    @Override
    public void onEnable() {
        setupConfigFile();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("StonePurifier enabled. processNewChunks=" + processNewChunks
                + ", defaultRadiusChunks=" + defaultRadiusChunks
                + ", chunksPerTick=" + chunksPerTick);
    }

    @Override
    public void onDisable() {
        getLogger().info("StonePurifier disabled.");
    }

    private void setupConfigFile() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }

        configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            config = new YamlConfiguration();

            // Defaults
            config.set("processNewChunks", true);
            config.set("logNewChunkEdits", false);
            config.set("defaultRadiusChunks", 4);
            config.set("chunksPerTick", 1);

            try {
                config.save(configFile);
            } catch (IOException e) {
                getLogger().warning("Failed to write default config.yml: " + e.getMessage());
            }
        }
    }

    private void loadSettings() {
        config = YamlConfiguration.loadConfiguration(configFile);

        processNewChunks = config.getBoolean("processNewChunks", true);
        logNewChunkEdits = config.getBoolean("logNewChunkEdits", false);
        defaultRadiusChunks = Math.max(0, config.getInt("defaultRadiusChunks", 4));
        chunksPerTick = Math.max(1, config.getInt("chunksPerTick", 1));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!processNewChunks) return;
        if (!event.isNewChunk()) return;

        // Replace in brand-new chunks only
        Chunk chunk = event.getChunk();
        int replaced = replaceInChunk(chunk);

        if (logNewChunkEdits && replaced > 0) {
            getLogger().info("New chunk purified at " + chunk.getWorld().getName() + " "
                    + chunk.getX() + "," + chunk.getZ() + " replaced=" + replaced);
        }
    }

    /**
     * Replace DIORITE/ANDESITE/GRANITE blocks in a chunk with STONE.
     * Only exact block types are replaced (not polished variants, slabs, stairs, etc.).
     */
    private int replaceInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight(); // exclusive
        int replaced = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Material current = chunk.getBlock(x, y, z).getType();
                    if (TARGET_MATERIALS.contains(current)) {
                        chunk.getBlock(x, y, z).setType(Material.STONE, false);
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    private boolean senderIsOpOrHasPerm(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("stonepurifier.use");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("stonepurifier")) return false;

        if (!senderIsOpOrHasPerm(sender)) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage("StonePurifier config reloaded.");
            return true;
        }

        if (cleanTaskRunning) {
            sender.sendMessage("A StonePurifier cleanup task is already running.");
            return true;
        }

        int radius = defaultRadiusChunks;
        if (args.length >= 1) {
            try {
                radius = Math.max(0, Integer.parseInt(args[0]));
            } catch (NumberFormatException ignored) {
                sender.sendMessage("Invalid radius. Usage: /stonepurifier [radiusChunks]  OR  /stonepurifier reload");
                return true;
            }
        }

        if (sender instanceof Player player) {
            startCleanupNearPlayer(player, radius);
            return true;
        }

        // Console: clean all currently loaded chunks in all worlds
        startCleanupAllLoaded(sender);
        return true;
    }

    private void startCleanupNearPlayer(Player player, int radiusChunks) {
        World world = player.getWorld();
        Chunk center = player.getLocation().getChunk();

        Deque<Chunk> chunksToProcess = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();

        // Only loaded chunks in radius
        int cx = center.getX();
        int cz = center.getZ();

        for (int x = cx - radiusChunks; x <= cx + radiusChunks; x++) {
            for (int z = cz - radiusChunks; z <= cz + radiusChunks; z++) {
                if (!world.isChunkLoaded(x, z)) continue;

                Chunk chunk = world.getChunkAt(x, z);
                long key = (((long) chunk.getX()) << 32) ^ (chunk.getZ() & 0xffffffffL);
                if (seen.add(key)) {
                    chunksToProcess.add(chunk);
                }
            }
        }

        if (chunksToProcess.isEmpty()) {
            player.sendMessage("No loaded chunks found within radius " + radiusChunks + ".");
            return;
        }

        player.sendMessage("StonePurifier: cleaning " + chunksToProcess.size()
                + " loaded chunks within radius " + radiusChunks + " (processed over time).");

        runCleanupTask(player, chunksToProcess);
    }

    private void startCleanupAllLoaded(CommandSender sender) {
        Deque<Chunk> chunksToProcess = new ArrayDeque<>();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                chunksToProcess.add(chunk);
            }
        }

        if (chunksToProcess.isEmpty()) {
            sender.sendMessage("No loaded chunks found on the server.");
            return;
        }

        sender.sendMessage("StonePurifier: cleaning ALL loaded chunks (" + chunksToProcess.size()
                + ") across all worlds (processed over time).");

        runCleanupTask(sender, chunksToProcess);
    }

    private void runCleanupTask(CommandSender sender, Deque<Chunk> chunksToProcess) {
        cleanTaskRunning = true;

        new BukkitRunnable() {
            private int chunksDone = 0;
            private int totalReplaced = 0;

            @Override
            public void run() {
                int processedThisTick = 0;

                while (processedThisTick < chunksPerTick && !chunksToProcess.isEmpty()) {
                    Chunk chunk = chunksToProcess.pollFirst();
                    if (chunk == null) break;

                    // Chunk might unload between enqueue and processing; skip if unloaded
                    if (!chunk.isLoaded()) {
                        processedThisTick++;
                        chunksDone++;
                        continue;
                    }

                    int replaced = replaceInChunk(chunk);
                    totalReplaced += replaced;

                    processedThisTick++;
                    chunksDone++;
                }

                if (chunksToProcess.isEmpty()) {
                    sender.sendMessage("StonePurifier: cleanup complete. Chunks processed="
                            + chunksDone + ", blocks replaced=" + totalReplaced + ".");
                    cleanTaskRunning = false;
                    cancel();
                    return;
                }

                // Light progress ping every so often
                if (chunksDone % 25 == 0) {
                    sender.sendMessage("StonePurifier: progress: chunks processed="
                            + chunksDone + ", blocks replaced=" + totalReplaced
                            + ", remaining chunks=" + chunksToProcess.size() + ".");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }
}
