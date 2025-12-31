// MapFix.java
// Author: ChatGPT
// Spigot API: 1.21.10

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapFix extends JavaPlugin {

    private static final Pattern MAP_FILE_PATTERN = Pattern.compile("^map_(\\d+)\\.dat$");

    @Override
    public void onEnable() {
        getLogger().info("MapFix enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MapFix disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mapfix")) {
            return false;
        }

        if (!sender.isOp() && !sender.hasPermission("mapfix.use")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("Usage:");
            sender.sendMessage("  /mapfix <targetWorld>");
            sender.sendMessage("  /mapfix <targetWorld> <sourceWorld>");
            sender.sendMessage("Notes:");
            sender.sendMessage("  - With 1 arg, sourceWorld defaults to your current world (players only).");
            return true;
        }

        String targetWorldName = args[0];
        World targetWorld = Bukkit.getWorld(targetWorldName);
        if (targetWorld == null) {
            sender.sendMessage("Target world not loaded or not found: " + targetWorldName);
            return true;
        }

        World sourceWorld;
        if (args.length == 2) {
            String sourceWorldName = args[1];
            sourceWorld = Bukkit.getWorld(sourceWorldName);
            if (sourceWorld == null) {
                sender.sendMessage("Source world not loaded or not found: " + sourceWorldName);
                return true;
            }
        } else {
            if (sender instanceof Player player) {
                sourceWorld = player.getWorld();
            } else {
                sender.sendMessage("From console, you must specify a source world:");
                sender.sendMessage("  /mapfix <targetWorld> <sourceWorld>");
                return true;
            }
        }

        sender.sendMessage("Scanning maps in source world folder: " + sourceWorld.getName());
        sender.sendMessage("Re-pointing them to target world: " + targetWorld.getName());

        Set<Short> mapIds = findMapIdsInWorldFolder(sourceWorld);
        if (mapIds.isEmpty()) {
            sender.sendMessage("No map_#.dat files found in " + sourceWorld.getName() + "/data");
            return true;
        }

        int changedCount = 0;
        int missingCount = 0;
        int alreadyOkCount = 0;

        for (short mapId : mapIds) {
            MapView mapView = Bukkit.getMap(mapId);
            if (mapView == null) {
                missingCount++;
                continue;
            }

            World currentWorld = mapView.getWorld();
            if (currentWorld != null && currentWorld.getUID().equals(targetWorld.getUID())) {
                alreadyOkCount++;
                continue;
            }

            mapView.setWorld(targetWorld);
            changedCount++;
        }

        // Encourage persistence
        try {
            sourceWorld.save();
        } catch (Throwable ignored) { }
        try {
            targetWorld.save();
        } catch (Throwable ignored) { }

        sender.sendMessage("Done.");
        sender.sendMessage("Maps discovered: " + mapIds.size());
        sender.sendMessage("Changed: " + changedCount);
        sender.sendMessage("Already correct: " + alreadyOkCount);
        sender.sendMessage("MapViews missing/unloadable: " + missingCount);
        sender.sendMessage("Tip: If you want to be extra sure, run /save-all now.");

        return true;
    }

    private Set<Short> findMapIdsInWorldFolder(World world) {
        Set<Short> ids = new TreeSet<>();

        File worldFolder = world.getWorldFolder();
        if (worldFolder == null) {
            return ids;
        }

        File dataFolder = new File(worldFolder, "data");
        if (!dataFolder.isDirectory()) {
            return ids;
        }

        File[] files = dataFolder.listFiles();
        if (files == null) {
            return ids;
        }

        for (File file : files) {
            String fileName = file.getName();
            Matcher matcher = MAP_FILE_PATTERN.matcher(fileName);
            if (!matcher.matches()) {
                continue;
            }

            String numberText = matcher.group(1);
            try {
                int intId = Integer.parseInt(numberText);
                if (intId < 0 || intId > 65535) {
                    continue;
                }
                ids.add((short) intId);
            } catch (NumberFormatException ignored) {
            }
        }

        return ids;
    }
}
