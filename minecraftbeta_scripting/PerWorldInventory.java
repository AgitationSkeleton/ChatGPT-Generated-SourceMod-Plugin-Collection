import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

/**
 * PerWorldInventory - CraftBukkit Beta 1.7.3 / Poseidon compatible.
 *
 * - Players opt-in with /pwi (toggle).
 * - On world change (teleport between worlds), inventories are swapped per-world.
 * - If either from/to world name contains "nether" or "skylands" (case-insensitive), do nothing.
 *
 * Author: ChatGPT
 */
public final class PerWorldInventory extends JavaPlugin {

    private static final String OPT_IN_FILE = "players.yml";
    private final Set optedInPlayersLower = new HashSet(); // store lowercase player names
    private final Logger log = Logger.getLogger("Minecraft");

    private final PlayerListener playerListener = new PwiPlayerListener();

    public void onEnable() {
        getDataFolder().mkdirs();
        loadOptIns();

        getServer().getPluginManager().registerEvent(
                Event.Type.PLAYER_TELEPORT,
                playerListener,
                Priority.High,
                this
        );

        log.info("[PerWorldInventory] Enabled.");
    }

    public void onDisable() {
        saveOptIns();
        log.info("[PerWorldInventory] Disabled.");
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"pwi".equalsIgnoreCase(command.getName())) {
            return false;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;
        String playerKey = normalizePlayerKey(player.getName());

        boolean nowEnabled;
        if (optedInPlayersLower.contains(playerKey)) {
            optedInPlayersLower.remove(playerKey);
            nowEnabled = false;
        } else {
            optedInPlayersLower.add(playerKey);
            nowEnabled = true;

            // Ensure current world's snapshot exists, so swapping back restores correctly later.
            World currentWorld = player.getWorld();
            File currentWorldFile = getWorldInventoryFile(player.getName(), currentWorld.getName());
            if (!currentWorldFile.exists()) {
                saveInventoryToFile(player, currentWorld.getName());
            }
        }

        saveOptIns();

        player.sendMessage(ChatColor.YELLOW + "[PWI] " + ChatColor.WHITE
                + "Per-world inventory is now "
                + (nowEnabled ? (ChatColor.GREEN + "ENABLED") : (ChatColor.RED + "DISABLED"))
                + ChatColor.WHITE + ".");

        return true;
    }

    private final class PwiPlayerListener extends PlayerListener {
        public void onPlayerTeleport(PlayerTeleportEvent event) {
            if (event.isCancelled()) {
                return;
            }

            Player player = event.getPlayer();
            if (player == null) {
                return;
            }

            String playerKey = normalizePlayerKey(player.getName());
            if (!optedInPlayersLower.contains(playerKey)) {
                return; // not opted-in -> do nothing
            }

            if (event.getFrom() == null || event.getTo() == null) {
                return;
            }

            World fromWorld = event.getFrom().getWorld();
            World toWorld = event.getTo().getWorld();
            if (fromWorld == null || toWorld == null) {
                return;
            }

            if (fromWorld.getName().equals(toWorld.getName())) {
                return; // same world -> ignore
            }

            // Skip swapping if either world is excluded
            if (isExcludedWorldName(fromWorld.getName()) || isExcludedWorldName(toWorld.getName())) {
                return;
            }

            try {
                // Save FROM world inventory
                saveInventoryToFile(player, fromWorld.getName());
                // Load TO world inventory (or clear if none)
                loadInventoryFromFileOrEmpty(player, toWorld.getName());
            } catch (Exception ex) {
                log.warning("[PerWorldInventory] Swap failed for " + player.getName()
                        + " (" + fromWorld.getName() + " -> " + toWorld.getName() + "): " + ex.toString());
            }
        }
    }

    private boolean isExcludedWorldName(String worldName) {
        if (worldName == null) {
            return false;
        }
        String lower = worldName.toLowerCase();
        return lower.indexOf("nether") >= 0 || lower.indexOf("skylands") >= 0;
    }

    private void loadOptIns() {
        optedInPlayersLower.clear();

        File file = new File(getDataFolder(), OPT_IN_FILE);
        if (!file.exists()) {
            return;
        }

        Configuration config = new Configuration(file);
        config.load();

        Object raw = config.getProperty("players");
        if (raw instanceof java.util.List) {
            java.util.List list = (java.util.List) raw;
            for (int i = 0; i < list.size(); i++) {
                Object val = list.get(i);
                if (val != null) {
                    optedInPlayersLower.add(normalizePlayerKey(String.valueOf(val)));
                }
            }
        }
    }

