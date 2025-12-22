package com.redchanit.tillseeds;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

public final class TillSeeds extends JavaPlugin implements Listener {

    private double seedDropChance; // 0.125 = 12.5%

    @Override
    public void onEnable() {
        // In your workflow, config.yml is not embedded in the jar, so saveDefaultConfig() would throw.
        // We'll just load defaults in-code and only read config if it exists on disk.
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TillSeeds enabled. Seed drop chance=" + seedDropChance);
    }

    private void loadSettings() {
        // Default if no config exists
        seedDropChance = 0.125;

        // If a config exists on disk, read from it; otherwise keep default.
        // Note: getConfig() always returns an object; it does NOT guarantee a file exists.
        FileConfiguration config = getConfig();
        if (config.contains("seedDropChance")) {
            seedDropChance = config.getDouble("seedDropChance", 0.125);
        }

        if (seedDropChance < 0.0) seedDropChance = 0.0;
        if (seedDropChance > 1.0) seedDropChance = 1.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTill(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        Player player = event.getPlayer();
        ItemStack heldItem = event.getItem();
        if (heldItem == null) return;

        // Must be a hoe
        if (!Tag.ITEMS_HOES.isTagged(heldItem.getType())) return;

        // Only when tilling grass block into farmland
        if (clickedBlock.getType() != Material.GRASS_BLOCK) return;

        Location blockLocation = clickedBlock.getLocation();

        // Run next tick to confirm the block actually became farmland
        getServer().getScheduler().runTask(this, () -> {
            Block currentBlock = blockLocation.getBlock();
            if (currentBlock.getType() != Material.FARMLAND) return;

            if (ThreadLocalRandom.current().nextDouble() <= seedDropChance) {
                Location dropLocation = blockLocation.clone().add(0.5, 1.0, 0.5);
                currentBlock.getWorld().dropItemNaturally(dropLocation, new ItemStack(Material.WHEAT_SEEDS, 1));
            }
        });
    }
}
