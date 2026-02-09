import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BetaHomes extends JavaPlugin {

    private final Properties homes = new Properties();
    private File homesFile;
    private Logger log;

    @Override
    public void onEnable() {
        this.log = getServer().getLogger();

        File dataDir = getDataFolder();
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        homesFile = new File(dataDir, "homes.properties");
        loadHomes();

        log.info("[BetaHomes] Enabled.");
    }

    @Override
    public void onDisable() {
        saveHomes();
        if (log == null) {
            log = getServer().getLogger();
        }
        log.info("[BetaHomes] Disabled.");
    }

    private void loadHomes() {
        if (!homesFile.exists()) {
            return;
        }

        FileInputStream input = null;
        try {
            input = new FileInputStream(homesFile);
            homes.load(input);
        } catch (Exception ex) {
            log.warning("[BetaHomes] Failed to load homes.properties: " + ex.getMessage());
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
        }
    }

    private void saveHomes() {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(homesFile);
            homes.store(output, "BetaHomes - player homes by world");
        } catch (Exception ex) {
            if (log == null) {
                log = getServer().getLogger();
            }
            log.warning("[BetaHomes] Failed to save homes.properties: " + ex.getMessage());
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) {}
        }
    }

    private static String makeKey(String playerName, String worldName) {
        return playerName.toLowerCase() + "." + worldName.toLowerCase();
    }

    private static String serializeLocation(Location loc) {
        return loc.getX() + "," + loc.getY() + "," + loc.getZ() + "," + loc.getYaw() + "," + loc.getPitch();
    }

    private static Location deserializeLocation(World world, String value) {
        String[] parts = value.split(",");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Not enough parts.");
        }

        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        double z = Double.parseDouble(parts[2]);

        float yaw = 0.0f;
        float pitch = 0.0f;

        if (parts.length >= 5) {
            yaw = Float.parseFloat(parts[3]);
            pitch = Float.parseFloat(parts[4]);
        }

        return new Location(world, x, y, z, yaw, pitch);
    }

    private static String join(Set<String> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : items) {
            if (!first) sb.append(", ");
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase();

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        if (cmdName.equals("sethome")) {
            World world = player.getWorld();
            String worldName = world.getName();
            String key = makeKey(playerName, worldName);

            Location loc = player.getLocation();
            homes.setProperty(key, serializeLocation(loc));
            saveHomes();

            player.sendMessage("Home set for world '" + worldName + "'.");
            return true;
        }

		if (cmdName.equals("home")) {
			World world;
		
			if (args.length == 0) {
				// Default: current world
				world = player.getWorld();
			} else if (args.length == 1) {
				world = Bukkit.getWorld(args[0]);
				if (world == null) {
					player.sendMessage("World '" + args[0] + "' is not loaded.");
					return true;
				}
			} else {
				player.sendMessage("Usage: /home [world]");
				return true;
			}
		
			String key = makeKey(playerName, world.getName());
			String value = homes.getProperty(key);
			if (value == null) {
				player.sendMessage("No home set for world '" + world.getName() + "'.");
				return true;
			}
		
			Location target;
			try {
				target = deserializeLocation(world, value);
			} catch (Exception ex) {
				player.sendMessage("Home data for '" + world.getName() + "' is invalid. Clearing it.");
				homes.remove(key);
				saveHomes();
				return true;
			}
		
			int cx = target.getBlockX() >> 4;
			int cz = target.getBlockZ() >> 4;
			world.getChunkAt(cx, cz).load();
		
			player.teleport(target);
			player.sendMessage("Teleported to home in world '" + world.getName() + "'.");
			return true;
		}
		

        if (cmdName.equals("homelist")) {
            Set<String> worlds = new TreeSet<String>();
            String prefix = playerName.toLowerCase() + ".";

            for (Object kObj : homes.keySet()) {
                String k = String.valueOf(kObj);
                if (k.startsWith(prefix)) {
                    String worldPart = k.substring(prefix.length());
                    if (worldPart.length() > 0) {
                        worlds.add(worldPart);
                    }
                }
            }

            if (worlds.isEmpty()) {
                player.sendMessage("You have no homes set.");
            } else {
                player.sendMessage("Homes set in: " + join(worlds));
            }
            return true;
        }

        if (cmdName.equals("clearhome")) {
            if (args.length != 1) {
                player.sendMessage("Usage: /clearhome <world|all>");
                return true;
            }

            String target = args[0].toLowerCase();

            if (target.equals("all")) {
                String prefix = playerName.toLowerCase() + ".";
                Set<String> toRemove = new TreeSet<String>();

                for (Object kObj : homes.keySet()) {
                    String k = String.valueOf(kObj);
                    if (k.startsWith(prefix)) {
                        toRemove.add(k);
                    }
                }

                for (String k : toRemove) {
                    homes.remove(k);
                }

                saveHomes();
                player.sendMessage("Cleared all homes.");
                return true;
            }

            String key = makeKey(playerName, target);
            if (homes.remove(key) == null) {
                player.sendMessage("No home set for world '" + target + "'.");
                return true;
            }

            saveHomes();
            player.sendMessage("Cleared home for world '" + target + "'.");
            return true;
        }

        return false;
    }
}
