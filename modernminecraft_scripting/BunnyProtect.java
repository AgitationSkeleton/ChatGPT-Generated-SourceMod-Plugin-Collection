package com.redchanit.bunnyprotect;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BunnyProtect extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BunnyProtect enabled. Nametagged rabbits are immune to all damage.");
    }

    @Override
    public void onDisable() {
        getLogger().info("BunnyProtect disabled.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();

        if (entity.getType() != EntityType.RABBIT) {
            return;
        }

        Rabbit rabbit = (Rabbit) entity;

        // "Nametagged" rabbits will have a custom name set.
        String customName = rabbit.getCustomName();
        if (customName == null || customName.trim().isEmpty()) {
            return;
        }

        // Protect from ALL sources of damage
        event.setCancelled(true);
    }
}
