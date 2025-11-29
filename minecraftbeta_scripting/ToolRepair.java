import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

public class ToolRepair extends JavaPlugin {

    private static final Set<Material> REPAIRABLE_ITEMS = EnumSet.of(
            // Swords
            Material.WOOD_SWORD,
            Material.STONE_SWORD,
            Material.IRON_SWORD,
            Material.GOLD_SWORD,
            Material.DIAMOND_SWORD,

            // Pickaxes
            Material.WOOD_PICKAXE,
            Material.STONE_PICKAXE,
            Material.IRON_PICKAXE,
            Material.GOLD_PICKAXE,
            Material.DIAMOND_PICKAXE,

            // Axes
            Material.WOOD_AXE,
            Material.STONE_AXE,
            Material.IRON_AXE,
            Material.GOLD_AXE,
            Material.DIAMOND_AXE,

            // Shovels (spades)
            Material.WOOD_SPADE,
            Material.STONE_SPADE,
            Material.IRON_SPADE,
            Material.GOLD_SPADE,
            Material.DIAMOND_SPADE,

            // Hoes
            Material.WOOD_HOE,
            Material.STONE_HOE,
            Material.IRON_HOE,
            Material.GOLD_HOE,
            Material.DIAMOND_HOE,

            // Other tools
            Material.FLINT_AND_STEEL,
            Material.SHEARS,

            // Leather armor
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS,

            // Chainmail armor
            Material.CHAINMAIL_HELMET,
            Material.CHAINMAIL_CHESTPLATE,
            Material.CHAINMAIL_LEGGINGS,
            Material.CHAINMAIL_BOOTS,

            // Iron armor
            Material.IRON_HELMET,
            Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS,
            Material.IRON_BOOTS,

            // Gold armor
            Material.GOLD_HELMET,
            Material.GOLD_CHESTPLATE,
            Material.GOLD_LEGGINGS,
            Material.GOLD_BOOTS,

            // Diamond armor
            Material.DIAMOND_HELMET,
            Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS,
            Material.DIAMOND_BOOTS
    );

    @Override
    public void onEnable() {
        int count = 0;

        for (Material itemType : REPAIRABLE_ITEMS) {
            if (itemType.getMaxDurability() <= 0) {
                continue;
            }

            // Result: one brand-new item
            ItemStack result = new ItemStack(itemType, 1);
            result.setDurability((short) 0);

            // Vertical recipe: two items stacked
            // Shape is relative, so this works in any column.
            ShapedRecipe vertical = new ShapedRecipe(result);
            vertical.shape("A", "A");
            vertical.setIngredient('A', itemType);
            getServer().addRecipe(vertical);
            count++;

            // Horizontal recipe: two items side-by-side
            // Shape is relative, so this works in any row.
            ShapedRecipe horizontal = new ShapedRecipe(result);
            horizontal.shape("AA");
            horizontal.setIngredient('A', itemType);
            getServer().addRecipe(horizontal);
            count++;
        }

        System.out.println("[ToolRepair] Registered " + count + " repair recipes.");
    }

    @Override
    public void onDisable() {
        System.out.println("[ToolRepair] Disabled.");
    }
}
