import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class ElytraNoDurability extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ElytraNoDurability enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ElytraNoDurability disabled.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ELYTRA) {
            return;
        }

        // Optional: if you ONLY want to protect worn elytras, uncomment this check.
        // if (event.getSlot() != EquipmentSlot.CHEST) return;

        // Prevent durability loss.
        event.setCancelled(true);

        // Safety: if another plugin already modified damage, explicitly zero it.
        // (Cancelled should be enough, but this avoids odd compatibility issues.)
        event.setDamage(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMend(PlayerItemMendEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ELYTRA) {
            return;
        }

        // Optional: if you ONLY want to protect worn elytras, uncomment this check.
        // if (event.getSlot() != EquipmentSlot.CHEST) return;

        // If durability never goes down, mending is irrelevant; cancel to avoid odd interactions.
        event.setCancelled(true);
    }
}
