package net.redchanit.treefeller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

/**
 * Simple tree feller for Beta 1.7.3 / CB1060-style servers.
 *
 * Features:
 *  - If player is holding an axe and breaks a log that is part of a tree
 *    (logs with leaves nearby), all connected logs forming that tree are broken.
 *  - Extra logs consume durability on the held axe.
 *  - Players can toggle the behavior with /treefeller.
 *  - Per-player debug info: /treefellerinfo.
 *  - Global crouch-to-cancel: /treefellercrouch.
 *  - Leaves a stump: only logs above the broken block are removed.
 */
public class TreefellerPlugin extends JavaPlugin {

    private static final Logger log = Logger.getLogger("Minecraft");

    private final TreeBlockListener blockListener = new TreeBlockListener(this);

    // store player names in lowercase
    private final Set<String> disabledPlayers = new HashSet<String>();
    private final Set<String> infoPlayers     = new HashSet<String>();

    private boolean crouchCancelEnabled = true; // global: on by default

    private Configuration config;
    private File configFile;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        configFile = new File(getDataFolder(), "config.yml");
        config = new Configuration(configFile);

        if (configFile.exists()) {
            try {
                config.load();
            } catch (Exception e) {
                log.warning("[Treefeller] Could not load config.yml: " + e.getMessage());
            }
        }

        // load disabled players list
        List<String> list = config.getStringList("disabledPlayers", new ArrayList<String>());
        if (list != null) {
            for (String name : list) {
                if (name != null) {
                    disabledPlayers.add(name.toLowerCase());
                }
            }
        }

        // load info-enabled players list
        List<String> infoList = config.getStringList("infoPlayers", new ArrayList<String>());
        if (infoList != null) {
            for (String name : infoList) {
                if (name != null) {
                    infoPlayers.add(name.toLowerCase());
                }
            }
        }

