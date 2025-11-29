import java.util.HashSet;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class SilkGold extends JavaPlugin implements Listener {

    // Players who have opted out (feature OFF for them)
    private final Set<String> optedOutPlayers = new HashSet<String>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        System.out.println("[SilkGold] Enabled.");
    }

    @Override
    public void onDisable() {
        System.out.println("[SilkGold] Disabled.");
        optedOutPlayers.clear();
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String playerName = player.getName().toLowerCase();
        if (optedOutPlayers.contains(playerName)) {
            return; // Player has /silkgold toggled OFF
        }

        ItemStack inHand = player.getItemInHand();
        if (inHand == null) {
            return;
        }

        Material toolType = inHand.getType();
        if (!isGoldTool(toolType)) {
            return;
        }

        Block block = event.getBlock();
        Material blockType = block.getType();

        if (!isSilkGoldBlock(blockType)) {
            return;
        }

        // Override normal break behavior
        event.setCancelled(true);

        World world = block.getWorld();
        Location dropLocation = block.getLocation();

        // Create the special silk-drop item BEFORE we remove the block
        ItemStack drop = createSilkDrop(block);

        // Remove the block in the world
        block.setType(Material.AIR);

        // Drop our custom item
        world.dropItemNaturally(dropLocation, drop);

        // Manually damage the gold tool
        damageToolInHand(player, inHand);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Block block = event.getBlockPlaced();
        if (block == null || block.getType() != Material.MOB_SPAWNER) {
            return;
        }

        ItemStack item = event.getItemInHand();
        if (item == null || item.getType() != Material.MOB_SPAWNER) {
            return;
        }

        short code = item.getDurability();
        CreatureType creatureType = decodeSpawnerType(code);

        BlockState state = block.getState();
        if (state instanceof CreatureSpawner) {
            CreatureSpawner spawner = (CreatureSpawner) state;
            if (creatureType == null) {
                creatureType = CreatureType.PIG; // fallback
            }
            spawner.setCreatureType(creatureType);
            state.update();
        }
    }

	private boolean isGoldTool(Material type) {
		// Tools that should trigger SilkGold behavior
		return type == Material.GOLD_PICKAXE
				|| type == Material.GOLD_SPADE
				|| type == Material.GOLD_AXE
				|| type == Material.GOLD_SWORD;
	}


    private boolean isSilkGoldBlock(Material type) {
        // Whitelist of blocks that should behave like modern Silk Touch
        // restricted to things that exist in Beta 1.7.3.
        switch (type) {
            case GRASS:                 // grass block -> grass block
            case STONE:                 // stone -> stone instead of cobble
            case DIAMOND_ORE:           // ore -> ore
            case COAL_ORE:
            case LAPIS_ORE:
            case REDSTONE_ORE:
            case GLOWING_REDSTONE_ORE:
            case CLAY:                  // clay block -> clay block, not balls
            case SNOW:                  // snow layer -> snow item
            case SNOW_BLOCK:            // snow block -> snow block
            case GLASS:                 // glass -> glass
            case LEAVES:                // leaves (3 types via data)
            case GLOWSTONE:             // glowstone -> block, not dust
            case ICE:                   // ice -> ice
            case BOOKSHELF:             // bookshelf -> bookshelf, not books
            case GRAVEL:                // gravel -> gravel, not flint
            case MOB_SPAWNER:           // spawners -> spawners, with stored type
                return true;
            default:
                return false;
        }
    }

    private Material getDropMaterial(Material blockType) {
        // Special-case for snow cover, otherwise just use the same block.
        if (blockType == Material.SNOW) {
            return Material.SNOW;
        }
        return blockType;
    }

    private ItemStack createSilkDrop(Block block) {
        Material blockType = block.getType();

        // Special treatment for spawners so we can store their entity type
        if (blockType == Material.MOB_SPAWNER) {
            BlockState state = block.getState();
            short code = 0;
            if (state instanceof CreatureSpawner) {
                CreatureSpawner spawner = (CreatureSpawner) state;
                CreatureType creatureType = spawner.getCreatureType();
                code = encodeSpawnerType(creatureType);
            }
            // Durability encodes which creature this spawner came from
            return new ItemStack(Material.MOB_SPAWNER, 1, code);
        }

        byte data = block.getData();
        Material dropType = getDropMaterial(blockType);

        if (dropType == blockType) {
            // Same material, keep data (e.g., leaves variants)
            return new ItemStack(dropType, 1, (short) 0, data);
        } else {
            // Different material (e.g., SNOW cover -> SNOW item)
            return new ItemStack(dropType, 1);
        }
    }

    private void damageToolInHand(Player player, ItemStack tool) {
        if (tool == null) {
            return;
        }

        short newDurability = (short) (tool.getDurability() + 1);
        short maxDurability = tool.getType().getMaxDurability();

        if (maxDurability <= 0) {
            // Not a damageable item; nothing to do
            return;
        }

        if (newDurability >= maxDurability) {
            // Break the tool
            tool.setAmount(0); // effectively removes it
        } else {
            tool.setDurability(newDurability);
        }

        // Make sure the updated stack is in the player's hand
        player.setItemInHand(tool);
    }

    // ---- Spawner type encoding/decoding ----

    private short encodeSpawnerType(CreatureType type) {
        if (type == null) {
            return 0;
        }
        // Map some common types to small codes; everything else collapses to pig
        switch (type) {
            case SKELETON:
                return 1;
            case ZOMBIE:
                return 2;
            case CREEPER:
                return 3;
            case SPIDER:
                return 4;
            case PIG:
                return 5;
            default:
                return 0;
        }
    }

    private CreatureType decodeSpawnerType(short code) {
        switch (code) {
            case 1:
                return CreatureType.SKELETON;
            case 2:
                return CreatureType.ZOMBIE;
            case 3:
                return CreatureType.CREEPER;
            case 4:
                return CreatureType.SPIDER;
            case 5:
                return CreatureType.PIG;
            default:
                return CreatureType.PIG; // safe fallback
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("silkgold")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        String playerName = player.getName().toLowerCase();

        if (optedOutPlayers.contains(playerName)) {
            optedOutPlayers.remove(playerName);
            player.sendMessage(ChatColor.GREEN + "SilkGold is now enabled for you.");
        } else {
            optedOutPlayers.add(playerName);
            player.sendMessage(ChatColor.YELLOW + "SilkGold is now disabled for you.");
        }

        return true;
    }
}
