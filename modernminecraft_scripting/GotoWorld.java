package com.redchanit.gotoworld;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class GotoWorld extends JavaPlugin {

    private static final long COOLDOWN_MS = 5000L;

    private final Map<UUID, Long> lastUseTime = new HashMap<UUID, Long>();

    private String defaultWorldName = null;

    // Cached reflection lookup for teleportAsync (present on modern Spigot)
    private Method teleportAsyncMethod = null;

    @Override
    public void onEnable() {
        defaultWorldName = readDefaultWorldNameFromServerProperties();

        if (defaultWorldName == null || defaultWorldName.trim().isEmpty()) {
            List<World> worlds = Bukkit.getServer().getWorlds();
            if (!worlds.isEmpty()) {
                defaultWorldName = worlds.get(0).getName();
            }
        }

        teleportAsyncMethod = findTeleportAsyncMethod();

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
        // Cooldown check
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

        // 1) Try to find the world if already loaded
        World targetWorld = resolveWorld(requestedWorldName);

        // 2) If not loaded, attempt to load it from disk (this is the key for _nether/_the_end folders)
        if (targetWorld == null) {
            targetWorld = tryLoadWorldFromDisk(requestedWorldName);
        }

        if (targetWorld == null) {
            player.sendMessage("That world is not loaded or does not exist on disk.");
            return true;
        }

        // Always build a Location explicitly tied to the targetWorld
        Location spawn = targetWorld.getSpawnLocation();
        Location targetLocation = new Location(
                targetWorld,
                spawn.getX(),
                spawn.getY(),
                spawn.getZ(),
                spawn.getYaw(),
                spawn.getPitch()
        );

        boolean teleportStarted = teleportPlayerPreferAsync(player, targetLocation);

        if (teleportStarted) {
            player.sendMessage("Teleported to world: " + targetWorld.getName());
            lastUseTime.put(playerId, now);
        } else {
            player.sendMessage("Teleport failed.");
        }

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
            sb.append(" (");
            sb.append(world.getEnvironment().name().toLowerCase());
            sb.append(")");
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

    private World resolveWorld(String requestedWorldName) {
        // Exact first (fast path)
        World exact = Bukkit.getWorld(requestedWorldName);
        if (exact != null) {
            return exact;
        }

        // Case-insensitive scan
        List<World> worlds = Bukkit.getServer().getWorlds();
        for (World world : worlds) {
            if (world.getName().equalsIgnoreCase(requestedWorldName)) {
                return world;
            }
        }

        return null;
    }

    private World tryLoadWorldFromDisk(String requestedWorldName) {
        // If a folder exists for that world, Bukkit can usually load it
        File worldFolder = new File(Bukkit.getWorldContainer(), requestedWorldName);
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            return null;
        }

        try {
            getLogger().info("World not loaded; attempting to load from disk: " + requestedWorldName);

            WorldCreator creator = new WorldCreator(requestedWorldName);
            World created = Bukkit.createWorld(creator);

            if (created != null) {
                getLogger().info("Loaded world: " + created.getName() + " (" + created.getEnvironment() + ")");
            }

            return created;
        } catch (Exception ex) {
            getLogger().warning("Failed to load world '" + requestedWorldName + "': " + ex.getMessage());
            return null;
        }
    }

    private Method findTeleportAsyncMethod() {
        try {
            // Signature: CompletableFuture<Boolean> teleportAsync(Location)
            return Player.class.getMethod("teleportAsync", Location.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean teleportPlayerPreferAsync(Player player, Location targetLocation) {
        // Prefer teleportAsync if present (helps cross-world/dimension + chunk safety)
        if (teleportAsyncMethod != null) {
            try {
                Object result = teleportAsyncMethod.invoke(player, targetLocation);
                // We don't need to block; just treat invocation as "started"
                return result != null;
            } catch (Exception ex) {
                // Fallback to sync teleport below
                getLogger().warning("teleportAsync failed; falling back to teleport(): " + ex.getMessage());
            }
        }

        return player.teleport(targetLocation);
    }

    private String readDefaultWorldNameFromServerProperties() {
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
