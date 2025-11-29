import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class BoatDrop extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        // Register this class as an event listener
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Nothing special needed here
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        // Only care about boats
        if (!(event.getVehicle() instanceof Boat)) {
            return;
        }

        final Location breakLocation = event.getVehicle().getLocation();

        // Let the boat break as normal (it will drop planks and sticks),
        // then fix the drops on the next tick.
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                // Remove nearby planks and sticks that came from the boat break
                for (Entity entity : breakLocation.getWorld().getEntities()) {
                    if (!(entity instanceof Item)) {
                        continue;
                    }

                    Item itemEntity = (Item) entity;
                    ItemStack stack = itemEntity.getItemStack();
                    Material type = stack.getType();

                    // Only touch items very close to the break location
                    if (itemEntity.getLocation().distanceSquared(breakLocation) > 4.0) {
                        continue;
                    }

                    // In Beta 1.7.3, boats drop WOOD planks and STICKs
                    if (type == Material.WOOD || type == Material.STICK) {
                        itemEntity.remove();
                    }
                }

                // Drop a single boat item instead
                breakLocation.getWorld().dropItemNaturally(
                        breakLocation,
                        new ItemStack(Material.BOAT, 1)
                );
            }
        });
    }
}