    private void saveOptIns() {
        File file = new File(getDataFolder(), OPT_IN_FILE);
        Configuration config = new Configuration(file);

        java.util.List out = new java.util.ArrayList();
        for (java.util.Iterator it = optedInPlayersLower.iterator(); it.hasNext();) {
            out.add(it.next());
        }

        config.setProperty("players", out);
        config.save();
    }

    private String normalizePlayerKey(String playerName) {
        return (playerName == null) ? "" : playerName.trim().toLowerCase();
    }

    private File getWorldInventoryFile(String playerName, String worldName) {
        File playerDir = new File(getDataFolder(), "data" + File.separator + sanitizeFileComponent(playerName));
        if (!playerDir.exists()) {
            playerDir.mkdirs();
        }
        return new File(playerDir, sanitizeFileComponent(worldName) + ".yml");
    }

    private String sanitizeFileComponent(String input) {
        if (input == null) {
            return "unknown";
        }
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void saveInventoryToFile(Player player, String worldName) {
        File file = getWorldInventoryFile(player.getName(), worldName);
        Configuration config = new Configuration(file);

        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] armor = player.getInventory().getArmorContents();

        config.setProperty("inv.size", Integer.valueOf(contents != null ? contents.length : 0));
        config.setProperty("armor.size", Integer.valueOf(armor != null ? armor.length : 0));

        if (contents != null) {
            for (int slotIndex = 0; slotIndex < contents.length; slotIndex++) {
                writeItem(config, "inv." + slotIndex, contents[slotIndex]);
            }
        }

        if (armor != null) {
            for (int slotIndex = 0; slotIndex < armor.length; slotIndex++) {
                writeItem(config, "armor." + slotIndex, armor[slotIndex]);
            }
        }

        config.save();
    }

    private void loadInventoryFromFileOrEmpty(Player player, String worldName) {
        File file = getWorldInventoryFile(player.getName(), worldName);

        if (!file.exists()) {
            // No saved inventory for this world: clear to empty
            ItemStack[] contents = emptyLike(player.getInventory().getContents());
            ItemStack[] armor = emptyLike(player.getInventory().getArmorContents());

            player.getInventory().setContents(contents);
            player.getInventory().setArmorContents(armor);
            player.updateInventory();
            return;
        }

        Configuration config = new Configuration(file);
        config.load();

        int invSize = config.getInt("inv.size", player.getInventory().getContents() != null
                ? player.getInventory().getContents().length : 0);
        int armorSize = config.getInt("armor.size", player.getInventory().getArmorContents() != null
                ? player.getInventory().getArmorContents().length : 0);

        ItemStack[] contents = new ItemStack[invSize];
        ItemStack[] armor = new ItemStack[armorSize];

        for (int slotIndex = 0; slotIndex < invSize; slotIndex++) {
            contents[slotIndex] = readItem(config, "inv." + slotIndex);
        }
        for (int slotIndex = 0; slotIndex < armorSize; slotIndex++) {
            armor[slotIndex] = readItem(config, "armor." + slotIndex);
        }

        player.getInventory().setContents(contents);
        player.getInventory().setArmorContents(armor);
        player.updateInventory();
    }

    private ItemStack[] emptyLike(ItemStack[] like) {
        if (like == null) {
            return new ItemStack[0];
        }
        return new ItemStack[like.length];
    }

    private void writeItem(Configuration config, String pathPrefix, ItemStack item) {
        if (item == null || item.getTypeId() == 0 || item.getAmount() <= 0) {
            config.setProperty(pathPrefix + ".type", Integer.valueOf(0));
            config.setProperty(pathPrefix + ".amount", Integer.valueOf(0));
            config.setProperty(pathPrefix + ".durability", Integer.valueOf(0));
            return;
        }

        config.setProperty(pathPrefix + ".type", Integer.valueOf(item.getTypeId()));
        config.setProperty(pathPrefix + ".amount", Integer.valueOf(item.getAmount()));
        config.setProperty(pathPrefix + ".durability", Integer.valueOf(item.getDurability()));
    }

    private ItemStack readItem(Configuration config, String pathPrefix) {
        int typeId = config.getInt(pathPrefix + ".type", 0);
        int amount = config.getInt(pathPrefix + ".amount", 0);
        int durability = config.getInt(pathPrefix + ".durability", 0);

        if (typeId <= 0 || amount <= 0) {
            return null;
        }

        ItemStack item = new ItemStack(typeId, amount);
        item.setDurability((short) durability);
        return item;
    }
}