package com.redchanit.gotoworld;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class GotoWorld extends JavaPlugin {

    // Simple anti-spam cooldown for teleports
    private static final long COOLDOWN_MS = 5000L;

    private final Map<UUID, Long> lastUseTime = new HashMap<UUID, Long>();

    // Cached from server.properties "level-name"
    private String defaultWorldName = null;

    @Override
    public void onEnable() {
        defaultWorldName = readDefaultWorldNameFromServerProperties();

        if (defaultWorldName == null || defaultWorldName.trim().isEmpty()) {
            // Fallback: first loaded world if properties missing
            List<World> worlds = Bukkit.getServer().getWorlds();
            if (!worlds.isEmpty()) {
                defaultWorldName = worlds.get(0).getName();
            }
        }

        if (defaultWorldName != null) {
            getLogger().info("Enabled. Default world (main) is: " + defaultWorldName);
        } else {
            getLogger().warning("Enabled, but could not determine default world (server.properties missing/unreadable?).");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("goto")) {
            return handleGoto(sender, args);
        }

        if (cmd.equals("gotolist")) {
            return handleGotoList(sender);
        }

        if (cmd.equals("main")) {
            return handleMain(sender, args);
        }

        return false;
    }

    private boolean handleMain(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage("Usage: /main");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;

        if (defaultWorldName == null || defaultWorldName.trim().isEmpty()) {
            player.sendMessage("Default world could not be determined.");
            return true;
        }

        return teleportPlayerToWorld(player, defaultWorldName);
    }

    private boolean handleGoto(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /goto <worldname>");
            sender.sendMessage("Tip: use \"main\" for the default world.");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;
        String requestedName = args[0];

        if (requestedName.equalsIgnoreCase("main")) {
            if (defaultWorldName == null || defaultWorldName.trim().isEmpty()) {
                player.sendMessage("Default world could not be determined.");
                return true;
            }
            return teleportPlayerToWorld(player, defaultWorldName);
        }

        return teleportPlayerToWorld(player, requestedName);
    }

    private boolean teleportPlayerToWorld(Player player, String requestedWorldName) {
        // Cooldown check (applies to /goto and /main)
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastUseTime.containsKey(playerId)) {
            long last = lastUseTime.get(playerId);
            long elapsed = now - last;
            if (elapsed < COOLDOWN_MS) {
                long remainingSeconds = (COOLDOWN_MS - elapsed + 999) / 1000;
                player.sendMessage("You must wait " + remainingSeconds + " more second(s) before teleporting again.");
                return true;
            }
        }

        World targetWorld = findWorldIgnoreCase(requestedWorldName);
        if (targetWorld == null) {
            player.sendMessage("That world is not loaded or does not exist.");
            return true;
        }

        Location targetLocation = targetWorld.getSpawnLocation();
        player.teleport(targetLocation);
        player.sendMessage("Teleported to world: " + targetWorld.getName());

        lastUseTime.put(playerId, now);
        return true;
    }

    private boolean handleGotoList(CommandSender sender) {
        List<World> worlds = Bukkit.getServer().getWorlds();

        if (worlds.isEmpty()) {
            sender.sendMessage("No worlds are currently loaded.");
            return true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Loaded worlds: ");

        for (int worldIndex = 0; worldIndex < worlds.size(); worldIndex++) {
            World world = worlds.get(worldIndex);
            sb.append(world.getName());
            if (worldIndex < worlds.size() - 1) {
                sb.append(", ");
            }
        }

        sender.sendMessage(sb.toString());

        if (defaultWorldName != null && !defaultWorldName.trim().isEmpty()) {
            sender.sendMessage("Alias: main -> " + defaultWorldName + " (use /goto main or /main)");
        } else {
            sender.sendMessage("Alias: main -> (unknown) (server.properties missing/unreadable?)");
        }

        return true;
    }

    private World findWorldIgnoreCase(String name) {
        List<World> worlds = Bukkit.getServer().getWorlds();
        for (World world : worlds) {
            if (world.getName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
    }

    private String readDefaultWorldNameFromServerProperties() {
        // Spigot servers typically run with working directory at the server root,
        // so relative "server.properties" should resolve correctly.
        File propsFile = new File("server.properties");
        if (!propsFile.exists() || !propsFile.isFile()) {
            return null;
        }

        Properties props = new Properties();
        InputStream inputStream = null;

        try {
            inputStream = new FileInputStream(propsFile);
            props.load(inputStream);

            String levelName = props.getProperty("level-name");
            if (levelName != null && !levelName.trim().isEmpty()) {
                return levelName.trim();
            }

            return null;
        } catch (Exception ex) {
            getLogger().warning("Failed to read server.properties: " + ex.getMessage());
            return null;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
