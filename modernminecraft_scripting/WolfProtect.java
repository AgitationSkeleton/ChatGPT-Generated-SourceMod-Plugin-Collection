package com.redchanit.wolfprotect;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Arrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

public class WolfProtect extends JavaPlugin {

    // Players in this set have protection DISABLED.
    // Everyone else is protected by default.
    private final Set<String> disabledProtectionPlayers = new HashSet<String>();

    private final WolfProtectListener entityListener = new WolfProtectListener();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(entityListener, this);
        getLogger().info("[WolfProtect] Enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[WolfProtect] Disabled.");
    }

    /**
     * Check if wolf protection is enabled for a given player.
     */
    public boolean isProtectionEnabled(Player player) {
        String name = player.getName().toLowerCase();
        return !disabledProtectionPlayers.contains(name);
    }

    /**
     * Toggle protection for a player and return the new enabled state.
     */
    public boolean toggleProtection(Player player) {
        String name = player.getName().toLowerCase();
        if (disabledProtectionPlayers.contains(name)) {
            disabledProtectionPlayers.remove(name); // now enabled
            return true;
        } else {
            disabledProtectionPlayers.add(name);     // now disabled
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("wolfprotect")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used in-game by a player.");
            return true;
        }

        Player player = (Player) sender;
        boolean nowEnabled = toggleProtection(player);

        if (nowEnabled) {
            player.sendMessage(ChatColor.GREEN + "[WolfProtect] Protection is now ON. "
                    + "You can no longer damage your own tamed wolves.");
        } else {
            player.sendMessage(ChatColor.RED + "[WolfProtect] Protection is now OFF. "
                    + "You can damage your own tamed wolves.");
        }

        return true;
    }

    // ----------------------
    // Entity damage listener
    // ----------------------

    public class WolfProtectListener implements Listener {

        private final WolfProtect plugin;

        public WolfProtectListener() {
            this.plugin = WolfProtect.this;
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
            Entity victim = event.getEntity();
            if (!(victim instanceof Wolf)) {
                return;
            }

            Wolf wolf = (Wolf) victim;
            if (!wolf.isTamed()) {
                return;
            }

            // Figure out if the damager is a player (directly or via projectile)
            Player attackingPlayer = null;
            Entity damager = event.getDamager();

            if (damager instanceof Player) {
                attackingPlayer = (Player) damager;
            } else if (damager instanceof Projectile) {
                Projectile projectile = (Projectile) damager;

                // Keep compatibility with older arrows specifically, but this also
                // works for all other projectiles (snowballs, tridents, etc.)
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player) {
                    attackingPlayer = (Player) shooter;
                }
            }

            if (attackingPlayer == null) {
                // not a player / player's projectile
                return;
            }

            AnimalTamer owner = wolf.getOwner();
            if (owner == null || !(owner instanceof Player)) {
                return;
            }

            Player ownerPlayer = (Player) owner;

            // Only care when the *owner* is the one dealing damage
            if (!attackingPlayer.getUniqueId().equals(ownerPlayer.getUniqueId())) {
                return;
            }

            // Only block if this player currently has protection enabled
            if (!plugin.isProtectionEnabled(attackingPlayer)) {
                return;
            }

            // Cancel the damage to the tamed wolf
            event.setCancelled(true);
        }
    }
}