        // global crouch cancel toggle (default true)
        crouchCancelEnabled = config.getBoolean("crouchCancelEnabled", true);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Event.Type.BLOCK_BREAK, blockListener, Priority.Normal, this);

        log.info("[Treefeller] Enabled.");
    }

    @Override
    public void onDisable() {
        // save disabled players list
        List<String> list = new ArrayList<String>(disabledPlayers.size());
        for (String name : disabledPlayers) {
            list.add(name);
        }
        config.setProperty("disabledPlayers", list);

        // save info-enabled players list
        List<String> infoList = new ArrayList<String>(infoPlayers.size());
        for (String name : infoPlayers) {
            infoList.add(name);
        }
        config.setProperty("infoPlayers", infoList);

        // save crouch cancel flag
        config.setProperty("crouchCancelEnabled", crouchCancelEnabled);

        try {
            config.save();
        } catch (Exception e) {
            log.warning("[Treefeller] Could not save config.yml: " + e.getMessage());
        }

        log.info("[Treefeller] Disabled.");
    }

    public boolean isDisabledFor(Player player) {
        return disabledPlayers.contains(player.getName().toLowerCase());
    }

    public void toggleFor(Player player) {
        String name = player.getName().toLowerCase();
        if (disabledPlayers.contains(name)) {
            disabledPlayers.remove(name);
            player.sendMessage(ChatColor.GREEN + "[Treefeller] Enabled.");
        } else {
            disabledPlayers.add(name);
            player.sendMessage(ChatColor.YELLOW + "[Treefeller] Disabled.");
        }
    }

    public boolean isInfoEnabled(Player player) {
        return infoPlayers.contains(player.getName().toLowerCase());
    }

    public void toggleInfo(Player player) {
        String name = player.getName().toLowerCase();
        if (infoPlayers.contains(name)) {
            infoPlayers.remove(name);
            player.sendMessage(ChatColor.YELLOW + "[Treefeller] Info disabled.");
        } else {
            infoPlayers.add(name);
            player.sendMessage(ChatColor.GREEN + "[Treefeller] Info enabled.");
        }
    }

    public boolean isCrouchCancelEnabled() {
        return crouchCancelEnabled;
    }

    public void toggleCrouchCancel(CommandSender sender) {
        crouchCancelEnabled = !crouchCancelEnabled;
        String state = crouchCancelEnabled ? "enabled" : "disabled";
        sender.sendMessage(ChatColor.AQUA + "[Treefeller] Crouch-cancel is now " + state + ".");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("treefeller")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            toggleFor((Player) sender);
            return true;
        }

        if (name.equals("treefellerinfo")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            toggleInfo((Player) sender);
            return true;
        }

        if (name.equals("treefellercrouch")) {
            toggleCrouchCancel(sender);
            return true;
        }

        return false;
    }

    // ======= Inner block listener =======

    private static class TreeBlockListener extends BlockListener {

        private static final int MAX_LOGS   = 256; // safety cap so we don't nuke a whole forest
        private static final int MAX_RADIUS = 7;   // max horizontal distance from base trunk

        private final TreefellerPlugin plugin;

        public TreeBlockListener(TreefellerPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onBlockBreak(BlockBreakEvent event) {
            if (event.isCancelled()) {
                return;
            }

            Player player = event.getPlayer();
            if (player == null) {
                return;
            }

            if (plugin.isDisabledFor(player)) {
                return;
            }

            // Global crouch-cancel: if enabled and player is sneaking,
            // do nothing extra (just vanilla block break).
            if (plugin.isCrouchCancelEnabled() && player.isSneaking()) {
                return;
            }

            Block baseBlock = event.getBlock();
            if (baseBlock == null || baseBlock.getType() != Material.LOG) {
                return;
            }

            ItemStack inHand = player.getItemInHand();
            if (inHand == null || !isAxe(inHand.getType())) {
                return;
            }

            // Only consider it a tree if there are leaves near the trunk above.
            if (!isTree(baseBlock)) {
                return;
            }

            // Find all connected logs that form this tree (branches etc.), with safety rules.
            List<Block> allLogs = getConnectedLogs(baseBlock);

            if (allLogs.isEmpty()) {
                return;
            }

            int baseY = baseBlock.getY();

            // Only fell logs ABOVE the broken block (leave stump below / at same level).
            List<Block> logsToBreak = new ArrayList<Block>();
            for (Block b : allLogs) {
                if (b.getY() > baseY) {
                    logsToBreak.add(b);
                }
            }

            if (logsToBreak.isEmpty()) {
                return;
            }

            int extraBlocks = logsToBreak.size();

			// Axe durability info (before/after)
			// NOTE: getDurability() is DAMAGE, not uses left.
			short beforeDamage = inHand.getDurability();
			short maxDur       = inHand.getType().getMaxDurability();
			int   beforeLeft   = maxDur - beforeDamage;
			
			// Damage the axe for the extra logs (original block already damages once).
			damageTool(player, inHand, extraBlocks);
			
			if (plugin.isInfoEnabled(player)) {
				ItemStack afterItem = player.getItemInHand();
			
				if (afterItem == null || afterItem.getType() != inHand.getType()) {
					// Axe broke – we used at least all remaining durability
					int used = beforeLeft; // everything that was left
					player.sendMessage(ChatColor.RED + "[Treefeller] Used " + used
							+ " durability and broke your axe (0/" + maxDur + " uses left).");
				} else {
					short afterDamage = afterItem.getDurability();
					int   afterLeft   = maxDur - afterDamage;
			
					int used = beforeLeft - afterLeft;
					if (used < 0) {
						// Fallback if something weird happens
						used = extraBlocks;
					}
			
					player.sendMessage(ChatColor.GRAY + "[Treefeller] Used " + used
							+ " durability. Now at " + afterLeft + "/" + maxDur + " uses left.");
				}
			}


            // Break all the logs we found above the cut, dropping their items.
            for (Block logBlock : logsToBreak) {
                dropLogNaturally(logBlock);
                logBlock.setType(Material.AIR);
            }
        }

        private static boolean isAxe(Material type) {
            return type == Material.WOOD_AXE
                || type == Material.STONE_AXE
                || type == Material.IRON_AXE
                || type == Material.GOLD_AXE
                || type == Material.DIAMOND_AXE;
        }

        /**
         * Heuristic to distinguish real trees from player log pillars:
         * look for leaves in a cube above and around the broken block.
         * Radius and height are large enough to catch big / branchy oaks.
         */
        private static boolean isTree(Block baseLog) {
            World world = baseLog.getWorld();
            int baseX = baseLog.getX();
            int baseY = baseLog.getY();
            int baseZ = baseLog.getZ();

            int radius = 4;
            int height = 12; // search higher to catch tall / big oaks

            for (int y = baseY; y <= baseY + height; y++) {
                for (int x = baseX - radius; x <= baseX + radius; x++) {
                    for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                        Block b = world.getBlockAt(x, y, z);
                        if (b.getType() == Material.LEAVES) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Breadth-first search for all connected LOG blocks starting from 'baseLog'.
         * Uses 3D neighborhood (including diagonals) but:
         *  - Only within MAX_RADIUS horizontally of the base trunk.
         *  - Skips logs that are clearly part of a build (near planks, glass, furnaces, etc.).
         */
        private static List<Block> getConnectedLogs(Block baseLog) {
            List<Block> result = new ArrayList<Block>();
            Set<Block> visited = new HashSet<Block>();
            LinkedList<Block> queue = new LinkedList<Block>();

            queue.add(baseLog);
            visited.add(baseLog);

            World world = baseLog.getWorld();
            int baseX = baseLog.getX();
            int baseY = baseLog.getY();
            int baseZ = baseLog.getZ();

            while (!queue.isEmpty()) {
                Block current = queue.removeFirst();
                result.add(current);

                if (result.size() >= MAX_LOGS) {
                    break;
                }

                int cx = current.getX();
                int cy = current.getY();
                int cz = current.getZ();

                // Check all 26 neighbors (3x3x3 minus the center)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }

                            Block neighbor = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                            if (visited.contains(neighbor)) {
                                continue;
                            }

                            if (!isLogPartOfTree(neighbor, baseX, baseY, baseZ)) {
                                continue;
                            }

                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }

            return result;
        }

        /**
         * Decide whether this LOG block should be considered part of the same natural tree.
         *
         * Rules:
         *  - Must be a LOG.
         *  - Must be within MAX_RADIUS horizontally of the base trunk.
         *  - Must NOT be close to obvious building blocks (planks, glass, furnaces, doors, etc.).
         */
        private static boolean isLogPartOfTree(Block candidate, int baseX, int baseY, int baseZ) {
            if (candidate.getType() != Material.LOG) {
                return false;
            }

            int x = candidate.getX();
            int z = candidate.getZ();

            int dx = x - baseX;
            int dz = z - baseZ;
            if ((dx * dx) + (dz * dz) > (MAX_RADIUS * MAX_RADIUS)) {
                return false;
            }

            if (isNearBuilding(candidate)) {
                return false;
            }

            return true;
        }

        /**
         * Check a 3x3x3 area around the given block for obvious
         * man-made building blocks. If any are found, we treat the
         * candidate as part of a build and skip it.
         */
        private static boolean isNearBuilding(Block center) {
            World world = center.getWorld();
            int cx = center.getX();
            int cy = center.getY();
            int cz = center.getZ();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block neighbor = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (isBuildingBlock(neighbor.getType())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Heuristic list of blocks that usually indicate a player build.
         * We intentionally do NOT include LOG or LEAVES here.
         */
        private static boolean isBuildingBlock(Material type) {
            if (type == Material.WOOD           // planks
                || type == Material.GLASS
                || type == Material.COBBLESTONE
                || type == Material.MOSSY_COBBLESTONE
                || type == Material.STEP
                || type == Material.DOUBLE_STEP
                || type == Material.WOOD_STAIRS
                || type == Material.COBBLESTONE_STAIRS
                || type == Material.FURNACE
                || type == Material.DISPENSER
                || type == Material.CHEST
                || type == Material.WORKBENCH
                || type == Material.BOOKSHELF
                || type == Material.WOOL
                || type == Material.LEVER
                || type == Material.STONE_BUTTON
                || type == Material.TORCH
                || type == Material.REDSTONE_TORCH_ON
                || type == Material.REDSTONE_TORCH_OFF
                || type == Material.WOODEN_DOOR
                || type == Material.IRON_DOOR_BLOCK
                || type == Material.FENCE
                || type == Material.LADDER
                || type == Material.SIGN_POST
                || type == Material.WALL_SIGN) {
                return true;
            }
            return false;
        }

        private static void damageTool(Player player, ItemStack tool, int extra) {
            if (tool == null) {
                return;
            }

            short current = tool.getDurability();
            short max = tool.getType().getMaxDurability();
            if (max <= 0) {
                return; // non-damageable item
            }

            int newDamage = current + extra;
            if (newDamage >= max) {
                // Break the tool
                player.setItemInHand(null);
            } else {
                tool.setDurability((short) newDamage);
                player.setItemInHand(tool);
            }
        }

        private static void dropLogNaturally(Block block) {
            World world = block.getWorld();
            byte data = block.getData(); // wood type
            ItemStack drop = new ItemStack(Material.LOG, 1, (short) data);
            world.dropItemNaturally(block.getLocation(), drop);
        }
    }
}
