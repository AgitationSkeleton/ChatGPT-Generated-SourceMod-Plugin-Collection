package com.redchanit.oldoredrops;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class OldOreDrops extends JavaPlugin implements Listener {

    private Enchantment fortuneEnchant;

    @Override
    public void onEnable() {
        // 1.21+ uses registry keys; avoid hard constants that may not exist.
        fortuneEnchant = Enchantment.getByKey(NamespacedKey.minecraft("fortune"));

        getServer().getPluginManager().registerEvents(this, this);

        if (fortuneEnchant == null) {
            getLogger().warning("Could not resolve enchantment key minecraft:fortune. Fortune check will be disabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material blockType = event.getBlock().getType();
        if (!isTargetOre(blockType)) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (hasFortune(tool)) {
            // Let vanilla handle it (raw drops / fortune scaling, no ore-block duplication).
            return;
        }

        // Replace vanilla drops with the ore block itself.
        event.setDropItems(false);
        event.setExpToDrop(0);

        ItemStack oreDrop = new ItemStack(blockType, 1);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), oreDrop);
    }

    private boolean hasFortune(ItemStack tool) {
        if (fortuneEnchant == null) {
            return false;
        }
        if (tool == null || tool.getType() == Material.AIR) {
            return false;
        }
        return tool.getEnchantmentLevel(fortuneEnchant) > 0;
    }

    private boolean isTargetOre(Material material) {
        return material == Material.IRON_ORE
                || material == Material.DEEPSLATE_IRON_ORE
                || material == Material.GOLD_ORE
                || material == Material.DEEPSLATE_GOLD_ORE;
    }
}
