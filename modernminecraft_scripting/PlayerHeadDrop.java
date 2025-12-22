package com.redchanit.playerheaddrop;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerHeadDrop extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PlayerHeadDrop enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("PlayerHeadDrop disabled.");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        World world = player.getWorld();

        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta != null) {
            // This sets the head to the dead player's skin/texture
            skullMeta.setOwningPlayer(player);
            skullMeta.setDisplayName(player.getName() + "'s Head");
            headItem.setItemMeta(skullMeta);
        }

        world.dropItemNaturally(player.getLocation(), headItem);
    }
}
