package com.example.catprotection;

import org.bukkit.Bukkit;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Ocelot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class CatProtection extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CatProtection plugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("CatProtection plugin disabled!");
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // Protect both Cats and Ocelots
        if (event.getEntity() instanceof Cat || event.getEntity() instanceof Ocelot) {
            event.setCancelled(true);
        }
    }
}
