import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class StairDropFix extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        System.out.println("[StairDropFix] Enabled. Stairs now always drop themselves on break.");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        System.out.println("[StairDropFix] Disabled.");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material blockType = block.getType();

        if (!isStair(blockType)) {
            return;
        }

        // Cancel vanilla handling so we can force our own drops
        event.setCancelled(true);

        // Remove the block
        block.setType(Material.AIR);

        // Drop one stair of the same type
        ItemStack stairDrop = new ItemStack(blockType, 1);
        block.getWorld().dropItemNaturally(block.getLocation(), stairDrop);
    }

    private boolean isStair(Material material) {
        return material == Material.WOOD_STAIRS
            || material == Material.COBBLESTONE_STAIRS;
        // Add more here if your jar has extra stair types.
    }
}
