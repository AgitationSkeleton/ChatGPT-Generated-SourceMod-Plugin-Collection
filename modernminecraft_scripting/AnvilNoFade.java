package com.redchanit.anvilnofade;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AnvilNoFade extends JavaPlugin implements Listener {

    // Track the anvil a player is currently using.
    private final Map<UUID, AnvilUseContext> activeAnvilsByPlayer = new ConcurrentHashMap<>();

    // Slot index for the output/result slot in an anvil inventory view.
    private static final int ANVIL_RESULT_SLOT = 2;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AnvilNoFade enabled.");
    }

    @Override
    public void onDisable() {
        activeAnvilsByPlayer.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory instanceof AnvilInventory)) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Location anvilLocation = inventory.getLocation();
        if (anvilLocation == null) {
            // Some virtual anvils (from other plugins) may have no location.
            return;
        }

        Block anvilBlock = anvilLocation.getBlock();
        Material type = anvilBlock.getType();
        if (!isAnyAnvilType(type)) {
            return;
        }

        activeAnvilsByPlayer.put(player.getUniqueId(), new AnvilUseContext(anvilLocation, type));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        if (!(inventory instanceof AnvilInventory)) {
            return;
        }

        activeAnvilsByPlayer.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryView view = event.getView();
        if (!(view.getTopInventory() instanceof AnvilInventory)) {
            return;
        }

        // Only care about clicks in the top (anvil) inventory result slot.
        // event.getRawSlot() refers to the combined view index; top inventory slots are [0..topSize-1].
        if (event.getRawSlot() != ANVIL_RESULT_SLOT) {
            return;
        }

        ItemStack currentItem = event.getCurrentItem();
        if (currentItem == null || currentItem.getType().isAir()) {
            return;
        }

        AnvilUseContext context = activeAnvilsByPlayer.get(player.getUniqueId());
        if (context == null) {
            return;
        }

        // Snapshot the type before the take happens (in case we need to restore).
        Location anvilLocation = context.anvilLocation;
        Material beforeType = anvilLocation.getBlock().getType();
        if (!isAnyAnvilType(beforeType)) {
            // Anvil already gone or replaced; nothing to protect.
            return;
        }

        // After the server processes the anvil use, it may degrade or break the block.
        // Re-check next tick and restore if needed.
        Bukkit.getScheduler().runTask(this, () -> {
            Block block = anvilLocation.getBlock();
            Material afterType = block.getType();

            // If it degraded (ANVIL -> CHIPPED -> DAMAGED) or broke (-> AIR), undo it.
            boolean degraded =
                    isAnyAnvilType(beforeType)
                            && (isAnyAnvilType(afterType) || afterType == Material.AIR)
                            && afterType != beforeType;

            if (!degraded) {
                return;
            }

            // Restore the original anvil state.
            block.setType(beforeType, false);
        });
    }

    private static boolean isAnyAnvilType(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }

    private static Location asBlockLocation(Location location) {
        // Equivalent to toBlockLocation() (not available on all APIs)
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static final class AnvilUseContext {
        private final Location anvilLocation;
        @SuppressWarnings("unused")
        private final Material openedType;

        private AnvilUseContext(Location anvilLocation, Material openedType) {
            this.anvilLocation = asBlockLocation(anvilLocation);
            this.openedType = openedType;
        }
    }
}
